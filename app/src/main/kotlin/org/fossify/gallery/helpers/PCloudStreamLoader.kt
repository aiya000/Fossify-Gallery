package org.fossify.gallery.helpers

import android.content.Context
import android.util.Log
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.signature.ObjectKey
import okhttp3.Call
import okhttp3.Response
import org.fossify.gallery.extensions.config
import org.fossify.gallery.extensions.isPCloudPath
import org.fossify.gallery.extensions.pCloudItemsDB
import org.fossify.gallery.models.PCloudItem
import java.io.InputStream

// Feeds Glide the thumbnails of pCloud media. Every loadImage() caller hands Glide a medium as a
// plain path string, so this loader is prepended to the String loaders in SvgModule and takes
// the "pcloud:" ones before Glide's own StringLoader tries to read them as a Uri. The pictures
// come from pCloud's getthumb, which renders images and videos alike
class PCloudStreamLoader(private val context: Context) : ModelLoader<String, InputStream> {
    override fun handles(model: String) = model.isPCloudPath()

    // Runs on Glide's worker threads, so the file can be looked up here. This is also where the
    // cache key comes from: the content hash goes in, so a file replaced on pCloud under the same
    // name gets a fresh thumbnail once it has been rescanned. A path the scanner does not know,
    // a folder, or a file pCloud makes no thumbnail for yields null, which Glide reports as a
    // failed load and the adapters show as a warning icon
    override fun buildLoadData(model: String, width: Int, height: Int, options: Options): ModelLoader.LoadData<InputStream>? {
        val item = context.pCloudItemsDB.getItem(model) ?: return null
        if (item.isFolder || !item.hasThumb) {
            return null
        }

        val size = thumbSizeFor(width, height)
        return ModelLoader.LoadData(ObjectKey("$model:${item.contentHash}:$size"), Fetcher(context, item, size))
    }

    class Factory(private val context: Context) : ModelLoaderFactory<String, InputStream> {
        override fun build(multiFactory: MultiModelLoaderFactory) = PCloudStreamLoader(context)

        override fun teardown() {}
    }

    private class Fetcher(private val context: Context, private val item: PCloudItem, private val size: String) : DataFetcher<InputStream> {
        @Volatile
        private var call: Call? = null
        private var response: Response? = null

        override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in InputStream>) {
            val config = context.config
            if (!config.isPCloudLoggedIn) {
                callback.onLoadFailed(IllegalStateException("No pCloud account is signed in"))
                return
            }

            try {
                val call = PCloudApi.thumbCall(config.pCloudApiHost, config.pCloudAccessToken, item.itemId, size)
                this.call = call
                val response = call.execute()
                this.response = response
                callback.onDataReady(PCloudApi.contentOf(response))
            } catch (e: PCloudException) {
                // a token pCloud stopped accepting is rescanPCloud()'s business to act on; here
                // it only means the thumbnail is not coming
                if (e.requiresLogIn) {
                    Log.w(TAG, "pCloud wants the account signed in again: ${e.message}")
                }

                callback.onLoadFailed(e)
            } catch (e: Exception) {
                callback.onLoadFailed(e)
            }
        }

        override fun cleanup() {
            response?.close()
            response = null
        }

        override fun cancel() {
            call?.cancel()
        }

        override fun getDataClass() = InputStream::class.java

        override fun getDataSource() = DataSource.REMOTE
    }

    companion object {
        private const val TAG = "PCloudStreamLoader"

        // pCloud accepts any side between 16 and 2048 in multiples of 4, but renders every new
        // size on demand. A short ladder keeps that to a handful per file whatever the column
        // count, and Glide scales the rest
        private val THUMB_SIDES = intArrayOf(128, 256, 512, 1024, 2048)

        // The square box the thumbnail is fitted into, not cropped: cropping is the "crop
        // thumbnails" setting's call and Glide does it. A target of unknown size gets 256
        fun thumbSizeFor(width: Int, height: Int): String {
            val wanted = maxOf(width, height)
            val side = if (wanted <= 0) {
                256
            } else {
                THUMB_SIDES.firstOrNull { it >= wanted } ?: THUMB_SIDES.last()
            }

            return "${side}x$side"
        }
    }
}
