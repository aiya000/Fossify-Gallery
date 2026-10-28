package org.fossify.gallery.helpers

import android.content.Context
import android.util.Log
import org.fossify.commons.extensions.getFilenameFromPath
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
import org.fossify.gallery.extensions.isSmbRecycleBinPath
import org.fossify.gallery.extensions.mediaDB
import org.fossify.gallery.models.Directory
import org.fossify.gallery.models.Medium
import java.util.concurrent.atomic.AtomicBoolean

// Walks the configured SMB share and rewrites the SMB half of the media and directories cache
// from it. Folders without an image or video in them are left out, the same way MediaFetcher
// leaves out local folders without media.
//
// Unlike pCloud, SMB has no recursive listing and no diff stream: the tree is walked folder by
// folder, one request each, and there is nothing to sync against afterwards. A whole-share scan
// is therefore the only kind of full refresh there is, and scanFolder() is what the screens use
// to keep it off the critical path
class SmbScanner(private val context: Context) {
    companion object {
        // one scan at a time, claimed by RemoteScanService. Which scan gets to be that one, and
        // what happens to it when something else wants the network, is RemoteScanScheduler's
        val isRunning = AtomicBoolean(false)

        @Volatile
        private var current: SmbScanner? = null

        fun start(scanner: SmbScanner): Boolean {
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

        // calls off the registered scan, if one runs; it ends in a SmbScanAbortedException at
        // its next folder, with the cache as it was. [keepPlace] is whether it leaves its place
        // in RemoteScanPlaces for the next scan to pick up, see abort()
        fun abortCurrent(keepPlace: Boolean) {
            current?.abort(keepPlace)
        }

        // a folder deeper than this is not walked. A share can be pointed at a whole disk by
        // accident, and a walk of one has no end that a user would wait for
        private const val MAX_DEPTH = 24

        // the tag Context.runSmbScan() reports a failed scan under; a skipped folder belongs in
        // the same place, being the half of the same story that no exception is thrown for
        private const val TAG = "SmbScan"
    }

    // what a scan found, and how many folders it had to pass over to find it. A scan that
    // skipped nothing saw the whole share; one that did not is a partial answer, and the count
    // is what says so
    class Result(val folderCount: Int, val mediaCount: Int, val skippedFolderCount: Int = 0)

    // what one folder of a full walk came to: its media, and the folder row built from them
    class Found(val media: List<Medium>, val directory: Directory)

    private val aborted = AtomicBoolean(false)
    private val keepsPlace = AtomicBoolean(true)

    // Calls the scan off. Made way for another scan, it keeps its place for the next walk of the
    // same share to pick up (#80); stopped by the user, it does not -- stopping means "leave the
    // network alone", not "hold my place". Once one call has said not to keep it, a later one
    // that says to keep it does not bring it back
    fun abort(keepPlace: Boolean = false) {
        if (!keepPlace) {
            keepsPlace.set(false)
        }

        aborted.set(true)
    }

    private fun throwIfAborted() {
        if (aborted.get()) {
            throw SmbScanAbortedException()
        }
    }

    // Leaves a walk that was called off where the next one finds it, or not, by what abort()
    // was told; the log is the only record of how far it had got
    private fun putDown(kind: RemoteScanPlaces.Kind, connection: SmbConnection, walk: SmbWalk<*>) {
        if (!keepsPlace.get()) {
            RemoteScanPlaces.drop(kind, connection.id)
            Log.i(TAG, "Stopped the walk of the share at ${walk.listedCount} folders listed; its place is not kept (${connection.root})")
            return
        }

        RemoteScanPlaces.hold(kind, connection.id, ownerOf(connection), walk)
        Log.i(TAG, "Put the walk of the share down at ${walk.listedCount} folders listed; its place is held (${connection.root})")
    }

    // what makes a held place this share's: everything that says which tree is walked and as
    // whom. Not the name, which changes nothing about what a walk finds
    private fun ownerOf(connection: SmbConnection) = connection.copy(name = "")

    // a folder that cannot be listed -- no permission for it, or it was removed while the walk
    // ran -- is skipped rather than ending the scan; the rest of the share is still worth having.
    // Null says to skip it
    private fun listOrSkip(path: String, depth: Int): List<SmbClient.Entry>? {
        throwIfAborted()
        return try {
            listWithOneRetry(path)
        } catch (e: Exception) {
            throwIfAborted()
            // The root not listing means the share is not reachable, which is not a folder to
            // skip over -- and neither is any folder whose listing left no connection standing.
            // Walking on without one skips every folder that is left, and the scan then reports
            // an empty share rather than an unreachable one
            if (depth == 0 || !SmbClient.isConnected(path)) {
                throw e
            }

            Log.w(TAG, "Skipping \"$path\", a folder of the share that could not be listed", e)
            null
        }
    }

    // the cache's media rows of one connection, outside the recycle bin, as SmbWalk compares them
    private fun cacheSignaturesOf(connection: SmbConnection) =
        SmbWalk.signaturesOf(context.mediaDB.getMediaWithPrefix(connection.rowPrefix).filter { !it.path.isSmbRecycleBinPath() })

    // Walks the whole share of [connection] and replaces its rows with what it found; the other
    // connections' rows are not this walk's to touch (#155). Blocks and talks to the network, so
    // call it off the main thread.
    //
    // onProgress is called as each folder is left behind, with what the walk holds so far and
    // the folder it has just come out of. A share has no count to work towards -- what is in it
    // is only known once it has all been walked -- so this is the only thing that can tell the
    // user a scan is moving rather than stuck. It runs on the scanning thread: keep it quick,
    // and do not touch a view from it.
    //
    // A walk that was called off to make way for another scan left its place behind (#80), and
    // this picks it up rather than starting over. What it had found stays out of the cache all the
    // while: the cache is only written once the whole share has been walked, resumed or not. The
    // folders something wrote to while the walk was put down are read again first, so that what
    // it found there before does not undo the write
    fun scanAll(connection: SmbConnection, onProgress: (folderCount: Int, mediaCount: Int, path: String) -> Unit = { _, _, _ -> }): Result {
        val kind = RemoteScanPlaces.Kind.SMB_WALK
        val held = RemoteScanPlaces.take<SmbWalk<Found>>(kind, connection.id, ownerOf(connection))
        val walk = if (held != null) {
            val signatures = cacheSignaturesOf(connection)
            val changed = SmbWalk.changedFolders(held.cacheSignatures, signatures)
            held.reopen(changed)
            held.cacheSignatures = signatures
            Log.i(
                TAG,
                "Picking up the walk of the share at ${held.listedCount} folders listed, ${changed.size} folders changed since (${connection.root})"
            )
            held
        } else {
            SmbWalk<Found>(connection.root, MAX_DEPTH) { it.media.size }.apply { cacheSignatures = cacheSignaturesOf(connection) }
        }

        val collector = Collector()
        try {
            walk.walk(
                list = ::listOrSkip,
                read = collector::read,
                isWalkable = { parentPath, entry -> !isRecycleBinFolder(parentPath, entry) },
                onFolder = { path -> onProgress(walk.foundCount, walk.mediaCount, path) }
            )
        } catch (e: SmbScanAbortedException) {
            putDown(kind, connection, walk)
            throw e
        }

        val media = walk.results.flatMap { it.media }
        val directories = walk.results.map { it.directory }
        store(connection, media, directories, walk.skippedPaths)
        context.config.setSmbLastFullScanAt(connection.id, System.currentTimeMillis())
        return Result(directories.size, media.size, walk.skippedPaths.size)
    }

    // The same for one folder and its direct children only; the subfolders keep the rows an
    // earlier scan gave them
    fun scanFolder(path: String): Result {
        val entries = try {
            listWithOneRetry(path)
        } catch (e: Exception) {
            throwIfAborted()
            throw e
        }

        val found = Collector().read(path, entries)
        storeFolder(path, found?.media.orEmpty(), listOfNotNull(found?.directory))
        return Result(if (found == null) 0 else 1, found?.media?.size ?: 0)
    }

    // Walks the whole share the way scanAll() does, but reads only the folders the cache has no
    // row for (#127). A known folder is listed to find the folders under it and nothing else:
    // its media are not read again, its row is not rewritten, and nothing is dropped. So the
    // share is asked one listing per folder, as a full walk is, and the cache is left as it was
    // except for the folders that are new -- each one stored as it is found, so that it is in
    // the folder list before the walk is over. A folder hidden in this app has a row, so it is
    // not new; the recycle bin is left out of the walk like everywhere else. A folder that
    // cannot be listed is skipped the way scanAll() skips it.
    //
    // The counts are the new folders and their media. smbLastFullScanAt is not moved: this was
    // not a walk that could have dropped anything, so the automatic scans are still owed one.
    //
    // Called off to make way for another scan, it leaves its place behind like scanAll() does, and
    // the next search picks it up. Nothing has to be read again for that: each new folder was
    // stored as it was found, and the folders the cache knows are counted afresh
    fun scanNewFolders(
        connection: SmbConnection,
        onProgress: (newFolderCount: Int, mediaCount: Int, path: String) -> Unit = { _, _, _ -> }
    ): Result {
        val kind = RemoteScanPlaces.Kind.SMB_NEW_FOLDERS
        val held = RemoteScanPlaces.take<SmbWalk<Int>>(kind, connection.id, ownerOf(connection))
        held?.let { Log.i(TAG, "Picking up the search for new folders on the share at ${it.listedCount} folders listed (${connection.root})") }
        val walk = held ?: SmbWalk<Int>(connection.root, MAX_DEPTH) { it }

        // a new folder the walk found before it was put down is known by now, and that is why
        // the walk keeps its own count of them rather than reading it off the cache
        val known = directoryPathsOf(connection).toHashSet()
        val collector = Collector()
        try {
            walk.walk(
                list = ::listOrSkip,
                read = { path, entries ->
                    if (path in known) {
                        null
                    } else {
                        collector.read(path, entries)?.let {
                            storeFolder(path, it.media, listOf(it.directory))
                            it.media.size
                        }
                    }
                },
                isWalkable = { parentPath, entry -> !isRecycleBinFolder(parentPath, entry) },
                onFolder = { path -> onProgress(walk.foundCount, walk.mediaCount, path) }
            )
        } catch (e: SmbScanAbortedException) {
            putDown(kind, connection, walk)
            throw e
        }

        return Result(walk.foundCount, walk.mediaCount, walk.skippedPaths.size)
    }

    // the direct subfolders of a folder, for the folder pickers. Not cached: a picker is opened
    // rarely and a stale list of folders is worse there than a moment's wait
    fun listFolders(path: String): List<String> {
        return listWithOneRetry(path)
            .filter { it.isFolder && !isRecycleBinFolder(path, it) }
            .map { childPathOf(path, it.name) }
            .sorted()
    }

    // The app's recycle bin, a folder in the root, is left out of every listing and every walk:
    // its rows are SmbWriter's, not a listing's, and a medium in it is deleted, not in a folder
    // called ".gallery-recycle-bin". See SMB_RECYCLE_BIN
    private fun isRecycleBinFolder(parentPath: String, entry: SmbClient.Entry) =
        parentPath == smbRootOfPath(parentPath) && entry.isFolder && entry.name == RECYCLE_BIN_FOLDER_NAME

    // Lists a folder, and asks a second time over a fresh connection when the first ask was cut
    // short by the connection going away. A share that has been walked for a while loses one
    // regularly -- a session the server gave up on, a firewall tidying an old one away -- and
    // the folder it happened on is usually perfectly readable; without the second ask, a walk of
    // a large share would end on the first of those rather than on anything being wrong.
    //
    // A folder that failed while the connection stood is not retried. Nothing about it would be
    // different the second time, and the caller has its own way of dealing with one
    private fun listWithOneRetry(path: String): List<SmbClient.Entry> {
        return try {
            SmbClient.list(context, path)
        } catch (e: Exception) {
            throwIfAborted()
            if (SmbClient.isConnected(path)) {
                throw e
            }

            Log.w(TAG, "Listing \"$path\" of the share again over a new connection", e)
            SmbClient.disconnect(path)
            SmbClient.list(context, path)
        }
    }

    // a root, "smb:" or "smb:2", takes the separator after it like any other folder does
    private fun childPathOf(parentPath: String, name: String) = "$parentPath/$name"

    // The folder rows of one connection: everything under its root, and the root itself, which
    // has a row when media lie in it directly. Not the bare scheme, which would be every
    // connection's
    private fun directoryPathsOf(connection: SmbConnection): List<String> {
        val under = context.directoryDB.getPathsWithPrefix(connection.rowPrefix)
        return if (context.directoryDB.getDirectoryId(connection.root) != null) under + connection.root else under
    }

    private fun getMediaType(name: String) = when {
        name.isImageFast() -> TYPE_IMAGES
        name.isVideoFast() -> TYPE_VIDEOS
        name.isGif() -> TYPE_GIFS
        name.isRawFast() -> TYPE_RAWS
        name.isSvg() -> TYPE_SVGS
        else -> 0
    }

    // Turns the entries of a folder into the rows the cache holds. The Directory rows are built
    // by the same createDirectoryFromMedia() that builds local folders, so MainActivity's
    // recheck of the displayed folders finds nothing to change in them
    private inner class Collector {
        private val config = context.config
        private val favoritePaths = context.getFavoritePaths()
        private val albumCovers = config.parseAlbumCovers()
        private val hiddenString = context.getString(R.string.hidden)
        private val includedFolders = config.includedFolders
        private val noMediaFolders = context.getNoMediaFoldersSync()
        private val getProperFileSize = config.directorySorting and SORT_BY_SIZE != 0
        private val mediaFetcher = MediaFetcher(context)

        // the media of one listed folder and the row they make, without walking into anything;
        // null for a folder with no media in it
        fun read(path: String, entries: List<SmbClient.Entry>): Found? {
            val folderMedia = ArrayList<Medium>()
            entries.filter { !it.isFolder }.forEach { entry ->
                val type = getMediaType(entry.name)
                if (type == 0) {
                    return@forEach
                }

                val childPath = childPathOf(path, entry.name)
                // a share knows when a file was written, not when the photo was taken, so the
                // modification time stands in for both like it does on pCloud and OTG
                folderMedia.add(
                    Medium(
                        id = null,
                        name = entry.name,
                        path = childPath,
                        parentPath = path,
                        modified = entry.modified,
                        taken = entry.modified,
                        size = entry.size,
                        type = type,
                        videoDuration = 0,
                        isFavorite = favoritePaths.contains(childPath),
                        deletedTS = 0L,
                        mediaStoreId = 0L
                    )
                )
            }

            if (folderMedia.isEmpty()) {
                return null
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

            return Found(folderMedia, directory)
        }
    }

    // replaces the SMB rows in one transaction, so a folder list read in between never sees half
    // of a scan. Rows the share no longer has are dropped one by one: a NOT IN over thousands of
    // paths would trip SQLite's argument limit. The rows of the recycle bin are not a listing's
    // to drop, the walk never holds them
    private fun store(connection: SmbConnection, media: List<Medium>, directories: List<Directory>, skippedPaths: Set<String>) {
        GalleryDatabase.getInstance(context).runInTransaction {
            val keptMediaPaths = media.map { it.path }.toHashSet()
            context.mediaDB.getPathsWithPrefix(connection.rowPrefix)
                .filter { it !in keptMediaPaths && !it.isSmbRecycleBinPath() && !isUnderSkipped(it, skippedPaths) }
                .forEach { path ->
                    context.mediaDB.deleteMediumPath(path)
                    context.favoritesDB.deleteFavoritePath(path)
                }

            val keptDirectoryPaths = directories.map { it.path }.toHashSet()
            directoryPathsOf(connection)
                .filter { it !in keptDirectoryPaths && !isUnderSkipped(it, skippedPaths) }
                .forEach { path -> context.directoryDB.deleteDirPath(path) }

            context.mediaDB.insertAll(media)
            context.directoryDB.insertAll(directories.map { withExistingId(it) })
        }
    }

    // The same folder, carrying the row id the cache already holds for it. Inserting it without
    // one replaces the row, and SQLite hands out a fresh id: the folder list falls back to id
    // order wherever the sorting leaves two folders equal, and under the custom order that is
    // every folder the user has not dragged into place. A folder would therefore move to the end
    // of the list for having been opened. A rescan re-describes a folder; it does not find a new one
    private fun withExistingId(directory: Directory) =
        context.directoryDB.getDirectoryId(directory.path)?.let { directory.copy(id = it) } ?: directory

    // a row of a folder the walk could not get into, or of anything below one. The share may
    // well still hold it; this scan only never got to look, and what a scan did not look at is
    // not something it may drop
    private fun isUnderSkipped(path: String, skippedPaths: Set<String>) =
        skippedPaths.any { path == it || path.startsWith("$it/") }

    // the same, narrowed to one folder: only its own media rows are dropped, a subfolder's rows
    // are that subfolder's business
    private fun storeFolder(path: String, media: List<Medium>, directories: List<Directory>) {
        GalleryDatabase.getInstance(context).runInTransaction {
            val keptMediaPaths = media.map { it.path }.toHashSet()
            context.mediaDB.getMediaFromPath(path).map { it.path }.filter { it !in keptMediaPaths }.forEach { mediumPath ->
                context.mediaDB.deleteMediumPath(mediumPath)
                context.favoritesDB.deleteFavoritePath(mediumPath)
            }

            if (directories.isEmpty()) {
                context.directoryDB.deleteDirPath(path)
            }

            context.mediaDB.insertAll(media)
            context.directoryDB.insertAll(directories.map { withExistingId(it) })
        }
    }

    // drops a folder the share no longer has, with everything under it. SmbWriter uses it for a
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
        }
    }

    // Moves every row under a folder to its new path, at any depth, and the folder's place in a
    // virtual group with them -- PCloudScanner.moveFolderRows() for the share, minus the
    // pcloud_items table the share has no counterpart for. Per-folder settings keyed by path
    // (sorting, the cover image, pinning) stay with the old path, as they do for a local folder.
    //
    // SmbWriter does this after a rename it asked for. Nothing else calls it: a share tells of no
    // change made elsewhere, so a folder renamed on the NAS is found by the next walk instead
    fun moveFolderRows(oldPath: String, newPath: String) {
        GalleryDatabase.getInstance(context).runInTransaction {
            context.mediaDB.updatePathsUnderFolder(oldPath, newPath)
            context.favoritesDB.updatePathsUnderFolder(oldPath, newPath)
            context.directoryDB.updatePathsUnderFolder(oldPath, newPath, newPath.getFilenameFromPath())
        }

        context.config.updateFolderGroupMemberPath(oldPath, newPath)
        context.config.updateSmbHiddenFolderPaths(oldPath, newPath)
    }

    // drops every row of the connection [connectionId], for one that was removed or pointed at
    // something else; the rows of the recycle bin go with them, the bin being on that share
    fun forgetAll(connectionId: Int) {
        RemoteScanPlaces.dropConnection(connectionId)
        val root = smbRootOf(connectionId)
        val rowPrefix = "$root/"
        GalleryDatabase.getInstance(context).runInTransaction {
            context.mediaDB.getPathsWithPrefix(rowPrefix).forEach { path ->
                context.mediaDB.deleteMediumPath(path)
                context.favoritesDB.deleteFavoritePath(path)
            }

            context.directoryDB.getPathsWithPrefix(rowPrefix).forEach { context.directoryDB.deleteDirPath(it) }
            context.directoryDB.deleteDirPath(root)
        }
    }
}

// thrown out of a scan that SmbScanner.abort() called off; nothing went wrong, so nothing is
// reported for it
class SmbScanAbortedException : Exception("SMB scan aborted")
