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
            StorageOrder.of(hasPCloud = true, shares = listOf(STORAGE_FILTER_SMB))
        )
    }

    // a storage that is not set up closes up, and the rest keep their places
    @Test
    fun `a storage that is not set up is left out, the order of the rest unchanged`() {
        assertEquals(listOf(STORAGE_FILTER_ALL, STORAGE_FILTER_LOCAL, STORAGE_FILTER_SMB), StorageOrder.of(hasPCloud = false, shares = listOf(STORAGE_FILTER_SMB)))
        assertEquals(listOf(STORAGE_FILTER_ALL, STORAGE_FILTER_PCLOUD, STORAGE_FILTER_LOCAL), StorageOrder.of(hasPCloud = true, shares = emptyList()))
    }

    // with the device alone there is nothing to switch to, so "all" would be the device twice
    @Test
    fun `the device alone is the device alone`() {
        assertEquals(listOf(STORAGE_FILTER_LOCAL), StorageOrder.of(hasPCloud = false, shares = emptyList()))
    }

    // the order chosen in the settings is whatever list is handed in
    @Test
    fun `another order is honoured as given`() {
        val theirs = listOf(STORAGE_FILTER_LOCAL, STORAGE_FILTER_SMB, STORAGE_FILTER_PCLOUD, STORAGE_FILTER_ALL)
        assertEquals(theirs, StorageOrder.of(hasPCloud = true, shares = listOf(STORAGE_FILTER_SMB), order = theirs))
    }

    // a storage not set up closes up in a chosen order too, and "all" keeps the place it was given
    @Test
    fun `a chosen order closes up around a storage that is not set up`() {
        val theirs = listOf(STORAGE_FILTER_LOCAL, STORAGE_FILTER_SMB, STORAGE_FILTER_PCLOUD, STORAGE_FILTER_ALL)
        assertEquals(listOf(STORAGE_FILTER_LOCAL, STORAGE_FILTER_SMB, STORAGE_FILTER_ALL), StorageOrder.of(hasPCloud = false, shares = listOf(STORAGE_FILTER_SMB), order = theirs))
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

    // Every share is a storage of its own (#155). One added since the order was stored stands
    // right after the last share the order has, wherever the user dragged that one
    @Test
    fun `a share added later stands after the shares already in the order`() {
        val second = smbStorageFilterOf(2)
        assertEquals(
            listOf(STORAGE_FILTER_ALL, STORAGE_FILTER_PCLOUD, STORAGE_FILTER_LOCAL, STORAGE_FILTER_SMB, second),
            StorageOrder.parse(null, listOf(STORAGE_FILTER_SMB, second))
        )

        assertEquals(
            listOf(STORAGE_FILTER_SMB, second, STORAGE_FILTER_LOCAL, STORAGE_FILTER_ALL, STORAGE_FILTER_PCLOUD),
            StorageOrder.parse("4,1,3,2", listOf(STORAGE_FILTER_SMB, second))
        )
    }

    // a share dragged somewhere of its own keeps that place, and one no longer set up is dropped
    // from the order rather than kept as a storage nobody can pick
    @Test
    fun `a share keeps the place it was dragged to, and one that is gone is dropped`() {
        val second = smbStorageFilterOf(2)
        val third = smbStorageFilterOf(3)
        val theirs = listOf(second, STORAGE_FILTER_ALL, STORAGE_FILTER_PCLOUD, STORAGE_FILTER_LOCAL, STORAGE_FILTER_SMB)
        assertEquals(theirs, StorageOrder.parse(StorageOrder.serialize(theirs), listOf(STORAGE_FILTER_SMB, second)))
        assertEquals(StorageOrder.DEFAULT, StorageOrder.parse(StorageOrder.serialize(listOf(third) + StorageOrder.DEFAULT)))
    }

    // with the first share gone and another left, the menu offers the one that is there
    @Test
    fun `only the shares that are set up are offered`() {
        val second = smbStorageFilterOf(2)
        val order = StorageOrder.parse(null, listOf(second))
        assertEquals(
            listOf(STORAGE_FILTER_ALL, STORAGE_FILTER_LOCAL, second),
            StorageOrder.of(hasPCloud = false, shares = listOf(second), order = order)
        )
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
