package org.fossify.gallery.helpers

import com.bumptech.glide.Priority
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

// The threads that fetch the thumbnails of the remote storages, kept apart from Glide's own
// (#165).
//
// Glide decodes every thumbnail on one small pool of source threads, this device's and the
// remote storages' alike. A thumbnail of the share is a whole photo read over the network
// before anything can be decoded out of it, and a read that has started holds its thread until
// it is through: Glide's cancel() only raises a flag. With the pool blocked on a few of those,
// the tiles of this device -- milliseconds each -- queued behind them, which is what made the
// folder list go blank for a long while after a look at the share.
//
// So the remote loaders hand their work here and give Glide's thread back at once; Glide picks
// the decode up again once the bytes are on the device (SourceGenerator reschedules a DecodeJob
// whose data arrives from another thread, which is how its OkHttp integration works too). The
// queue is a priority queue on Glide's own Priority, first come first served within a rank, so
// that what Glide was asked to load first is fetched first here as well. The threads are few:
// a share is read over one connection, and more readers would only share it out thinner.
//
// Which rank a tile gets is loadImageBase()'s: this device's thumbnails HIGH, the remote ones
// NORMAL, so that in Glide's own queue a local decode is never stuck behind a remote one either
object RemoteThumbnails {
    const val THREADS = 3

    // how long an idle thread is kept before it is let go: a scroll through a folder is bursts
    // of work with pauses between them, and a thread kept through the pause is one less to start
    private const val KEEP_ALIVE_SECONDS = 30L

    private val order = AtomicLong()

    private val executor = ThreadPoolExecutor(THREADS, THREADS, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS, PriorityBlockingQueue(), NamedThreads()).apply {
        allowCoreThreadTimeOut(true)
    }

    // Runs [work] on one of the threads here, in order of [priority] and then of arrival. Returns
    // at once, so a Glide fetcher calling this from loadData() is done with Glide's thread
    fun submit(priority: Priority, work: () -> Unit) {
        executor.execute(Job(priority, order.getAndIncrement(), work))
    }

    // One piece of work and its place in the queue. Glide's Priority is ordered from the most
    // urgent down (IMMEDIATE, HIGH, NORMAL, LOW), so the lower ordinal goes first; between two of
    // the same rank, the one that was submitted first
    class Job(val priority: Priority, private val order: Long, private val work: () -> Unit) : Runnable, Comparable<Job> {
        override fun run() = work()

        override fun compareTo(other: Job): Int {
            val byRank = priority.ordinal.compareTo(other.priority.ordinal)
            return if (byRank != 0) byRank else order.compareTo(other.order)
        }
    }

    // named, so that a log line or a stack trace says whose thread a remote read is on -- which
    // is also what test-device/drive/40 and 41 read to see that it is not one of Glide's
    private class NamedThreads : ThreadFactory {
        private val count = AtomicInteger()

        override fun newThread(runnable: Runnable): Thread {
            return Thread(runnable, "$THREAD_NAME_PREFIX${count.getAndIncrement()}").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
            }
        }
    }

    const val THREAD_NAME_PREFIX = "remote-thumbnails-"
}
