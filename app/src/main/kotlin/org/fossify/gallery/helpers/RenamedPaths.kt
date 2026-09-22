package org.fossify.gallery.helpers

// What a rename does to the paths kept around a file or a folder, with nothing of Android and
// nothing of any one storage in it, so both halves can be asked about on their own.
//
// A rename in this app never moves anything: the new name goes where the old one was. That is all
// either of these does, and both have an edge that is easy to get wrong by hand -- a name sitting
// in the root with no folder before it, and a folder whose name merely begins with the renamed one

// "<root>\2026\IMG_0001.jpg" and "IMG_0002.jpg" -> "<root>\2026\IMG_0002.jpg", the path a rename
// on the share is sent as. The last segment is the only one that changes; a name in the share's
// own root has no separator before it, and the new name is then the whole path
fun renamedSiblingPath(path: String, newName: String, separator: Char): String {
    val lastSeparator = path.lastIndexOf(separator)
    return if (lastSeparator < 0) newName else "${path.substring(0, lastSeparator)}$separator$newName"
}

// The paths of a set that named a renamed folder, or something under it, under its new name --
// and the set as it stood when none of them did.
//
// The hidden folders of pCloud and of the share are kept as paths in the settings rather than as
// a file on the storage, so a rename has to carry them; both storages ask this. A folder whose
// name only begins with the renamed one is not under it -- "smb:/Trips 2026" stays where it is
// when "smb:/Trips" is renamed -- which is what the slash in the prefix is there for
fun Set<String>.withFolderRenamed(oldPath: String, newPath: String): Set<String> {
    val moved = filter { it == oldPath || it.startsWith("$oldPath/") }
    if (moved.isEmpty()) {
        return this
    }

    val updated = HashSet<String>(this)
    updated.removeAll(moved.toSet())
    moved.mapTo(updated) { newPath + it.substring(oldPath.length) }
    return updated
}
