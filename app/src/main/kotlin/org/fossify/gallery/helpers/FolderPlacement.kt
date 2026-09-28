package org.fossify.gallery.helpers

// What the destination picker's OK does with a folder of the folder list, at the top of the
// list or inside a group: the folder itself goes there, rather than its media being poured into
// a folder that was tapped. A group belongs to no storage, so the storage is the one the chips
// were showing -- the chips are put away inside a group, and what they showed last still stands
sealed interface FolderPlacement {
    // the folder changes group and nothing on any storage moves: a move that stays on the
    // storage the folder is on, which is what "Move to" always did with the OK
    data class Regroup(val groupId: Long?) : FolderPlacement

    // a new folder at [destination], in the root of the storage, with the media copied or moved
    // into it and the folder put into [groupId]
    data class Transfer(val destination: String, val groupId: Long?) : FolderPlacement

    // a pair of storages turned away on purpose, see FolderRefusal
    data class Refused(val reason: FolderRefusal) : FolderPlacement
}

// The pairs the picker's OK turns away rather than carry out. Both are written down in
// agents/storage/what-each-storage-does-differently-on-purpose.md
enum class FolderRefusal {
    // pCloud straight onto the share would have to be staged on the way (#28); the same rule as
    // MediaStorage.PCloud.canTransferTo(), which the picker asks when a folder is tapped
    PCLOUD_ONTO_SHARE,

    // the share has no copy within itself, a copy there would be a move
    COPY_WITHIN_SHARE
}

// [storageFilter] is what the chips show; with every storage shown there is none picked, and
// the folder stays on its own. [deviceRoot] is where a folder is made on the device.
// [isTaken] is asked about whole paths, the way availableName() asks: a copy never lands in a
// folder that is already there, where its media would be mixed into somebody else's
fun placeFolder(
    sourceFolder: String,
    isCopy: Boolean,
    storageFilter: Int,
    groupId: Long?,
    deviceRoot: String,
    isTaken: (path: String) -> Boolean
): FolderPlacement {
    val sourceStorage = storageFilterOf(sourceFolder)
    val storage = if (storageFilter == STORAGE_FILTER_ALL) sourceStorage else storageFilter
    if (!isCopy && storage == sourceStorage) {
        return FolderPlacement.Regroup(groupId)
    }

    if (sourceStorage == STORAGE_FILTER_PCLOUD && storage == STORAGE_FILTER_SMB) {
        return FolderPlacement.Refused(FolderRefusal.PCLOUD_ONTO_SHARE)
    }

    if (isCopy && sourceStorage == STORAGE_FILTER_SMB && storage == STORAGE_FILTER_SMB) {
        return FolderPlacement.Refused(FolderRefusal.COPY_WITHIN_SHARE)
    }

    val root = when (storage) {
        STORAGE_FILTER_PCLOUD -> PCLOUD_PATH_SCHEME
        STORAGE_FILTER_SMB -> SMB_PATH_SCHEME
        else -> deviceRoot.trimEnd('/')
    }

    // not availableName(): a folder has no extension, whatever dots its name holds
    val name = sourceFolder.trimEnd('/').substringAfterLast('/')
    var destination = "$root/$name"
    var index = 1
    while (isTaken(destination)) {
        destination = "$root/$name ($index)"
        index++
    }

    return FolderPlacement.Transfer(destination, groupId)
}

private fun storageFilterOf(path: String) = when {
    path.startsWith(PCLOUD_PATH_SCHEME) -> STORAGE_FILTER_PCLOUD
    path.startsWith(SMB_PATH_SCHEME) -> STORAGE_FILTER_SMB
    else -> STORAGE_FILTER_LOCAL
}
