package org.fossify.gallery.helpers

import org.junit.Assert.assertEquals
import org.junit.Test

// What a copy is called when the destination folder already holds that name. Both transfers
// use it -- a copy off pCloud and a copy off the share land in the same folders, and the user
// should not be able to tell which one numbered a file
class AvailableNameTest {
    private fun taking(vararg taken: String): (String) -> Boolean = { taken.contains(it) }

    @Test
    fun `a free name is left alone`() {
        assertEquals(
            "/storage/emulated/0/DCIM/IMG_0001.jpg",
            availableName("/storage/emulated/0/DCIM", "IMG_0001.jpg", taking())
        )
    }

    // the number goes before the extension, so the copy is still opened by the same app
    @Test
    fun `a taken name is numbered before its extension`() {
        assertEquals(
            "/DCIM/IMG_0001 (1).jpg",
            availableName("/DCIM", "IMG_0001.jpg", taking("/DCIM/IMG_0001.jpg"))
        )
    }

    @Test
    fun `the numbering climbs until something is free`() {
        assertEquals(
            "/DCIM/IMG_0001 (3).jpg",
            availableName("/DCIM", "IMG_0001.jpg", taking("/DCIM/IMG_0001.jpg", "/DCIM/IMG_0001 (1).jpg", "/DCIM/IMG_0001 (2).jpg"))
        )
    }

    // a gap is filled rather than skipped: the lowest free number wins
    @Test
    fun `a gap in the numbering is taken`() {
        assertEquals(
            "/DCIM/IMG_0001 (2).jpg",
            availableName("/DCIM", "IMG_0001.jpg", taking("/DCIM/IMG_0001.jpg", "/DCIM/IMG_0001 (1).jpg", "/DCIM/IMG_0001 (3).jpg"))
        )
    }

    @Test
    fun `a name without an extension is numbered at the end`() {
        assertEquals("/DCIM/README (1)", availableName("/DCIM", "README", taking("/DCIM/README")))
    }

    // only the last dot counts as the extension, the rest of the name is kept whole
    @Test
    fun `a name with several dots keeps all but the last one`() {
        assertEquals(
            "/DCIM/2026.02.15 (1).jpg",
            availableName("/DCIM", "2026.02.15.jpg", taking("/DCIM/2026.02.15.jpg"))
        )
    }

    // the destination is a pseudo path for a copy onto pCloud, and the folder is pasted on
    // the same way there
    @Test
    fun `a pseudo path is a folder like any other`() {
        assertEquals(
            "pcloud:/Camera/IMG_0001 (1).jpg",
            availableName("pcloud:/Camera", "IMG_0001.jpg", taking("pcloud:/Camera/IMG_0001.jpg"))
        )
    }
}
