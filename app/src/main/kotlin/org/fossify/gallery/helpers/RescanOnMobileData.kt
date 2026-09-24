package org.fossify.gallery.helpers

import android.app.Activity
import org.fossify.commons.dialogs.ConfirmationDialog

// What a sync policy answers for an event the user made with a gesture: start the rescan, hold
// it back, or put it to the user first.
//
// ASK is the answer #124 added. The event's own setting wants the rescan, and the network alone
// is holding it back: "unmetered only" is on and the connection is metered. Before, that read as
// SKIP, and a pull out of the house did nothing without a word -- the only way to a rescan was
// the settings screen, twice. Now the screen that started the event asks, once, with the scope
// named, since that is what decides whether it is a few listings or a walk of a thousand folders.
//
// Only a gesture gets the third answer. The events nobody sees -- on launch, after a write, on
// the interval -- keep answering a plain yes or no: a question with no gesture behind it is a nag
enum class RescanVerdict { RUN, ASK, SKIP }

// How much a rescan is going to walk, which is what the question names and what decides whether
// an earlier yes covers it: a yes to the whole share covers a folder of it, a yes to one folder
// covers no more than another folder. Ordered from the smallest up, so that ordinal compares
enum class RescanScope { FOLDER, GROUP, WHOLE }

// The question of #124, and the memory of its answer.
//
// A yes is remembered per storage for the rest of the app's run, at the scope it was given, so
// that a trip through five folders on mobile data is asked once and not five times. It is
// forgotten when the app is next launched -- the next trip out is a new question -- and never
// written to the settings: nothing here changes what the settings say. A no is not remembered at
// all. The next gesture asks again, which is what a gesture is for
object RescanOnMobileData {
    private val consented = mutableMapOf<RemoteScanScheduler.Storage, RescanScope>()

    @Synchronized
    fun isConsented(storage: RemoteScanScheduler.Storage, scope: RescanScope): Boolean {
        val given = consented[storage] ?: return false
        return given.ordinal >= scope.ordinal
    }

    @Synchronized
    fun consent(storage: RemoteScanScheduler.Storage, scope: RescanScope) {
        if (!isConsented(storage, scope)) {
            consented[storage] = scope
        }
    }

    @Synchronized
    fun forget() {
        consented.clear()
    }

    // Runs [rescan] at once when every storage in [asked] already has a yes on record that covers
    // its scope, and says so with true; otherwise puts [message] to the user and runs it on a
    // yes, remembering the yes for each of them. A no, or a dialog dismissed, runs nothing and
    // remembers nothing.
    //
    // Called on the UI thread, since it may open a dialog. [asked] holds one entry per storage
    // the rescan is for, so that a pull on "All storages" asks once for both rather than twice
    fun askThen(activity: Activity, asked: Map<RemoteScanScheduler.Storage, RescanScope>, message: String, rescan: () -> Unit): Boolean {
        if (asked.all { (storage, scope) -> isConsented(storage, scope) }) {
            rescan()
            return true
        }

        // the screen may be gone by the time a background lookup comes back with the question
        if (activity.isFinishing || activity.isDestroyed) {
            return false
        }

        ConfirmationDialog(activity, message) {
            asked.forEach { (storage, scope) -> consent(storage, scope) }
            rescan()
        }

        return false
    }
}
