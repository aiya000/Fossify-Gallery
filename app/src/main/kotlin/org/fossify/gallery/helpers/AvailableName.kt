package org.fossify.gallery.helpers

// The path a copy gets in a folder that may already hold that name: the name itself, or the
// lowest free "name (n)". [isTaken] is asked about the whole path, so the caller decides what
// "already there" means -- a file on the device, a row of a cache, a listing of a share.
//
// It lives here rather than inside one transfer because a second storage copying into the same
// device folder has to number its files the way the first one does; the pCloud transfer had
// this to itself, which is the shape every remote-only helper in this app has gone wrong in
fun availableName(folder: String, name: String, isTaken: (path: String) -> Boolean): String {
    val first = "$folder/$name"
    if (!isTaken(first)) {
        return first
    }

    // "IMG_0001.jpg" -> "IMG_0001" + "jpg", "README" -> "README" + nothing, so that the number
    // lands before the extension and the copy is still opened by the same app
    val extension = name.substringAfterLast('.', "")
    val base = if (extension.isEmpty()) name else name.removeSuffix(".$extension")
    var index = 1
    while (true) {
        val candidate = if (extension.isEmpty()) "$folder/$base ($index)" else "$folder/$base ($index).$extension"
        if (!isTaken(candidate)) {
            return candidate
        }

        index++
    }
}
