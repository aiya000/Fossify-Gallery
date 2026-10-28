package org.fossify.gallery.helpers

import org.junit.Assert.assertEquals
import org.junit.Test

// How long the app's own message stays up (#76). The bar itself needs a screen and is driven in
// test-device/drive/87-messages-are-the-apps-own.sh; what is pinned here is the time it is given
class InAppMessagePolicyTest {
    @Test
    fun `a word stays as long as the shortest bar`() {
        assertEquals(InAppMessagePolicy.SHORT_MILLIS, InAppMessagePolicy.durationMillis("Saving".length, wasLong = false))
    }

    // the callers that asked the OS for a long toast had a reason; a short text does not shorten it
    @Test
    fun `a message that asked for long is given what a long toast had`() {
        assertEquals(InAppMessagePolicy.LONG_MILLIS, InAppMessagePolicy.durationMillis("File saved".length, wasLong = true))
    }

    @Test
    fun `a sentence is given the time it takes to read`() {
        val sentence = "The edited copy was kept at /storage/emulated/0/Pictures/edit.jpg"
        assertEquals(1500 + 40 * sentence.length, InAppMessagePolicy.durationMillis(sentence.length, wasLong = false))
    }

    @Test
    fun `a long error does not sit on the screen`() {
        assertEquals(InAppMessagePolicy.LONGEST_MILLIS, InAppMessagePolicy.durationMillis(500, wasLong = true))
    }
}
