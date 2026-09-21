package org.fossify.gallery.helpers

import android.content.Context
import android.util.Log
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
        // one scan at a time, claimed by Context.rescanSmb()
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
        // its next folder, with the cache as it was
        fun abortCurrent() {
            current?.abort()
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

    private val aborted = AtomicBoolean(false)

    fun abort() {
        aborted.set(true)
    }

    private fun throwIfAborted() {
        if (aborted.get()) {
            throw SmbScanAbortedException()
        }
    }

    // Walks the whole share and replaces the SMB rows with what it found. Blocks and talks to
    // the network, so call it off the main thread.
    //
    // onProgress is called as each folder is left behind, with what the walk holds so far and
    // the folder it has just come out of. A share has no count to work towards -- what is in it
    // is only known once it has all been walked -- so this is the only thing that can tell the
    // user a scan is moving rather than stuck. It runs on the scanning thread: keep it quick,
    // and do not touch a view from it
    fun scanAll(onProgress: (folderCount: Int, mediaCount: Int, path: String) -> Unit = { _, _, _ -> }): Result {
        val media = ArrayList<Medium>()
        val directories = ArrayList<Directory>()
        val collector = Collector(media, directories, onProgress)
        collector.collect(SMB_PATH_SCHEME, 0)

        store(media, directories, collector.skippedPaths)
        context.config.smbLastFullScanAt = System.currentTimeMillis()
        return Result(directories.size, media.size, collector.skippedPaths.size)
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

        val media = ArrayList<Medium>()
        val directories = ArrayList<Directory>()
        Collector(media, directories).collectOne(path, entries)
        storeFolder(path, media, directories)
        return Result(directories.size, media.size)
    }

    // the direct subfolders of a folder, for the folder pickers. Not cached: a picker is opened
    // rarely and a stale list of folders is worse there than a moment's wait
    fun listFolders(path: String): List<String> {
        return listWithOneRetry(path)
            .filter { it.isFolder }
            .map { childPathOf(path, it.name) }
            .sorted()
    }

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
            if (SmbClient.isConnected()) {
                throw e
            }

            Log.w(TAG, "Listing \"$path\" of the share again over a new connection", e)
            SmbClient.disconnect()
            SmbClient.list(context, path)
        }
    }

    private fun childPathOf(parentPath: String, name: String) =
        if (parentPath == SMB_PATH_SCHEME) "$SMB_PATH_PREFIX$name" else "$parentPath/$name"

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
    private inner class Collector(
        private val media: ArrayList<Medium>,
        private val directories: ArrayList<Directory>,
        private val onProgress: (folderCount: Int, mediaCount: Int, path: String) -> Unit = { _, _, _ -> }
    ) {
        private val config = context.config
        private val favoritePaths = context.getFavoritePaths()
        private val albumCovers = config.parseAlbumCovers()
        private val hiddenString = context.getString(R.string.hidden)
        private val includedFolders = config.includedFolders
        private val noMediaFolders = context.getNoMediaFoldersSync()
        private val getProperFileSize = config.directorySorting and SORT_BY_SIZE != 0
        private val mediaFetcher = MediaFetcher(context)

        // the folders the walk could not get into. They are not in the media it collected, and
        // store() must therefore not read their absence as the share having dropped them
        val skippedPaths = HashSet<String>()

        // Lists one folder and walks into its subfolders. A folder that cannot be listed -- no
        // permission for it, or it was removed while the walk ran -- is skipped rather than
        // ending the scan; the rest of the share is still worth having
        fun collect(path: String, depth: Int) {
            throwIfAborted()
            val entries = try {
                listWithOneRetry(path)
            } catch (e: Exception) {
                throwIfAborted()
                // The root not listing means the share is not reachable, which is not a folder
                // to skip over -- and neither is any folder whose listing left no connection
                // standing. Walking on without one skips every folder that is left, and the
                // scan then reports an empty share rather than an unreachable one
                if (depth == 0 || !SmbClient.isConnected()) {
                    throw e
                }

                Log.w(TAG, "Skipping \"$path\", a folder of the share that could not be listed", e)
                skippedPaths.add(path)
                return
            }

            collectOne(path, entries)
            onProgress(directories.size, media.size, path)
            if (depth >= MAX_DEPTH) {
                return
            }

            entries.filter { it.isFolder }.forEach { collect(childPathOf(path, it.name), depth + 1) }
        }

        // the media of one folder, without walking into anything
        fun collectOne(path: String, entries: List<SmbClient.Entry>) {
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
                return
            }

            mediaFetcher.sortMedia(folderMedia, config.getFolderSorting(path), path)
            directories.add(
                context.createDirectoryFromMedia(
                    path = path,
                    curMedia = folderMedia,
                    albumCovers = albumCovers,
                    hiddenString = hiddenString,
                    includedFolders = includedFolders,
                    getProperFileSize = getProperFileSize,
                    noMediaFolders = noMediaFolders
                )
            )

            media.addAll(folderMedia)
        }
    }

    // replaces the SMB rows in one transaction, so a folder list read in between never sees half
    // of a scan. Rows the share no longer has are dropped one by one: a NOT IN over thousands of
    // paths would trip SQLite's argument limit
    private fun store(media: List<Medium>, directories: List<Directory>, skippedPaths: Set<String>) {
        GalleryDatabase.getInstance(context).runInTransaction {
            val keptMediaPaths = media.map { it.path }.toHashSet()
            context.mediaDB.getPathsWithPrefix(SMB_PATH_SCHEME)
                .filter { it !in keptMediaPaths && !isUnderSkipped(it, skippedPaths) }
                .forEach { path ->
                    context.mediaDB.deleteMediumPath(path)
                    context.favoritesDB.deleteFavoritePath(path)
                }

            val keptDirectoryPaths = directories.map { it.path }.toHashSet()
            context.directoryDB.getPathsWithPrefix(SMB_PATH_SCHEME)
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

    // drops every SMB row there is, for a share that was unconfigured or pointed somewhere else
    fun forgetAll() {
        GalleryDatabase.getInstance(context).runInTransaction {
            context.mediaDB.getPathsWithPrefix(SMB_PATH_SCHEME).forEach { path ->
                context.mediaDB.deleteMediumPath(path)
                context.favoritesDB.deleteFavoritePath(path)
            }

            context.directoryDB.getPathsWithPrefix(SMB_PATH_SCHEME).forEach { context.directoryDB.deleteDirPath(it) }
        }
    }
}

// thrown out of a scan that SmbScanner.abort() called off; nothing went wrong, so nothing is
// reported for it
class SmbScanAbortedException : Exception("SMB scan aborted")
