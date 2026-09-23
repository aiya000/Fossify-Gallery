package org.fossify.gallery.helpers

import android.content.Context
import android.widget.Toast
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.dialogs.RenameDialog
import org.fossify.commons.dialogs.RenameItemDialog
import org.fossify.commons.dialogs.RenameItemsDialog
import org.fossify.commons.extensions.deleteFiles
import org.fossify.commons.extensions.formatSize
import org.fossify.commons.extensions.getDoesFilePathExist
import org.fossify.commons.extensions.getFilenameFromPath
import org.fossify.commons.extensions.getParentPath
import org.fossify.commons.extensions.handleDeletePasswordProtection
import org.fossify.commons.extensions.handleLockedFolderOpening
import org.fossify.commons.extensions.internalStoragePath
import org.fossify.commons.extensions.isAStorageRootFolder
import org.fossify.commons.extensions.isAccessibleWithSAFSdk30
import org.fossify.commons.extensions.isExternalStorageManager
import org.fossify.commons.extensions.isGif
import org.fossify.commons.extensions.isImageFast
import org.fossify.commons.extensions.isMediaFile
import org.fossify.commons.extensions.isRawFast
import org.fossify.commons.extensions.isSvg
import org.fossify.commons.extensions.isVideoFast
import org.fossify.commons.extensions.needsStupidWritePermissions
import org.fossify.commons.extensions.recycleBinPath
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toFileDirItem
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.FAVORITES
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isRPlus
import org.fossify.commons.helpers.sumByLong
import org.fossify.commons.models.FileDirItem
import org.fossify.gallery.R
import org.fossify.gallery.dialogs.ConfirmDeleteFolderDialog
import org.fossify.gallery.dialogs.DeleteWithRememberDialog
import org.fossify.gallery.dialogs.RemoteNameDialog
import org.fossify.gallery.extensions.config
import org.fossify.gallery.extensions.deleteDBPath
import org.fossify.gallery.extensions.directoryDB
import org.fossify.gallery.extensions.handleMediaManagementPrompt
import org.fossify.gallery.extensions.isDownloadsFolder
import org.fossify.gallery.extensions.isPCloudPath
import org.fossify.gallery.extensions.isPCloudRecycleBinPath
import org.fossify.gallery.extensions.isRemotePath
import org.fossify.gallery.extensions.isSmbPath
import org.fossify.gallery.extensions.movePathsInRecycleBin
import org.fossify.gallery.extensions.tryDeleteFileDirItem
import org.fossify.gallery.extensions.updateDBMediaPath
import org.fossify.gallery.extensions.writeToPCloud
import org.fossify.gallery.extensions.writeToShare
import org.fossify.gallery.jobs.PCloudTransferService
import org.fossify.gallery.jobs.SmbTransferService
import org.fossify.gallery.models.Medium
import java.io.File

// The three places a medium can be: this device, pCloud, and the network share. The set is
// closed, so a `when` over it is exhaustive, and a fourth storage would fail to compile rather
// than fail on screen.
//
// What a storage can and cannot do is written here, once, instead of at every menu that has to
// know -- a menu that was not told is exactly how #71 and #72 happened. A difference that is
// data ("is a name renamed one at a time", "is there a file to hand to another app") is a
// property; a difference that is steps is an override. The operations come over one family at
// a time (#106); renaming, copying or moving, and deleting are here, the rest still lives
// where it always has.
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

    // --- deleting: asked about, allowed, and done, in three steps a screen strings together
    // with its own updates in between. The settings that shape the question -- the delete
    // password, "do not ask again", the bin and "skip the bin" -- are the same on every storage;
    // what differs is the words, whether there is a bin to pass through, and what the deleting
    // itself is. Emptying a bin and restoring out of one are the bins' own, and stay where they
    // are until #112 settles what the bins become

    // whether media of this storage pass through a recycle bin on the way out, when the
    // setting is on. The share has none, and a delete on it is for good (#28, until #112)
    abstract val hasRecycleBin: Boolean

    // whether [path] names something already in this storage's recycle bin, which is deleted
    // for good from there and offered no bin to skip
    abstract fun isInRecycleBin(path: String): Boolean

    // the question before [media] go, in the storage's own words: into the bin when [toBin],
    // for good otherwise
    protected abstract fun deleteMediaQuestion(activity: BaseSimpleActivity, media: List<Medium>, toBin: Boolean): String

    // the same for the folders at [paths], which may hold the folder list's tiles as well
    protected abstract fun deleteFoldersQuestion(activity: BaseSimpleActivity, paths: List<String>, toBin: Boolean): String

    // The permissions a delete of [fileDirItems] needs, asked on screen; [then] runs once they
    // are in. The device asks for the folders they are in, a remote storage for nothing
    abstract fun onceMayDeleteMedia(activity: BaseSimpleActivity, fileDirItems: List<FileDirItem>, then: () -> Unit)

    // the same before folders go, asked about [path], one of them
    abstract fun onceMayDeleteFolders(activity: BaseSimpleActivity, path: String, then: () -> Unit)

    // Deletes [fileDirItems], all on this storage, into the bin where there is one and the
    // settings and [skipRecycleBin] leave it open, for good otherwise; the rows go with them.
    // [onDone] runs on the main thread once the storage is through, with whether it all went;
    // what did not is told to the user here, not by the caller
    abstract fun deleteMedia(activity: BaseSimpleActivity, fileDirItems: ArrayList<FileDirItem>, skipRecycleBin: Boolean, onDone: (deleted: Boolean) -> Unit)

    // Deletes the folders at [paths] with everything under them, the media into the bin when
    // [toRecycleBin]. Says on screen what it is doing, since a folder takes a moment. [onDone]
    // runs on the main thread once the storage is through
    abstract fun deleteFolders(activity: BaseSimpleActivity, paths: List<String>, toRecycleBin: Boolean, onDone: () -> Unit)

    // Asks before [media] go, the way the settings say: the delete password, or no question at
    // all, or the question with "do not ask again" and, where there is a bin to skip, "skip the
    // recycle bin". [onConfirmed] gets whether the bin is to be skipped. Nothing runs when the
    // question is answered no
    fun confirmDeleteMedia(activity: BaseSimpleActivity, media: List<Medium>, onConfirmed: (skipRecycleBin: Boolean) -> Unit) {
        onceAllowedToChangeMedia(activity) {
            val config = context.config
            when {
                config.isDeletePasswordProtectionOn -> activity.handleDeletePasswordProtection { onConfirmed(config.tempSkipRecycleBin) }
                config.tempSkipDeleteConfirmation || config.skipDeleteConfirmation -> onConfirmed(config.tempSkipRecycleBin)
                else -> {
                    val canUseBin = hasRecycleBin && config.useRecycleBin && !isInRecycleBin(media.first().path)
                    val question = deleteMediaQuestion(activity, media, toBin = canUseBin && !config.tempSkipRecycleBin)
                    DeleteWithRememberDialog(activity, question, canUseBin) { remember, skipRecycleBin ->
                        config.tempSkipDeleteConfirmation = remember
                        if (remember && hasRecycleBin) {
                            config.tempSkipRecycleBin = skipRecycleBin
                        }

                        onConfirmed(skipRecycleBin)
                    }
                }
            }
        }
    }

    // The same before the folders at [paths] go. [onConfirmed] gets whether their media go
    // into the bin, which the settings decide and no checkbox does
    fun confirmDeleteFolders(activity: BaseSimpleActivity, paths: List<String>, onConfirmed: (toRecycleBin: Boolean) -> Unit) {
        val config = context.config
        val toBin = hasRecycleBin && config.useRecycleBin && !config.tempSkipRecycleBin
        when {
            config.isDeletePasswordProtectionOn -> activity.handleDeletePasswordProtection { onConfirmed(toBin) }
            config.skipDeleteConfirmation -> onConfirmed(toBin)
            else -> askDeleteFolders(activity, paths, toBin) { onConfirmed(toBin) }
        }
    }

    // the folder question, with the red warning line under it
    protected open fun askDeleteFolders(activity: BaseSimpleActivity, paths: List<String>, toBin: Boolean, onYes: () -> Unit) {
        val warning = activity.resources.getQuantityString(org.fossify.commons.R.plurals.delete_warning, paths.size, paths.size)
        ConfirmDeleteFolderDialog(activity, deleteFoldersQuestion(activity, paths, toBin), warning) {
            onYes()
        }
    }

    // "\"name\"" for one, "N items" for more, which is how every delete question names them
    protected fun namesOf(activity: BaseSimpleActivity, paths: List<String>): String = if (paths.size == 1) {
        "\"${paths.first().getFilenameFromPath()}\""
    } else {
        activity.resources.getQuantityString(org.fossify.commons.R.plurals.delete_items, paths.size, paths.size)
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

        override val hasRecycleBin = true
        override fun isInRecycleBin(path: String) = path.startsWith(context.recycleBinPath)

        // named with their size, which is read off the files
        override fun deleteMediaQuestion(activity: BaseSimpleActivity, media: List<Medium>, toBin: Boolean): String {
            val items = media.map { it.toFileDirItem() }
            val size = items.sumByLong { it.getProperSize(activity, countHidden = true) }.formatSize()
            val names = "${namesOf(activity, media.map { it.path })} ($size)"
            val base = if (toBin) org.fossify.commons.R.string.move_to_recycle_bin_confirmation else org.fossify.commons.R.string.deletion_confirmation
            return String.format(activity.resources.getString(base), names)
        }

        // the favorites tile on its own is not a folder anything is moved out of
        override fun deleteFoldersQuestion(activity: BaseSimpleActivity, paths: List<String>, toBin: Boolean): String {
            val favoritesOnly = paths.size == 1 && paths.first() == FAVORITES
            val base = if (!toBin || favoritesOnly) org.fossify.commons.R.string.deletion_confirmation else org.fossify.commons.R.string.move_to_recycle_bin_confirmation
            return String.format(activity.resources.getString(base), namesOf(activity, paths))
        }

        // the bin's own tile is emptied rather than deleted, and asked about in those words
        override fun askDeleteFolders(activity: BaseSimpleActivity, paths: List<String>, toBin: Boolean, onYes: () -> Unit) {
            if (paths.size == 1 && paths.first() == RECYCLE_BIN) {
                ConfirmationDialog(
                    activity,
                    "",
                    org.fossify.commons.R.string.empty_recycle_bin_confirmation,
                    org.fossify.commons.R.string.yes,
                    org.fossify.commons.R.string.no
                ) {
                    onYes()
                }
                return
            }

            super.askDeleteFolders(activity, paths, toBin, onYes)
        }

        // the write permission for the folder the files are in, on the two systems that have
        // one to ask for -- SAF, and the all-files or media management right from Android 11
        override fun onceMayDeleteMedia(activity: BaseSimpleActivity, fileDirItems: List<FileDirItem>, then: () -> Unit) {
            val paths = fileDirItems.map { it.path }
            val safPath = paths.firstOrNull { activity.needsStupidWritePermissions(it) } ?: paths.firstOrNull() ?: return
            activity.handleSAFDialog(safPath) { granted ->
                if (!granted) {
                    return@handleSAFDialog
                }

                val sdk30SAFPath = paths.firstOrNull { activity.isAccessibleWithSAFSdk30(it) } ?: safPath
                activity.checkManageMediaOrHandleSAFDialogSdk30(sdk30SAFPath) { allowed ->
                    if (allowed) {
                        then()
                    }
                }
            }
        }

        override fun onceMayDeleteFolders(activity: BaseSimpleActivity, path: String, then: () -> Unit) {
            activity.handleSAFDialog(path) { granted ->
                if (!granted) {
                    return@handleSAFDialog
                }

                activity.handleSAFDialogSdk30(path) { allowed ->
                    if (allowed) {
                        then()
                    }
                }
            }
        }

        // Into the bin is a copy into the app's own directory with the row marked deleted, and
        // then the file goes; for good is the file going and the row with it. A file that is
        // in the bin already, or skips it, is for good
        override fun deleteMedia(activity: BaseSimpleActivity, fileDirItems: ArrayList<FileDirItem>, skipRecycleBin: Boolean, onDone: (deleted: Boolean) -> Unit) {
            val paths = fileDirItems.map { it.path }
            val toBin = context.config.useRecycleBin && !skipRecycleBin && !isInRecycleBin(paths.first())
            val deleteTheFiles = {
                activity.deleteFiles(fileDirItems) { deleted ->
                    if (!deleted) {
                        activity.toast(org.fossify.commons.R.string.unknown_error_occurred)
                    } else if (!toBin) {
                        ensureBackgroundThread {
                            paths.forEach { context.deleteDBPath(it) }
                        }
                    }

                    activity.runOnUiThread { onDone(deleted) }
                }
            }

            if (toBin) {
                activity.movePathsInRecycleBin(ArrayList(paths)) { moved ->
                    if (moved) {
                        deleteTheFiles()
                    } else {
                        activity.toast(org.fossify.commons.R.string.unknown_error_occurred)
                        activity.runOnUiThread { onDone(false) }
                    }
                }
            } else {
                deleteTheFiles()
            }
        }

        // The media the gallery lists in each folder go, the folder itself only when the
        // "delete empty folders" setting says so. A folder that is not one on the device -- a
        // tile, or gone already -- is passed over
        override fun deleteFolders(activity: BaseSimpleActivity, paths: List<String>, toRecycleBin: Boolean, onDone: () -> Unit) {
            val folders = paths.map { File(it) }.filter { it.isDirectory }
            val fileDirItems = folders.map { FileDirItem(it.absolutePath, it.name, true) }
            when {
                fileDirItems.isEmpty() -> return
                fileDirItems.size == 1 -> {
                    try {
                        activity.toast(String.format(activity.getString(org.fossify.commons.R.string.deleting_folder), fileDirItems.first().name))
                    } catch (e: Exception) {
                        activity.showErrorToast(e)
                    }
                }

                else -> {
                    val base = if (toRecycleBin) org.fossify.commons.R.plurals.moving_items_into_bin else org.fossify.commons.R.plurals.delete_items
                    activity.toast(activity.resources.getQuantityString(base, fileDirItems.size, fileDirItems.size))
                }
            }

            val config = context.config
            val itemsToDelete = ArrayList<FileDirItem>()
            val filter = config.filterMedia
            val showHidden = config.shouldShowHidden
            folders.forEach { folder ->
                folder.listFiles()?.filter {
                    it.absolutePath.isMediaFile() && (showHidden || !it.name.startsWith('.')) &&
                        ((it.isImageFast() && filter and TYPE_IMAGES != 0) ||
                            (it.isVideoFast() && filter and TYPE_VIDEOS != 0) ||
                            (it.isGif() && filter and TYPE_GIFS != 0) ||
                            (it.isRawFast() && filter and TYPE_RAWS != 0) ||
                            (it.isSvg() && filter and TYPE_SVGS != 0))
                }?.mapTo(itemsToDelete) { it.toFileDirItem(context) }
            }

            val deleteTheFiles = {
                activity.deleteFiles(itemsToDelete) {
                    activity.runOnUiThread { onDone() }

                    ensureBackgroundThread {
                        val otgPath = config.OTGPath
                        folders.filter { !context.getDoesFilePathExist(it.absolutePath, otgPath) }.forEach {
                            context.directoryDB.deleteDirPath(it.absolutePath)
                        }

                        if (config.deleteEmptyFolders) {
                            folders.filter {
                                !it.absolutePath.isDownloadsFolder() && it.isDirectory && it.toFileDirItem(context).getProperFileCount(context, true) == 0
                            }.forEach {
                                activity.tryDeleteFileDirItem(it.toFileDirItem(context), true, true)
                            }
                        }
                    }
                }
            }

            if (toRecycleBin) {
                activity.movePathsInRecycleBin(ArrayList(itemsToDelete.map { it.path })) { moved ->
                    if (moved) {
                        deleteTheFiles()
                    } else {
                        activity.toast(org.fossify.commons.R.string.unknown_error_occurred)
                    }
                }
            } else {
                deleteTheFiles()
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

        // the app's own bin on pCloud, a folder in the root, see PCLOUD_RECYCLE_BIN
        override val hasRecycleBin = true
        override fun isInRecycleBin(path: String) = path.isPCloudRecycleBinPath()

        override fun deleteMediaQuestion(activity: BaseSimpleActivity, media: List<Medium>, toBin: Boolean): String {
            val id = if (toBin) R.string.pcloud_move_to_recycle_bin_confirmation else R.string.pcloud_delete_confirmation
            return activity.getString(id, namesOf(activity, media.map { it.path }))
        }

        override fun deleteFoldersQuestion(activity: BaseSimpleActivity, paths: List<String>, toBin: Boolean): String {
            val id = if (toBin) R.string.pcloud_move_folder_to_recycle_bin_confirmation else R.string.pcloud_delete_folder_confirmation
            return activity.getString(id, namesOf(activity, paths))
        }

        // nothing of the device is touched, so there is nothing to ask for
        override fun onceMayDeleteMedia(activity: BaseSimpleActivity, fileDirItems: List<FileDirItem>, then: () -> Unit) = then()
        override fun onceMayDeleteFolders(activity: BaseSimpleActivity, path: String, then: () -> Unit) = then()

        // into the app's bin on pCloud, for good from there, or to pCloud's own trash; the
        // folders they were in are read again from pCloud afterwards, if the settings ask for
        // that after a write, which also brings back a medium pCloud would not part with
        override fun deleteMedia(activity: BaseSimpleActivity, fileDirItems: ArrayList<FileDirItem>, skipRecycleBin: Boolean, onDone: (deleted: Boolean) -> Unit) {
            val paths = fileDirItems.map { it.path }
            val isInBin = isInRecycleBin(paths.first())
            val toBin = context.config.useRecycleBin && !skipRecycleBin && !isInBin
            val foldersToRescan = if (isInBin) emptyList() else paths.map { it.getParentPath() }.distinct()
            val write: PCloudWriter.() -> Unit = {
                when {
                    toBin -> moveToRecycleBin(paths)
                    isInBin -> deleteFromRecycleBin(paths)
                    else -> deleteFiles(paths)
                }
            }

            context.writeToPCloud(foldersToRescan, write) { deleted ->
                activity.runOnUiThread { onDone(deleted) }
            }
        }

        // the parents are read again afterwards, so that a folder pCloud kept after all comes back
        override fun deleteFolders(activity: BaseSimpleActivity, paths: List<String>, toRecycleBin: Boolean, onDone: () -> Unit) {
            activity.toast(activity.resources.getQuantityString(org.fossify.commons.R.plurals.deleting_items, paths.size, paths.size))
            val parents = paths.map { it.getParentPath() }.distinct()
            context.writeToPCloud(parents, { deleteFolders(paths, toRecycleBin) }) {
                activity.runOnUiThread { onDone() }
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

        // no bin on the share and none made (#28): a delete is for good, the question says so,
        // and there is no bin to offer to skip. #112 is where that changes
        override val hasRecycleBin = false
        override fun isInRecycleBin(path: String) = false

        override fun deleteMediaQuestion(activity: BaseSimpleActivity, media: List<Medium>, toBin: Boolean) =
            activity.getString(R.string.smb_delete_confirmation, namesOf(activity, media.map { it.path }))

        override fun deleteFoldersQuestion(activity: BaseSimpleActivity, paths: List<String>, toBin: Boolean) =
            activity.getString(R.string.smb_delete_folder_confirmation, namesOf(activity, paths))

        override fun onceMayDeleteMedia(activity: BaseSimpleActivity, fileDirItems: List<FileDirItem>, then: () -> Unit) = then()
        override fun onceMayDeleteFolders(activity: BaseSimpleActivity, path: String, then: () -> Unit) = then()

        // from the share and from nowhere else; the writer takes the rows with the files, and
        // nothing is rescanned
        override fun deleteMedia(activity: BaseSimpleActivity, fileDirItems: ArrayList<FileDirItem>, skipRecycleBin: Boolean, onDone: (deleted: Boolean) -> Unit) {
            val paths = fileDirItems.map { it.path }
            context.writeToShare({ deleteFiles(paths) }) { deleted ->
                activity.runOnUiThread { onDone(deleted) }
            }
        }

        // nothing is rescanned afterwards: what the share still has of a folder that only half
        // went is put right by the next walk of it, and a walk of a whole share is minutes
        override fun deleteFolders(activity: BaseSimpleActivity, paths: List<String>, toRecycleBin: Boolean, onDone: () -> Unit) {
            activity.toast(activity.resources.getQuantityString(org.fossify.commons.R.plurals.deleting_items, paths.size, paths.size))
            context.writeToShare({ deleteFolders(paths) }) {
                activity.runOnUiThread { onDone() }
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
