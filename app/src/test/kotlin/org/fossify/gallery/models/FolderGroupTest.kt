package org.fossify.gallery.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// A group is shown as a folder row with a pseudo path in place of a real one, and that path is
// how it is told apart from a real folder everywhere afterwards -- the selection, the download
// queue, the folder picker. The round trip is what all of that rests on
class FolderGroupTest {
    @Test
    fun `a group's pseudo path leads back to its id`() {
        val group = FolderGroup(42, "holiday")

        assertEquals(42L, group.getPseudoPath().toFolderGroupId())
    }

    @Test
    fun `a pseudo path is recognised as one`() {
        assertTrue(FolderGroup(1, "holiday").getPseudoPath().isFolderGroupPath())
    }

    @Test
    fun `a real folder's path is not a group's`() {
        assertFalse("smb:/pictures".isFolderGroupPath())
        assertFalse("/storage/emulated/0/DCIM".isFolderGroupPath())
        assertFalse("pcloud:/Camera".isFolderGroupPath())
    }

    @Test
    fun `a real folder's path has no group id`() {
        assertNull("smb:/pictures".toFolderGroupId())
    }

    // a pseudo path that is not a number is not a group either: whoever asks gets null and
    // treats the row as the plain folder it looks like, rather than throwing
    @Test
    fun `a malformed pseudo path has no group id`() {
        assertNull("group://".toFolderGroupId())
        assertNull("group://holiday".toFolderGroupId())
    }
}
