package org.fossify.gallery.helpers

import android.content.Context
import org.fossify.commons.extensions.getFilenameExtension
import org.fossify.gallery.extensions.config
import org.fossify.gallery.extensions.pCloudItemsDB
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

// Local copies of pCloud files for the parts of the app that need a real file: the zoomable
// photo view, GIF and SVG decoding, and later the editor. A copy is fetched once through
// getfilelink and reused until it is evicted; the cache is trimmed to a fixed size, oldest
// first. Thumbnails do not come through here, PCloudStreamLoader streams those into Glide
class PCloudFileCache(private val context: Context) {
    companion object {
        private const val MAX_BYTES = 512L * 1024 * 1024

        // the newest copy is written into the cache before the trim runs, so it is never the
        // one thrown out unless it alone is over the limit
        private const val PARTIAL_SUFFIX = ".part"
    }

    private val dir = File(context.cacheDir, "pcloud")

    // Blocks and talks to the network, so call it off the main thread. Throws PCloudException
    // when pCloud refused (requiresLogIn tells whether the token died), an IOException when
    // the download failed, and FileNotFoundException for a path the scanner does not know.
    // The copy is named by the file id and content hash, so a file replaced on pCloud under
    // the same name never serves the old bytes once it has been rescanned
    fun fetch(path: String): File {
        val item = context.pCloudItemsDB.getItem(path) ?: throw FileNotFoundException(path)
        if (item.isFolder) {
            throw FileNotFoundException(path)
        }

        val target = File(dir, "${item.itemId}-${java.lang.Long.toUnsignedString(item.contentHash)}.${path.getFilenameExtension()}")
        if (target.isFile && target.length() > 0) {
            target.setLastModified(System.currentTimeMillis())
            return target
        }

        val config = context.config
        if (!config.isPCloudLoggedIn) {
            throw IllegalStateException("No pCloud account is signed in")
        }

        val url = PCloudApi.getFileLink(config.pCloudApiHost, config.pCloudAccessToken, item.itemId)
        dir.mkdirs()
        val partial = File(dir, "${target.name}$PARTIAL_SUFFIX")
        try {
            PCloudApi.download(url).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("pCloud answered HTTP ${response.code}")
                }

                partial.outputStream().use { out ->
                    response.body.byteStream().copyTo(out)
                }
            }

            if (!partial.renameTo(target)) {
                throw IOException("Could not move the downloaded file into place")
            }
        } finally {
            partial.delete()
        }

        trim(keep = target)
        return target
    }

    // the copy that is already there, or null without touching the network
    fun peek(path: String): File? {
        val item = context.pCloudItemsDB.getItem(path) ?: return null
        val target = File(dir, "${item.itemId}-${java.lang.Long.toUnsignedString(item.contentHash)}.${path.getFilenameExtension()}")
        return target.takeIf { it.isFile && it.length() > 0 }
    }

    private fun trim(keep: File) {
        val files = dir.listFiles()?.filter { it.isFile && it != keep } ?: return
        var total = files.sumOf { it.length() } + keep.length()
        if (total <= MAX_BYTES) {
            return
        }

        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= MAX_BYTES) {
                break
            }

            val size = file.length()
            if (file.delete()) {
                total -= size
            }
        }
    }
}
