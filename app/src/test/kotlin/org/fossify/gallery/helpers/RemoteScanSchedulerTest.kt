package org.fossify.gallery.helpers

import org.fossify.gallery.helpers.RemoteScanScheduler.Request
import org.fossify.gallery.helpers.RemoteScanScheduler.Storage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// The rules #59 settled, written down as tests instead of as a checklist.
//
// Each of these was checked by hand: turn the automatic rescan on, arrive at the share, wait for
// the notification, swipe sideways at the right moment and watch whether the notification goes
// away. That is minutes of waiting to see one comparison come out one way, and the moment is easy
// to miss. Here the scan is not started at all -- what a request calls off is watched through
// RemoteScanScheduler.abortScan, and what it left behind is read off the queue.
//
// The scheduler is one object for the whole app, so every test hands it back empty.
class RemoteScanSchedulerTest {
    private val calledOff = ArrayList<Storage>()
    private var realAbortScan: (Storage) -> Unit = {}

    @Before
    fun setUp() {
        realAbortScan = RemoteScanScheduler.abortScan
        RemoteScanScheduler.abortScan = { calledOff.add(it) }
        emptyTheQueue()
        calledOff.clear()
    }

    @After
    fun tearDown() {
        emptyTheQueue()
        RemoteScanScheduler.abortScan = realAbortScan
    }

    // what the notification's stop action does, which is also the shortest way back to an empty
    // scheduler: the queue goes, and finished() lets go of whatever was marked as running
    private fun emptyTheQueue() {
        RemoteScanScheduler.abortAll()
        RemoteScanScheduler.finished()
    }

    private fun whole(storage: Storage, priority: Int) = Request(storage, priority)

    private fun folder(storage: Storage, priority: Int, path: String) =
        Request(storage, priority, folders = listOf(path))

    // the service takes the next request and marks it as the running one
    private fun startNext(): Request? = RemoteScanScheduler.takeNext()

    // Rule 1-a: arriving at another storage calls off a scan nobody asked for. By hand: the
    // notification disappears when you swipe away from a share the settings started scanning
    @Test
    fun `a storage switch calls off the automatic scan it left behind`() {
        RemoteScanScheduler.enqueue(whole(Storage.SMB, RemoteScanScheduler.PRIORITY_AUTO))
        startNext()

        RemoteScanScheduler.leftBehind(Storage.SMB)

        assertEquals(listOf(Storage.SMB), calledOff)
    }

    // Rule 1-b, the one that changed in #77: a walk the user asked for by hand survives a swipe.
    // By hand: rescan from the menu, swipe away, and the notification has to stay
    @Test
    fun `a storage switch leaves a scan the user asked for alone`() {
        RemoteScanScheduler.enqueue(whole(Storage.SMB, RemoteScanScheduler.PRIORITY_MANUAL))
        startNext()

        RemoteScanScheduler.leftBehind(Storage.SMB)

        assertTrue("a swipe is the easiest gesture to make by accident", calledOff.isEmpty())
    }

    // The middle case, which reads as a surprise until the ranks are laid out: the rescan the
    // settings start on arriving at a storage is itself ranked with the switch, and leftBehind()
    // drops only what ranks *below* it. So leaving that storage again does not call it off.
    // "the settings' automatic scan goes" means the one they start on launch or on their
    // interval, not the one arriving here started
    @Test
    fun `a scan the arrival started is not called off by leaving again`() {
        RemoteScanScheduler.enqueue(whole(Storage.SMB, RemoteScanScheduler.PRIORITY_SWITCH))
        startNext()

        RemoteScanScheduler.leftBehind(Storage.SMB)

        assertTrue(calledOff.isEmpty())
    }

    // the same rule for a request that had not started yet: it is dropped, not kept waiting
    @Test
    fun `a storage switch drops a queued automatic scan and keeps a queued manual one`() {
        RemoteScanScheduler.enqueue(folder(Storage.SMB, RemoteScanScheduler.PRIORITY_AUTO, "smb:/pictures"))
        RemoteScanScheduler.leftBehind(Storage.SMB)
        assertFalse(RemoteScanScheduler.hasWork())

        RemoteScanScheduler.enqueue(folder(Storage.SMB, RemoteScanScheduler.PRIORITY_MANUAL, "smb:/pictures"))
        RemoteScanScheduler.leftBehind(Storage.SMB)
        assertTrue(RemoteScanScheduler.hasWork())
    }

    // a swipe is about the storage being left, and says nothing about the other one
    @Test
    fun `a storage switch does not touch the other storage`() {
        RemoteScanScheduler.enqueue(whole(Storage.PCLOUD, RemoteScanScheduler.PRIORITY_AUTO))
        startNext()

        RemoteScanScheduler.leftBehind(Storage.SMB)

        assertTrue(calledOff.isEmpty())
    }

    // Rule 3: what a transfer wrote is invisible until its folders are read again, so it cuts
    // short whatever is running. By hand: start a whole scan, copy a file into pCloud, and the
    // file has to show up in its folder without waiting for the scan
    @Test
    fun `a transfer cuts short the scan that is running and goes first`() {
        RemoteScanScheduler.enqueue(whole(Storage.SMB, RemoteScanScheduler.PRIORITY_MANUAL))
        startNext()

        RemoteScanScheduler.enqueue(folder(Storage.SMB, RemoteScanScheduler.PRIORITY_TRANSFER, "smb:/pictures"))

        assertEquals(listOf(Storage.SMB), calledOff)
        RemoteScanScheduler.finished()
        assertEquals(RemoteScanScheduler.PRIORITY_TRANSFER, startNext()?.priority)
    }

    // and the scan it cut short is gone, rather than starting again on its own once the transfer
    // has been read. Neither scanner can resume from where it was, see #80
    @Test
    fun `a scan that was cut short is not put back`() {
        RemoteScanScheduler.enqueue(whole(Storage.SMB, RemoteScanScheduler.PRIORITY_MANUAL))
        startNext()
        RemoteScanScheduler.enqueue(folder(Storage.SMB, RemoteScanScheduler.PRIORITY_TRANSFER, "smb:/pictures"))
        RemoteScanScheduler.finished()

        startNext()
        RemoteScanScheduler.finished()

        assertNull(startNext())
    }

    // the one rule underneath all three: strictly higher, or wait your turn
    @Test
    fun `an equal rank waits its turn instead of cutting in`() {
        RemoteScanScheduler.enqueue(whole(Storage.PCLOUD, RemoteScanScheduler.PRIORITY_MANUAL))
        startNext()

        RemoteScanScheduler.enqueue(whole(Storage.SMB, RemoteScanScheduler.PRIORITY_MANUAL))

        assertTrue(calledOff.isEmpty())
        assertTrue(RemoteScanScheduler.hasWork())
    }

    @Test
    fun `a lower rank waits its turn too`() {
        RemoteScanScheduler.enqueue(whole(Storage.SMB, RemoteScanScheduler.PRIORITY_MANUAL))
        startNext()

        RemoteScanScheduler.enqueue(whole(Storage.PCLOUD, RemoteScanScheduler.PRIORITY_AUTO))

        assertTrue(calledOff.isEmpty())
    }

    @Test
    fun `the queue comes out by rank, highest first`() {
        RemoteScanScheduler.enqueue(whole(Storage.SMB, RemoteScanScheduler.PRIORITY_AUTO))
        RemoteScanScheduler.enqueue(whole(Storage.PCLOUD, RemoteScanScheduler.PRIORITY_MANUAL))
        RemoteScanScheduler.enqueue(folder(Storage.SMB, RemoteScanScheduler.PRIORITY_TRANSFER, "smb:/pictures"))

        val ranks = generateSequence { startNext() }.map { it.priority }.toList()

        assertEquals(
            listOf(
                RemoteScanScheduler.PRIORITY_TRANSFER,
                RemoteScanScheduler.PRIORITY_MANUAL,
                RemoteScanScheduler.PRIORITY_AUTO
            ),
            ranks
        )
    }

    @Test
    fun `within one rank it is first come first served`() {
        RemoteScanScheduler.enqueue(folder(Storage.SMB, RemoteScanScheduler.PRIORITY_MANUAL, "smb:/first"))
        RemoteScanScheduler.enqueue(folder(Storage.SMB, RemoteScanScheduler.PRIORITY_MANUAL, "smb:/second"))
        RemoteScanScheduler.enqueue(folder(Storage.SMB, RemoteScanScheduler.PRIORITY_MANUAL, "smb:/third"))

        val paths = generateSequence { startNext() }.map { it.folders.single() }.toList()

        assertEquals(listOf("smb:/first", "smb:/second", "smb:/third"), paths)
    }

    // two whole-storage requests ask for the same walk, so the second does not add a second walk
    @Test
    fun `the same whole storage scan is not queued twice`() {
        RemoteScanScheduler.enqueue(whole(Storage.SMB, RemoteScanScheduler.PRIORITY_AUTO))
        RemoteScanScheduler.enqueue(whole(Storage.SMB, RemoteScanScheduler.PRIORITY_AUTO))

        assertEquals(1, generateSequence { startNext() }.count())
    }

    // ...but it is raised, so a rescan asked for by hand is not left sitting at the automatic
    // scan's place in the queue
    @Test
    fun `a queued scan is raised to the rank of the request that asked again`() {
        RemoteScanScheduler.enqueue(whole(Storage.SMB, RemoteScanScheduler.PRIORITY_AUTO))
        RemoteScanScheduler.enqueue(whole(Storage.SMB, RemoteScanScheduler.PRIORITY_MANUAL))

        val queued = generateSequence { startNext() }.toList()

        assertEquals(1, queued.size)
        assertEquals(RemoteScanScheduler.PRIORITY_MANUAL, queued.single().priority)
    }

    @Test
    fun `a queued scan is never lowered`() {
        RemoteScanScheduler.enqueue(whole(Storage.SMB, RemoteScanScheduler.PRIORITY_MANUAL))
        RemoteScanScheduler.enqueue(whole(Storage.SMB, RemoteScanScheduler.PRIORITY_AUTO))

        val queued = generateSequence { startNext() }.toList()

        assertEquals(1, queued.size)
        assertEquals(RemoteScanScheduler.PRIORITY_MANUAL, queued.single().priority)
    }

    // a full listing and a replayed diff are not the same work, so one does not stand in for the
    // other
    @Test
    fun `a full scan and a diff are queued separately`() {
        RemoteScanScheduler.enqueue(Request(Storage.PCLOUD, RemoteScanScheduler.PRIORITY_MANUAL, full = true))
        RemoteScanScheduler.enqueue(Request(Storage.PCLOUD, RemoteScanScheduler.PRIORITY_MANUAL, full = false))

        assertEquals(2, generateSequence { startNext() }.count())
    }

    // a folder is not the storage, so it queues behind a whole-storage walk rather than replacing it
    @Test
    fun `a single folder does not stand in for a whole storage scan`() {
        RemoteScanScheduler.enqueue(whole(Storage.SMB, RemoteScanScheduler.PRIORITY_MANUAL))
        RemoteScanScheduler.enqueue(folder(Storage.SMB, RemoteScanScheduler.PRIORITY_MANUAL, "smb:/pictures"))

        assertEquals(2, generateSequence { startNext() }.count())
    }

    // stopping a scan from the notification means "leave the network alone", not "start the next one"
    @Test
    fun `stopping the running scan empties the queue with it`() {
        RemoteScanScheduler.enqueue(whole(Storage.SMB, RemoteScanScheduler.PRIORITY_MANUAL))
        startNext()
        RemoteScanScheduler.enqueue(whole(Storage.PCLOUD, RemoteScanScheduler.PRIORITY_MANUAL))

        RemoteScanScheduler.abortAll()

        assertEquals(listOf(Storage.SMB), calledOff)
        assertFalse(RemoteScanScheduler.hasWork())
    }
}
