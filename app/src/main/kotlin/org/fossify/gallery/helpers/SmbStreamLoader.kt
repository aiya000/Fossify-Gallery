package org.fossify.gallery.helpers

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.signature.ObjectKey
import org.fossify.gallery.extensions.config
import org.fossify.gallery.extensions.isSmbPath
import org.fossify.gallery.extensions.mediaDB
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.InputStream

// Feeds Glide the thumbnails of SMB media. Every loadImage() caller hands Glide a medium as a
// plain path string, so this loader is prepended to the String loaders in SvgModule and takes
// the "smb:" ones before Glide's own StringLoader tries to read them as a Uri.
//
// A share renders no thumbnails of its own, so there is nothing to ask it for: a photo is read
// whole and Glide downsamples it while decoding, and a video has one frame pulled out of it
// over random reads. Both are decoded once -- what Glide keeps in its disk cache afterwards is
// the small bitmap, so the cost is paid per file, not per scroll
class SmbStreamLoader(private val context: Context) : ModelLoader<String, InputStream> {
    override fun handles(model: String) = model.isSmbPath()

    // Runs on Glide's worker threads, so the row can be looked up here. The size and the
    // modification time go into the cache key: a share tells nothing else about a file's
    // content, and together they are what a rescan would notice a change in
    override fun buildLoadData(model: String, width: Int, height: Int, options: Options): ModelLoader.LoadData<InputStream>? {
        val medium = context.mediaDB.getMediumByPath(model) ?: return null
        return ModelLoader.LoadData(
            ObjectKey("$model:${medium.size}:${medium.modified}"),
            Fetcher(context, model, medium.type == TYPE_VIDEOS, width, height)
        )
    }

    class Factory(private val context: Context) : ModelLoaderFactory<String, InputStream> {
        override fun build(multiFactory: MultiModelLoaderFactory) = SmbStreamLoader(context)

        override fun teardown() {}
    }

    private class Fetcher(
        private val context: Context, private val path: String, private val isVideo: Boolean, private val width: Int, private val height: Int
    ) : DataFetcher<InputStream> {
        @Volatile
        private var cancelled = false
        private var stream: InputStream? = null

        // what the bytes came from, so that a photo read straight off the share is not reported
        // as a local load
        private var source = DataSource.REMOTE

        override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in InputStream>) {
            if (!context.config.isSmbConfigured) {
                callback.onLoadFailed(IllegalStateException("No SMB share is configured"))
                return
            }

            try {
                val stream = if (isVideo) frameStream() else photoStream()
                if (cancelled) {
                    stream.close()
                    return
                }

                this.stream = stream
                callback.onDataReady(stream)
            } catch (e: Exception) {
                callback.onLoadFailed(e)
            }
        }

        // the copy the viewer already fetched, if there is one, so that going back to the grid
        // does not read the file again; otherwise straight off the share
        private fun photoStream(): InputStream {
            val cached = SmbFileCache(context).peek(path)
            if (cached != null) {
                source = DataSource.LOCAL
                return FileInputStream(cached)
            }

            return SmbClient.open(context, path).inputStream()
        }

        // one frame, compressed to JPEG so that it can go down the same InputStream pipeline as
        // a photo. The frame is asked for at the scale the grid wants where the platform can do
        // it, which keeps a 4K video from being decoded at full size for a 128px tile
        private fun frameStream(): InputStream {
            val retriever = MediaMetadataRetriever()
            var bitmap: Bitmap? = null
            try {
                SmbMediaDataSource(context, path).use { dataSource ->
                    retriever.setDataSource(dataSource)
                    bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && width > 0 && height > 0) {
                        retriever.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, width, height)
                    } else {
                        retriever.getFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    }
                }
            } finally {
                retriever.release()
            }

            val frame = bitmap ?: throw IllegalStateException("No frame could be read from $path")
            val bytes = ByteArrayOutputStream()
            frame.compress(Bitmap.CompressFormat.JPEG, FRAME_QUALITY, bytes)
            frame.recycle()
            return ByteArrayInputStream(bytes.toByteArray())
        }

        override fun cleanup() {
            stream?.close()
            stream = null
        }

        override fun cancel() {
            cancelled = true
        }

        override fun getDataClass() = InputStream::class.java

        override fun getDataSource() = source

        companion object {
            private const val FRAME_QUALITY = 90
        }
    }
}
