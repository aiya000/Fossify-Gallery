package org.fossify.gallery.helpers

import com.bumptech.glide.Priority
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.PriorityQueue

// The order the remote thumbnails are fetched in (#165): by Glide's own rank first, and within
// a rank in the order they were asked for. The threads themselves are driven in
// test-device/drive/40 and 41, which read off the log whose thread a remote read ran on
class RemoteThumbnailsTest {
    private var next = 0L

    private fun job(name: String, priority: Priority) = RemoteThumbnails.Job(priority, next++) { } to name

    private fun drained(vararg jobs: Pair<RemoteThumbnails.Job, String>): List<String> {
        val names = jobs.toMap()
        val queue = PriorityQueue<RemoteThumbnails.Job>()
        jobs.forEach { queue.add(it.first) }
        return generateSequence { queue.poll() }.map { names.getValue(it) }.toList()
    }

    @Test
    fun `the more urgent rank goes first, whatever order it was asked in`() {
        val order = drained(
            job("low", Priority.LOW),
            job("normal", Priority.NORMAL),
            job("high", Priority.HIGH),
            job("immediate", Priority.IMMEDIATE)
        )

        assertEquals(listOf("immediate", "high", "normal", "low"), order)
    }

    // a scroll asks for the tiles top to bottom, and that is the order they should turn up in
    @Test
    fun `within one rank it is first come first served`() {
        val order = drained(
            job("first", Priority.NORMAL),
            job("second", Priority.NORMAL),
            job("third", Priority.NORMAL)
        )

        assertEquals(listOf("first", "second", "third"), order)
    }

    @Test
    fun `a later request of a higher rank cuts in ahead of earlier ones`() {
        val order = drained(
            job("first normal", Priority.NORMAL),
            job("second normal", Priority.NORMAL),
            job("late high", Priority.HIGH)
        )

        assertEquals(listOf("late high", "first normal", "second normal"), order)
    }
}
