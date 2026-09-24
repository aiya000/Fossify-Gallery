package org.fossify.gallery.helpers

// The order the storages stand in: in the storage menu, under a sideways swipe of the folder
// list, and along the chip row of the folder pickers, which all read the same list so that
// they cannot drift apart.
//
// "All storages" leads, so that the widest view sits at one end; then pCloud, this device, and
// the network share (#128). The order is fixed here for now; the second step of #128 is to make
// it the user's to change, which is why it is a list handed in rather than a `when`
object StorageOrder {
    val DEFAULT = listOf(STORAGE_FILTER_ALL, STORAGE_FILTER_PCLOUD, STORAGE_FILTER_LOCAL, STORAGE_FILTER_SMB)

    // The storages there are to choose between, in order. A remote storage is only there once
    // it is set up, and "all" only once there is more than one to be all of: with the device
    // alone there is nothing to switch to, and the list is the device by itself
    fun of(hasPCloud: Boolean, hasSmb: Boolean, order: List<Int> = DEFAULT): List<Int> {
        val present = order.filter {
            when (it) {
                STORAGE_FILTER_PCLOUD -> hasPCloud
                STORAGE_FILTER_SMB -> hasSmb
                STORAGE_FILTER_ALL -> hasPCloud || hasSmb
                else -> true
            }
        }

        return present
    }
}
