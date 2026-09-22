package org.fossify.gallery.helpers

import android.content.Context
import android.util.Log
import org.fossify.gallery.extensions.mediaDB
import java.io.File

// The videos the user asked to have on the device before watching them, because reading one off
// the share as it plays stalls when the network cannot keep up.
//
// Deliberately not SmbFileCache. That one holds whatever needed a real file -- photos, GIFs, the
// editor's input -- under one 512MB budget, oldest out first. A single video here can be several
// gigabytes, so sharing that budget would mean one video emptying the photo cache, and holding
// videos to it would mean the big ones could never be kept at all.
//
// So this one has no size limit and is swept by age instead: a copy is dropped a day after it
// was last watched, like a bin that empties itself. Nothing schedules that sweep -- it runs when
// the app starts and when a download finishes, which is as close to "a day later" as an app that
// the system is free to kill can get. The user can also empty it by hand from the settings
class SmbVideoCache(private val context: Context) {
    companion object {
        private const val TAG = "SmbVideo"

        // how long a copy outlives the last time it was watched
        const val MAX_AGE_MILLIS = 24L * 60 * 60 * 1000

        // the copy is written under this and renamed into place, so a download that broke off
        // can never be picked up as a whole video
        const val PARTIAL_SUFFIX = ".part"
    }

    private val dir = File(context.cacheDir, SMB_VIDEO_CACHE_DIR)

    // the copy that is already there, or null. Never touches the network. The size and the
    // modification time are what name it, and a screen showing the video already holds both, so
    // this overload is the one to call from the main thread -- the other reads the database
    fun peek(path: String, size: Long, modified: Long): File? =
        File(dir, SmbFileCache.nameOf(path, size, modified)).takeIf { it.isFile && it.length() > 0 }

    fun peek(path: String): File? = targetOf(path)?.takeIf { it.isFile && it.length() > 0 }

    // where a download of this video would go, or null for a path no scan has seen: without the
    // size and the modification time there is no name to give it. Reads the database, so call it
    // off the main thread
    fun targetOf(path: String): File? {
        val medium = context.mediaDB.getMediumByPath(path) ?: return null
        return File(dir, SmbFileCache.nameOf(path, medium.size, medium.modified))
    }

    fun ensureDir(): File {
        dir.mkdirs()
        return dir
    }

    // Carries a downloaded video over to its owner's new name, the same as SmbFileCache does for
    // its copies and for the same reason. It matters more here: a copy in this directory is the
    // one the user waited on, and several gigabytes of it, so letting a rename cost a second
    // download would be the most expensive thing a rename could do.
    //
    // The copy keeps the time it was last watched, so the day it has left is not renewed by this
    fun renameCopy(oldPath: String, newPath: String, size: Long, modified: Long) {
        val from = File(dir, SmbFileCache.nameOf(oldPath, size, modified))
        if (from.isFile) {
            val lastWatched = from.lastModified()
            val to = File(dir, SmbFileCache.nameOf(newPath, size, modified))
            if (from.renameTo(to)) {
                to.setLastModified(lastWatched)
            }
        }
    }

    // the sweep goes by the last modification time, so watching a video again keeps it for
    // another day. Called when playback starts off a copy
    fun touch(path: String, size: Long, modified: Long) {
        peek(path, size, modified)?.setLastModified(System.currentTimeMillis())
    }

    // drops every copy that has not been watched for a day, and any leftover partial download.
    // Blocks on the file system, so call it off the main thread
    fun sweepExpired() {
        val files = dir.listFiles() ?: return
        val oldest = System.currentTimeMillis() - MAX_AGE_MILLIS
        var dropped = 0
        for (file in files) {
            // a partial file belongs to a download that is no longer running -- the service
            // deletes its own when it stops -- so it is of no use to anybody
            val isExpired = file.lastModified() < oldest || file.name.endsWith(PARTIAL_SUFFIX)
            if (isExpired && file.delete()) {
                dropped++
            }
        }

        if (dropped > 0) {
            Log.i(TAG, "Dropped $dropped downloaded videos that had not been watched for a day")
        }
    }

    // what the settings row shows next to the clear action
    fun size(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    fun clear() {
        dir.deleteRecursively()
    }
}
