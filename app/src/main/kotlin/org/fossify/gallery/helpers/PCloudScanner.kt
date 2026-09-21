package org.fossify.gallery.helpers

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import okhttp3.Call
import org.fossify.commons.extensions.getFilenameFromPath
import org.fossify.commons.extensions.getParentPath
import org.fossify.commons.extensions.isGif
import org.fossify.commons.extensions.isImageFast
import org.fossify.commons.extensions.isRawFast
import org.fossify.commons.extensions.isSvg
import org.fossify.commons.extensions.isVideoFast
import org.fossify.commons.helpers.SORT_BY_SIZE
import org.fossify.gallery.R
import org.fossify.gallery.databases.GalleryDatabase
import org.fossify.gallery.extensions.config
import org.fossify.gallery.extensions.createDirectoryFromMedia
import org.fossify.gallery.extensions.directoryDB
import org.fossify.gallery.extensions.favoritesDB
import org.fossify.gallery.extensions.getFavoritePaths
import org.fossify.gallery.extensions.getNoMediaFoldersSync
import org.fossify.gallery.extensions.isPCloudRecycleBinPath
import org.fossify.gallery.extensions.mediaDB
import org.fossify.gallery.extensions.pCloudItemsDB
import org.fossify.gallery.extensions.toPCloudRemotePath
import org.fossify.gallery.models.Directory
import org.fossify.gallery.models.Medium
import org.fossify.gallery.models.PCloudItem
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

// Pulls the whole pCloud tree down in one listfolder call and rewrites the pCloud half of the
// media and directories cache from it. Folders without an image or video in them are left out,
// the same way MediaFetcher leaves out local folders without media.
//
// The answer is parsed as a stream: an account with thousands of files answers with megabytes
// of JSON, and holding that as one string plus an org.json tree costs several times its size.
// What is kept per entry is a few fields, and files that are not media are dropped as soon as
// they are parsed, so memory follows the number of media files rather than the answer size
class PCloudScanner(private val context: Context) {
    companion object {
        // one scan at a time. RemoteScanService claims this before it runs one, and it is the
        // only thing that does: the scans are ranked and queued by RemoteScanScheduler now,
        // which is what decides who waits for whom
        val isRunning = AtomicBoolean(false)

        // The scan that runs, while it runs, so that it can be called off -- by the scheduler
        // when something outranks it, or by the notification's stop action
        @Volatile
        private var current: PCloudScanner? = null

        // how many diff pages a sync replays before it lists the whole account instead
        private const val MAX_DIFF_PAGES = 20

        // claims the scanner for the given scan, or answers false when one is running
        fun start(scanner: PCloudScanner): Boolean {
            if (!isRunning.compareAndSet(false, true)) {
                return false
            }

            current = scanner
            return true
        }

        fun finish() {
            current = null
            isRunning.set(false)
        }

        // Calls off the registered scan, if one runs: it ends in a PCloudScanAbortedException
        // at its next check, with the cache as it was and the diff id where it was, and the
        // request on the wire is cancelled. Nothing happens when no scan is registered
        fun abortCurrent() {
            current?.abort()
        }
    }

    class Result(val folderCount: Int, val mediaCount: Int)

    private val aborted = AtomicBoolean(false)

    // the request on the wire, while one is; cancelled by abort()
    @Volatile
    private var call: Call? = null

    fun abort() {
        aborted.set(true)
        call?.cancel()
    }

    private fun throwIfAborted() {
        if (aborted.get()) {
            throw PCloudScanAbortedException()
        }
    }

    // Runs one request to pCloud, handing block the callback that registers its Call, so that
    // abort() can cancel it. Checked before and after: a cancelled request ends in an
    // IOException of its own, which is reported as the abort it was
    private inline fun <T> abortable(block: (onCall: (Call) -> Unit) -> T): T {
        throwIfAborted()
        try {
            return block { call = it }
        } catch (e: Exception) {
            throwIfAborted()
            throw e
        } finally {
            call = null
        }
    }

    // one entry of a listfolder answer, trimmed to what the cache needs. children holds only
    // folders and media files. type is 0 for a folder and for a file that is not media
    private class Entry(
        val name: String, val isFolder: Boolean, val itemId: Long, val modified: Long, val size: Long,
        val type: Int, val hash: Long, val hasThumb: Boolean, val duration: Int, val children: List<Entry>
    ) {
        fun withChildren(children: List<Entry>) = Entry(name, isFolder, itemId, modified, size, type, hash, hasThumb, duration, children)
    }

    private val config = context.config

    // pCloud sends dates like "Thu, 25 Sep 2014 10:31:14 +0000"
    private val dateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US)

    // blocks and talks to the network, so call it off the main thread. Throws PCloudException
    // when pCloud refused, an IOException when it could not be reached and a
    // PCloudScanAbortedException when abort() was called; the cache is left untouched in
    // every case, it is only rewritten once the whole tree has arrived
    fun scanAll(): Result {
        // the diff id is taken before the tree so that a change made while the tree is on its
        // way is replayed by the next sync rather than lost; replaying it is harmless. Without
        // one, every sync falls back to a full scan
        val diffId = try {
            abortable { onCall -> PCloudApi.diff(config.pCloudApiHost, config.pCloudAccessToken, sinceDiffId = null, last = 1, onCall = onCall).diffId }
        } catch (e: PCloudException) {
            if (e.requiresLogIn) throw e
            0L
        }

        val root = fetchTree()

        val media = ArrayList<Medium>()
        val directories = ArrayList<Directory>()
        val items = ArrayList<PCloudItem>()
        val collector = Collector(media, directories, items)
        collector.collect(root, PCLOUD_PATH_SCHEME)

        throwIfAborted()
        store(media, directories, items)
        config.pCloudDiffId = diffId
        config.pCloudLastFullScanAt = System.currentTimeMillis()
        return Result(directories.size, media.size)
    }

    // Brings the cache up to date with what changed on pCloud since the last scan: the diff
    // API lists the events, and each folder they touched is listed again the way scanFolder()
    // does it, while folders that were renamed, moved or deleted are handled from the event
    // alone. Without a diff id to start from, when pCloud asks for a reset, or when an event
    // names a folder the cache does not know, it is a full scan instead. The counts are those
    // of the folders listed again. Blocks like scanAll() does and throws the same way; the
    // diff id moves on only once every touched folder is through, so a sync that broke off
    // or was aborted replays its events next time. What an event wrote to the cache by then
    // stays, replaying it changes nothing
    fun sync(): Result {
        var diffId = config.pCloudDiffId
        if (diffId <= 0L) {
            return scanAll()
        }

        val touchedFolderIds = LinkedHashSet<Long>()
        var pages = 0
        while (true) {
            val page = abortable { onCall -> PCloudApi.diff(config.pCloudApiHost, config.pCloudAccessToken, sinceDiffId = diffId, onCall = onCall) }
            page.entries.forEach { entry ->
                if (!apply(entry, touchedFolderIds)) {
                    return scanAll()
                }
            }

            if (page.entries.isEmpty() || page.diffId <= diffId) {
                break
            }

            diffId = page.diffId
            if (++pages >= MAX_DIFF_PAGES) {
                // that many events are quicker to list than to replay
                return scanAll()
            }
        }

        // the folders are found by id at the end, an event after the one that touched a folder
        // may have moved or dropped it since. One the cache cannot place means the diff missed
        // something and the whole account is listed instead
        val touchedPaths = ArrayList<String>()
        touchedFolderIds.forEach { folderId ->
            touchedPaths.add(folderPathOf(folderId) ?: return scanAll())
        }

        var folderCount = 0
        var mediaCount = 0
        touchedPaths.forEach { path ->
            scanFolder(path)?.let {
                folderCount += it.folderCount
                mediaCount += it.mediaCount
            }
        }

        throwIfAborted()
        config.pCloudDiffId = diffId
        config.pCloudLastFullScanAt = System.currentTimeMillis()
        return Result(folderCount, mediaCount)
    }

    // Applies one event to the cache, or notes the folder it touched for a listing. Answers
    // false when the event cannot be applied from what the cache knows. Events pCloud sends
    // about shares and the account are not about files and are passed over
    private fun apply(entry: PCloudApi.DiffEntry, touchedFolderIds: LinkedHashSet<Long>): Boolean {
        when (entry.event) {
            "reset" -> return false

            // an empty folder gets its row so that its id is known, the same as createFolder()
            // does; whatever lands in it comes as events of its own
            "createfolder" -> {
                val parentPath = folderPathOf(entry.parentFolderId) ?: return false
                context.pCloudItemsDB.insertIfMissing(listOf(PCloudItem(null, "$parentPath/${entry.name}", entry.itemId, true, 0L, false, 0L)))
            }

            // renamed or moved: every row under it follows, like after a rename from this app.
            // pCloud sends this for the root too, which is nothing to place under a parent
            "modifyfolder" -> {
                if (entry.itemId == 0L) {
                    return true
                }

                val parentPath = folderPathOf(entry.parentFolderId) ?: return false
                val newPath = "$parentPath/${entry.name}"
                val old = context.pCloudItemsDB.getItemByItemId(entry.itemId, true)
                when {
                    old == null -> context.pCloudItemsDB.insertIfMissing(listOf(PCloudItem(null, newPath, entry.itemId, true, 0L, false, 0L)))
                    old.path != newPath -> moveFolderRows(old.path, newPath)
                }
            }

            "deletefolder" -> context.pCloudItemsDB.getItemByItemId(entry.itemId, true)?.let { forget(it.path) }

            // the folder the file is in now, and the one it was in when the cache last saw it,
            // are listed again; a file that is not media leaves the listing as it was
            "createfile", "modifyfile" -> {
                context.pCloudItemsDB.getItemByItemId(entry.itemId, false)?.let { old ->
                    folderIdOf(old.path.getParentPath())?.let { touchedFolderIds.add(it) }
                }

                if (getMediaType(entry.name, entry.category) != 0) {
                    touchedFolderIds.add(entry.parentFolderId)
                }
            }

            "deletefile" -> context.pCloudItemsDB.getItemByItemId(entry.itemId, false)?.let { old ->
                folderIdOf(old.path.getParentPath())?.let { touchedFolderIds.add(it) }
            }
        }

        return true
    }

    // Moves every row under a folder to its new path, at any depth, and the folder's place in a
    // virtual group with them. Per-folder settings keyed by path (sorting, the cover image,
    // pinning) stay with the old path, as they do for a local folder. PCloudWriter does this
    // after a rename it asked for, the sync after one made elsewhere
    fun moveFolderRows(oldPath: String, newPath: String) {
        GalleryDatabase.getInstance(context).runInTransaction {
            context.mediaDB.updatePathsUnderFolder(oldPath, newPath)
            context.favoritesDB.updatePathsUnderFolder(oldPath, newPath)
            context.directoryDB.updatePathsUnderFolder(oldPath, newPath, newPath.getFilenameFromPath())
            context.pCloudItemsDB.updatePaths(oldPath, newPath)
        }

        config.updateFolderGroupMemberPath(oldPath, newPath)
        config.updatePCloudHiddenFolderPaths(oldPath, newPath)
    }

    // the pseudo path of a folder id, or null for one the cache has no row for. The root is
    // folder 0 and needs no row
    private fun folderPathOf(folderId: Long): String? {
        if (folderId == 0L) {
            return PCLOUD_PATH_SCHEME
        }

        return context.pCloudItemsDB.getItemByItemId(folderId, true)?.path
    }

    private fun folderIdOf(path: String): Long? {
        if (path == PCLOUD_PATH_SCHEME) {
            return 0L
        }

        return context.pCloudItemsDB.getItem(path)?.itemId
    }

    // Refreshes one folder from a non-recursive listfolder: its media rows, its Directory row
    // and its pcloud_items rows are replaced, subfolders are left as they are. A folder pCloud
    // no longer has is dropped from the cache, with everything under it, and null comes back.
    // The app's recycle bin is never listed: its rows are the writer's, not a listing's, and
    // the layout of its files on pCloud is not the one the rows show (see PCLOUD_RECYCLE_BIN).
    // Blocks like scanAll() does and throws the same way
    fun scanFolder(path: String): Result? {
        if (path == PCLOUD_RECYCLE_BIN || path.isPCloudRecycleBinPath()) {
            return null
        }

        val folder = try {
            fetchFolder(path)
        } catch (e: PCloudException) {
            if (e.result == PCLOUD_RESULT_DIRECTORY_NOT_FOUND) {
                forget(path)
                return null
            }

            throw e
        }

        val media = ArrayList<Medium>()
        val directories = ArrayList<Directory>()
        val items = ArrayList<PCloudItem>()
        val unlistedFolders = ArrayList<PCloudItem>()
        val scannedAt = System.currentTimeMillis()
        Collector(media, directories, items, scannedAt, unlistedFolders).collect(folder, path)

        val keptItemPaths = HashSet<String>()
        media.mapTo(keptItemPaths) { it.path }
        unlistedFolders.mapTo(keptItemPaths) { it.path }
        throwIfAborted()
        storeFolder(path, media, directories, items, unlistedFolders, keptItemPaths)
        return Result(directories.size, media.size)
    }

    // The folders directly inside a pCloud folder, as pseudo paths sorted by name, for a picker
    // walking the tree rather than the cache: a folder with no media in it has no Directory
    // row, yet is a fine destination. Every folder named gets a pcloud_items row on the way,
    // so that a write into a folder picked here finds its id; a row already there is kept.
    // Blocks and throws like scanFolder() does
    fun listFolders(path: String): List<String> {
        val folder = fetchFolder(path)
        val subfolders = folder.children
            .filter { it.isFolder && !isRecycleBinFolder(path, it) }
            .sortedBy { it.name.lowercase() }
            .map { PCloudItem(null, "$path/${it.name}", it.itemId, true, 0L, false, 0L) }

        val rows = if (path == PCLOUD_PATH_SCHEME) subfolders else subfolders + PCloudItem(null, path, folder.itemId, true, 0L, false, 0L)
        context.pCloudItemsDB.insertIfMissing(rows)
        return subfolders.map { it.path }
    }

    // pCloud refuses a recursive listing of the root with 1101 "Invalid request" (seen on a real
    // account, not documented), while a folder below it lists fine that way. So the root is
    // listed flat and each folder in it is fetched as a tree of its own
    private fun fetchTree(): Entry {
        val root = fetchFolder(PCLOUD_PATH_SCHEME)
        return root.withChildren(root.children.filter { !isRecycleBinFolder(PCLOUD_PATH_SCHEME, it) }.map { child ->
            if (child.isFolder) fetchSubtree("$PCLOUD_PATH_SCHEME/${child.name}") else child
        })
    }

    // the app's recycle bin, a folder in the root, is left out of every listing
    private fun isRecycleBinFolder(parentPath: String, entry: Entry) =
        parentPath == PCLOUD_PATH_SCHEME && entry.isFolder && entry.name == PCLOUD_RECYCLE_BIN_FOLDER_NAME

    // one recursive listing, or, should pCloud refuse that for this folder too, a flat one
    // with every folder in it fetched the same way
    private fun fetchSubtree(path: String): Entry {
        try {
            return fetchFolder(path, recursive = true)
        } catch (e: PCloudException) {
            if (e.result != PCLOUD_RESULT_INVALID_REQUEST) {
                throw e
            }
        }

        val folder = fetchFolder(path)
        return folder.withChildren(folder.children.map { child ->
            if (child.isFolder) fetchSubtree("$path/${child.name}") else child
        })
    }

    private fun fetchFolder(path: String, recursive: Boolean = false): Entry {
        var folder: Entry? = null
        val params = mapOf("path" to path.toPCloudRemotePath(), "recursive" to if (recursive) "1" else "0")
        abortable { onCall ->
            PCloudApi.stream(config.pCloudApiHost, config.pCloudAccessToken, "listfolder", params, onCall) { name, reader ->
                if (name == "metadata") {
                    folder = readEntry(reader)
                } else {
                    reader.skipValue()
                }
            }
        }

        return folder ?: throw IllegalStateException("pCloud answered listfolder without metadata")
    }

    private fun readEntry(reader: JsonReader): Entry {
        var name = ""
        var isFolder = false
        var itemId = 0L
        var modified = 0L
        var size = 0L
        var category = 0
        var hash = 0L
        var hasThumb = false
        var duration = 0
        var children = emptyList<Entry>()

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "name" -> name = reader.nextString()
                "isfolder" -> isFolder = reader.nextBoolean()
                "folderid", "fileid" -> itemId = reader.nextLong()
                "modified" -> modified = parseDate(reader.nextString())
                "size" -> size = reader.nextLong()
                "category" -> category = reader.nextInt()
                // an unsigned 64 bit number, which may not fit nextLong(); the bit pattern is
                // all that matters, it is only ever compared for equality
                "hash" -> hash = readStringOrNull(reader)?.let { java.lang.Long.parseUnsignedLong(it) } ?: 0L
                "thumb" -> hasThumb = reader.nextBoolean()
                // seconds, sent for videos only, as a string like "12.34"
                "duration" -> duration = readStringOrNull(reader)?.toDoubleOrNull()?.toInt() ?: 0
                "contents" -> children = readContents(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        val type = if (isFolder) 0 else getMediaType(name, category)
        return Entry(name, isFolder, itemId, modified, size, type, hash, hasThumb, duration, children)
    }

    private fun readContents(reader: JsonReader): List<Entry> {
        val entries = ArrayList<Entry>()
        reader.beginArray()
        while (reader.hasNext()) {
            val entry = readEntry(reader)
            if (entry.isFolder || entry.type != 0) {
                entries.add(entry)
            }
        }
        reader.endArray()
        return entries
    }

    // JsonReader gives a number's text through nextString() too
    private fun readStringOrNull(reader: JsonReader): String? {
        return if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            null
        } else {
            reader.nextString()
        }
    }

    private fun parseDate(raw: String): Long {
        return try {
            dateFormat.parse(raw)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    // classified by the filename like local files are, with pCloud's own category as the
    // fallback for an extension it does not know. 0 means the file is not media
    private fun getMediaType(name: String, category: Int) = when {
        name.isImageFast() -> TYPE_IMAGES
        name.isVideoFast() -> TYPE_VIDEOS
        name.isGif() -> TYPE_GIFS
        name.isRawFast() -> TYPE_RAWS
        name.isSvg() -> TYPE_SVGS
        category == PCLOUD_CATEGORY_IMAGE -> TYPE_IMAGES
        category == PCLOUD_CATEGORY_VIDEO -> TYPE_VIDEOS
        else -> 0
    }

    // Walks the parsed tree and turns it into the rows the cache holds. The Directory rows are
    // built by the same createDirectoryFromMedia() that builds local folders, so MainActivity's
    // recheck of the displayed folders finds nothing to change in them. Every folder that was
    // listed gets a pcloud_items row, media in it or not, so that the diff sync can place an
    // event by its folder id; a subfolder a non-recursive listing only named goes to
    // unlistedFolders, its row must not claim a scan that did not happen
    private inner class Collector(
        private val media: ArrayList<Medium>,
        private val directories: ArrayList<Directory>,
        private val items: ArrayList<PCloudItem>,
        private val scannedAt: Long = System.currentTimeMillis(),
        private val unlistedFolders: ArrayList<PCloudItem>? = null
    ) {
        private val favoritePaths = context.getFavoritePaths()
        private val albumCovers = config.parseAlbumCovers()
        private val hiddenString = context.getString(R.string.hidden)
        private val includedFolders = config.includedFolders
        private val noMediaFolders = context.getNoMediaFoldersSync()
        private val getProperFileSize = config.directorySorting and SORT_BY_SIZE != 0
        private val mediaFetcher = MediaFetcher(context)

        fun collect(folder: Entry, path: String) {
            items.add(PCloudItem(null, path, folder.itemId, true, 0L, false, scannedAt))

            val folderMedia = ArrayList<Medium>()
            folder.children.forEach { child ->
                val childPath = "$path/${child.name}"
                if (child.isFolder) {
                    if (unlistedFolders == null) {
                        collect(child, childPath)
                    } else {
                        unlistedFolders.add(PCloudItem(null, childPath, child.itemId, true, 0L, false, 0L))
                    }
                } else {
                    // pCloud knows when a file was uploaded or changed, not when the photo was
                    // taken, so the modification time stands in for both like it does on OTG
                    val medium = Medium(
                        id = null,
                        name = child.name,
                        path = childPath,
                        parentPath = path,
                        modified = child.modified,
                        taken = child.modified,
                        size = child.size,
                        type = child.type,
                        videoDuration = child.duration,
                        isFavorite = favoritePaths.contains(childPath),
                        deletedTS = 0L,
                        mediaStoreId = 0L
                    )
                    folderMedia.add(medium)
                    items.add(PCloudItem(null, childPath, child.itemId, false, child.hash, child.hasThumb, scannedAt))
                }
            }

            if (folderMedia.isEmpty()) {
                return
            }

            mediaFetcher.sortMedia(folderMedia, config.getFolderSorting(path), path)
            val directory = context.createDirectoryFromMedia(
                path = path,
                curMedia = folderMedia,
                albumCovers = albumCovers,
                hiddenString = hiddenString,
                includedFolders = includedFolders,
                getProperFileSize = getProperFileSize,
                noMediaFolders = noMediaFolders
            )

            media.addAll(folderMedia)
            directories.add(directory)
        }
    }

    // replaces the pCloud rows in one transaction, so a folder list read in between never sees
    // half of a scan. Rows pCloud no longer has are dropped one by one: the list is short, and
    // a NOT IN over thousands of paths would trip SQLite's argument limit. The rows of the
    // app's recycle bin are not a listing's to drop, the tree never holds them
    private fun store(media: List<Medium>, directories: List<Directory>, items: List<PCloudItem>) {
        GalleryDatabase.getInstance(context).runInTransaction {
            val keptMediaPaths = media.map { it.path }.toHashSet()
            context.mediaDB.getPathsWithPrefix(PCLOUD_PATH_SCHEME).filter { it !in keptMediaPaths && !it.isPCloudRecycleBinPath() }.forEach { path ->
                context.mediaDB.deleteMediumPath(path)
                context.favoritesDB.deleteFavoritePath(path)
            }

            val keptDirectoryPaths = directories.map { it.path }.toHashSet()
            context.directoryDB.getPathsWithPrefix(PCLOUD_PATH_SCHEME).filter { it !in keptDirectoryPaths && it != PCLOUD_RECYCLE_BIN }.forEach { path ->
                context.directoryDB.deleteDirPath(path)
            }

            context.pCloudItemsDB.deleteAllOutsideRecycleBin()
            context.pCloudItemsDB.insertAll(items)
            context.mediaDB.insertAll(media)
            context.directoryDB.insertAll(directories)
        }
    }

    // the same, narrowed to one folder: only its own media rows and the item rows of its direct
    // children are dropped, a subfolder's rows are that subfolder's business. A subfolder gets
    // a row only when it has none yet, an existing one keeps its own last scan time
    private fun storeFolder(
        path: String, media: List<Medium>, directories: List<Directory>, items: List<PCloudItem>, unlistedFolders: List<PCloudItem>, keptItemPaths: Set<String>
    ) {
        GalleryDatabase.getInstance(context).runInTransaction {
            val keptMediaPaths = media.map { it.path }.toHashSet()
            context.mediaDB.getMediaFromPath(path).map { it.path }.filter { it !in keptMediaPaths }.forEach { mediumPath ->
                context.mediaDB.deleteMediumPath(mediumPath)
                context.favoritesDB.deleteFavoritePath(mediumPath)
            }

            val prefix = "$path/"
            context.pCloudItemsDB.getPathsWithPrefix(prefix)
                .filter { !it.substring(prefix.length).contains('/') && it !in keptItemPaths }
                .forEach { context.pCloudItemsDB.deleteItemPath(it) }

            if (directories.isEmpty()) {
                context.directoryDB.deleteDirPath(path)
            }

            context.pCloudItemsDB.insertAll(items)
            context.pCloudItemsDB.insertIfMissing(unlistedFolders)
            context.mediaDB.insertAll(media)
            context.directoryDB.insertAll(directories)
        }
    }

    // drops a folder pCloud no longer has, with everything under it. PCloudWriter uses it for a
    // folder it has just deleted
    fun forget(path: String) {
        GalleryDatabase.getInstance(context).runInTransaction {
            val prefix = "$path/"
            context.mediaDB.getPathsWithPrefix(prefix).forEach { mediumPath ->
                context.mediaDB.deleteMediumPath(mediumPath)
                context.favoritesDB.deleteFavoritePath(mediumPath)
            }

            context.directoryDB.getPathsWithPrefix(prefix).forEach { context.directoryDB.deleteDirPath(it) }
            context.directoryDB.deleteDirPath(path)
            context.pCloudItemsDB.getPathsWithPrefix(prefix).forEach { context.pCloudItemsDB.deleteItemPath(it) }
            context.pCloudItemsDB.deleteItemPath(path)
        }
    }
}

// thrown out of a scan that PCloudScanner.abort() called off; nothing went wrong, so nothing
// is reported for it
class PCloudScanAbortedException : Exception("pCloud scan aborted")
