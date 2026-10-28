package org.fossify.gallery.helpers

import android.content.Context
import android.util.Log
import org.fossify.gallery.jobs.RemoteScanService

// One queue for every scan of a remote storage, in place of a lock per storage.
//
// A scan takes minutes and there are four things that ask for one: the folder list arriving at a
// storage, a menu item, the settings' own schedule, and the refresh a transfer owes the folder it
// wrote to. Each used to guard itself with an AtomicBoolean, so what happened when two wanted the
// network at once was decided by which got there first -- a whole-share walk of several minutes
// could be thrown away by a sideways swipe, and a request that arrived while another ran was
// simply dropped on the floor.
//
// Here they are ranked instead, and there is one rule: a request preempts the running scan only
// when its priority is strictly higher, and otherwise waits its turn. Everything that was decided
// for #59 falls out of that one comparison:
//
// - a storage switch outranks the settings' automatic scan, so arriving at a storage stops a scan
//   nobody is looking at
// - a storage switch does not outrank a scan the user asked for by hand, so a swipe -- the easiest
//   gesture in the app to make by accident -- cannot throw away minutes of deliberate work
// - the refresh after a transfer outranks everything: it is short, and what was transferred stays
//   invisible until it has run
//
// A scan that is called off keeps its place (#80): the scanner leaves how far it had got in
// RemoteScanPlaces, unpublished, and the next scan of the same storage picks it up from there. How
// it was called off decides what happens to the request itself:
//
// - outranked by a request that came in, it is put back in the queue at its own rank once it has
//   stopped, and picks up where it was after the request that cut in has run
// - left behind by the folder list, it is not put back -- nobody is looking at that storage any
//   more -- but its place is still held for whichever scan of it comes next
// - stopped from the notification, it is neither put back nor does it keep its place: the user
//   wants the network left alone, not their place held
object RemoteScanScheduler {
    // the settings' own schedule: on launch, on arriving at a storage, on a pull. Nobody is
    // waiting on one, so it is the only thing a storage switch is allowed to cut short
    const val PRIORITY_AUTO = 0

    // the folder list has arrived at this storage and wants its folders
    const val PRIORITY_SWITCH = 1

    // asked for by hand: a menu item, or a pull the settings turned into a rescan
    const val PRIORITY_MANUAL = 2

    // the folders a transfer has just written to. Short, and what it wrote is out of sight
    // until this has run, so it goes to the front and cuts short whatever is running
    const val PRIORITY_TRANSFER = 3

    enum class Storage { PCLOUD, SMB }

    private const val TAG = "RemoteScan"

    // What one request is. [folders] empty means the whole storage; [full] is pCloud's "list the
    // whole account" rather than replaying the diff.
    //
    // [onDone] runs on whatever thread the scan ended on, and only for a scan that ran through --
    // a request that was preempted or failed does not call it, matching what the old
    // runPCloudScan() did. It is dropped as soon as the request leaves the queue, so an activity
    // captured in one is held no longer than the scan it was waiting for.
    //
    // [newFoldersOnly] is "Find new folders" (#127): the whole storage walked for the folders
    // the cache has no row for, and only those read; a known folder is neither re-read nor
    // dropped. It is a whole-storage request, but not the same work as a rescan.
    //
    // [connectionId] is which share an SMB request is for (#155): each connection is walked on
    // its own, and [folders] are all of that one connection. pCloud has one account and leaves
    // it at 0
    class Request(
        val storage: Storage,
        val priority: Int,
        val reportCounts: Boolean = false,
        val folders: List<String> = emptyList(),
        val full: Boolean = false,
        val newFoldersOnly: Boolean = false,
        val connectionId: Int = 0,
        val onDone: (() -> Unit)? = null,
    ) {
        val isWholeStorage get() = folders.isEmpty()

        // two whole-storage requests for the same storage ask for the same work, so a second one
        // is not worth queueing behind the first -- it only has to make sure the first is not
        // ranked below what the newcomer was asking for. Two shares are two storages
        fun isSameWorkAs(other: Request) =
            storage == other.storage && connectionId == other.connectionId && isWholeStorage && other.isWholeStorage &&
                full == other.full && newFoldersOnly == other.newFoldersOnly
    }

    // How the running scan was called off, in rising order of finality: a later call-off can make
    // it more final, never less. A scan left behind and then outranked is still not put back
    private enum class CallOff { OUTRANKED, LEFT_BEHIND, STOPPED }

    private val lock = Any()
    private val queue = ArrayList<Request>()
    private var running: Request? = null
    private var runningCallOff: CallOff? = null

    // How a running scan is called off, and whether it keeps its place. The scanners are reached
    // through this rather than named directly, so that what got called off can be watched without
    // a share to walk or an account to log in to -- which is the whole of rule 1, and the hardest
    // thing here to check by hand
    internal var abortScan: (Storage, keepPlace: Boolean) -> Unit = { storage, keepPlace ->
        when (storage) {
            Storage.PCLOUD -> PCloudScanner.abortCurrent(keepPlace)
            Storage.SMB -> SmbScanner.abortCurrent(keepPlace)
        }
    }

    // calls off the running scan, remembering the most final way it has been called off so far.
    // Called under the lock
    private fun callOff(current: Request, how: CallOff) {
        val was = runningCallOff
        if (was == null || how > was) {
            runningCallOff = how
        }

        abortScan(current.storage, runningCallOff != CallOff.STOPPED)
    }

    // Queues the request and makes sure something is draining the queue. Safe to call from any
    // thread; returns once the request is in, not once it has run
    fun submit(context: Context, request: Request) {
        enqueue(request)
        RemoteScanService.start(context)
    }

    // The queueing on its own, with no service to start: where the request lands, and what it
    // calls off on the way in. Split out of submit() because that one call to Android is all
    // that stood between these rules and a test of them
    internal fun enqueue(request: Request) {
        synchronized(lock) {
            merge(request)

            val current = running
            if (current != null && request.priority > current.priority) {
                Log.i(TAG, "${describe(request)} outranks the running ${describe(current)}; calling it off")
                callOff(current, CallOff.OUTRANKED)
            }
        }
    }

    // Puts a request in the queue, or folds it into the same work already there. Called under
    // the lock
    private fun merge(request: Request) {
        val existing = queue.firstOrNull { it.isSameWorkAs(request) }
        if (existing == null) {
            insert(request)
            return
        }

        // the work is already queued. Raise it to the newcomer's rank if that is higher, so that
        // a manual rescan is not left sitting behind at an automatic scan's place
        if (request.priority > existing.priority) {
            queue.remove(existing)
            insert(
                Request(
                    storage = existing.storage,
                    priority = request.priority,
                    reportCounts = existing.reportCounts,
                    folders = existing.folders,
                    full = existing.full,
                    newFoldersOnly = existing.newFoldersOnly,
                    connectionId = existing.connectionId,
                    onDone = existing.onDone
                )
            )
        }
    }

    // by priority, and first come first served within one priority: the queue is short and
    // ordering it on insert keeps taking from it to a single removeAt(0)
    private fun insert(request: Request) {
        val at = queue.indexOfFirst { it.priority < request.priority }
        if (at < 0) {
            queue.add(request)
        } else {
            queue.add(at, request)
        }
    }

    // The next request to run, marked as running, or null when the queue is empty. The service
    // calls this in a loop until it answers null
    fun takeNext(): Request? = synchronized(lock) {
        // removeAt(0) rather than removeFirst(): the Kotlin name collides with the one Java 21
        // added to List, and the collision is resolved differently depending on the device's API
        // level
        val next = if (queue.isEmpty()) null else queue.removeAt(0)
        running = next
        runningCallOff = null
        next
    }

    // The running request is over. [calledOff] is whether it ended by being called off, rather
    // than running through or failing; one that was outranked is then put back, to pick up its
    // place after what cut in. A scan that ran through in the moment it was being called off
    // says false, and is not run a second time
    fun finished(calledOff: Boolean = false) {
        synchronized(lock) {
            val current = running
            if (current != null && calledOff && runningCallOff == CallOff.OUTRANKED) {
                Log.i(TAG, "putting the ${describe(current)} back, to pick up where it was")
                merge(current)
            }

            running = null
            runningCallOff = null
        }
    }

    // whether anything is left to do, so the service knows not to stop yet
    fun hasWork(): Boolean = synchronized(lock) { queue.isNotEmpty() }

    // The folder list has left this storage behind, so a scan of it is of no use to the list any
    // more. It is called off only when the switch outranks it, which is the whole of rule 1: the
    // settings' automatic scan goes, and a walk the user asked for by hand stays. A sideways drag
    // is the easiest gesture in the app to make by accident, and a share takes minutes to walk.
    // What goes is not put back, but it keeps its place for the next scan of that storage.
    //
    // [connectionId] names the one share left behind; null is every one of them, which is what
    // leaving pCloud or a list that shows no share at all means
    fun leftBehind(storage: Storage, connectionId: Int? = null) {
        fun isLeft(request: Request) = request.storage == storage && (connectionId == null || request.connectionId == connectionId)

        synchronized(lock) {
            queue.removeAll { isLeft(it) && it.priority < PRIORITY_SWITCH }
            val current = running
            if (current != null && isLeft(current) && current.priority < PRIORITY_SWITCH) {
                Log.i(TAG, "the list left ${current.storage} behind; calling off its ${describe(current)}")
                callOff(current, CallOff.LEFT_BEHIND)
            }
        }
    }

    // Calls off the running scan whatever its rank, which is what the notification's stop action
    // does, and empties the queue with it: the user stopping a scan means they want the network
    // left alone, not the next request started on its heels. Nor is its place kept
    fun abortAll() {
        synchronized(lock) {
            queue.clear()
            running?.let { callOff(it, CallOff.STOPPED) }
        }
    }

    private fun describe(request: Request) = buildString {
        append(request.storage)
        if (request.storage == Storage.SMB && request.connectionId != 0) {
            append(" ${request.connectionId}")
        }

        append(if (request.isWholeStorage) " whole" else " ${request.folders.size} folders")
        if (request.newFoldersOnly) {
            append(", new folders only")
        }

        append(" at priority ${request.priority}")
    }
}
