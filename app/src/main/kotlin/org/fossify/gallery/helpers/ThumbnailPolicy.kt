package org.fossify.gallery.helpers

import org.fossify.commons.extensions.isPng
import org.fossify.gallery.extensions.isRemotePath

// What the grid's thumbnails may ask of Glide, and what has to be made small before it is handed
// over.
//
// Glide reads a picture's size out of the stream before it decodes it: it marks the stream, reads
// ahead until BitmapFactory will say how big the picture is, and rewinds. The mark only promises
// so many bytes -- Downsampler.MARK_POSITION -- and BitmapFactory reads a PNG through to its very
// end before it answers. A PNG past that mark can therefore never be rewound, and the load fails.
// A JPEG answers out of its first few kilobytes, which is why a 30 MB photo is no trouble at all
// and a 6 MB render is.
//
// On the device that failure is caught by loadImageBase()'s fallback to Picasso, which opens the
// file again by itself. A medium on a remote storage has no file to open -- "smb:/..." is not a
// path anything but SmbClient understands -- so Picasso fails there too, silently, and the tile
// is left showing its placeholder with no warning icon to say why
object ThumbnailPolicy {
    // Glide's Downsampler.MARK_POSITION
    const val REWINDABLE_BYTES = 5L * 1024 * 1024

    // the PNG signature, then the length and the type of the first chunk, then the width and the
    // height: a PNG carries its size in the first 24 bytes and nowhere else
    const val PNG_HEADER_BYTES = 24

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    // Whether the fallback to Picasso is worth making. Picasso loads "file://$path", so it can
    // only ever help a medium that is a file: on a remote path it fails without saying so, which
    // turns a load Glide reported as failed into a tile that never explains itself
    fun canBeRetriedWithPicasso(type: Int, path: String) = type == TYPE_IMAGES && path.isPng() && !path.isRemotePath()

    // Whether a medium on a remote storage has to be decoded down to size before Glide sees it.
    // Only the ones Glide cannot rewind: everything else goes over as it is, and Glide does the
    // downsampling itself, which is both faster and better at it
    fun mustBeSampledBeforeGlide(path: String, size: Long) = path.isPng() && size > REWINDABLE_BYTES

    // The size in a PNG's IHDR chunk, or null when these bytes do not begin a PNG. Read here
    // rather than asked of BitmapFactory, because asking it is the very thing that cannot be done
    // without reading the whole file -- which is what got us here
    fun pngSizeOf(header: ByteArray): Pair<Int, Int>? {
        if (header.size < PNG_HEADER_BYTES) {
            return null
        }

        if (PNG_SIGNATURE.indices.any { header[it] != PNG_SIGNATURE[it] }) {
            return null
        }

        val width = bigEndianIntAt(header, 16)
        val height = bigEndianIntAt(header, 20)
        return if (width > 0 && height > 0) width to height else null
    }

    // How far down to decode, as the power of two BitmapFactory's inSampleSize wants. The picture
    // is kept at least as large as the tile it is going into -- a thumbnail decoded smaller than
    // the view would be drawn blurry -- so this is the largest step that still covers it
    fun sampleSizeFor(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int {
        if (width <= 0 || height <= 0 || targetWidth <= 0 || targetHeight <= 0) {
            return 1
        }

        var sample = 1
        while (width / (sample * 2) >= targetWidth && height / (sample * 2) >= targetHeight) {
            sample *= 2
        }

        return sample
    }

    private fun bigEndianIntAt(bytes: ByteArray, offset: Int): Int {
        var value = 0
        for (index in offset until offset + 4) {
            value = (value shl 8) or (bytes[index].toInt() and 0xFF)
        }

        return value
    }
}
