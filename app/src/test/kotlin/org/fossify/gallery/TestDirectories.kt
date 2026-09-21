package org.fossify.gallery

import org.fossify.gallery.models.Directory
import org.fossify.gallery.models.FolderGroup

// A folder with nothing to it but its path. Everything these tests ask about is the structure --
// which folder sits in which group, and in what order -- so the counts, dates and thumbnails a
// real Directory carries are left at zero
fun directoryAt(path: String) = Directory(
    id = null,
    path = path,
    tmb = "",
    name = path.substringAfterLast('/'),
    mediaCnt = 0,
    modified = 0L,
    taken = 0L,
    size = 0L,
    location = 0,
    types = 0,
    sortValue = ""
)

// a group as the folder list draws it: a row with the group's pseudo path in place of a real one
fun groupDirectoryOf(id: Long) = directoryAt(FolderGroup(id, "group $id").getPseudoPath())
