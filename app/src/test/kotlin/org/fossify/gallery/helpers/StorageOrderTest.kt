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

    // the order chosen in the settings is whatever list is handed in
    @Test
    fun `another order is honoured as given`() {
        val theirs = listOf(STORAGE_FILTER_LOCAL, STORAGE_FILTER_SMB, STORAGE_FILTER_PCLOUD, STORAGE_FILTER_ALL)
        assertEquals(theirs, StorageOrder.of(hasPCloud = true, hasSmb = true, order = theirs))
    }

    // a storage not set up closes up in a chosen order too, and "all" keeps the place it was given
    @Test
    fun `a chosen order closes up around a storage that is not set up`() {
        val theirs = listOf(STORAGE_FILTER_LOCAL, STORAGE_FILTER_SMB, STORAGE_FILTER_PCLOUD, STORAGE_FILTER_ALL)
        assertEquals(listOf(STORAGE_FILTER_LOCAL, STORAGE_FILTER_SMB, STORAGE_FILTER_ALL), StorageOrder.of(hasPCloud = false, hasSmb = true, order = theirs))
    }

    @Test
    fun `an order goes out and comes back the same`() {
        val theirs = listOf(STORAGE_FILTER_SMB, STORAGE_FILTER_ALL, STORAGE_FILTER_LOCAL, STORAGE_FILTER_PCLOUD)
        assertEquals(theirs, StorageOrder.parse(StorageOrder.serialize(theirs)))
    }

    // nothing stored yet, or an export from before the order could be chosen
    @Test
    fun `no order stored is the default order`() {
        assertEquals(StorageOrder.DEFAULT, StorageOrder.parse(null))
        assertEquals(StorageOrder.DEFAULT, StorageOrder.parse(""))
    }

    // a stored order is only a preference: a broken one must never lose a storage from the menu
    @Test
    fun `a broken order still holds every storage once`() {
        assertEquals(
            listOf(STORAGE_FILTER_SMB, STORAGE_FILTER_LOCAL, STORAGE_FILTER_ALL, STORAGE_FILTER_PCLOUD),
            StorageOrder.parse("4, 99, x, 1, 4")
        )
    }
}
