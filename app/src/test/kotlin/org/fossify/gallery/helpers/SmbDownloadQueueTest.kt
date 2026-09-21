package org.fossify.gallery.helpers

import org.fossify.gallery.directoryAt
import org.fossify.gallery.groupDirectoryOf
import org.fossify.gallery.models.Directory
import org.junit.Assert.assertEquals
import org.junit.Test

// The order "download the videos in this selection" fetches in, and plays in afterwards.
//
// By hand this is: pick a group that has a subgroup in it, start the download, and watch whether
// the subgroup's folders come where the subgroup sits in the list or after all the plain folders.
// It takes a share, a download and the patience to watch the notification through. The rule
// itself is just a walk, so here it is walked over a made-up structure instead.
class SmbDownloadQueueTest {
    private fun folder(path: String) = directoryAt(path)

    private fun group(id: Long) = groupDirectoryOf(id)

    // what each folder holds, standing in for its videos: the folder's own path, so that what
    // comes out reads as the order the folders were visited in
    private fun walk(selected: List<Directory>, children: Map<Long, List<Directory>>) =
        SmbDownloadQueue.flatten(
            selected = selected,
            childrenOf = { children[it] ?: emptyList() },
            videosOfFolder = { listOf(it) },
        )

    // the top level is the order the rows were tapped in, which the selection keeps
    @Test
    fun `plain folders are taken in the order they were selected`() {
        val order = walk(listOf(folder("smb:/second"), folder("smb:/first")), emptyMap())

        assertEquals(listOf("smb:/second", "smb:/first"), order)
    }

    // the one that is hard to see by hand: a subgroup falls where it sits, not after the folders
    @Test
    fun `a subgroup is walked where it sits, not after the plain folders`() {
        val children = mapOf(
            1L to listOf(folder("smb:/a"), group(2), folder("smb:/b")),
            2L to listOf(folder("smb:/inner"))
        )

        val order = walk(listOf(group(1)), children)

        assertEquals(listOf("smb:/a", "smb:/inner", "smb:/b"), order)
    }

    @Test
    fun `nesting goes as deep as it is drawn`() {
        val children = mapOf(
            1L to listOf(group(2), folder("smb:/a")),
            2L to listOf(group(3), folder("smb:/b")),
            3L to listOf(folder("smb:/c"))
        )

        val order = walk(listOf(group(1)), children)

        assertEquals(listOf("smb:/c", "smb:/b", "smb:/a"), order)
    }

    // a group is walked into because its folders lie all over the share; a plain folder means the
    // media directly in it, the way it does everywhere else in the gallery
    @Test
    fun `a group and a folder can be selected together`() {
        val children = mapOf(1L to listOf(folder("smb:/inside")))

        val order = walk(listOf(folder("smb:/outside"), group(1)), children)

        assertEquals(listOf("smb:/outside", "smb:/inside"), order)
    }

    @Test
    fun `a group selected twice is walked once`() {
        val children = mapOf(1L to listOf(folder("smb:/a")))

        val order = walk(listOf(group(1), group(1)), children)

        assertEquals(listOf("smb:/a"), order)
    }

    // a group that somehow holds itself must not walk for ever
    @Test
    fun `a group that holds itself is walked once`() {
        val children = mapOf(
            1L to listOf(folder("smb:/a"), group(2)),
            2L to listOf(folder("smb:/b"), group(1))
        )

        val order = walk(listOf(group(1)), children)

        assertEquals(listOf("smb:/a", "smb:/b"), order)
    }

    @Test
    fun `an empty selection asks for nothing`() {
        assertEquals(emptyList<String>(), walk(emptyList(), emptyMap()))
    }

    // a group with nothing in it yet is not an error, it simply yields nothing
    @Test
    fun `an empty group yields nothing`() {
        assertEquals(emptyList<String>(), walk(listOf(group(1)), emptyMap()))
    }
}
