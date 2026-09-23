package org.fossify.gallery.helpers

import android.content.Context
import android.util.Log
import org.fossify.commons.extensions.getFilenameFromPath
import org.fossify.commons.extensions.getParentPath
import org.fossify.gallery.databases.GalleryDatabase
import org.fossify.gallery.extensions.favoritesDB
import org.fossify.gallery.extensions.fromSmbRecycleBinPath
import org.fossify.gallery.extensions.isSmbRecycleBinPath
import org.fossify.gallery.extensions.mediaDB
import org.fossify.gallery.extensions.rebuildDirectoryRow
import org.fossify.gallery.extensions.toSmbRecycleBinPath
import org.fossify.gallery.extensions.updateDBMediaPath
import org.fossify.gallery.models.Medium
import java.io.File

// Changes the share on the user's behalf and keeps the cache in step with what was changed, the
// same shape as PCloudWriter. The share is asked first and the rows follow only once it has
// agreed, so a refused write leaves the cache as it was.
//
// The app's own recycle bin is a folder on the share, the same as on pCloud, see
// moveToRecycleBin(): a delete moves the medium into it with the one request a rename uses,
// and no bytes travel for a delete or a restore. That is why the bin is on the share and not
// on this device (#112). It was "no bin on the share" once (#28), when the share was taken
// for somebody else's NAS; it is the maintainer's own.
//
// Blocking and talking to the network, so call it off the main thread; throws what smbj threw
// when the share refused, and an IOException when it could not be reached
class SmbWriter(private val context: Context) {
    companion object {
        private const val TAG = "SmbWrite"

        // what the original is called while a write over it is in flight. It is visible on the
        // share for those few seconds, so it says what it is
        private const val STASH_SUFFIX = "being-replaced"
    }

    // Deletes media from the share and drops their rows. The list is walked to the end even
    // when one of them fails, so that a single file the share will not part with -- one held
    // open elsewhere, one in a folder that has turned read-only -- does not decide the fate of
    // the rest of the selection; what went wrong is thrown once the rest is through.
    //
    // The folders they were in get their rows rebuilt from the media left, which is what takes
    // a folder that is now empty out of the folder list
    fun deleteFiles(paths: List<String>) {
        var done = 0
        var failure: Exception? = null
        paths.forEach { path ->
            try {
                SmbClient.delete(context, path)
            } catch (e: Exception) {
                // a toast is gone the moment it is read, and a delete that failed is exactly
                // what someone comes back to look into later
                Log.w(TAG, "$path could not be deleted from the share", e)
                failure = e
                return@forEach
            }

            GalleryDatabase.getInstance(context).runInTransaction {
                context.mediaDB.deleteMediumPath(path)
                context.favoritesDB.deleteFavoritePath(path)
            }

            done++
        }

        paths.map { it.getParentPath() }.distinct().forEach { context.rebuildDirectoryRow(it) }
        // said once per batch, after the rows have gone with the files, so the line means "they
        // are off the share and the lists would not show them"
        Log.i(TAG, "Deleted $done of ${paths.size} media from the share")
        failure?.let { throw it }
    }

    // The folders and everything under them, from the share and from the cache alike. With
    // toRecycleBin the media the gallery lists under them, at any depth, go to the recycle bin
    // first, one by one; the folder, with whatever else was in it, then goes for good.
    //
    // A folder whose delete failed keeps its rows, even though a recursive delete may have taken
    // some of what was under it before it stopped. The rows then name media the share no longer
    // has, which the next walk of that folder puts right -- and until it does, the folder list
    // says the folder is still there, which is the true half of what happened. Forgetting it
    // instead would take a folder that is still on the share out of the gallery, and leave
    // someone believing they had deleted something they had not
    fun deleteFolders(paths: List<String>, toRecycleBin: Boolean = false) {
        val scanner = SmbScanner(context)
        var done = 0
        var failure: Exception? = null
        paths.forEach { path ->
            try {
                if (toRecycleBin) {
                    val media = context.mediaDB.getPathsWithPrefix("$path/").filter { !it.isSmbRecycleBinPath() }
                    moveToRecycleBin(media, refreshFolders = false)
                }

                SmbClient.deleteFolder(context, path)
            } catch (e: Exception) {
                Log.w(TAG, "$path could not be deleted from the share", e)
                failure = e
                return@forEach
            }

            scanner.forget(path)
            done++
        }

        Log.i(TAG, "Deleted $done of ${paths.size} folders from the share")
        failure?.let { throw it }
    }

    // Moves media into the app's recycle bin on the share -- a folder in the root, made on
    // first use, see SMB_RECYCLE_BIN -- and marks their rows deleted under the path each has
    // there. The bin keeps the original layout, so the path in the bin is the original path
    // under the bin's folder; the same file deleted twice gets a number the second time. The
    // rows keep their name, which is what a restore gives the file back, and the cached copies
    // follow the file so the bin's thumbnails are not fetched again.
    //
    // One at a time, and the list is walked to the end when one fails, the same as
    // deleteFiles(): what went wrong is thrown once the rest is through. The folders the media
    // were in get their rows rebuilt, unless the caller is about to drop them anyway
    fun moveToRecycleBin(paths: List<String>, refreshFolders: Boolean = true) {
        if (paths.isEmpty()) {
            return
        }

        var done = 0
        var failure: Exception? = null
        paths.forEach { path ->
            val medium = context.mediaDB.getMediumByPath(path)
            try {
                val binFolder = path.toSmbRecycleBinPath().getParentPath()
                SmbClient.createFolder(context, binFolder)
                val binPath = availableName(binFolder, path.getFilenameFromPath()) { SmbClient.fileExists(context, it) }
                SmbClient.moveTo(context, path, binPath)
                GalleryDatabase.getInstance(context).runInTransaction {
                    context.mediaDB.updateDeleted(binPath, System.currentTimeMillis(), path)
                    context.favoritesDB.deleteFavoritePath(path)
                }

                medium?.let { renameCachedCopies(path, binPath, it) }
            } catch (e: Exception) {
                Log.w(TAG, "$path could not be moved into the share's recycle bin", e)
                failure = e
                return@forEach
            }

            done++
        }

        if (refreshFolders) {
            paths.map { it.getParentPath() }.distinct().forEach { context.rebuildDirectoryRow(it) }
        }

        // said once per batch, after the rows have moved with the files, so the line means "they
        // are in the bin and the folder would not show them"
        Log.i(TAG, "Moved $done of ${paths.size} media into the share's recycle bin")
        failure?.let { throw it }
    }

    // Brings media back out of the recycle bin, into the folder each was deleted from, or into
    // destinationFolder for all of them. A folder the share no longer has is made again, with
    // the folders above it; a name that is taken there gets a number. The rows follow, with
    // their deleted mark cleared, and the folders restored into get their rows rebuilt; the
    // folders left empty in the bin are taken away. Answers the paths the media have now
    fun restoreFromRecycleBin(paths: List<String>, destinationFolder: String? = null): List<String> {
        val restored = ArrayList<String>()
        var failure: Exception? = null
        paths.forEach { binPath ->
            val medium = context.mediaDB.getMediumByPath(binPath)
            val name = medium?.name ?: binPath.getFilenameFromPath()
            val folder = destinationFolder ?: binPath.fromSmbRecycleBinPath().getParentPath()
            try {
                SmbClient.createFolder(context, folder)
                val newPath = availableName(folder, name) { SmbClient.fileExists(context, it) }
                SmbClient.moveTo(context, binPath, newPath)
                GalleryDatabase.getInstance(context).runInTransaction {
                    context.mediaDB.restoreDeleted(binPath, newPath, folder, newPath.getFilenameFromPath())
                }

                medium?.let { renameCachedCopies(binPath, newPath, it) }
                pruneEmptyBinFolders(binPath.getParentPath())
                restored.add(newPath)
            } catch (e: Exception) {
                Log.w(TAG, "$binPath could not be restored from the share's recycle bin", e)
                failure = e
            }
        }

        restored.map { it.getParentPath() }.distinct().forEach { context.rebuildDirectoryRow(it) }
        Log.i(TAG, "Restored ${restored.size} of ${paths.size} media from the share's recycle bin")
        failure?.let { throw it }
        return restored
    }

    // Deletes media in the recycle bin for good, and drops their rows and their cached copies.
    // A file the share no longer has counts as deleted. The list is walked to the end when one
    // fails, so that the daily sweep gets as far as it can and tries the rest again tomorrow
    fun deleteFromRecycleBin(paths: List<String>) {
        var done = 0
        var failure: Exception? = null
        paths.forEach { binPath ->
            val medium = context.mediaDB.getMediumByPath(binPath)
            try {
                SmbClient.delete(context, binPath)
                pruneEmptyBinFolders(binPath.getParentPath())
            } catch (e: Exception) {
                Log.w(TAG, "$binPath could not be deleted from the share's recycle bin", e)
                failure = e
                return@forEach
            }

            context.mediaDB.deleteMediumPath(binPath)
            medium?.let {
                SmbFileCache(context).deleteCopy(binPath, it.size, it.modified)
                SmbVideoCache(context).deleteCopy(binPath, it.size, it.modified)
            }

            done++
        }

        Log.i(TAG, "Deleted $done of ${paths.size} media from the share's recycle bin")
        failure?.let { throw it }
    }

    fun emptyRecycleBin() {
        deleteFromRecycleBin(context.mediaDB.getSmbDeletedMedia().map { it.path })
    }

    // The path a medium was deleted from is where a restore puts it back, and whether the
    // share still has that folder; for the dialog that asks before a restore. Asks the share,
    // which is a request over the network, so this belongs off the main thread. A share that
    // cannot be asked is taken to have the folder: the dialog is not the place to report that,
    // the restore that follows it is
    fun restoreDestinationOf(binPath: String): Pair<String, Boolean> {
        val folder = binPath.fromSmbRecycleBinPath().getParentPath()
        val exists = folder == SMB_PATH_SCHEME || try {
            SmbClient.folderExists(context, folder)
        } catch (e: Exception) {
            Log.w(TAG, "Could not ask the share whether it still has $folder", e)
            true
        }

        return Pair(folder, exists)
    }

    // the folders of the bin's layout are made as media go in, and taken away again once they
    // hold nothing, from the one the medium was in up to the bin itself, which stays. A folder
    // that still holds something ends the walk, and so does one that would not go
    private fun pruneEmptyBinFolders(folder: String) {
        var current = folder
        while (current != SMB_RECYCLE_BIN && current.isSmbRecycleBinPath()) {
            val removed = try {
                SmbClient.deleteFolderIfEmpty(context, current)
            } catch (e: Exception) {
                Log.w(TAG, "$current, a folder of the share's recycle bin, could not be looked at", e)
                false
            }

            if (!removed) {
                return
            }

            current = current.getParentPath()
        }
    }

    // Gives a medium of the share another name, in the folder it is already in. Answers the new
    // path.
    //
    // One at a time, which is what the screens offer: the share refuses a name that is taken
    // rather than numbering it, so a batch would have to decide what to do about each refusal,
    // and nobody has asked for that yet
    fun renameFile(path: String, newName: String): String {
        val newPath = "${path.getParentPath()}/$newName"
        // read before the rows move, so the cached copies can be found under their old name
        val medium = context.mediaDB.getMediumByPath(path)
        SmbClient.rename(context, path, newName)
        GalleryDatabase.getInstance(context).runInTransaction {
            context.updateDBMediaPath(path, newPath)
        }

        medium?.let { renameCachedCopies(path, newPath, it) }
        // the folder's thumbnail may have been this medium, and it is kept as a path
        context.rebuildDirectoryRow(path.getParentPath())
        Log.i(TAG, "Renamed a medium on the share to \"$newName\"")
        return newPath
    }

    // Gives a folder of the share another name, where it already is. Answers the new path.
    //
    // Every row under it follows, at any depth, and so does the folder's place in a virtual
    // group; see SmbScanner.moveFolderRows(). The share moves the whole subtree itself in the one
    // request, so there is no half-renamed folder to think about here -- unlike the recursive
    // delete, which is many requests and can stop in the middle
    fun renameFolder(path: String, newName: String): String {
        val newPath = "${path.getParentPath()}/$newName"
        val media = context.mediaDB.getMediaWithPrefix("$path/")
        SmbClient.rename(context, path, newName)
        SmbScanner(context).moveFolderRows(path, newPath)
        media.forEach { renameCachedCopies(it.path, newPath + it.path.substring(path.length), it) }
        Log.i(TAG, "Renamed a folder on the share to \"$newName\", with ${media.size} media under it")
        return newPath
    }

    // Moves a medium into another folder of the same share. Answers the path it has now.
    //
    // No bytes move. The share does this with the same request a rename uses, so a video of
    // several gigabytes travels as fast as a thumbnail -- which is the whole reason a move within
    // one storage is worth having as its own case, instead of a copy followed by a delete.
    //
    // A name the destination already has gets a number, the way a copy into a folder does: the
    // user picked a folder and not a name, so a collision there is not theirs to be asked about.
    // The rows and the cached copies follow, since what moved is the same bytes under a new path
    fun moveFileTo(path: String, destinationFolder: String): String {
        // read before the rows move, so the cached copies can be found under their old name
        val medium = context.mediaDB.getMediumByPath(path)
        val newPath = availableName(destinationFolder, path.getFilenameFromPath()) { SmbClient.fileExists(context, it) }
        SmbClient.moveTo(context, path, newPath)
        GalleryDatabase.getInstance(context).runInTransaction {
            context.updateDBMediaPath(path, newPath)
        }

        medium?.let { renameCachedCopies(path, newPath, it) }
        // both ends: the folder it left may now be empty, and the one it arrived in has a medium
        // more -- and either of their thumbnails may have been this one
        context.rebuildDirectoryRow(path.getParentPath())
        context.rebuildDirectoryRow(destinationFolder)
        Log.i(TAG, "Moved a medium into $destinationFolder on the share")
        return newPath
    }

    // Writes a local file over a medium of the share, without ever leaving the medium missing.
    //
    // The share has no "replace this file" request of its own: create() refuses a name that is
    // taken, and opening the file that is there to write over it in place would leave a torn
    // medium behind if the write broke off half way. So the original is renamed aside first --
    // one request, nothing downloaded or uploaded again for it -- the new content is written
    // under the name that is now free, and only once that has landed is the stash deleted. When
    // the write fails the stash is given its name back, so what is on the share afterwards is
    // either the old file or the new one, never neither. This is PCloudWriter.overwriteFile()
    // built out of the requests the share has.
    //
    // An overwritten medium does not pass through the bin, on any storage, which is why the
    // screens ask before they call this at all. The caller keeps the local file it handed in, so
    // nothing the user made is lost even when the stash cannot be put back
    fun overwriteFile(path: String, localPath: String) {
        val local = File(localPath)
        val name = path.getFilenameFromPath()
        val stashPath = "$path.$STASH_SUFFIX"
        // read before anything moves, so the cached copies can still be told by their old name
        val medium = context.mediaDB.getMediumByPath(path)
        SmbClient.rename(context, path, "$name.$STASH_SUFFIX")

        try {
            SmbClient.create(context, path) { output -> local.inputStream().use { it.copyTo(output) } }
            // the gallery sorts by this, and a medium written over is a medium changed just now
            SmbClient.setModified(context, path, local.lastModified())
        } catch (e: Exception) {
            try {
                SmbClient.rename(context, stashPath, name)
            } catch (restore: Exception) {
                // the one outcome worth a line of its own: the share is left holding the medium
                // under a name this app invented, and nobody would think to look for it
                Log.e(TAG, "$stashPath could not be given its name back after a failed overwrite", restore)
            }

            Log.w(TAG, "$path could not be written over on the share", e)
            throw e
        }

        SmbClient.delete(context, stashPath)
        medium?.let {
            // The size and the modification time are what name a cached copy, so the copies of
            // what was there before are dropped while the row still says which ones they are.
            // The row then moves on to the new pair, which is what stops the old copy being
            // served for the new content
            SmbFileCache(context).deleteCopy(path, it.size, it.modified)
            SmbVideoCache(context).deleteCopy(path, it.size, it.modified)
            context.mediaDB.updateSizeAndModified(path = path, size = local.length(), modified = local.lastModified())
        }

        // the folder's thumbnail may be this medium, and the folder's own row counts its bytes
        context.rebuildDirectoryRow(path.getParentPath())
        Log.i(TAG, "Wrote over \"$name\" on the share")
    }

    // What the share renamed is the same bytes, so whatever was fetched or downloaded for it is
    // carried over to the new name rather than left to be swept and fetched again
    private fun renameCachedCopies(oldPath: String, newPath: String, medium: Medium) {
        SmbFileCache(context).renameCopy(oldPath, newPath, medium.size, medium.modified)
        SmbVideoCache(context).renameCopy(oldPath, newPath, medium.size, medium.modified)
    }
}
