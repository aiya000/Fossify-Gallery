package org.fossify.gallery.helpers

import android.content.Context
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
    // the folder's place in a virtual group. Per-folder settings keyed by path (sorting, the
    // cover image, pinning) stay with the old path, as they do for a local folder
    fun renameFolder(path: String, newName: String): String {
        val newPath = "${path.getParentPath()}/$newName"
        PCloudApi.renameFolder(apiHost, accessToken, path.toPCloudRemotePath(), newName)
        GalleryDatabase.getInstance(context).runInTransaction {
            context.mediaDB.updatePathsUnderFolder(path, newPath)
            context.favoritesDB.updatePathsUnderFolder(path, newPath)
            context.directoryDB.updatePathsUnderFolder(path, newPath, newName)
            context.pCloudItemsDB.updatePaths(path, newPath)
        }

        config.updateFolderGroupMemberPath(path, newPath)
        return newPath
    }

    // Answers the new folder's path. An empty folder gets no Directory row, the same as an
    // empty local folder, only a pcloud_items row so that a folder created inside it next
    // finds the id. The parent's id comes from its row; the root is folder 0
    fun createFolder(parentPath: String, name: String): String {
        val parentFolderId = if (parentPath == PCLOUD_PATH_SCHEME) {
            0L
        } else {
            context.pCloudItemsDB.getItem(parentPath)?.itemId ?: throw IllegalStateException("$parentPath is not in the pCloud cache")
        }

        val folderId = PCloudApi.createFolder(apiHost, accessToken, parentFolderId, name)
        val newPath = "$parentPath/$name"
        context.pCloudItemsDB.insertAll(listOf(PCloudItem(null, newPath, folderId, true, 0L, false, 0L)))
        return newPath
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
