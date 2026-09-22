package org.fossify.gallery.helpers

import android.content.Context
import android.util.Log
import org.fossify.commons.extensions.getParentPath
import org.fossify.gallery.databases.GalleryDatabase
import org.fossify.gallery.extensions.favoritesDB
import org.fossify.gallery.extensions.mediaDB
import org.fossify.gallery.extensions.rebuildDirectoryRow

// Changes the share on the user's behalf and keeps the cache in step with what was changed, the
// same shape as PCloudWriter. The share is asked first and the rows follow only once it has
// agreed, so a refused write leaves the cache as it was.
//
// There is no recycle bin here, and there will not be one. The app's own bin is a folder on the
// storage it belongs to, and a folder the app made on somebody's NAS is a folder they would have
// to know about and clean up themselves; what is deleted from the share is deleted (#28). The
// screens say so before they ask this to do anything.
//
// Blocking and talking to the network, so call it off the main thread; throws what smbj threw
// when the share refused, and an IOException when it could not be reached
class SmbWriter(private val context: Context) {
    companion object {
        private const val TAG = "SmbWrite"
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

    // The folders and everything under them, from the share and from the cache alike.
    //
    // A folder whose delete failed keeps its rows, even though a recursive delete may have taken
    // some of what was under it before it stopped. The rows then name media the share no longer
    // has, which the next walk of that folder puts right -- and until it does, the folder list
    // says the folder is still there, which is the true half of what happened. Forgetting it
    // instead would take a folder that is still on the share out of the gallery, and leave
    // someone believing they had deleted something they had not
    fun deleteFolders(paths: List<String>) {
        val scanner = SmbScanner(context)
        var done = 0
        var failure: Exception? = null
        paths.forEach { path ->
            try {
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
}
