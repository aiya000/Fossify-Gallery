package org.fossify.gallery.extensions

import org.fossify.gallery.directoryAt
import org.fossify.gallery.models.Directory
import org.fossify.gallery.models.FolderGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Every real folder inside a group, subgroups included. It is what a group's count, size and
// collage are summed from, and what decides whether a group is drawn at all when the storage
// chips have narrowed the folder list
class FolderGroupsTest {
    private val pictures = directoryAt("smb:/pictures")
    private val camera = directoryAt("smb:/camera")
    private val scans = directoryAt("smb:/scans")
    private val loose = directoryAt("smb:/loose")

    private fun contentsOf(
        groupId: Long,
        groups: List<FolderGroup>,
        members: Map<String, Long>,
        dirs: List<Directory>
    ) = collectFolderGroupContents(groupId, groups, members, dirs).map { it.path }

    @Test
    fun `a group holds the folders assigned to it, and nothing else`() {
        val groups = listOf(FolderGroup(1, "holiday"))
        val members = mapOf(pictures.path to 1L, camera.path to 1L)

        val contents = contentsOf(1, groups, members, listOf(pictures, camera, loose))

        assertEquals(listOf(pictures.path, camera.path), contents)
    }

    @Test
    fun `a group holds what its subgroups hold`() {
        val groups = listOf(FolderGroup(1, "holiday"), FolderGroup(2, "prints", parentId = 1))
        val members = mapOf(pictures.path to 1L, scans.path to 2L)

        val contents = contentsOf(1, groups, members, listOf(pictures, scans))

        assertEquals(listOf(pictures.path, scans.path), contents)
    }

    // a subgroup answers for itself alone; asking it must not climb back up to its parent
    @Test
    fun `a subgroup does not hold its parent's folders`() {
        val groups = listOf(FolderGroup(1, "holiday"), FolderGroup(2, "prints", parentId = 1))
        val members = mapOf(pictures.path to 1L, scans.path to 2L)

        val contents = contentsOf(2, groups, members, listOf(pictures, scans))

        assertEquals(listOf(scans.path), contents)
    }

    // a folder out of view -- another storage's, hidden, excluded -- is simply not among dirs
    @Test
    fun `a folder that is not in the list is not in the group`() {
        val groups = listOf(FolderGroup(1, "holiday"))
        val members = mapOf(pictures.path to 1L, camera.path to 1L)

        val contents = contentsOf(1, groups, members, listOf(pictures))

        assertEquals(listOf(pictures.path), contents)
    }

    // the visited set: a group that somehow became its own ancestor is walked once rather than
    // for ever
    @Test
    fun `a group that holds itself is walked once`() {
        val groups = listOf(FolderGroup(1, "one", parentId = 2), FolderGroup(2, "two", parentId = 1))
        val members = mapOf(pictures.path to 1L, scans.path to 2L)

        val contents = contentsOf(1, groups, members, listOf(pictures, scans))

        assertEquals(listOf(pictures.path, scans.path), contents)
    }

    @Test
    fun `an empty group holds nothing`() {
        val contents = contentsOf(1, listOf(FolderGroup(1, "empty")), emptyMap(), listOf(loose))

        assertTrue(contents.isEmpty())
    }
}
