package org.fossify.gallery.helpers

import org.fossify.gallery.R

// The pairs of storages a copy or a move is turned away from rather than carried out, for a
// medium and for a folder alike. Both are written down in
// agents/storage/what-each-storage-does-differently-on-purpose.md, and every pair is in
// MediaTransferTableTest's table and FolderPlacementTableTest's
enum class TransferRefusal {
    // pCloud straight onto the share would have to be staged on the way (#28)
    PCLOUD_ONTO_SHARE;

    // what the picker says, or the toast when the refusal comes later
    val messageId: Int
        get() = when (this) {
            PCLOUD_ONTO_SHARE -> R.string.smb_no_remote_copy_to_share
        }
}

// [sourceStorage] and [destinationStorage] are STORAGE_FILTER_* values, see storageFilterOf();
// each share has one of its own. A copy within a share and anything from one share to another
// are carried out by reading the bytes off and writing them back (#154)
@Suppress("UNUSED_PARAMETER")
fun transferRefusal(sourceStorage: Int, destinationStorage: Int, isCopy: Boolean): TransferRefusal? {
    val destinationShare = smbConnectionIdOfFilter(destinationStorage)
    return when {
        sourceStorage == STORAGE_FILTER_PCLOUD && destinationShare != null -> TransferRefusal.PCLOUD_ONTO_SHARE
        else -> null
    }
}

fun storageFilterOf(path: String): Int {
    val smbConnectionId = smbConnectionIdOf(path)
    return when {
        path.startsWith(PCLOUD_PATH_SCHEME) -> STORAGE_FILTER_PCLOUD
        smbConnectionId != null -> smbStorageFilterOf(smbConnectionId)
        // an SMB path whose id is not written the way smbRootOf() writes it is no connection's;
        // it is still not the device's, and the first share is the one that would own it
        path.startsWith(SMB_PATH_SCHEME) -> STORAGE_FILTER_SMB
        else -> STORAGE_FILTER_LOCAL
    }
}
