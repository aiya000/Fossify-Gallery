package org.fossify.gallery.helpers

// How long one of the app's own messages stays up (extensions/InAppMessage.kt, #76).
//
// A toast had two lengths and no idea what it said. A bar that may hold five lines is given the
// time its words take to read instead: a floor so that a short word is not gone in a blink, and a
// ceiling so that a long error does not sit on the screen
object InAppMessagePolicy {
    // Snackbar.LENGTH_LONG, what the shortest message gets
    const val SHORT_MILLIS = 2750

    // what a toast that asked for Toast.LENGTH_LONG got from the OS
    const val LONG_MILLIS = 3500
    const val LONGEST_MILLIS = 7000

    private const val MILLIS_TO_START_READING = 1500
    private const val MILLIS_PER_CHARACTER = 40

    fun durationMillis(textLength: Int, wasLong: Boolean): Int {
        val floor = if (wasLong) LONG_MILLIS else SHORT_MILLIS
        val reading = MILLIS_TO_START_READING + MILLIS_PER_CHARACTER * textLength
        return reading.coerceIn(floor, LONGEST_MILLIS)
    }
}
