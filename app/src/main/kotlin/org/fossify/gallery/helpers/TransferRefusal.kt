package org.fossify.gallery.helpers

import org.fossify.gallery.R

// The pairs of storages a copy or a move is turned away from rather than carried out, for a
// medium and for a folder alike. Both are written down in
// agents/storage/what-each-storage-does-differently-on-purpose.md, and every pair is in
// MediaTransferTableTest's table and FolderPlacementTableTest's
enum class TransferRefusal {
    // pCloud straight onto the share would have to be staged on the way (#28)
    PCLOUD_ONTO_SHARE,

    // the share has no copy within itself: SmbTransferService would carry it out as a move, and
    // the original would be gone from where the user left it (#150)
    COPY_WITHIN_SHARE,

    // one share to another, a copy or a move alike: the rename a move within a share is cannot
    // reach another share, and carrying the bytes across is the streaming copy #154 is about (#155)
    BETWEEN_SHARES;

    // what the picker says, or the toast when the refusal comes later
    val messageId: Int
        get() = when (this) {
            PCLOUD_ONTO_SHARE -> R.string.smb_no_remote_copy_to_share
            COPY_WITHIN_SHARE -> R.string.smb_no_copy_within_share
            BETWEEN_SHARES -> R.string.smb_no_transfer_between_shares
        }
}

// [sourceStorage] and [destinationStorage] are STORAGE_FILTER_* values, see storageFilterOf();
// each share has one of its own
fun transferRefusal(sourceStorage: Int, destinationStorage: Int, isCopy: Boolean): TransferRefusal? {
    val sourceShare = smbConnectionIdOfFilter(sourceStorage)
    val destinationShare = smbConnectionIdOfFilter(destinationStorage)
    return when {
        sourceStorage == STORAGE_FILTER_PCLOUD && destinationShare != null -> TransferRefusal.PCLOUD_ONTO_SHARE
        sourceShare != null && destinationShare != null && sourceShare != destinationShare -> TransferRefusal.BETWEEN_SHARES
        isCopy && sourceShare != null && sourceShare == destinationShare -> TransferRefusal.COPY_WITHIN_SHARE
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
