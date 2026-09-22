package org.fossify.gallery.helpers

import android.content.Context
import org.fossify.gallery.extensions.isPCloudPath
import org.fossify.gallery.extensions.isRemotePath
import org.fossify.gallery.extensions.isSmbPath

// The three places a medium can be: this device, pCloud, and the network share. The set is
// closed, so a `when` over it is exhaustive, and a fourth storage would fail to compile rather
// than fail on screen.
//
// What a storage can and cannot do is written here, once, instead of at every menu that has to
// know -- a menu that was not told is exactly how #71 and #72 happened. A difference that is
// data ("is a name renamed one at a time", "is there a file to hand to another app") is a
// property; a difference that is steps is an override. The operations themselves still live
// where they always have, and come over one family at a time (#106).
//
// It holds a Context because the operations will need one (the database, the notifications,
// the services), so it is a holder of behaviour rather than a value: two of them are not
// equal, and `holds()` is how a selection is checked against one.
sealed class MediaStorage(protected val context: Context) {
    // whether this path is on this storage
    abstract fun holds(path: String): Boolean

    // no file on the device stands behind a medium: it is fetched into one before anything
    // that needs a file, and what was changed is written back over the original. The menus
    // that offer that writing back are the ones opened from a screen of the gallery
    abstract val isRemote: Boolean

    // --- a medium of it, in the grid's selection and in the fullscreen view

    // a rename goes through the remote API one medium at a time
    abstract val canRenameSeveral: Boolean

    // the date taken is fixed from the file's EXIF, which needs the file
    abstract val canFixDateTaken: Boolean

    // handed to another app, set as wallpaper, shared, rotated and resized as a file, fetched
    // first where there is none. The share is not fetched for these yet, see #71
    abstract val canOpenWith: Boolean
    abstract val canSetAs: Boolean
    abstract val canShare: Boolean
    abstract val canRotate: Boolean
    abstract val canResize: Boolean

    // resizing a selection writes each one back over itself, which pCloud does not have yet
    abstract val canResizeSeveral: Boolean

    // a shortcut pins a path of the device's own file system
    abstract val canCreateShortcut: Boolean

    // a medium is hidden by renaming its file with a leading dot, which is a write into the
    // device's file system. Whether the app may write there at all is asked separately. A
    // folder of a remote storage is hidden by a setting instead, see DirectoryAdapter
    abstract val canHide: Boolean

    // a video is read as it plays rather than fetched whole first, so it can be had in hand
    // beforehand -- that is what the "download videos" actions are for
    abstract val streamsVideos: Boolean

    // --- a folder of it, in the folder list's selection

    // a folder is renamed through the remote API one at a time, the same as a medium
    abstract val canRenameSeveralFolders: Boolean

    // the properties dialog and the exclusion list are about a directory on the device
    abstract val canShowFolderProperties: Boolean
    abstract val canExcludeFolders: Boolean

    class Device(context: Context) : MediaStorage(context) {
        override fun holds(path: String) = !path.isRemotePath()
        override val isRemote = false

        override val canRenameSeveral = true
        override val canFixDateTaken = true
        override val canOpenWith = true
        override val canSetAs = true
        override val canShare = true
        override val canRotate = true
        override val canResize = true
        override val canResizeSeveral = true
        override val canCreateShortcut = true
        override val canHide = true
        override val streamsVideos = false

        override val canRenameSeveralFolders = true
        override val canShowFolderProperties = true
        override val canExcludeFolders = true
    }

    class PCloud(context: Context) : MediaStorage(context) {
        override fun holds(path: String) = path.isPCloudPath()
        override val isRemote = true

        override val canRenameSeveral = false
        override val canFixDateTaken = false
        override val canOpenWith = true
        override val canSetAs = true
        override val canShare = true
        override val canRotate = true
        override val canResize = true
        override val canResizeSeveral = false
        override val canCreateShortcut = false
        override val canHide = false
        override val streamsVideos = false

        override val canRenameSeveralFolders = false
        override val canShowFolderProperties = false
        override val canExcludeFolders = false
    }

    class Smb(context: Context) : MediaStorage(context) {
        override fun holds(path: String) = path.isSmbPath()
        override val isRemote = true

        override val canRenameSeveral = false
        override val canFixDateTaken = false
        override val canOpenWith = false
        override val canSetAs = false
        override val canShare = false
        override val canRotate = false
        override val canResize = false
        override val canResizeSeveral = false
        override val canCreateShortcut = false
        override val canHide = false
        override val streamsVideos = true

        override val canRenameSeveralFolders = false
        override val canShowFolderProperties = false
        override val canExcludeFolders = false
    }

    companion object {
        fun of(context: Context, path: String): MediaStorage = when {
            path.isPCloudPath() -> PCloud(context)
            path.isSmbPath() -> Smb(context)
            else -> Device(context)
        }

        // the one storage every path of a selection is on, or null when the selection is empty
        // or mixes storages -- a mixed selection is offered nothing that goes through a storage
        fun ofAll(context: Context, paths: Collection<String>): MediaStorage? {
            val storage = of(context, paths.firstOrNull() ?: return null)
            return storage.takeIf { paths.all(it::holds) }
        }
    }
}
