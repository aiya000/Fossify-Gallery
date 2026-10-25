package org.fossify.gallery.helpers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// When a pull on the folder list asks before the rescan it would start. The question itself
// needs a screen and is driven in test-device/drive/96-ask-before-a-rescan-on-a-pull.sh; what is
// pinned here is which pulls get it
class PullRescanConfirmationTest {
    @Test
    fun `a pull at the top of the list asks, when the setting is on`() {
        assertTrue(PullRescanConfirmation.isWanted(RescanScope.WHOLE, wanted = true, verdict = RescanVerdict.RUN))
    }

    @Test
    fun `with the setting off it goes ahead`() {
        assertFalse(PullRescanConfirmation.isWanted(RescanScope.WHOLE, wanted = false, verdict = RescanVerdict.RUN))
    }

    // the group's folders are a few listings, not a walk of the storage
    @Test
    fun `inside a group it goes ahead`() {
        assertFalse(PullRescanConfirmation.isWanted(RescanScope.GROUP, wanted = true, verdict = RescanVerdict.RUN))
    }

    // the mobile-data question is already there, and says why it is asking
    @Test
    fun `a rescan the network is holding back keeps its own question`() {
        assertFalse(PullRescanConfirmation.isWanted(RescanScope.WHOLE, wanted = true, verdict = RescanVerdict.ASK))
    }

    @Test
    fun `a rescan the settings skip has nothing to ask about`() {
        assertFalse(PullRescanConfirmation.isWanted(RescanScope.WHOLE, wanted = true, verdict = RescanVerdict.SKIP))
    }
}
