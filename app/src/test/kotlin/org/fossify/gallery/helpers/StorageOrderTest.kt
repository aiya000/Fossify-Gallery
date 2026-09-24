package org.fossify.gallery.helpers

import org.junit.Assert.assertEquals
import org.junit.Test

// The order the storages stand in, in the menu, under a swipe and along the pickers' chips
// (#128). It is a preference of the maintainer's with nothing underneath it that breaks loudly
// when it is undone, which is why it is pinned here as well as driven on the emulator
class StorageOrderTest {
    @Test
    fun `with everything set up, all storages leads, then pCloud, this device, the share`() {
        assertEquals(
            listOf(STORAGE_FILTER_ALL, STORAGE_FILTER_PCLOUD, STORAGE_FILTER_LOCAL, STORAGE_FILTER_SMB),
            StorageOrder.of(hasPCloud = true, hasSmb = true)
        )
    }

    // a storage that is not set up closes up, and the rest keep their places
    @Test
    fun `a storage that is not set up is left out, the order of the rest unchanged`() {
        assertEquals(listOf(STORAGE_FILTER_ALL, STORAGE_FILTER_LOCAL, STORAGE_FILTER_SMB), StorageOrder.of(hasPCloud = false, hasSmb = true))
        assertEquals(listOf(STORAGE_FILTER_ALL, STORAGE_FILTER_PCLOUD, STORAGE_FILTER_LOCAL), StorageOrder.of(hasPCloud = true, hasSmb = false))
    }

    // with the device alone there is nothing to switch to, so "all" would be the device twice
    @Test
    fun `the device alone is the device alone`() {
        assertEquals(listOf(STORAGE_FILTER_LOCAL), StorageOrder.of(hasPCloud = false, hasSmb = false))
    }

    // the seam the second step of #128 will use: the order is whatever list is handed in
    @Test
    fun `another order is honoured as given`() {
        val theirs = listOf(STORAGE_FILTER_LOCAL, STORAGE_FILTER_SMB, STORAGE_FILTER_PCLOUD, STORAGE_FILTER_ALL)
        assertEquals(theirs, StorageOrder.of(hasPCloud = true, hasSmb = true, order = theirs))
    }
}
