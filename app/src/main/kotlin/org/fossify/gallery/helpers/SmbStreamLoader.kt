package org.fossify.gallery.helpers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
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
import java.util.concurrent.CancellationException

// Feeds Glide the thumbnails of SMB media. Every loadImage() caller hands Glide a medium as a
// plain path string, so this loader is prepended to the String loaders in SvgModule and takes
// the "smb:" ones before Glide's own StringLoader tries to read them as a Uri.
//
// A share renders no thumbnails of its own, so there is nothing to ask it for: a photo is read
// whole and Glide downsamples it while decoding, and a video has one frame pulled out of it
// over random reads. Both are decoded once -- what Glide keeps in its disk cache afterwards is
// the small bitmap, so the cost is paid per file, not per scroll.
//
// The reading is done on RemoteThumbnails' threads, not Glide's (#165): loadData() hands the
// work over and returns, and Glide is called back once every byte is on the device, so that
// its own threads are never held by the network and this device's tiles never wait behind
// the share's. That is also why a photo goes through SmbFileCache rather than straight off the
// share: a stream read during the decode would be the network on Glide's thread after all
class SmbStreamLoader(private val context: Context) : ModelLoader<String, InputStream> {
    override fun handles(model: String) = model.isSmbPath()

    // Runs on Glide's worker threads, so the row can be looked up here. The size and the
    // modification time go into the cache key: a share tells nothing else about a file's
    // content, and together they are what a rescan would notice a change in
    override fun buildLoadData(model: String, width: Int, height: Int, options: Options): ModelLoader.LoadData<InputStream>? {
        val medium = context.mediaDB.getMediumByPath(model) ?: return null
        return ModelLoader.LoadData(
            ObjectKey("$model:${medium.size}:${medium.modified}"),
            Fetcher(context, model, medium.type == TYPE_VIDEOS, medium.size, width, height)
        )
    }

    class Factory(private val context: Context) : ModelLoaderFactory<String, InputStream> {
        override fun build(multiFactory: MultiModelLoaderFactory) = SmbStreamLoader(context)

        override fun teardown() {}
    }

    private class Fetcher(
        private val context: Context,
        private val path: String,
        private val isVideo: Boolean,
        private val size: Long,
        private val width: Int,
        private val height: Int
    ) : DataFetcher<InputStream> {
        @Volatile
        private var cancelled = false
        private var stream: InputStream? = null

        // what the bytes came from, so that a photo already on the device is not reported as a
        // remote load
        @Volatile
        private var source = DataSource.REMOTE

        // Hands the read to RemoteThumbnails and returns at once; Glide hears back from that
        // thread. A fetch Glide has cancelled by then -- the tile scrolled away, the list
        // switched to another storage -- is not read at all, which is the other half of #165:
        // Glide drops a job that has not started, but a fetcher that has started is left to run
        // to the end, so the check is made here, on the far side of the queue
        override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in InputStream>) {
            if (context.config.smbConnectionOf(path) == null) {
                callback.onLoadFailed(IllegalStateException("No SMB share is configured for $path"))
                return
            }

            RemoteThumbnails.submit(priority) {
                if (cancelled) {
                    callback.onLoadFailed(CancellationException("The thumbnail of $path was no longer wanted"))
                    return@submit
                }

                try {
                    val stream = if (isVideo) frameStream() else photoStream()
                    this.stream = stream
                    Log.d(TAG, "Read the thumbnail of $path on ${Thread.currentThread().name}")
                    if (cancelled) {
                        cleanup()
                        callback.onLoadFailed(CancellationException("The thumbnail of $path was no longer wanted"))
                        return@submit
                    }

                    callback.onDataReady(stream)
                } catch (e: Exception) {
                    cleanup()
                    callback.onLoadFailed(e)
                }
            }
        }

        // The photo as a file on the device, fetched into SmbFileCache if it is not there yet,
        // so that what Glide decodes from is the disk. The viewer reads the same copy afterwards
        private fun photoStream(): InputStream {
            // a picture Glide cannot rewind is decoded here instead, whichever of the two it
            // would have come from -- the bytes are the same bytes, and it is their number that
            // Glide cannot get back past. See ThumbnailPolicy
            if (ThumbnailPolicy.mustBeSampledBeforeGlide(path, size)) {
                return sampledPhotoStream()
            }

            val cache = SmbFileCache(context)
            val cached = cache.peek(path)
            if (cached != null) {
                source = DataSource.LOCAL
                return FileInputStream(cached)
            }

            return FileInputStream(cache.fetch(path))
        }

        // The same picture, decoded down to about the size of the tile it is going into and
        // handed on as a stream of its own. Read once: the size comes out of the PNG's own
        // header rather than out of a first pass over the file, so the share is asked for the
        // bytes a single time however large the file is
        private fun sampledPhotoStream(): InputStream {
            val cached = SmbFileCache(context).peek(path)
            val bitmap = if (cached != null) {
                source = DataSource.LOCAL
                val header = ByteArray(ThumbnailPolicy.PNG_HEADER_BYTES)
                FileInputStream(cached).use { it.read(header) }
                decodeSampled(header) { FileInputStream(cached) }
            } else {
                SmbClient.open(context, path).use { open ->
                    val header = ByteArray(ThumbnailPolicy.PNG_HEADER_BYTES)
                    open.readAt(0, header, 0, header.size)
                    decodeSampled(header) { open.inputStream() }
                }
            }

            // PNG for a picture that has one, because a picture with transparency in it turns
            // black where it is see-through once it has been through JPEG; JPEG for everything
            // else, which is most of them and a tenth of the bytes
            val bytes = ByteArrayOutputStream()
            if (bitmap.hasAlpha()) {
                bitmap.compress(Bitmap.CompressFormat.PNG, FRAME_QUALITY, bytes)
            } else {
                bitmap.compress(Bitmap.CompressFormat.JPEG, FRAME_QUALITY, bytes)
            }

            bitmap.recycle()
            return ByteArrayInputStream(bytes.toByteArray())
        }

        private fun decodeSampled(header: ByteArray, openStream: () -> InputStream): Bitmap {
            val (fullWidth, fullHeight) = ThumbnailPolicy.pngSizeOf(header)
                ?: throw IllegalStateException("$path does not begin with a PNG header")

            val options = BitmapFactory.Options().apply {
                inSampleSize = ThumbnailPolicy.sampleSizeFor(fullWidth, fullHeight, width, height)
            }

            return openStream().use { BitmapFactory.decodeStream(it, null, options) }
                ?: throw IllegalStateException("$path could not be decoded")
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

    companion object {
        const val TAG = "RemoteThumbnails"
    }
}
