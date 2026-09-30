package org.fossify.gallery.helpers

// One share the gallery connects to (#155). There can be several, each a storage of its own,
// and the id is what ties a pseudo path, a row and a storage filter to the one it belongs to.
//
// The first connection is id 0 and has the paths it had before there could be a second one:
// "smb:/Trips/IMG_0001.jpg". Every other connection carries its id after the scheme,
// "smb:2/Trips/IMG_0001.jpg". An id is never handed out twice, except 0, which is taken again
// by the next connection made once the first is gone -- the rows of a connection are dropped
// with it, so nothing is left behind to be mistaken for the new one's.
//
// This holds no Context: everything about the shape of a path is here, where a JVM test can
// reach it, and Config is what reads and writes the connections themselves
data class SmbConnection(
    val id: Int,
    // what its storage is called; empty for one the user did not name, see Context.smbLabel()
    val name: String = "",
    val host: String,
    val port: Int = SMB_DEFAULT_PORT,
    val share: String,
    // the folder inside the share its root stands for, without surrounding separators
    val rootPath: String = "",
    val user: String = "",
    val password: String = "",
    val domain: String = "",
) {
    val isConfigured: Boolean
        get() = host.isNotEmpty() && share.isNotEmpty()

    // the pseudo path of its root folder, "smb:" or "smb:2"
    val root: String
        get() = smbRootOf(id)

    // what every path under the root starts with, "smb:/" or "smb:2/". A query for one
    // connection's rows goes by this: the bare scheme would match every connection's
    val rowPrefix: String
        get() = "$root/"

    // the app's recycle bin on this share, a folder in its root
    val recycleBin: String
        get() = "$rowPrefix$RECYCLE_BIN_FOLDER_NAME"

    val storageFilter: Int
        get() = smbStorageFilterOf(id)

    // whether the path is this connection's: its root or anything under it
    fun holds(path: String) = smbConnectionIdOf(path) == id

    // "\\host\share\root", how a connection is shown when it has no name of its own
    val address: String
        get() = "\\\\$host\\$share${if (rootPath.isEmpty()) "" else "\\${rootPath.replace('/', '\\')}"}"
}

// "smb:" for the first connection, "smb:<id>" for any other
fun smbRootOf(id: Int) = if (id == 0) SMB_PATH_SCHEME else "$SMB_PATH_SCHEME$id"

// Which connection a pseudo path is of: "smb:" and "smb:/a" are 0, "smb:2" and "smb:2/a" are 2.
// Null for a path that is not an SMB path at all, and for one whose id is not written the way
// smbRootOf() writes it -- "smb:02/a" or "smb:0/a" would otherwise name a connection twice over
fun smbConnectionIdOf(path: String): Int? {
    if (!path.startsWith(SMB_PATH_SCHEME)) {
        return null
    }

    val rest = path.substring(SMB_PATH_SCHEME.length)
    val digits = rest.takeWhile { it.isDigit() }
    if (rest.length > digits.length && rest[digits.length] != '/') {
        return null
    }

    return when {
        digits.isEmpty() -> 0
        digits.startsWith('0') -> null
        else -> digits.toIntOrNull()
    }
}

// the root of the connection a pseudo path is of, "smb:" for anything that is not one
fun smbRootOfPath(path: String) = smbRootOf(smbConnectionIdOf(path) ?: 0)

// The storage filter a connection's folders are shown under: the first keeps
// STORAGE_FILTER_SMB, see STORAGE_FILTER_SMB_CONNECTION_BASE
fun smbStorageFilterOf(id: Int) = if (id == 0) STORAGE_FILTER_SMB else STORAGE_FILTER_SMB_CONNECTION_BASE + id

// and back: the connection a storage filter shows, null for a filter that is not one
fun smbConnectionIdOfFilter(filter: Int): Int? = when {
    filter == STORAGE_FILTER_SMB -> 0
    filter > STORAGE_FILTER_SMB_CONNECTION_BASE -> filter - STORAGE_FILTER_SMB_CONNECTION_BASE
    else -> null
}

// The preference key a connection's setting is kept under: the first connection's are the keys
// the single share had, every other one's have its id after them
fun smbKey(base: String, id: Int) = if (id == 0) base else "${base}_$id"
