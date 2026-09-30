package org.fossify.gallery.helpers

// The order the storages stand in: in the storage menu, under a sideways swipe of the folder
// list, and along the chip row of the folder pickers, which all read the same list so that
// they cannot drift apart.
//
// By default "All storages" leads, so that the widest view sits at one end; then pCloud, this
// device, and the network share (#128). The user can drag them into another order in the
// settings (Config.storageOrder), "All storages" included.
//
// Every share is a storage of its own (#155), each with a filter value of its own. The first
// one's is STORAGE_FILTER_SMB, always in DEFAULT; the others are the shares set up now, handed
// in as [shares], and they stand after it until they are dragged somewhere else
object StorageOrder {
    val DEFAULT = listOf(STORAGE_FILTER_ALL, STORAGE_FILTER_PCLOUD, STORAGE_FILTER_LOCAL, STORAGE_FILTER_SMB)

    // An order as it is stored and exported: the STORAGE_FILTER_* values, comma-joined
    fun serialize(order: List<Int>): String = order.joinToString(",")

    // An order read back is always every storage there is, each once: the four of DEFAULT and
    // the shares in [shares]. What is not a storage is dropped, and a storage missing from it is
    // put back in its default place among the missing -- a share next to the last share the
    // order has, anything else at the end. A stored order is a preference, and a broken one
    // must not lose a storage from the menu; a share added since the order was stored is one
    // it could not have had
    fun parse(stored: String?, shares: Collection<Int> = emptyList()): List<Int> {
        val known = DEFAULT + shares.filter { it !in DEFAULT }
        val order = stored.orEmpty().split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it in known }.distinct().toMutableList()
        known.filter { it !in order }.forEach { missing ->
            val lastShare = order.indexOfLast { smbConnectionIdOfFilter(it) != null }
            if (smbConnectionIdOfFilter(missing) != null && lastShare >= 0) {
                order.add(lastShare + 1, missing)
            } else {
                order.add(missing)
            }
        }

        return order
    }

    // The storages there are to choose between, in order. A remote storage is only there once
    // it is set up -- pCloud when [hasPCloud], a share when its filter is in [shares] -- and
    // "all" only once there is more than one to be all of: with the device alone there is
    // nothing to switch to, and the list is the device by itself
    fun of(hasPCloud: Boolean, shares: Collection<Int>, order: List<Int> = parse(null, shares)): List<Int> {
        val present = order.filter {
            when {
                it == STORAGE_FILTER_PCLOUD -> hasPCloud
                smbConnectionIdOfFilter(it) != null -> it in shares
                it == STORAGE_FILTER_ALL -> hasPCloud || shares.isNotEmpty()
                else -> true
            }
        }

        return present
    }
}
