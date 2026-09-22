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
import org.fossify.commons.models.FileDirItem
import org.fossify.gallery.R
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
import org.fossify.gallery.jobs.PCloudTransferService
import org.fossify.gallery.jobs.SmbTransferService

// The three places a medium can be: this device, pCloud, and the network share. The set is
// closed, so a `when` over it is exhaustive, and a fourth storage would fail to compile rather
// than fail on screen.
//
// What a storage can and cannot do is written here, once, instead of at every menu that has to
// know -- a menu that was not told is exactly how #71 and #72 happened. A difference that is
// data ("is a name renamed one at a time", "is there a file to hand to another app") is a
// property; a difference that is steps is an override. The operations come over one family at
// a time (#106); renaming and copying or moving are here, the rest still lives where it
// always has.
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

    // --- copying and moving: a pair of storages, the one the media are on and the one the
    // folder they go to is on. Between two folders of the device it is done on the spot; with a
    // remote storage on either side it goes to a service, one job at a time with a progress
    // notification, and the screens learn of the end through the service's listeners

    // Runs [then] once the app may change this storage's media where they are. On the device
    // that is the media management prompt; a remote storage has nothing in the MediaStore to
    // manage, and is asked nothing
    abstract fun onceAllowedToChangeMedia(activity: BaseSimpleActivity, then: () -> Unit)

    // whether media of this storage can be copied or moved into a folder of [destination]. What
    // goes onto the share is read off a file of the device, and what is already remote would
    // have to be staged on the way, which is not built (#28); the destination picker turns such
    // a folder away, and copyMoveTo() is the backstop
    open fun canTransferTo(destination: MediaStorage): Boolean = true

    // Copies or moves [fileDirItems], all of them in the folder [source] of this storage, into
    // the folder [destination], which may be on any storage. [onDone] gets the destination once
    // the files are there, which is only ever between two folders of the device: a transfer
    // with a remote storage on either side goes to a service and never calls it -- nothing has
    // moved when this returns -- and [onQueued] fires instead once the job is with the service,
    // several permission dialogs later. A caller whose only business was the transfer waits for
    // that. A pair that cannot be done says so and stops
    abstract fun copyMoveTo(
        activity: BaseSimpleActivity,
        fileDirItems: ArrayList<FileDirItem>,
        source: String,
        destination: String,
        isCopy: Boolean,
        onQueued: (() -> Unit)?,
        onDone: (destination: String) -> Unit
    )

    // Queues a transfer between the device and pCloud, or within pCloud, once the storage
    // permissions the device's side needs are in: a move away from the device deletes the
    // sources afterwards, a download writes into the destination. The notification permission
    // is asked for so that the progress can be seen; the transfer runs without it too
    protected fun enqueuePCloudTransfer(
        activity: BaseSimpleActivity,
        kind: PCloudTransferService.Kind,
        fileDirItems: ArrayList<FileDirItem>,
        source: String,
        destination: String,
        isCopy: Boolean,
        onQueued: (() -> Unit)?
    ) {
        if (!context.config.isPCloudLoggedIn) {
            activity.toast(R.string.pcloud_log_in_required)
            return
        }

        val paths = fileDirItems.map { it.path }
        if (paths.isEmpty()) {
            return
        }

        val enqueue = {
            activity.handleNotificationPermission {
                // a transfer into the temporary folder tile turns it into a real folder, the way
                // a local copy or move drops the tile; the service rebuilds the folder's row
                if (destination == context.config.tempFolderPath) {
                    context.config.tempFolderPath = ""
                }

                PCloudTransferService.enqueue(activity, PCloudTransferService.Job(kind, paths, destination, isCopy))
                activity.toast(R.string.pcloud_transfer_started)
                onQueued?.invoke()
            }
        }

        when (kind) {
            PCloudTransferService.Kind.UPLOAD -> if (isCopy) {
                enqueue()
            } else {
                activity.handleSAFDialog(source) { granted ->
                    if (granted) {
                        activity.checkManageMediaOrHandleSAFDialogSdk30(paths.first()) { allowed ->
                            if (allowed) {
                                enqueue()
                            }
                        }
                    }
                }
            }

            PCloudTransferService.Kind.DOWNLOAD -> activity.handleSAFDialog(destination) { granted ->
                if (granted) {
                    activity.handleSAFDialogSdk30(destination) { allowed ->
                        if (allowed) {
                            enqueue()
                        }
                    }
                }
            }

            PCloudTransferService.Kind.WITHIN_PCLOUD -> enqueue()
        }
    }

    // Queues a copy or a move that has the share on one side, once the permissions it needs are
    // in: a folder on the device is written into, a folder on pCloud wants an account, a move
    // away from the device deletes the files it read, and a move inside the share wants nothing
    // at all. The notification permission is asked for so that the progress can be seen; the
    // transfer runs without it too
    protected fun enqueueSmbTransfer(
        activity: BaseSimpleActivity,
        kind: SmbTransferService.Kind,
        fileDirItems: ArrayList<FileDirItem>,
        source: String,
        destination: String,
        isCopy: Boolean,
        onQueued: (() -> Unit)?
    ) {
        if (!context.config.isSmbConfigured) {
            activity.toast(R.string.smb_not_configured)
            return
        }

        val paths = fileDirItems.map { it.path }
        if (paths.isEmpty()) {
            return
        }

        if (kind == SmbTransferService.Kind.TO_PCLOUD && !context.config.isPCloudLoggedIn) {
            activity.toast(R.string.pcloud_log_in_required)
            return
        }

        // a move within the share is a move whatever the caller thought it was asking for: there
        // is no copy within the share, see SmbTransferService.Job
        val isReallyCopy = isCopy && kind != SmbTransferService.Kind.WITHIN_SHARE
        val enqueue = {
            activity.handleNotificationPermission {
                // a transfer into the temporary folder tile turns it into a real folder, the way
                // a local copy or move drops the tile; the service rebuilds the folder's row
                if (destination == context.config.tempFolderPath) {
                    context.config.tempFolderPath = ""
                }

                SmbTransferService.enqueue(activity, SmbTransferService.Job(kind, paths, destination, isReallyCopy))
                activity.toast(if (isReallyCopy) R.string.smb_transfer_started else R.string.smb_move_started)
                onQueued?.invoke()
            }
        }

        when (kind) {
            // nothing of the device is touched, so there is nothing to ask for
            SmbTransferService.Kind.TO_PCLOUD, SmbTransferService.Kind.WITHIN_SHARE -> enqueue()

            // what a copy onto the share reads is media the app already lists; a move also
            // deletes those files afterwards, which is what the storage permissions are wanted for
            SmbTransferService.Kind.FROM_DEVICE -> if (isReallyCopy) {
                enqueue()
            } else {
                activity.handleSAFDialog(source) { granted ->
                    if (granted) {
                        activity.checkManageMediaOrHandleSAFDialogSdk30(paths.first()) { allowed ->
                            if (allowed) {
                                enqueue()
                            }
                        }
                    }
                }
            }

            SmbTransferService.Kind.TO_DEVICE -> activity.handleSAFDialog(destination) { granted ->
                if (granted) {
                    activity.handleSAFDialogSdk30(destination) { allowed ->
                        if (allowed) {
                            enqueue()
                        }
                    }
                }
            }
        }
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

        override fun onceAllowedToChangeMedia(activity: BaseSimpleActivity, then: () -> Unit) {
            activity.handleMediaManagementPrompt(then)
        }

        // The commons dialogs do the renaming themselves, file and MediaStore; what is left to
        // do here is the app's own rows
        override fun renameMedium(activity: BaseSimpleActivity, path: String, onDone: (newPath: String?) -> Unit) {
            onceAllowedToChangeMedia(activity) {
                if (isOnARootTheSystemKeeps(activity, path)) {
                    onDone(null)
                    return@onceAllowedToChangeMedia
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
            onceAllowedToChangeMedia(activity) {
                if (isOnARootTheSystemKeeps(activity, paths.first())) {
                    onDone()
                    return@onceAllowedToChangeMedia
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

        // between two folders of the device the commons copy does it, once the source folder
        // may be written to; a remote destination is an upload for its service
        override fun copyMoveTo(
            activity: BaseSimpleActivity,
            fileDirItems: ArrayList<FileDirItem>,
            source: String,
            destination: String,
            isCopy: Boolean,
            onQueued: (() -> Unit)?,
            onDone: (destination: String) -> Unit
        ) {
            when (of(context, destination)) {
                is Device -> activity.handleSAFDialog(source) { granted ->
                    if (granted) {
                        activity.copyMoveFilesTo(fileDirItems, source.trimEnd('/'), destination, isCopy, true, context.config.shouldShowHidden, onDone)
                    }
                }

                is PCloud -> enqueuePCloudTransfer(activity, PCloudTransferService.Kind.UPLOAD, fileDirItems, source, destination, isCopy, onQueued)
                is Smb -> enqueueSmbTransfer(activity, SmbTransferService.Kind.FROM_DEVICE, fileDirItems, source, destination, isCopy, onQueued)
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

        override fun onceAllowedToChangeMedia(activity: BaseSimpleActivity, then: () -> Unit) = then()

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

        // pCloud straight onto the share would have to be staged on the way, see #28
        override fun canTransferTo(destination: MediaStorage) = destination !is Smb

        // every pair is the pCloud service's, downloading, uploading or moving within the
        // account; the share is the one it cannot reach
        override fun copyMoveTo(
            activity: BaseSimpleActivity,
            fileDirItems: ArrayList<FileDirItem>,
            source: String,
            destination: String,
            isCopy: Boolean,
            onQueued: (() -> Unit)?,
            onDone: (destination: String) -> Unit
        ) {
            when (of(context, destination)) {
                is Device -> enqueuePCloudTransfer(activity, PCloudTransferService.Kind.DOWNLOAD, fileDirItems, source, destination, isCopy, onQueued)
                is PCloud -> enqueuePCloudTransfer(activity, PCloudTransferService.Kind.WITHIN_PCLOUD, fileDirItems, source, destination, isCopy, onQueued)
                is Smb -> activity.toast(R.string.smb_no_remote_copy_to_share, Toast.LENGTH_LONG)
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

        override fun onceAllowedToChangeMedia(activity: BaseSimpleActivity, then: () -> Unit) = then()

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

        // every pair is the share's service's: off the share onto the device or pCloud, and
        // within the share, where no bytes travel at all and a copy is a move
        override fun copyMoveTo(
            activity: BaseSimpleActivity,
            fileDirItems: ArrayList<FileDirItem>,
            source: String,
            destination: String,
            isCopy: Boolean,
            onQueued: (() -> Unit)?,
            onDone: (destination: String) -> Unit
        ) {
            val kind = when (of(context, destination)) {
                is Device -> SmbTransferService.Kind.TO_DEVICE
                is PCloud -> SmbTransferService.Kind.TO_PCLOUD
                is Smb -> SmbTransferService.Kind.WITHIN_SHARE
            }

            enqueueSmbTransfer(activity, kind, fileDirItems, source, destination, isCopy, onQueued)
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
