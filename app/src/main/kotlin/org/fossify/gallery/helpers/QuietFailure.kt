package org.fossify.gallery.helpers

import android.util.Log

// Where a failure nobody is waiting on is written down (#74).
//
// A catch that swallows everything looks exactly like "nothing happened", which is the hardest
// thing to report and the hardest thing to debug. What a person is waiting on is said on the
// screen (extensions/InAppMessage.kt). What they are not -- a cache row that would not write, a
// lookup with a fallback, a job that would not schedule -- is written here instead of thrown
// away, under one tag, so that `adb logcat -s QuietFailure` is the one place to look when the
// app seems to have done nothing. [what] completes "Could not ...", and names what it was about
const val QUIET_FAILURE_TAG = "QuietFailure"

fun logQuietFailure(what: String, e: Throwable) {
    Log.w(QUIET_FAILURE_TAG, "Could not $what", e)
}
