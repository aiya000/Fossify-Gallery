package org.fossify.gallery.helpers

import org.fossify.gallery.models.Medium
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// A walk put down part-way and picked up again has to come to exactly what one walk straight
// through comes to (#80), and a folder something wrote to in between has to be read again. Both
// are checked against a share made of a map, so that "where it stopped" can be any folder at all
// rather than wherever a real walk happened to be when a transfer cut in.
class SmbWalkTest {
    private class CalledOff : Exception()

    // a share as the walk sees it: each folder's entries. Files are named *.jpg, and a folder
    // holds "pictures of" itself so that what was read can be told apart from what is held
    private val share = mutableMapOf(
        "smb:" to listOf(folder("a"), folder("b"), file("top.jpg")),
        "smb:/a" to listOf(folder("a1"), file("1.jpg")),
        "smb:/a/a1" to listOf(file("2.jpg"), file("3.jpg")),
        "smb:/b" to listOf(folder("b1"), folder("b2")),
        "smb:/b/b1" to listOf(file("4.jpg")),
        "smb:/b/b2" to listOf(file("5.jpg")),
    )

    private fun folder(name: String) = SmbClient.Entry(name, true, 0L, 0L)
    private fun file(name: String) = SmbClient.Entry(name, false, 1L, 1L)

    private fun newWalk(maxDepth: Int = 24) = SmbWalk<List<String>>("smb:", maxDepth) { it.size }

    // the files of a folder, by path; null for one with none, which the walk does not keep
    private fun read(path: String, entries: List<SmbClient.Entry>) =
        entries.filter { !it.isFolder }.map { "$path/${it.name}" }.ifEmpty { null }

    // walks until [stopAfter] folders have been listed in this call, then throws the way an abort
    // does: from inside the listing, before the folder is read
    private fun walk(walk: SmbWalk<List<String>>, stopAfter: Int = Int.MAX_VALUE, order: MutableList<String> = ArrayList()) {
        var listed = 0
        walk.walk(
            list = { path, _ ->
                if (listed++ == stopAfter) {
                    throw CalledOff()
                }

                order.add(path)
                share[path]
            },
            read = ::read,
            isWalkable = { _, _ -> true }
        )
    }

    private fun walkCalledOffAt(walk: SmbWalk<List<String>>, stopAfter: Int) {
        try {
            walk(walk, stopAfter)
        } catch (e: CalledOff) {
            return
        }

        throw AssertionError("the walk ran through without being called off")
    }

    private fun filesOf(walk: SmbWalk<List<String>>) = walk.results.flatten().sorted()

    private val everyFile = listOf(
        "smb:/a/1.jpg", "smb:/a/a1/2.jpg", "smb:/a/a1/3.jpg", "smb:/b/b1/4.jpg", "smb:/b/b2/5.jpg", "smb:/top.jpg"
    )

    @Test
    fun `a walk straight through lists the share in the order the recursion did`() {
        val order = ArrayList<String>()
        val walk = newWalk()
        walk(walk, order = order)

        assertEquals(listOf("smb:", "smb:/a", "smb:/a/a1", "smb:/b", "smb:/b/b1", "smb:/b/b2"), order)
        assertEquals(everyFile, filesOf(walk))
        assertEquals(6, walk.mediaCount)
    }

    // every folder of the share is a place a walk can be cut short at; each of them has to come to
    // the same files as no cut at all, and list no folder twice
    @Test
    fun `a walk called off at any folder and picked up comes to the same as one straight through`() {
        for (stopAt in 0 until share.size) {
            val walk = newWalk()
            walkCalledOffAt(walk, stopAt)
            assertEquals("listed before the call-off at $stopAt", stopAt, walk.listedCount)

            walk(walk)

            assertEquals("called off at $stopAt", everyFile, filesOf(walk))
            assertEquals("called off at $stopAt", share.size, walk.listedCount)
        }
    }

    @Test
    fun `a walk can be called off more than once`() {
        val walk = newWalk()
        walkCalledOffAt(walk, 2)
        walkCalledOffAt(walk, 1)
        walkCalledOffAt(walk, 2)
        walk(walk)

        assertEquals(everyFile, filesOf(walk))
        assertEquals(share.size, walk.listedCount)
    }

    // the folder that could not be listed stays skipped across the pause, so its rows stay safe
    @Test
    fun `a folder skipped before the pause is still skipped after it`() {
        share["smb:/a/a1"] = emptyList()
        val walk = SmbWalk<List<String>>("smb:", 24) { it.size }
        walk.walk(
            list = { path, _ -> if (path == "smb:/a/a1") null else share[path] },
            read = ::read,
            isWalkable = { _, _ -> true }
        )

        assertEquals(setOf("smb:/a/a1"), walk.skippedPaths)
    }

    // A transfer cut in while the walk was put down, and wrote a file into a folder the walk had
    // already listed. Storing what the walk held there would drop the file again
    @Test
    fun `a folder written to while the walk was put down is read again`() {
        val walk = newWalk()
        walkCalledOffAt(walk, 3)
        share["smb:/a"] = listOf(folder("a1"), file("1.jpg"), file("copied.jpg"))

        walk.reopen(setOf("smb:/a"))
        val order = ArrayList<String>()
        walk(walk, order = order)

        assertTrue("smb:/a/copied.jpg" in filesOf(walk))
        assertEquals("only that folder is listed again, not what is under it", 1, order.count { it.startsWith("smb:/a") })
    }

    // the folder lost its last file meanwhile: what the walk held there goes, rather than coming back
    @Test
    fun `a folder emptied while the walk was put down loses what the walk held there`() {
        val walk = newWalk()
        walkCalledOffAt(walk, 3)
        share["smb:/a/a1"] = emptyList()

        walk.reopen(setOf("smb:/a/a1"))
        walk(walk)

        assertEquals(everyFile - setOf("smb:/a/a1/2.jpg", "smb:/a/a1/3.jpg"), filesOf(walk))
        assertEquals(4, walk.mediaCount)
    }

    // a folder that appeared under one already listed is not on the walk's list at all; a copy of
    // a folder onto the share makes one
    @Test
    fun `a folder that appeared while the walk was put down is read`() {
        val walk = newWalk()
        walkCalledOffAt(walk, 3)
        share["smb:/a/new"] = listOf(file("6.jpg"))

        walk.reopen(setOf("smb:/a/new"))
        walk(walk)

        assertTrue("smb:/a/new/6.jpg" in filesOf(walk))
    }

    // a changed folder the walk has not got to yet is read when it gets there, once
    @Test
    fun `a changed folder the walk has still to list is not listed twice`() {
        val walk = newWalk()
        walkCalledOffAt(walk, 3)

        walk.reopen(setOf("smb:/b", "smb:/b/b2"))
        val order = ArrayList<String>()
        walk(walk, order = order)

        assertEquals(listOf("smb:/b", "smb:/b/b1", "smb:/b/b2"), order)
    }

    @Test
    fun `a changed folder of another share or under a skipped one is left alone`() {
        val walk = SmbWalk<List<String>>("smb:", 24) { it.size }
        walk.walk(
            list = { path, _ -> if (path == "smb:/b") null else share[path] },
            read = ::read,
            isWalkable = { _, _ -> true }
        )

        walk.reopen(setOf("smb:2/a", "smb:/b/b1"))
        val order = ArrayList<String>()
        walk(walk, order = order)

        assertEquals(emptyList<String>(), order)
    }

    private fun medium(path: String, size: Long = 1L, modified: Long = 1L) =
        Medium().apply {
            this.path = path
            this.parentPath = path.substringBeforeLast('/')
            this.size = size
            this.modified = modified
        }

    @Test
    fun `the changed folders are the ones whose rows differ, whatever order the rows come in`() {
        val before = SmbWalk.signaturesOf(listOf(medium("smb:/a/1.jpg"), medium("smb:/a/2.jpg"), medium("smb:/b/3.jpg"), medium("smb:/c/4.jpg")))
        val after = SmbWalk.signaturesOf(
            listOf(
                medium("smb:/a/2.jpg"), medium("smb:/a/1.jpg"), // the same rows, the other way round
                medium("smb:/b/3.jpg", size = 2L), // rewritten in place
                medium("smb:/d/5.jpg"), // a folder that gained its first row; c lost its last
            )
        )

        assertEquals(setOf("smb:/b", "smb:/c", "smb:/d"), SmbWalk.changedFolders(before, after))
    }

    @Test
    fun `a file moved to another name in the same folder changes that folder`() {
        val before = SmbWalk.signaturesOf(listOf(medium("smb:/a/1.jpg")))
        val after = SmbWalk.signaturesOf(listOf(medium("smb:/a/one.jpg")))

        assertEquals(setOf("smb:/a"), SmbWalk.changedFolders(before, after))
    }
}
