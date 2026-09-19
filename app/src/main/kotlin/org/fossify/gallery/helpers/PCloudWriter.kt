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
import org.fossify.commons.helpers.SORT_BY_SIZE
import org.fossify.gallery.R
import org.fossify.gallery.databases.GalleryDatabase
import org.fossify.gallery.extensions.config
import org.fossify.gallery.extensions.createDirectoryFromMedia
import org.fossify.gallery.extensions.directoryDB
import org.fossify.gallery.extensions.favoritesDB
import org.fossify.gallery.extensions.getNoMediaFoldersSync
import org.fossify.gallery.extensions.mediaDB
import org.fossify.gallery.extensions.pCloudItemsDB
import org.fossify.gallery.extensions.toPCloudRemotePath
import org.fossify.gallery.extensions.updateDBMediaPath
import org.fossify.gallery.models.Medium
import org.fossify.gallery.models.PCloudItem
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream

// Writes to pCloud on the user's behalf and keeps the cache in step with what was written.
// Every method asks pCloud first and touches the database only once pCloud has agreed, so a
// refused write leaves the cache as it was, and a list of items is handled one by one so that
// a failure halfway leaves the ones before it done on both sides.
//
// Deleting goes to pCloud's own trash, never to the device's recycle bin: there is no file
// to move there. Blocking and talking to the network, so call it off the main thread; throws
// PCloudException when pCloud refused and an IOException when it could not be reached
class PCloudWriter(private val context: Context) {
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

    // the folder and everything under it, from pCloud and from the cache alike
    fun deleteFolders(paths: List<String>) {
        val scanner = PCloudScanner(context)
        paths.forEach { path ->
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

    // Sends one local file into a pCloud folder. The cache learns of it when the destination
    // is rescanned, which the caller does once its batch is through
    fun uploadFile(localPath: String, destinationFolder: String) {
        val file = File(localPath)
        val name = localPath.getFilenameFromPath()
        val body: RequestBody = if (file.isFile) {
            file.asRequestBody(localPath.getMimeType().toMediaTypeOrNull())
        } else {
            // an OTG or SAF file has no File behind it; the stream has an unknown length
            StreamRequestBody(localPath.getMimeType().toMediaTypeOrNull()) {
                context.getFileInputStreamSync(localPath) ?: throw FileNotFoundException(localPath)
            }
        }

        val modifiedSeconds = (if (file.isFile) file.lastModified() else System.currentTimeMillis()) / 1000
        PCloudApi.upload(apiHost, accessToken, folderIdOf(destinationFolder), name, body, modifiedSeconds)
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
    private fun refreshDirectory(path: String) {
        val media = ArrayList<Medium>(context.mediaDB.getMediaFromPath(path))
        if (media.isEmpty()) {
            context.directoryDB.deleteDirPath(path)
            return
        }

        MediaFetcher(context).sortMedia(media, config.getFolderSorting(path), path)
        val directory = context.createDirectoryFromMedia(
            path = path,
            curMedia = media,
            albumCovers = config.parseAlbumCovers(),
            hiddenString = context.getString(R.string.hidden),
            includedFolders = config.includedFolders,
            getProperFileSize = config.directorySorting and SORT_BY_SIZE != 0,
            noMediaFolders = context.getNoMediaFoldersSync()
        )
        context.directoryDB.insert(directory)
    }
}
