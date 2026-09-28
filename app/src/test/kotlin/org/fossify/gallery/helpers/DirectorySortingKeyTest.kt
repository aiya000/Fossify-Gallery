package org.fossify.gallery.helpers

import org.junit.Assert.assertEquals
import org.junit.Test

// Which stored sorting the folder list on screen goes by (#137): an opened group's own, then the
// storage's own, then the shared one
class DirectorySortingKeyTest {
    private val trips = 7L
    private val tripsKey = "${SORT_FOLDERS_GROUP_PREFIX}7"
    private val shareKey = "$SORT_FOLDERS_STORAGE_PREFIX$STORAGE_FILTER_SMB"

    private fun storing(vararg keys: String): (String) -> Boolean = { keys.contains(it) }

    @Test
    fun `a group with a sorting of its own goes by it`() {
        assertEquals(tripsKey, directorySortingKey(trips, STORAGE_FILTER_SMB, storing(tripsKey)))
    }

    // the group's own wins over the storage's, which is what "apply to this group only" is for
    @Test
    fun `a group's own sorting comes before the storage's`() {
        assertEquals(tripsKey, directorySortingKey(trips, STORAGE_FILTER_SMB, storing(tripsKey, shareKey)))
    }

    @Test
    fun `a group without its own falls back to the storage's`() {
        assertEquals(shareKey, directorySortingKey(trips, STORAGE_FILTER_SMB, storing(shareKey)))
    }

    @Test
    fun `a group without its own on a storage without its own goes by the shared one`() {
        assertEquals(DIRECTORY_SORT_ORDER, directorySortingKey(trips, STORAGE_FILTER_SMB, storing()))
    }

    // another group's sorting is not this group's, a subgroup's parent included
    @Test
    fun `another group's own sorting is not borrowed`() {
        assertEquals(DIRECTORY_SORT_ORDER, directorySortingKey(8L, STORAGE_FILTER_SMB, storing(tripsKey)))
    }

    // at the top of the list no group's sorting is read, even one that is stored
    @Test
    fun `at the top the groups' sortings are never read`() {
        assertEquals(shareKey, directorySortingKey(null, STORAGE_FILTER_SMB, storing(tripsKey, shareKey)))
        assertEquals(DIRECTORY_SORT_ORDER, directorySortingKey(null, STORAGE_FILTER_SMB, storing(tripsKey)))
    }
}
