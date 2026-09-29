package org.fossify.gallery.helpers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// When a sideways drag of the folder list turns it to another storage: at the top of the list
// only, and not inside a group or a folder (#136)
class StorageSwipePolicyTest {
    private fun can(
        storageCount: Int = 2,
        isAnimating: Boolean = false,
        scrollsHorizontally: Boolean = false,
        isSelecting: Boolean = false,
        isInsideGroup: Boolean = false,
        isInsideFolder: Boolean = false
    ) = canSwipeStorage(storageCount, isAnimating, scrollsHorizontally, isSelecting, isInsideGroup, isInsideFolder)

    @Test
    fun `at the top of the list with two storages it switches`() {
        assertTrue(can())
    }

    // the same group on the next storage usually holds nothing of it, and read as empty
    @Test
    fun `inside a group it does not switch`() {
        assertFalse(can(isInsideGroup = true))
    }

    @Test
    fun `inside a folder of grouped subfolders it does not switch`() {
        assertFalse(can(isInsideFolder = true))
    }

    @Test
    fun `with only this device there is nowhere to switch to`() {
        assertFalse(can(storageCount = 1))
    }

    @Test
    fun `not while the last switch is still sliding`() {
        assertFalse(can(isAnimating = true))
    }

    @Test
    fun `not while the list scrolls sideways`() {
        assertFalse(can(scrollsHorizontally = true))
    }

    @Test
    fun `not while folders are selected`() {
        assertFalse(can(isSelecting = true))
    }
}
