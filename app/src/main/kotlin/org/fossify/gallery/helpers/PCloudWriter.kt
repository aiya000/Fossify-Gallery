package org.fossify.gallery.helpers

import android.content.Context
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okio.BufferedSink
import okio.source
import org.fossify.commons.extensions.getFileInputStreamSync
import org.fossify.commons.extensions.getFilenameFromPath
import org.fossify.commons.extensions.getMimeType
import org.fossify.commons.extensions.getParentPath
import org.fossify.gallery.databases.GalleryDatabase
import org.fossify.gallery.extensions.config
import org.fossify.gallery.extensions.favoritesDB
import org.fossify.gallery.extensions.fromPCloudRecycleBinPath
import org.fossify.gallery.extensions.isPCloudRecycleBinPath
import org.fossify.gallery.extensions.mediaDB
import org.fossify.gallery.extensions.pCloudItemsDB
import org.fossify.gallery.extensions.rebuildDirectoryRow
import org.fossify.gallery.extensions.toPCloudRecycleBinPath
import org.fossify.gallery.extensions.toPCloudRemotePath
import org.fossify.gallery.extensions.updateDBMediaPath
import org.fossify.gallery.models.PCloudItem
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream

// Writes to pCloud on the user's behalf and keeps the cache in step with what was written.
// Every method asks pCloud first and touches the database only once pCloud has agreed, so a
// refused write leaves the cache as it was, and a list of items is handled one by one so that
// a failure halfway leaves the ones before it done on both sides.
//
// Deleting for good goes to pCloud's own trash, which this app cannot read; the app's own
// recycle bin is a folder on pCloud that media are moved into, see moveToRecycleBin().
// Blocking and talking to the network, so call it off the main thread; throws
// PCloudException when pCloud refused and an IOException when it could not be reached
class PCloudWriter(private val context: Context) {
    companion object {
        // how many "name (n)" variants are tried before a taken name is given up on
        private const val MAX_NAME_ATTEMPTS = 100

        // what the original is called while an overwrite is in flight. It is visible on
        // pcloud.com for those few seconds, so it says what it is
        private const val STASH_SUFFIX = "being-replaced"
    }

    private val config = context.config
    private val apiHost get() = config.pCloudApiHost
    private val accessToken get() = config.pCloudAccessToken

    // A file pCloud no longer has counts as deleted: its row goes like the others. The folders
    // the files were in get their rows rebuilt from what is left
    fun deleteFiles(paths: List<String>) {
        paths.forEach { path ->
            try {
                PCloudApi.deleteFile(apiHost, accessToken, path.toPCloudRemotePath())
            } catch (e: PCloudException) {
                if (e.result != PCLOUD_RESULT_FILE_NOT_FOUND) {
                    throw e
                }
            }

            GalleryDatabase.getInstance(context).runInTransaction {
                context.mediaDB.deleteMediumPath(path)
                context.favoritesDB.deleteFavoritePath(path)
                context.pCloudItemsDB.deleteItemPath(path)
            }
        }

        paths.map { it.getParentPath() }.distinct().forEach { refreshDirectory(it) }
    }

    // The folder and everything under it, from pCloud and from the cache alike. With
    // toRecycleBin the media under it, at any depth, go to the app's recycle bin first, one
    // by one; the folder, with whatever else was in it, then goes to pCloud's trash as before
    fun deleteFolders(paths: List<String>, toRecycleBin: Boolean = false) {
        val scanner = PCloudScanner(context)
        paths.forEach { path ->
            if (toRecycleBin) {
                val media = context.mediaDB.getPathsWithPrefix("$path/").filter { !it.isPCloudRecycleBinPath() }
                moveToRecycleBin(media, refreshFolders = false)
            }

            try {
                PCloudApi.deleteFolderRecursive(apiHost, accessToken, path.toPCloudRemotePath())
            } catch (e: PCloudException) {
                if (e.result != PCLOUD_RESULT_DIRECTORY_NOT_FOUND) {
                    throw e
                }
            }

            scanner.forget(path)
        }
    }

    // Moves the media into the app's recycle bin on pCloud (a folder in the root, made on
    // first use) and marks their rows deleted under the bin's pseudo path, which keeps the
    // original layout for the restore; see PCLOUD_RECYCLE_BIN. The bin folder is flat, so a
    // name that is taken there gets a number, and so does a pseudo path a row already holds
    // (the same file deleted twice). A medium the cache has no row for cannot be moved by id
    // and is passed over. The folders the media were in get their rows rebuilt, unless the
    // caller is about to drop them anyway
    fun moveToRecycleBin(paths: List<String>, refreshFolders: Boolean = true) {
        if (paths.isEmpty()) {
            return
        }

        val binFolderId = recycleBinFolderId()
        paths.forEach { path ->
            val item = context.pCloudItemsDB.getItem(path) ?: return@forEach
            moveWithFreeName(item.itemId, binFolderId, path.getFilenameFromPath())
            val binPath = freeRecycleBinPath(path.toPCloudRecycleBinPath())
            GalleryDatabase.getInstance(context).runInTransaction {
                context.mediaDB.updateDeleted(binPath, System.currentTimeMillis(), path)
                context.pCloudItemsDB.updatePaths(path, binPath)
            }
        }

        if (refreshFolders) {
            paths.map { it.getParentPath() }.distinct().forEach { refreshDirectory(it) }
        }
    }

    // Brings media back out of the recycle bin, into the folder each was deleted from, or into
    // destinationFolder for all of them. A folder pCloud no longer has is made again, with
    // the folders above it; a name that is taken there gets a number. The rows follow, with
    // their deleted mark cleared, and the folders restored into get their rows rebuilt.
    // Answers the paths the media have now
    fun restoreFromRecycleBin(paths: List<String>, destinationFolder: String? = null): List<String> {
        val restored = ArrayList<String>()
        paths.forEach { binPath ->
            val item = context.pCloudItemsDB.getItem(binPath) ?: throw IllegalStateException("$binPath is not in the pCloud cache")
            val name = context.mediaDB.getMediumByPath(binPath)?.name ?: binPath.getFilenameFromPath()
            val folder = destinationFolder ?: binPath.fromPCloudRecycleBinPath().getParentPath()
            val folderId = ensureFolder(folder)
            val newName = moveWithFreeName(item.itemId, folderId, name)
            val newPath = "$folder/$newName"
            GalleryDatabase.getInstance(context).runInTransaction {
                context.mediaDB.restoreDeleted(binPath, newPath, folder, newName)
                context.pCloudItemsDB.updatePaths(binPath, newPath)
            }

            restored.add(newPath)
        }

        restored.map { it.getParentPath() }.distinct().forEach { refreshDirectory(it) }
        return restored
    }

    // Deletes media in the recycle bin for good, which on pCloud means its own trash. A file
    // pCloud no longer has counts as deleted
    fun deleteFromRecycleBin(paths: List<String>) {
        paths.forEach { binPath ->
            context.pCloudItemsDB.getItem(binPath)?.let { item ->
                try {
                    PCloudApi.deleteFileById(apiHost, accessToken, item.itemId)
                } catch (e: PCloudException) {
                    if (e.result != PCLOUD_RESULT_FILE_NOT_FOUND) {
                        throw e
                    }
                }
            }

            GalleryDatabase.getInstance(context).runInTransaction {
                context.mediaDB.deleteMediumPath(binPath)
                context.pCloudItemsDB.deleteItemPath(binPath)
            }
        }
    }

    fun emptyRecycleBin() {
        deleteFromRecycleBin(context.mediaDB.getPCloudDeletedMedia().map { it.path })
    }

    // The path a folder was deleted from is where a restore puts it back, and whether pCloud
    // still has that folder; for the dialog that asks before a restore. Reads the cache only
    fun restoreDestinationOf(binPath: String): Pair<String, Boolean> {
        val folder = binPath.fromPCloudRecycleBinPath().getParentPath()
        val exists = folder == PCLOUD_PATH_SCHEME || context.pCloudItemsDB.getItem(folder) != null
        return Pair(folder, exists)
    }

    // the id of the recycle bin folder on pCloud, made on first use; its row stays across
    // full scans, see PCloudItemDao.deleteAllOutsideRecycleBin()
    private fun recycleBinFolderId(): Long {
        context.pCloudItemsDB.getItem(PCLOUD_RECYCLE_BIN)?.let { return it.itemId }
        val folderId = PCloudApi.createFolderIfNotExists(apiHost, accessToken, 0L, RECYCLE_BIN_FOLDER_NAME)
        context.pCloudItemsDB.insertAll(listOf(PCloudItem(null, PCLOUD_RECYCLE_BIN, folderId, true, 0L, false, 0L)))
        return folderId
    }

    // The id of a folder by pseudo path, made together with the folders above it when pCloud
    // does not have it. Asked of pCloud each time rather than read from the cache, so that a
    // row left from a folder deleted elsewhere never sends a file into the void; the row is
    // brought up to date on the way
    private fun ensureFolder(path: String): Long {
        if (path == PCLOUD_PATH_SCHEME) {
            return 0L
        }

        val parentId = ensureFolder(path.getParentPath())
        val folderId = PCloudApi.createFolderIfNotExists(apiHost, accessToken, parentId, path.getFilenameFromPath())
        val row = context.pCloudItemsDB.getItem(path)
        if (row == null || row.itemId != folderId) {
            context.pCloudItemsDB.insertAll(listOf(PCloudItem(null, path, folderId, true, 0L, false, 0L)))
        }

        return folderId
    }

    // moves the file into the folder under the name, or under "name (n)" when that is taken;
    // answers the name it ended up with
    private fun moveWithFreeName(fileId: Long, toFolderId: Long, name: String): String {
        for (attempt in 1..MAX_NAME_ATTEMPTS) {
            val candidate = if (attempt == 1) name else numberedName(name, attempt)
            try {
                PCloudApi.moveFileById(apiHost, accessToken, fileId, toFolderId, candidate)
                return candidate
            } catch (e: PCloudException) {
                if (e.result != PCLOUD_RESULT_ALREADY_EXISTS) {
                    throw e
                }
            }
        }

        throw PCloudException(PCLOUD_RESULT_ALREADY_EXISTS, "no free name for $name")
    }

    // the pseudo path itself, or "name (n)" when a row already holds it
    private fun freeRecycleBinPath(binPath: String): String {
        if (context.pCloudItemsDB.getItem(binPath) == null) {
            return binPath
        }

        val parent = binPath.getParentPath()
        val name = binPath.getFilenameFromPath()
        for (attempt in 2..MAX_NAME_ATTEMPTS) {
            val candidate = "$parent/${numberedName(name, attempt)}"
            if (context.pCloudItemsDB.getItem(candidate) == null) {
                return candidate
            }
        }

        throw IllegalStateException("no free recycle bin path for $binPath")
    }

    // "IMG_0001.jpg", 2 -> "IMG_0001 (2).jpg"
    private fun numberedName(name: String, n: Int): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) "${name.substring(0, dot)} ($n)${name.substring(dot)}" else "$name ($n)"
    }

    // Answers the new path. The pcloud_items row keeps its id and hash, so a thumbnail or a
    // cached original fetched under the old name is still found
    fun renameFile(path: String, newName: String): String {
        val newPath = "${path.getParentPath()}/$newName"
        PCloudApi.renameFile(apiHost, accessToken, path.toPCloudRemotePath(), newName)
        GalleryDatabase.getInstance(context).runInTransaction {
            context.updateDBMediaPath(path, newPath)
            context.pCloudItemsDB.updatePaths(path, newPath)
        }

        // the folder's thumbnail may have been this file
        refreshDirectory(path.getParentPath())
        return newPath
    }

    // Answers the new path. Every row under the folder follows it, at any depth, and so does
    // the folder's place in a virtual group, see PCloudScanner.moveFolderRows()
    fun renameFolder(path: String, newName: String): String {
        val newPath = "${path.getParentPath()}/$newName"
        PCloudApi.renameFolder(apiHost, accessToken, path.toPCloudRemotePath(), newName)
        PCloudScanner(context).moveFolderRows(path, newPath)
        return newPath
    }

    // Answers the new folder's path. An empty folder gets no Directory row, the same as an
    // empty local folder, only a pcloud_items row so that a folder created inside it next
    // finds the id
    fun createFolder(parentPath: String, name: String): String {
        val folderId = PCloudApi.createFolder(apiHost, accessToken, folderIdOf(parentPath), name)
        val newPath = "$parentPath/$name"
        context.pCloudItemsDB.insertAll(listOf(PCloudItem(null, newPath, folderId, true, 0L, false, 0L)))
        return newPath
    }

    // A copy within pCloud, one API call, nothing downloaded. The cache learns of the copy
    // when the destination is rescanned, which the caller does once its batch is through
    fun copyFileTo(path: String, destinationFolder: String) {
        PCloudApi.copyFileTo(apiHost, accessToken, path.toPCloudRemotePath(), folderIdOf(destinationFolder))
    }

    // A move within pCloud, one API call. The rows follow the file like they do for a rename,
    // and both folders get their Directory rows rebuilt. Answers the new path
    fun moveFileTo(path: String, destinationFolder: String): String {
        val newPath = "$destinationFolder/${path.substringAfterLast('/')}"
        PCloudApi.moveFileTo(apiHost, accessToken, path.toPCloudRemotePath(), folderIdOf(destinationFolder))
        GalleryDatabase.getInstance(context).runInTransaction {
            context.updateDBMediaPath(path, newPath)
            context.pCloudItemsDB.updatePaths(path, newPath)
        }

        refreshDirectory(path.getParentPath())
        refreshDirectory(destinationFolder)
        return newPath
    }

    // Writes a local file over an existing pCloud file, without ever leaving the medium
    // missing. The original is renamed aside first -- one API call, nothing is downloaded or
    // uploaded again for it -- then the new content goes in under the original name, and
    // only once that has landed is the stashed original dropped. When the upload fails the
    // stash is renamed back, so what is on pCloud afterwards is either the old file or the
    // new one, never neither. The caller keeps the local file it handed in, so nothing the
    // user made is lost even when the stash cannot be put back
    fun overwriteFile(path: String, localPath: String) {
        val item = context.pCloudItemsDB.getItem(path) ?: throw IllegalStateException("$path is not in the pCloud cache")
        val name = path.getFilenameFromPath()
        val stashName = "$name.$STASH_SUFFIX"
        PCloudApi.renameFileById(apiHost, accessToken, item.itemId, stashName)

        val uploaded = try {
            uploadFile(localPath, path.getParentPath(), name, overwrite = true)
        } catch (e: Exception) {
            // put the original back under its own name; the medium is whole again
            PCloudApi.renameFileById(apiHost, accessToken, item.itemId, name)
            throw e
        }

        PCloudApi.deleteFileById(apiHost, accessToken, item.itemId)
        if (uploaded != null) {
            // the id and hash name the cached copy and the thumbnail, so the new content is
            // shown instead of the old one from here on
            context.pCloudItemsDB.insertAll(listOf(item.copy(itemId = uploaded.fileId, contentHash = uploaded.contentHash)))
        }
    }

    // Sends one local file into a pCloud folder. The cache learns of it when the destination
    // is rescanned, which the caller does once its batch is through
    fun uploadFile(localPath: String, destinationFolder: String) {
        uploadFile(localPath, destinationFolder, localPath.getFilenameFromPath(), overwrite = false)
    }

    private fun uploadFile(localPath: String, destinationFolder: String, name: String, overwrite: Boolean): PCloudApi.UploadedFile? {
        val file = File(localPath)
        val body: RequestBody = if (file.isFile) {
            file.asRequestBody(localPath.getMimeType().toMediaTypeOrNull())
        } else {
            // an OTG or SAF file has no File behind it; the stream has an unknown length
            StreamRequestBody(localPath.getMimeType().toMediaTypeOrNull()) {
                context.getFileInputStreamSync(localPath) ?: throw FileNotFoundException(localPath)
            }
        }

        val modifiedSeconds = (if (file.isFile) file.lastModified() else System.currentTimeMillis()) / 1000
        return PCloudApi.upload(
            apiHost = apiHost,
            accessToken = accessToken,
            toFolderId = folderIdOf(destinationFolder),
            name = name,
            body = body,
            modifiedSeconds = modifiedSeconds,
            renameIfExists = !overwrite
        )
    }

    // the id the API wants for a folder: the root is folder 0, everything else has a row
    // from the scanner or from createFolder()
    private fun folderIdOf(path: String): Long {
        if (path == PCLOUD_PATH_SCHEME) {
            return 0L
        }

        return context.pCloudItemsDB.getItem(path)?.itemId ?: throw IllegalStateException("$path is not in the pCloud cache")
    }

    private class StreamRequestBody(private val mediaType: MediaType?, private val open: () -> InputStream) : RequestBody() {
        override fun contentType() = mediaType

        override fun contentLength() = -1L

        override fun writeTo(sink: BufferedSink) {
            open().use { input ->
                input.source().use { sink.writeAll(it) }
            }
        }
    }

    // rebuilds a folder's row from the media rows left in it, the way the scanner builds it,
    // or drops the row when nothing is left. The folder's own pcloud_items row stays
    private fun refreshDirectory(path: String) = context.rebuildDirectoryRow(path)
}
