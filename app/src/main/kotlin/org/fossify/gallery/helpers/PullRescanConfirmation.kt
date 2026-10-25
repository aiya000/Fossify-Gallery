package org.fossify.gallery.helpers

// Whether a pull on the folder list asks before it starts the rescan its settings call for.
//
// A pull is the easiest gesture in the app to make by accident -- the list is scrolled with the
// same finger -- and at the top of the list it reaches the whole of a storage: on the share that
// is a walk of every folder, minutes of work, which once started goes on in the background and
// slows everything else while it runs. A per-storage setting puts a question in front of it.
//
// Only the top of the list asks. Inside a group the pull reaches that group's folders alone,
// which is a few listings, and a question before those would be a nag -- the same reason the
// mobile-data question of #124 names its scope.
//
// Only a rescan that would otherwise run is asked about. One the network alone is holding back
// (ASK, see RescanVerdict) already has its own question, which says why it is asking, and a
// second one on top of it would be two dialogs for one gesture. One the settings skip has
// nothing to ask about
object PullRescanConfirmation {
    fun isWanted(scope: RescanScope, wanted: Boolean, verdict: RescanVerdict): Boolean {
        return wanted && scope == RescanScope.WHOLE && verdict == RescanVerdict.RUN
    }
}
