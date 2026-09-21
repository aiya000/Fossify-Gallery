package org.fossify.gallery.helpers

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Log

// How long a video on the share is, read out of the file itself.
//
// A share says nothing about it -- a listing carries a name, a size and a timestamp -- and the
// scan never opens a file, so this is the only place a length can come from. It costs the same
// few kilobytes of random reads a thumbnail does, the container's index rather than the video,
// but it is still a file opened over the network.
//
// Blocks on the network, so it may only be called off the main thread
object SmbVideoDuration {
    private const val TAG = "SmbVideo"

    // 0 when the file could not be opened or carries no length
    fun readMillis(context: Context, path: String): Long {
        val retriever = MediaMetadataRetriever()
        val source = try {
            SmbMediaDataSource(context, path)
        } catch (e: Exception) {
            Log.w(TAG, "Could not open $path to read its duration", e)
            retriever.release()
            return 0L
        }

        return try {
            retriever.setDataSource(source)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the duration of $path", e)
            0L
        } finally {
            retriever.release()
            source.close()
        }
    }

    // what the media row keeps, in whole seconds
    fun readSeconds(context: Context, path: String) = Math.round(readMillis(context, path) / 1000.0).toInt()
}
