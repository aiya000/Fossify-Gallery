package org.fossify.gallery.helpers

// Which stored sorting the folder list on screen reads and writes. An opened group with a sorting
// of its own comes first (#137, "apply to this group only"), then the storage's own ("apply to
// this storage only"), then the one every list without its own falls back to. A group inside a
// group does not inherit its parent's: each group's sorting is its own
fun directorySortingKey(groupId: Long?, storageFilter: Int, isStored: (String) -> Boolean): String {
    if (groupId != null) {
        val groupKey = groupDirectorySortingKey(groupId)
        if (isStored(groupKey)) {
            return groupKey
        }
    }

    val storageKey = storageDirectorySortingKey(storageFilter)
    return if (isStored(storageKey)) storageKey else DIRECTORY_SORT_ORDER
}

fun groupDirectorySortingKey(groupId: Long) = "$SORT_FOLDERS_GROUP_PREFIX$groupId"

fun storageDirectorySortingKey(storageFilter: Int) = "$SORT_FOLDERS_STORAGE_PREFIX$storageFilter"
