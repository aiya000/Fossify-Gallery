package org.fossify.gallery.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The speed a held video runs at is a Float, while the settings dialog addresses its rows by an
// Int id, so every offered speed makes the trip through a whole percent and back. A step that
// does not survive that trip would open the dialog with nothing selected, or store a speed the
// user never picked
class PlaybackSpeedStepsTest {
    @Test
    fun `every offered speed comes back from its radio item id`() {
        LONG_PRESS_PLAYBACK_SPEEDS.forEach {
            assertEquals(it, it.toPlaybackSpeedPercent().toPlaybackSpeed(), 0f)
        }
    }

    @Test
    fun `no two offered speeds share a radio item id`() {
        val ids = LONG_PRESS_PLAYBACK_SPEEDS.map { it.toPlaybackSpeedPercent() }

        assertEquals(LONG_PRESS_PLAYBACK_SPEEDS.size, ids.distinct().size)
    }

    // the dialog is opened with the stored speed as its checked id; a default that is not one of
    // the rows would check none of them
    @Test
    fun `the default speed is one of the offered ones`() {
        assertTrue(LONG_PRESS_PLAYBACK_SPEEDS.contains(DEFAULT_LONG_PRESS_PLAYBACK_SPEED))
    }

    // the gesture ran at a fixed 2x before it was made a setting, and stays there for anyone
    // who does not go looking for it
    @Test
    fun `the default speed is still the 2x the gesture came in with`() {
        assertEquals(2f, DEFAULT_LONG_PRESS_PLAYBACK_SPEED, 0f)
    }

    @Test
    fun `a percent reads back as the speed it names`() {
        assertEquals(2f, 200.toPlaybackSpeed(), 0f)
        assertEquals(1.25f, 125.toPlaybackSpeed(), 0f)
    }

    // rounded, not truncated: a step finer than a percent would otherwise drift downwards on
    // every trip through the dialog
    @Test
    fun `a speed finer than a percent is rounded to the nearest one`() {
        assertEquals(133, 1.333f.toPlaybackSpeedPercent())
        assertEquals(134, 1.336f.toPlaybackSpeedPercent())
    }

    // which is why the offered steps are whole percents: this one does not survive the trip,
    // and none of the rows may be like it
    @Test
    fun `a speed finer than a percent does not survive the trip`() {
        assertNotEquals(1.333f, 1.333f.toPlaybackSpeedPercent().toPlaybackSpeed(), 0f)
    }
}
