package org.fossify.gallery.helpers

import android.content.Context
import android.widget.Toast
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.dialogs.RenameDialog
import org.fossify.commons.dialogs.RenameItemDialog
import org.fossify.commons.dialogs.RenameItemsDialog
import org.fossify.commons.extensions.getFilenameFromPath
import org.fossify.commons.extensions.getParentPath
import org.fossify.commons.extensions.handleLockedFolderOpening
import org.fossify.commons.extensions.internalStoragePath
import org.fossify.commons.extensions.isAStorageRootFolder
import org.fossify.commons.extensions.isExternalStorageManager
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isRPlus
import org.fossify.gallery.dialogs.RemoteNameDialog
import org.fossify.gallery.extensions.config
import org.fossify.gallery.extensions.directoryDB
import org.fossify.gallery.extensions.handleMediaManagementPrompt
import org.fossify.gallery.extensions.isPCloudPath
import org.fossify.gallery.extensions.isRemotePath
import org.fossify.gallery.extensions.isSmbPath
import org.fossify.gallery.extensions.updateDBMediaPath
import org.fossify.gallery.extensions.writeToPCloud
import org.fossify.gallery.extensions.writeToShare

// The three places a medium can be: this device, pCloud, and the network share. The set is
// closed, so a `when` over it is exhaustive, and a fourth storage would fail to compile rather
// than fail on screen.
//
// What a storage can and cannot do is written here, once, instead of at every menu that has to
// know -- a menu that was not told is exactly how #71 and #72 happened. A difference that is
// data ("is a name renamed one at a time", "is there a file to hand to another app") is a
// property; a difference that is steps is an override. The operations come over one family at
// a time (#106); renaming is here, the rest still lives where it always has.
//
// It holds a Context because the operations need one (the database, the notifications, the
// services), so it is a holder of behaviour rather than a value: two of them are not equal,
// and `holds()` is how a selection is checked against one. An operation that opens a dialog
// takes the activity it opens on besides, since a Context cannot show one.
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

    // --- renaming: the name is asked for on screen, then given to the storage, and the rows
    // that stand for the medium or the folder follow it before anything is called back.
    //
    // [onDone] runs on the main thread with the path the thing has now, or with null when the
    // storage refused -- a name it already has, a folder the system will not let go of. Nothing
    // is called when the dialog is left without a name: nothing was asked, and the screen is as
    // it was. Several at once is offered only where canRenameSeveral / canRenameSeveralFolders
    // say so, and a storage that says no is never asked for it

    // one medium, given another name in the folder it is in
    abstract fun renameMedium(activity: BaseSimpleActivity, path: String, onDone: (newPath: String?) -> Unit)

    // several media of one folder at once, which is the device's alone. [onDone] runs once the
    // dialog is through with them, or the device refused; the dialog does not say which were
    // renamed, so the list is read again either way
    open fun renameSeveralMedia(activity: BaseSimpleActivity, paths: List<String>, onDone: () -> Unit) {
        throw UnsupportedOperationException("${javaClass.simpleName} renames one medium at a time, see canRenameSeveral")
    }

    // one folder, given another name where it is, with everything under it following
    abstract fun renameFolder(activity: BaseSimpleActivity, path: String, onDone: (newPath: String?) -> Unit)

    // several folders at once, the device's alone, the same as renameSeveralMedia()
    open fun renameSeveralFolders(activity: BaseSimpleActivity, paths: List<String>, onDone: () -> Unit) {
        throw UnsupportedOperationException("${javaClass.simpleName} renames one folder at a time, see canRenameSeveralFolders")
    }

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

        // The commons dialogs do the renaming themselves, file and MediaStore; what is left to
        // do here is the app's own rows. The prompt before them asks for the right to change
        // files on this device, which is nothing a remote storage needs
        override fun renameMedium(activity: BaseSimpleActivity, path: String, onDone: (newPath: String?) -> Unit) {
            activity.handleMediaManagementPrompt {
                if (isOnARootTheSystemKeeps(activity, path)) {
                    onDone(null)
                    return@handleMediaManagementPrompt
                }

                RenameItemDialog(activity, path) { newPath ->
                    ensureBackgroundThread {
                        context.updateDBMediaPath(path, newPath)
                        activity.runOnUiThread { onDone(newPath) }
                    }
                }
            }
        }

        override fun renameSeveralMedia(activity: BaseSimpleActivity, paths: List<String>, onDone: () -> Unit) {
            activity.handleMediaManagementPrompt {
                if (isOnARootTheSystemKeeps(activity, paths.first())) {
                    onDone()
                    return@handleMediaManagementPrompt
                }

                RenameDialog(activity, ArrayList(paths), true) {
                    onDone()
                }
            }
        }

        // Android 11 and up will not let a file in the root of an SD card or a USB stick be
        // renamed without the all-files permission, and says so rather than failing later
        private fun isOnARootTheSystemKeeps(activity: BaseSimpleActivity, path: String): Boolean {
            val isSDOrOtgRootFolder = context.isAStorageRootFolder(path.getParentPath()) && !path.startsWith(context.internalStoragePath)
            if (isRPlus() && isSDOrOtgRootFolder && !isExternalStorageManager()) {
                activity.toast(org.fossify.commons.R.string.rename_in_sd_card_system_restriction, Toast.LENGTH_LONG)
                return true
            }

            return false
        }

        override fun renameFolder(activity: BaseSimpleActivity, path: String, onDone: (newPath: String?) -> Unit) {
            if (context.isAStorageRootFolder(path)) {
                activity.toast(org.fossify.commons.R.string.rename_folder_root)
                onDone(null)
                return
            }

            activity.handleLockedFolderOpening(path) { success ->
                if (success) {
                    RenameItemDialog(activity, path) { newPath ->
                        // keep the folder in its virtual group
                        context.config.updateFolderGroupMemberPath(path, newPath)
                        ensureBackgroundThread {
                            try {
                                // the folder's thumbnail is one of its own media, and it moved
                                // with the folder
                                val thumbnail = context.directoryDB.getDirectoryThumbnail(path) ?: ""
                                context.directoryDB.updateDirectoryAfterRename(
                                    thumbnail = "$newPath/${thumbnail.getFilenameFromPath()}",
                                    name = newPath.getFilenameFromPath(),
                                    newPath = newPath,
                                    oldPath = path
                                )
                            } catch (e: Exception) {
                                activity.showErrorToast(e)
                            }

                            activity.runOnUiThread { onDone(newPath) }
                        }
                    }
                }
            }
        }

        override fun renameSeveralFolders(activity: BaseSimpleActivity, paths: List<String>, onDone: () -> Unit) {
            RenameItemsDialog(activity, ArrayList(paths)) {
                onDone()
            }
        }
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

        // the name goes to pCloud, and the writer carries the rows over with it; the folder is
        // then read again from pCloud, if the settings ask for that after a write
        override fun renameMedium(activity: BaseSimpleActivity, path: String, onDone: (newPath: String?) -> Unit) {
            RemoteNameDialog(activity, path.getFilenameFromPath(), org.fossify.commons.R.string.rename) { newName ->
                val newPath = "${path.getParentPath()}/$newName"
                context.writeToPCloud(listOf(path.getParentPath()), { renameFile(path, newName) }) { renamed ->
                    activity.runOnUiThread { onDone(newPath.takeIf { renamed }) }
                }
            }
        }

        // the writer moves every row under the folder along with it, at any depth
        override fun renameFolder(activity: BaseSimpleActivity, path: String, onDone: (newPath: String?) -> Unit) {
            activity.handleLockedFolderOpening(path) { success ->
                if (success) {
                    RemoteNameDialog(activity, path.getFilenameFromPath(), org.fossify.commons.R.string.rename) { newName ->
                        val newPath = "${path.getParentPath()}/$newName"
                        context.writeToPCloud(listOf(newPath), { renameFolder(path, newName) }) { renamed ->
                            activity.runOnUiThread { onDone(newPath.takeIf { renamed }) }
                        }
                    }
                }
            }
        }
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

        // the name goes to the share, and the writer carries the row and the cached copies over
        // with it, so what a viewer is holding is not fetched again. Nothing is rescanned
        override fun renameMedium(activity: BaseSimpleActivity, path: String, onDone: (newPath: String?) -> Unit) {
            RemoteNameDialog(activity, path.getFilenameFromPath(), org.fossify.commons.R.string.rename) { newName ->
                val newPath = "${path.getParentPath()}/$newName"
                context.writeToShare({ renameFile(path, newName) }) { renamed ->
                    activity.runOnUiThread { onDone(newPath.takeIf { renamed }) }
                }
            }
        }

        // the same as pCloud: the writer moves every row under the folder along with it
        override fun renameFolder(activity: BaseSimpleActivity, path: String, onDone: (newPath: String?) -> Unit) {
            activity.handleLockedFolderOpening(path) { success ->
                if (success) {
                    RemoteNameDialog(activity, path.getFilenameFromPath(), org.fossify.commons.R.string.rename) { newName ->
                        val newPath = "${path.getParentPath()}/$newName"
                        context.writeToShare({ renameFolder(path, newName) }) { renamed ->
                            activity.runOnUiThread { onDone(newPath.takeIf { renamed }) }
                        }
                    }
                }
            }
        }
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
