package org.fossify.gallery.extensions

import android.os.Environment
import org.fossify.commons.extensions.isExternalStorageManager
import org.fossify.commons.helpers.NOMEDIA
import org.fossify.commons.helpers.isRPlus
import org.fossify.gallery.helpers.PCLOUD_PATH_SCHEME
import org.fossify.gallery.helpers.PCLOUD_RECYCLE_BIN
import org.fossify.gallery.helpers.SMB_PATH_SCHEME
import org.fossify.gallery.helpers.SMB_RECYCLE_BIN
import java.io.File
import java.io.IOException
import java.util.Locale

// pCloud media carries a pseudo path instead of a filesystem one, see PCLOUD_PATH_PREFIX
fun String.isPCloudPath() = startsWith(PCLOUD_PATH_SCHEME)

// "pcloud:/Camera/IMG_0001.jpg" -> "/Camera/IMG_0001.jpg", the path the pCloud API expects.
// The root "pcloud:" becomes "/"
fun String.toPCloudRemotePath() = if (isPCloudPath()) "/${removePrefix(PCLOUD_PATH_SCHEME).trimStart('/')}" else this

// the other way round: "/Camera/IMG_0001.jpg" -> "pcloud:/Camera/IMG_0001.jpg", "/" -> "pcloud:"
fun String.toPCloudPseudoPath() = "$PCLOUD_PATH_SCHEME${trimEnd('/')}"

// a medium in the app's recycle bin on pCloud, see PCLOUD_RECYCLE_BIN. The bin folder itself
// is not one of them
fun String.isPCloudRecycleBinPath() = startsWith("$PCLOUD_RECYCLE_BIN/")

// "pcloud:/Camera/IMG_0001.jpg" -> "pcloud:/.gallery-recycle-bin/Camera/IMG_0001.jpg"
fun String.toPCloudRecycleBinPath() = "$PCLOUD_RECYCLE_BIN${removePrefix(PCLOUD_PATH_SCHEME)}"

// and back: the path the medium had before it went into the bin
fun String.fromPCloudRecycleBinPath() = "$PCLOUD_PATH_SCHEME${removePrefix(PCLOUD_RECYCLE_BIN)}"

// SMB media carries a pseudo path too, see SMB_PATH_PREFIX
fun String.isSmbPath() = startsWith(SMB_PATH_SCHEME)

// a medium in the app's recycle bin on the share, see SMB_RECYCLE_BIN. The bin folder itself
// is not one of them
fun String.isSmbRecycleBinPath() = startsWith("$SMB_RECYCLE_BIN/")

// "smb:/Trips/Osaka/IMG_0001.jpg" -> "smb:/.gallery-recycle-bin/Trips/Osaka/IMG_0001.jpg"
fun String.toSmbRecycleBinPath() = "$SMB_RECYCLE_BIN${removePrefix(SMB_PATH_SCHEME)}"

// and back: the path the medium had before it went into the bin
fun String.fromSmbRecycleBinPath() = "$SMB_PATH_SCHEME${removePrefix(SMB_RECYCLE_BIN)}"

// "smb:/photos/2026/IMG_0001.jpg" -> "photos/2026/IMG_0001.jpg", the path inside the share as
// smbj wants it, without a leading separator. The root "smb:" becomes ""
fun String.toSmbRemotePath() = if (isSmbPath()) removePrefix(SMB_PATH_SCHEME).trimStart('/') else this

// the other way round, "" -> "smb:"
fun String.toSmbPseudoPath() = "$SMB_PATH_SCHEME${if (isEmpty()) "" else "/${trim('/')}"}"

// a medium that is not on this device: there is no file behind it until one is fetched. The
// menu gating and the viewer ask this rather than naming one storage
fun String.isRemotePath() = isPCloudPath() || isSmbPath()

fun String.isThisOrParentIncluded(includedPaths: MutableSet<String>) =
    includedPaths.any { equals(it, true) } || includedPaths.any { "$this/".startsWith("$it/", true) }

fun String.isThisOrParentExcluded(excludedPaths: MutableSet<String>) =
    excludedPaths.any { equals(it, true) } || excludedPaths.any { "$this/".startsWith("$it/", true) }

// cache which folders contain .nomedia files to avoid checking them over and over again
fun String.shouldFolderBeVisible(
    excludedPaths: MutableSet<String>, includedPaths: MutableSet<String>, showHidden: Boolean,
    folderNoMediaStatuses: HashMap<String, Boolean>, callback: (path: String, hasNoMedia: Boolean) -> Unit
): Boolean {
    if (isEmpty()) {
        return false
    }

    val file = File(this)
    val filename = file.name
    if (filename.startsWith("img_", true) && file.isDirectory) {
        val files = file.list()
        if (files != null) {
            if (files.any { it.contains("burst", true) }) {
                return false
            }
        }
    }

    if (!showHidden && filename.startsWith('.')) {
        return false
    } else if (includedPaths.contains(this)) {
        return true
    }

    val containsNoMedia = if (showHidden) {
        false
    } else {
        folderNoMediaStatuses.getOrElse("$this/$NOMEDIA") { false } || ((!isRPlus() || isExternalStorageManager()) && File(this, NOMEDIA).exists())
    }

    return if (!showHidden && containsNoMedia) {
        false
    } else if (excludedPaths.contains(this)) {
        false
    } else if (isThisOrParentIncluded(includedPaths)) {
        true
    } else if (isThisOrParentExcluded(excludedPaths)) {
        false
    } else if (!showHidden) {
        var containsNoMediaOrDot = containsNoMedia || contains("/.")
        if (!containsNoMediaOrDot) {
            var curPath = this
            for (i in 0 until count { it == '/' } - 1) {
                curPath = curPath.substringBeforeLast('/')
                val pathToCheck = "$curPath/$NOMEDIA"
                if (folderNoMediaStatuses.contains(pathToCheck)) {
                    if (folderNoMediaStatuses[pathToCheck] == true) {
                        containsNoMediaOrDot = true
                        break
                    }
                } else {
                    val noMediaExists = folderNoMediaStatuses.getOrElse(pathToCheck, { false }) || File(pathToCheck).exists()
                    callback(pathToCheck, noMediaExists)
                    if (noMediaExists) {
                        containsNoMediaOrDot = true
                        break
                    }
                }
            }
        }
        !containsNoMediaOrDot
    } else {
        true
    }
}

// recognize /sdcard/DCIM as the same folder as /storage/emulated/0/DCIM
fun String.getDistinctPath(): String {
    return try {
        File(this).canonicalPath.lowercase(Locale.getDefault())
    } catch (e: IOException) {
        lowercase(Locale.getDefault())
    }
}

fun String.isDownloadsFolder() = equals(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).toString(), true)

fun String.isThisOrParentFolderHidden(): Boolean {
    var curFile = File(this)
    while (true) {
        if (curFile.isHidden) {
            return true
        }

        curFile = curFile.parentFile ?: break
        if (curFile.absolutePath == "/") {
            break
        }
    }
    return false
}
