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
    COPY_WITHIN_SHARE;

    // what the picker says, or the toast when the refusal comes later
    val messageId: Int
        get() = when (this) {
            PCLOUD_ONTO_SHARE -> R.string.smb_no_remote_copy_to_share
            COPY_WITHIN_SHARE -> R.string.smb_no_copy_within_share
        }
}

// [sourceStorage] and [destinationStorage] are STORAGE_FILTER_* values, see storageFilterOf()
fun transferRefusal(sourceStorage: Int, destinationStorage: Int, isCopy: Boolean): TransferRefusal? = when {
    sourceStorage == STORAGE_FILTER_PCLOUD && destinationStorage == STORAGE_FILTER_SMB -> TransferRefusal.PCLOUD_ONTO_SHARE
    isCopy && sourceStorage == STORAGE_FILTER_SMB && destinationStorage == STORAGE_FILTER_SMB -> TransferRefusal.COPY_WITHIN_SHARE
    else -> null
}

fun storageFilterOf(path: String) = when {
    path.startsWith(PCLOUD_PATH_SCHEME) -> STORAGE_FILTER_PCLOUD
    path.startsWith(SMB_PATH_SCHEME) -> STORAGE_FILTER_SMB
    else -> STORAGE_FILTER_LOCAL
}
