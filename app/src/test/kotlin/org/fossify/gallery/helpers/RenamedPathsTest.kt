package org.fossify.gallery.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

// Renaming on the share, in the two places it is only string work: the path the request carries,
// and the hidden-folder settings that have to follow the folder.
//
// Both were written by copying the pCloud side, and the copying is exactly why they are tested
// here -- a rename that sends the wrong path renames the wrong thing on somebody's NAS, and there
// is no recycle bin there to take it back out of
class RenamedPathsTest {
    @Test
    fun `only the last segment of a share path changes`() {
        assertEquals(
            "photos\\2026\\IMG_0002.jpg",
            renamedSiblingPath("photos\\2026\\IMG_0001.jpg", "IMG_0002.jpg", '\\')
        )
    }

    // a share whose root folder is the share's own root puts its files at the top level, and the
    // path then has no separator at all. Keeping the old path up to the last one would keep the
    // whole old name
    @Test
    fun `a name in the share's root becomes the whole path`() {
        assertEquals("IMG_0002.jpg", renamedSiblingPath("IMG_0001.jpg", "IMG_0002.jpg", '\\'))
    }

    // a folder is renamed by the same request, and nothing about it says "file"
    @Test
    fun `a folder is renamed where it already is`() {
        assertEquals("photos\\Trips 2026", renamedSiblingPath("photos\\Trips", "Trips 2026", '\\'))
    }

    // the folder's own name may be part of the file's name, and only the segment after the last
    // separator is the one being replaced
    @Test
    fun `a name that repeats the folder's name is not confused with it`() {
        assertEquals("Trips\\Trips 2.jpg", renamedSiblingPath("Trips\\Trips.jpg", "Trips 2.jpg", '\\'))
    }

    @Test
    fun `a hidden folder follows the rename`() {
        assertEquals(
            setOf("smb:/Trips 2026"),
            setOf("smb:/Trips").withFolderRenamed("smb:/Trips", "smb:/Trips 2026")
        )
    }

    @Test
    fun `the folders under it follow too, at any depth`() {
        assertEquals(
            setOf("smb:/Trips 2026/Kyoto", "smb:/Trips 2026/Kyoto/Gion"),
            setOf("smb:/Trips/Kyoto", "smb:/Trips/Kyoto/Gion").withFolderRenamed("smb:/Trips", "smb:/Trips 2026")
        )
    }

    // the one this is really written for: a sibling whose name starts with the renamed folder's
    // is not under it, and a prefix match without the slash would drag it along
    @Test
    fun `a sibling whose name merely starts the same is left alone`() {
        assertEquals(
            setOf("smb:/Trips 2026", "smb:/Tripsomething"),
            setOf("smb:/Trips", "smb:/Tripsomething").withFolderRenamed("smb:/Trips", "smb:/Trips 2026")
        )
    }

    @Test
    fun `the folders that have nothing to do with it stay as they are`() {
        assertEquals(
            setOf("smb:/Trips 2026", "smb:/Screens", "pcloud:/Camera"),
            setOf("smb:/Trips", "smb:/Screens", "pcloud:/Camera").withFolderRenamed("smb:/Trips", "smb:/Trips 2026")
        )
    }

    // nothing moved means nothing is written back to the settings, which is what the callers
    // decide on by comparing what came back with what they handed in
    @Test
    fun `a set with nothing under the folder comes back untouched`() {
        val folders = setOf("smb:/Screens", "pcloud:/Camera")
        assertSame(folders, folders.withFolderRenamed("smb:/Trips", "smb:/Trips 2026"))
    }
}
