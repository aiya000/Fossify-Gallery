package org.fossify.gallery.helpers

// The order the storages stand in: in the storage menu, under a sideways swipe of the folder
// list, and along the chip row of the folder pickers, which all read the same list so that
// they cannot drift apart.
//
// By default "All storages" leads, so that the widest view sits at one end; then pCloud, this
// device, and the network share (#128). The user can drag them into another order in the
// settings (Config.storageOrder), "All storages" included
object StorageOrder {
    val DEFAULT = listOf(STORAGE_FILTER_ALL, STORAGE_FILTER_PCLOUD, STORAGE_FILTER_LOCAL, STORAGE_FILTER_SMB)

    // An order as it is stored and exported: the STORAGE_FILTER_* values, comma-joined
    fun serialize(order: List<Int>): String = order.joinToString(",")

    // An order read back is always all four storages, each once. What is not a storage is dropped,
    // and a storage missing from it goes at the end, in its default place among the missing --
    // a stored order is a preference, and a broken one must not lose a storage from the menu
    fun parse(stored: String?): List<Int> {
        val given = stored.orEmpty().split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it in DEFAULT }.distinct()
        return given + DEFAULT.filter { it !in given }
    }

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
