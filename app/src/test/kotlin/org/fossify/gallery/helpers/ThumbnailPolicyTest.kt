package org.fossify.gallery.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The folder of renders on the share that drew nothing: nineteen PNGs, every tile blank, and no
// warning icon to say anything had gone wrong. Two things had to be true at once for that, and a
// case here holds each of them down.
//
// The threshold is Glide's, not ours -- Downsampler.MARK_POSITION -- so the sizes below are
// written out in full rather than derived from the constant: a case that computes its own
// expectation from the code it is checking would still pass if the constant moved
class ThumbnailPolicyTest {
    private val fiveMegabytes = 5L * 1024 * 1024

    @Test
    fun `a PNG on the share larger than Glide can rewind is decoded before Glide sees it`() {
        assertTrue(ThumbnailPolicy.mustBeSampledBeforeGlide("smb:/Renders/lilith.png", 5_558_005L))
        assertTrue(ThumbnailPolicy.mustBeSampledBeforeGlide("smb:/Renders/lilith.png", 72_037_398L))
    }

    // 5,156,896 bytes is a real render that draws; 5,558,005 is a real render that does not. The
    // line between them is the 5 MiB mark, and it belongs exactly there
    @Test
    fun `a PNG small enough to rewind is left to Glide, which downsamples it better than we do`() {
        assertFalse(ThumbnailPolicy.mustBeSampledBeforeGlide("smb:/Renders/lilith.png", 5_156_896L))
        assertFalse(ThumbnailPolicy.mustBeSampledBeforeGlide("smb:/Renders/lilith.png", fiveMegabytes))
        assertTrue(ThumbnailPolicy.mustBeSampledBeforeGlide("smb:/Renders/lilith.png", fiveMegabytes + 1))
    }

    // A JPEG says how big it is in its first few kilobytes, so Glide never reads far enough into
    // one to lose the mark. A 28 MB photo off the share draws today and must keep drawing
    @Test
    fun `size alone is not the problem -- a large JPEG is fine`() {
        assertFalse(ThumbnailPolicy.mustBeSampledBeforeGlide("smb:/Camera/IMG_0001.jpg", 28_147_293L))
    }

    @Test
    fun `a medium on a remote storage is never retried with Picasso`() {
        assertFalse(ThumbnailPolicy.canBeRetriedWithPicasso(TYPE_IMAGES, "smb:/Renders/lilith.png"))
        assertFalse(ThumbnailPolicy.canBeRetriedWithPicasso(TYPE_IMAGES, "pcloud:/Renders/lilith.png"))
    }

    // the fallback is still worth making for a file Picasso can actually open. It is there
    // because Glide fails on exactly these, and on the device it is what rescues them
    @Test
    fun `a PNG on the device still is`() {
        assertTrue(ThumbnailPolicy.canBeRetriedWithPicasso(TYPE_IMAGES, "/storage/emulated/0/DCIM/shot.png"))
        assertFalse(ThumbnailPolicy.canBeRetriedWithPicasso(TYPE_IMAGES, "/storage/emulated/0/DCIM/shot.jpg"))
        assertFalse(ThumbnailPolicy.canBeRetriedWithPicasso(TYPE_VIDEOS, "/storage/emulated/0/DCIM/clip.png"))
    }

    @Test
    fun `a PNG carries its size in its first 24 bytes`() {
        assertEquals(6000 to 4000, ThumbnailPolicy.pngSizeOf(pngHeader(6000, 4000)))
        assertEquals(1 to 1, ThumbnailPolicy.pngSizeOf(pngHeader(1, 1)))
    }

    @Test
    fun `bytes that do not begin a PNG yield no size`() {
        assertNull(ThumbnailPolicy.pngSizeOf(ByteArray(ThumbnailPolicy.PNG_HEADER_BYTES)))
        assertNull(ThumbnailPolicy.pngSizeOf(pngHeader(6000, 4000).copyOf(12)))
        assertNull(ThumbnailPolicy.pngSizeOf(pngHeader(0, 4000)))
    }

    // the largest step that still leaves the picture covering the tile. Decoding smaller than the
    // view is what makes a thumbnail look blurred, so the step below is the one to take
    @Test
    fun `the picture is decoded down to the tile, never under it`() {
        assertEquals(8, ThumbnailPolicy.sampleSizeFor(width = 4000, height = 4000, targetWidth = 500, targetHeight = 500))
        assertEquals(4, ThumbnailPolicy.sampleSizeFor(width = 4000, height = 4000, targetWidth = 501, targetHeight = 501))
        assertEquals(1, ThumbnailPolicy.sampleSizeFor(width = 400, height = 400, targetWidth = 500, targetHeight = 500))
    }

    // a tile Glide has not measured yet asks for a size of zero, and a picture must not be
    // decoded down to nothing for it
    @Test
    fun `an unmeasured tile decodes the picture whole`() {
        assertEquals(1, ThumbnailPolicy.sampleSizeFor(width = 4000, height = 4000, targetWidth = 0, targetHeight = 0))
        assertEquals(1, ThumbnailPolicy.sampleSizeFor(width = 0, height = 0, targetWidth = 500, targetHeight = 500))
    }

    // the signature, a 13-byte IHDR chunk header, and the two sizes, big endian
    private fun pngHeader(width: Int, height: Int): ByteArray {
        val header = ByteArray(ThumbnailPolicy.PNG_HEADER_BYTES)
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).copyInto(header)
        byteArrayOf(0, 0, 0, 13).copyInto(header, 8)
        byteArrayOf(0x49, 0x48, 0x44, 0x52).copyInto(header, 12)
        writeBigEndian(header, 16, width)
        writeBigEndian(header, 20, height)
        return header
    }

    private fun writeBigEndian(bytes: ByteArray, offset: Int, value: Int) {
        for (index in 0 until 4) {
            bytes[offset + index] = (value shr (8 * (3 - index)) and 0xFF).toByte()
        }
    }
}
