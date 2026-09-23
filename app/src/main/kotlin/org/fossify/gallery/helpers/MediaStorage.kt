package org.fossify.gallery.helpers

import android.content.Context
import android.system.Os
import android.util.Log
import android.widget.Toast
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.dialogs.PropertiesDialog
import org.fossify.commons.dialogs.RenameDialog
import org.fossify.commons.dialogs.RenameItemDialog
import org.fossify.commons.dialogs.RenameItemsDialog
import org.fossify.commons.extensions.deleteFiles
import org.fossify.commons.extensions.formatSize
import org.fossify.commons.extensions.getDoesFilePathExist
import org.fossify.commons.extensions.getFileUrisFromFileDirItems
import org.fossify.commons.extensions.getFilenameFromPath
import org.fossify.commons.extensions.getParentPath
import org.fossify.commons.extensions.handleDeletePasswordProtection
import org.fossify.commons.extensions.handleLockedFolderOpening
import org.fossify.commons.extensions.humanizePath
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
import org.fossify.gallery.dialogs.RemotePropertiesDialog
import org.fossify.gallery.extensions.config
import org.fossify.gallery.extensions.deleteDBPath
import org.fossify.gallery.extensions.directoryDB
import org.fossify.gallery.extensions.handleMediaManagementPrompt
import org.fossify.gallery.extensions.isDownloadsFolder
import org.fossify.gallery.extensions.isPCloudPath
import org.fossify.gallery.extensions.isPCloudRecycleBinPath
import org.fossify.gallery.extensions.isRemotePath
import org.fossify.gallery.extensions.isSmbPath
import org.fossify.gallery.extensions.isSmbRecycleBinPath
import org.fossify.gallery.extensions.mediaDB
import org.fossify.gallery.extensions.movePathsInRecycleBin
import org.fossify.gallery.extensions.pCloudItemsDB
import org.fossify.gallery.extensions.recycleBin
import org.fossify.gallery.extensions.rescanSmbFolders
import org.fossify.gallery.extensions.restoreRecycleBinPaths
import org.fossify.gallery.extensions.saveRotatedImageToFile
import org.fossify.gallery.extensions.toPCloudRemotePath
import org.fossify.gallery.extensions.toSmbRemotePath
import org.fossify.gallery.extensions.tryDeleteFileDirItem
import org.fossify.gallery.extensions.updateDBMediaPath
import org.fossify.gallery.extensions.writeToPCloud
import org.fossify.gallery.extensions.writeToShare
import org.fossify.gallery.jobs.PCloudTransferService
import org.fossify.gallery.jobs.SmbTransferService
import org.fossify.gallery.models.Medium
import java.io.File
import java.util.concurrent.CountDownLatch

// The three places a medium can be: this device, pCloud, and the network share. The set is
// closed, so a `when` over it is exhaustive, and a fourth storage would fail to compile rather
// than fail on screen.
//
// What a storage can and cannot do is written here, once, instead of at every menu that has to
// know -- a menu that was not told is exactly how #71 and #72 happened. A difference that is
// data ("is a name renamed one at a time", "is there a file to hand to another app") is a
// property; a difference that is steps is an override. The operations came over one family at
// a time (#106): fetching, renaming, copying or moving, deleting and writing are all here, and
// the recycle bin's own -- restoring, emptying, sweeping -- with the deleting (#112).
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
    // first where there is none, see fetchForReading()
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
    // what differs is the words and what the deleting itself is.
    //
    // Every storage has a recycle bin, and a medium deleted into it stays on the storage it was
    // on: the device's bin is the app's own files directory, a remote storage's a folder in its
    // root, moved into and out of with one request and no bytes (#112). The folder list shows
    // the three as one bin, and the rows and the "deleted when" are the app's; what a storage
    // does here is the moving, the restoring, the deleting for good and the daily sweep

    // whether [path] names something already in this storage's recycle bin, which is deleted
    // for good from there and offered no bin to skip
    abstract fun isInRecycleBin(path: String): Boolean

    // Where a medium in the bin goes back to, and whether that folder is still there; for the
    // dialog that asks before a restore. A remote storage may ask the network, so off the main
    // thread
    abstract fun restoreDestinationOf(binPath: String): Pair<String, Boolean>

    // Brings [binPaths], all in this storage's bin, back out of it: into the folder each was
    // deleted from, made again when it is gone, or into [destinationFolder] for all of them; a
    // name that is taken there gets a number. [onDone] runs on the main thread with whether it
    // all went; what did not is told to the user here
    abstract fun restoreFromBin(activity: BaseSimpleActivity, binPaths: List<String>, destinationFolder: String?, onDone: (restored: Boolean) -> Unit)

    // Deletes everything in this storage's bin for good, the rows with the files. [onDone] runs
    // on the main thread with whether it all went; a storage with nothing in its bin says yes
    // without asking anybody
    abstract fun emptyBin(activity: BaseSimpleActivity, onDone: (emptied: Boolean) -> Unit)

    // Deletes for good what went into this storage's bin before [olderThan], the rows with the
    // files. Blocking and off the main thread; what fails is left for the next sweep and thrown
    abstract fun sweepBin(olderThan: Long)

    // a path of this storage as the user reads it: the device's with the storage's name in
    // place of its mount point, a remote storage's without its scheme
    abstract fun humanizedPath(path: String): String

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
                    val canUseBin = config.useRecycleBin && !isInRecycleBin(media.first().path)
                    val question = deleteMediaQuestion(activity, media, toBin = canUseBin && !config.tempSkipRecycleBin)
                    DeleteWithRememberDialog(activity, question, canUseBin) { remember, skipRecycleBin ->
                        config.tempSkipDeleteConfirmation = remember
                        if (remember) {
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
        val toBin = config.useRecycleBin && !config.tempSkipRecycleBin
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

    // --- writing: a file of this device put onto this storage, over a medium of it or under a
    // name of its own. The editor, the rotation, "Save as" and the resize all end here. On the
    // device the result is written straight where it goes; a remote storage takes a finished
    // file, so the result is written into a staging file of the device first and then sent,
    // and a write that breaks off leaves nothing behind on it

    // whether something is at [path] already. Asked of the storage itself, which for the share
    // is a request over the network, so this belongs off the main thread
    abstract fun isNameTaken(path: String): Boolean

    // Runs [then] with [path] once it may be written to: whatever is there already has been
    // agreed to be written over, when [confirmOverwrite] asks for that, and the device's own
    // grants are in. [onCancel] runs when the user says no, or the storage could not be asked.
    // An overwritten medium does not pass through the recycle bin on any storage, so the
    // question is asked for real
    open fun ensureWritable(activity: BaseSimpleActivity, path: String, confirmOverwrite: Boolean, onCancel: (() -> Unit)?, then: (String) -> Unit) {
        ensureBackgroundThread {
            val taken = try {
                isNameTaken(path)
            } catch (e: Exception) {
                activity.runOnUiThread {
                    activity.showErrorToast(e)
                    onCancel?.invoke()
                }
                return@ensureBackgroundThread
            }

            activity.runOnUiThread {
                if (!taken || !confirmOverwrite) {
                    then(path)
                    return@runOnUiThread
                }

                val title = String.format(activity.getString(org.fossify.commons.R.string.file_already_exists_overwrite), path.getFilenameFromPath())
                ConfirmationDialog(activity, title) { then(path) }
            }
        }
    }

    // Writes the file at [localPath] over the medium at [path], which is on this storage, and
    // carries the rows and the cached copies along. [onDone] runs on the main thread with
    // whether the storage took it; the local file is left where it is either way, it is the
    // only place the edit exists until the storage has it. A medium of the device is edited
    // where it lies, so there is nothing to write back there
    abstract fun overwriteMedium(activity: BaseSimpleActivity, path: String, localPath: String, onDone: (written: Boolean) -> Unit)

    // Hands [write] a file of the device to write into, and once [write] says it is through,
    // has what was written onto this storage at [path]: on the device that file is [path]
    // itself, once its folder may be written to; on a remote storage it is a staging file,
    // which then goes over what is at [path] or up under that name. [onDone] runs on the
    // main thread with whether it all went; nothing is said on screen about a write that did
    // not, the storage has said it already
    abstract fun writeInto(activity: BaseSimpleActivity, path: String, write: (localTarget: String, wrote: (Boolean) -> Unit) -> Unit, onDone: (written: Boolean) -> Unit)

    // Turns the image at [path] by [degrees] where it is, and blocks until it is through, so
    // that a selection of them goes one at a time rather than all at once. Off the main thread.
    // Offered where canRotate says so
    abstract fun rotateMediumAndWait(activity: BaseSimpleActivity, path: String, degrees: Int)

    // what is said while an edit goes back, and when the editor turned out to have saved
    // somewhere else; the words name the storage
    abstract fun writingBackMessage(activity: BaseSimpleActivity): String
    abstract fun editNotWrittenMessage(activity: BaseSimpleActivity): String

    // the staging file a remote storage's writeInto() hands out, under the name the result is
    // to have there: what is uploaded carries the name of the file it is given. One at a time
    protected fun stagingFileFor(path: String): File {
        val stagingDir = File(context.cacheDir, REMOTE_SAVE_DIR).apply {
            deleteRecursively()
            mkdirs()
        }

        return File(stagingDir, path.getFilenameFromPath())
    }

    // --- fetching: a medium's bytes had in hand as a file of this device. The device's media
    // are files already; a remote storage's are fetched into the cache, once, and handed out
    // from there in the shape the caller needs. All of these talk to the network, so they
    // belong off the main thread, and throw what goes wrong

    // the cached copy itself, for the viewer to draw. On the device that is the file
    abstract fun fetchedCopy(path: String): File

    // A file to read: handed to another app, printed, set as the wallpaper, shared, resized
    // from. Whatever receives the file shows the name it is given, so a remote storage's copy
    // is handed out under the medium's own name. Offered where canOpenWith and its kin say so
    abstract fun fetchForReading(path: String): String

    // A copy of its own, to write to: the editor above all. A copy rather than a link, since a
    // link shares its bytes with the cached copy, and an edit written over it would make the
    // cache serve the edited image for the original even when the write back never lands.
    // Only one edit is in flight at a time, so the previous copy goes first
    abstract fun fetchForEditing(path: String): String

    // what is said while a fetch runs, and when it failed; the words name the storage
    abstract fun fetchingMessage(): String
    abstract fun fetchFailedMessage(): String

    // the properties of [media]: commons' dialog reads them off the files, a remote storage's
    // off the rows, since there is no file on the device for that dialog to read
    abstract fun showProperties(activity: BaseSimpleActivity, media: List<Medium>)

    // a fetched copy under the medium's own name, in a directory of its own next to the
    // cache: a hard link where one can be made, a plain copy where not. A link keeps the bytes
    // of its cache copy alive after the cache has trimmed the copy away, so every link whose
    // copy is gone is dropped whenever a new one is made; the links in use are kept, an editor
    // may still be holding one
    protected fun linkUnderOwnName(cached: File, path: String, workDir: File, isStillCached: (linkDirName: String) -> Boolean): String {
        val linkDir = File(workDir, cached.nameWithoutExtension)
        linkDir.mkdirs()
        val link = File(linkDir, path.getFilenameFromPath())
        if (!link.isFile || link.length() != cached.length()) {
            link.delete()
            try {
                Os.link(cached.absolutePath, link.absolutePath)
            } catch (e: Exception) {
                cached.copyTo(link, overwrite = true)
            }
        }

        workDir.listFiles()?.forEach { other ->
            if (other.isDirectory && !isStillCached(other.name)) {
                other.deleteRecursively()
            }
        }

        return link.absolutePath
    }

    // a fetched copy of its own in [editDir], emptied first
    protected fun copyForEditing(cached: File, path: String, editDir: File): String {
        editDir.deleteRecursively()
        editDir.mkdirs()
        return File(editDir, path.getFilenameFromPath()).also { cached.copyTo(it, overwrite = true) }.absolutePath
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

        // The bin is the app's own files directory, with each file kept under its full original
        // path; the rows name it with RECYCLE_BIN in place of that directory, and the bin's
        // listing hands them out with the directory put back, which is what a path here is
        override fun isInRecycleBin(path: String) = path.startsWith(context.recycleBinPath)

        override fun restoreDestinationOf(binPath: String): Pair<String, Boolean> {
            val folder = binPath.removePrefix(context.recycleBinPath).getParentPath()
            return Pair(folder, File(folder).isDirectory)
        }

        // a copy back out of the app's directory, then the file in the bin goes; the commons
        // extension has the folder rewritten to Pictures when the system will not let it be
        // written to, and says so
        override fun restoreFromBin(activity: BaseSimpleActivity, binPaths: List<String>, destinationFolder: String?, onDone: (restored: Boolean) -> Unit) {
            activity.restoreRecycleBinPaths(ArrayList(binPaths), destinationFolder) {
                onDone(true)
            }
        }

        override fun emptyBin(activity: BaseSimpleActivity, onDone: (emptied: Boolean) -> Unit) {
            ensureBackgroundThread {
                val emptied = try {
                    context.recycleBin.deleteRecursively()
                    context.mediaDB.clearDeviceRecycleBin()
                    true
                } catch (e: Exception) {
                    Log.w("RecycleBin", "The device's recycle bin could not be emptied", e)
                    false
                }

                activity.runOnUiThread { onDone(emptied) }
            }
        }

        override fun sweepBin(olderThan: Long) {
            context.mediaDB.getOldDeviceRecycleBinItems(olderThan).forEach {
                if (File(it.path.replaceFirst(RECYCLE_BIN, context.recycleBinPath)).delete()) {
                    context.mediaDB.deleteMediumPath(it.path)
                }
            }
        }

        override fun humanizedPath(path: String): String = context.humanizePath(path)

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

        override fun isNameTaken(path: String) = context.getDoesFilePathExist(path)

        // the question is asked of the file system on the spot, and then the grants the system
        // wants: the uris of a file the app did not make from Android 11, and SAF's say
        override fun ensureWritable(activity: BaseSimpleActivity, path: String, confirmOverwrite: Boolean, onCancel: (() -> Unit)?, then: (String) -> Unit) {
            fun proceedAfterGrants() {
                activity.handleSAFDialogSdk30(path) { granted ->
                    if (!granted) {
                        onCancel?.invoke()
                        return@handleSAFDialogSdk30
                    }

                    then(path)
                }
            }

            fun requestGrantsThenProceed() {
                if (isRPlus() && !isExternalStorageManager()) {
                    val fileDirItem = arrayListOf(File(path).toFileDirItem(context))
                    val fileUris = activity.getFileUrisFromFileDirItems(fileDirItem)
                    activity.updateSDK30Uris(fileUris) { success ->
                        if (success) proceedAfterGrants() else onCancel?.invoke()
                    }
                } else {
                    proceedAfterGrants()
                }
            }

            if (confirmOverwrite && isNameTaken(path)) {
                val title = String.format(activity.getString(org.fossify.commons.R.string.file_already_exists_overwrite), path.getFilenameFromPath())
                ConfirmationDialog(activity, title) {
                    requestGrantsThenProceed()
                }
            } else {
                requestGrantsThenProceed()
            }
        }

        override fun overwriteMedium(activity: BaseSimpleActivity, path: String, localPath: String, onDone: (written: Boolean) -> Unit) {
            throw UnsupportedOperationException("a medium of the device is edited where it lies; there is nothing to write back")
        }

        // the file itself, once SAF has had its say about the folder
        override fun writeInto(activity: BaseSimpleActivity, path: String, write: (localTarget: String, wrote: (Boolean) -> Unit) -> Unit, onDone: (written: Boolean) -> Unit) {
            activity.handleSAFDialog(path) { granted ->
                if (!granted) {
                    return@handleSAFDialog
                }

                write(path) { wrote ->
                    activity.runOnUiThread { onDone(wrote) }
                }
            }
        }

        // turned where it lies; a JPEG only gets its Orientation tag turned
        override fun rotateMediumAndWait(activity: BaseSimpleActivity, path: String, degrees: Int) {
            activity.saveRotatedImageToFile(path, path, degrees, true) {}
        }

        override fun writingBackMessage(activity: BaseSimpleActivity): String =
            throw UnsupportedOperationException("nothing is written back to the device")

        override fun editNotWrittenMessage(activity: BaseSimpleActivity): String =
            throw UnsupportedOperationException("nothing is written back to the device")

        // the file is already here
        override fun fetchedCopy(path: String) = File(path)
        override fun fetchForReading(path: String) = path
        override fun fetchForEditing(path: String) = path
        override fun fetchingMessage(): String = throw UnsupportedOperationException("nothing is fetched from the device")
        override fun fetchFailedMessage(): String = throw UnsupportedOperationException("nothing is fetched from the device")

        override fun showProperties(activity: BaseSimpleActivity, media: List<Medium>) {
            if (media.size == 1) {
                PropertiesDialog(activity, media.first().path, context.config.shouldShowHidden)
            } else {
                PropertiesDialog(activity, media.map { it.path } as ArrayList<String>, context.config.shouldShowHidden)
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

        // the app's own bin on pCloud, a folder in the root, see PCLOUD_RECYCLE_BIN; the writer
        // moves in and out of it by file id and carries the rows along
        override fun isInRecycleBin(path: String) = path.isPCloudRecycleBinPath()

        override fun restoreDestinationOf(binPath: String) = PCloudWriter(context).restoreDestinationOf(binPath)

        override fun restoreFromBin(activity: BaseSimpleActivity, binPaths: List<String>, destinationFolder: String?, onDone: (restored: Boolean) -> Unit) {
            context.writeToPCloud(emptyList(), { restoreFromRecycleBin(binPaths, destinationFolder) }) { restored ->
                activity.runOnUiThread { onDone(restored) }
            }
        }

        // nothing in the bin means nothing to ask pCloud, and no account to ask it with
        override fun emptyBin(activity: BaseSimpleActivity, onDone: (emptied: Boolean) -> Unit) {
            ensureBackgroundThread {
                if (context.mediaDB.getPCloudDeletedMedia().isEmpty()) {
                    activity.runOnUiThread { onDone(true) }
                    return@ensureBackgroundThread
                }

                context.writeToPCloud(emptyList(), { emptyRecycleBin() }) { emptied ->
                    activity.runOnUiThread { onDone(emptied) }
                }
            }
        }

        override fun sweepBin(olderThan: Long) {
            if (!context.config.isPCloudLoggedIn) {
                return
            }

            val old = context.mediaDB.getOldPCloudRecycleBinItems(olderThan)
            if (old.isNotEmpty()) {
                PCloudWriter(context).deleteFromRecycleBin(old.map { it.path })
            }
        }

        override fun humanizedPath(path: String): String = path.toPCloudRemotePath()

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

        // what the cache knows of the account; a name pCloud has that the cache does not is
        // numbered by pCloud on upload, see the writer
        override fun isNameTaken(path: String) = context.pCloudItemsDB.getItem(path) != null

        // through the replace that keeps the medium whole the whole way; the folder is then
        // read again from pCloud, if the settings ask for that after a write
        override fun overwriteMedium(activity: BaseSimpleActivity, path: String, localPath: String, onDone: (written: Boolean) -> Unit) {
            context.writeToPCloud(listOf(path.getParentPath()), { overwriteFile(path, localPath) }) { written ->
                activity.runOnUiThread { onDone(written) }
            }
        }

        // a name that is free is an upload, and one the user has agreed to write over goes
        // through the replace; the folder is read again afterwards either way
        override fun writeInto(activity: BaseSimpleActivity, path: String, write: (localTarget: String, wrote: (Boolean) -> Unit) -> Unit, onDone: (written: Boolean) -> Unit) {
            ensureBackgroundThread {
                val staged = stagingFileFor(path)
                write(staged.absolutePath) { wrote ->
                    if (!wrote) {
                        activity.runOnUiThread { onDone(false) }
                        return@write
                    }

                    val folder = path.getParentPath()
                    val isTaken = isNameTaken(path)
                    context.writeToPCloud(listOf(folder), {
                        if (isTaken) {
                            overwriteFile(path, staged.absolutePath)
                        } else {
                            uploadFile(staged.absolutePath, folder)
                        }
                    }) { written ->
                        activity.runOnUiThread { onDone(written) }
                    }
                }
            }
        }

        // fetched into a copy of its own, turned there, and written back over itself: there
        // is no folder on the device to save it beside, and "save a copy somewhere else" is
        // what copying to this device is for. A JPEG only gets its Orientation tag turned,
        // which pCloud's own web and app honour
        override fun rotateMediumAndWait(activity: BaseSimpleActivity, path: String, degrees: Int) {
            val localPath = try {
                fetchForEditing(path)
            } catch (e: Exception) {
                Log.w("PCloudTransfer", "Could not fetch $path to rotate it", e)
                activity.toast("${fetchFailedMessage()}: ${e.message ?: e.javaClass.simpleName}")
                return
            }

            val latch = CountDownLatch(1)
            activity.saveRotatedImageToFile(localPath, localPath, degrees, true) {
                overwriteMedium(activity, path, localPath) { written ->
                    if (!written) {
                        activity.toast(activity.getString(R.string.remote_edit_kept_at, localPath), Toast.LENGTH_LONG)
                    }

                    latch.countDown()
                }
            }

            latch.await()
        }

        override fun writingBackMessage(activity: BaseSimpleActivity): String = activity.getString(R.string.pcloud_writing_back)
        override fun editNotWrittenMessage(activity: BaseSimpleActivity): String = activity.getString(R.string.pcloud_edit_not_written)

        // the cache names a copy by file id and content hash, which is what the link directory
        // is named after too, so a link's directory says whether its copy is still there
        override fun fetchedCopy(path: String): File = PCloudFileCache(context).fetch(path)

        override fun fetchForReading(path: String): String {
            val cache = PCloudFileCache(context)
            val workDir = File(context.cacheDir, PCLOUD_WORK_DIR)
            // the links of the older, share-only version of this lived here
            File(context.cacheDir, "pcloud-share").deleteRecursively()
            return linkUnderOwnName(cache.fetch(path), path, workDir) { cache.holds(it) }
        }

        override fun fetchForEditing(path: String) = copyForEditing(PCloudFileCache(context).fetch(path), path, File(context.cacheDir, PCLOUD_EDIT_DIR))
        override fun fetchingMessage(): String = context.getString(R.string.pcloud_fetching)
        override fun fetchFailedMessage(): String = context.getString(R.string.pcloud_fetch_failed)

        override fun showProperties(activity: BaseSimpleActivity, media: List<Medium>) {
            RemotePropertiesDialog(activity, media)
        }
    }

    class Smb(context: Context) : MediaStorage(context) {
        override fun holds(path: String) = path.isSmbPath()
        override val isRemote = true

        override val canRenameSeveral = false
        override val canFixDateTaken = false
        override val canOpenWith = true
        override val canSetAs = true
        override val canShare = true
        // rotating in place writes the medium back over itself, which is not built for the
        // share yet, see rotateMediumAndWait(). The fullscreen view's rotation is a Save as
        // instead, and goes through writeInto()
        override val canRotate = false
        override val canResize = true
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

        // the app's own bin on the share, a folder in the root that keeps the original layout,
        // see SMB_RECYCLE_BIN; the writer moves in and out of it with the rename request, no
        // bytes travelling, and carries the rows and the cached copies along
        override fun isInRecycleBin(path: String) = path.isSmbRecycleBinPath()

        override fun restoreDestinationOf(binPath: String) = SmbWriter(context).restoreDestinationOf(binPath)

        override fun restoreFromBin(activity: BaseSimpleActivity, binPaths: List<String>, destinationFolder: String?, onDone: (restored: Boolean) -> Unit) {
            context.writeToShare({ restoreFromRecycleBin(binPaths, destinationFolder) }) { restored ->
                activity.runOnUiThread { onDone(restored) }
            }
        }

        // nothing in the bin means nothing to ask the share, and no share to ask
        override fun emptyBin(activity: BaseSimpleActivity, onDone: (emptied: Boolean) -> Unit) {
            ensureBackgroundThread {
                if (context.mediaDB.getSmbDeletedMedia().isEmpty()) {
                    activity.runOnUiThread { onDone(true) }
                    return@ensureBackgroundThread
                }

                context.writeToShare({ emptyRecycleBin() }) { emptied ->
                    activity.runOnUiThread { onDone(emptied) }
                }
            }
        }

        // one small request per expired medium; a share that is off today is tried tomorrow
        override fun sweepBin(olderThan: Long) {
            if (!context.config.isSmbConfigured) {
                return
            }

            val old = context.mediaDB.getOldSmbRecycleBinItems(olderThan)
            if (old.isNotEmpty()) {
                SmbWriter(context).deleteFromRecycleBin(old.map { it.path })
            }
        }

        override fun humanizedPath(path: String): String = "/${path.toSmbRemotePath()}"

        override fun deleteMediaQuestion(activity: BaseSimpleActivity, media: List<Medium>, toBin: Boolean): String {
            val id = if (toBin) R.string.smb_move_to_recycle_bin_confirmation else R.string.smb_delete_confirmation
            return activity.getString(id, namesOf(activity, media.map { it.path }))
        }

        override fun deleteFoldersQuestion(activity: BaseSimpleActivity, paths: List<String>, toBin: Boolean): String {
            val id = if (toBin) R.string.smb_move_folder_to_recycle_bin_confirmation else R.string.smb_delete_folder_confirmation
            return activity.getString(id, namesOf(activity, paths))
        }

        override fun onceMayDeleteMedia(activity: BaseSimpleActivity, fileDirItems: List<FileDirItem>, then: () -> Unit) = then()
        override fun onceMayDeleteFolders(activity: BaseSimpleActivity, path: String, then: () -> Unit) = then()

        // into the app's bin on the share, for good from there, or for good from where it is;
        // the writer takes the rows with the files, and nothing is rescanned
        override fun deleteMedia(activity: BaseSimpleActivity, fileDirItems: ArrayList<FileDirItem>, skipRecycleBin: Boolean, onDone: (deleted: Boolean) -> Unit) {
            val paths = fileDirItems.map { it.path }
            val isInBin = isInRecycleBin(paths.first())
            val toBin = context.config.useRecycleBin && !skipRecycleBin && !isInBin
            val write: SmbWriter.() -> Unit = {
                when {
                    toBin -> moveToRecycleBin(paths)
                    isInBin -> deleteFromRecycleBin(paths)
                    else -> deleteFiles(paths)
                }
            }

            context.writeToShare(write) { deleted ->
                activity.runOnUiThread { onDone(deleted) }
            }
        }

        // nothing is rescanned afterwards: what the share still has of a folder that only half
        // went is put right by the next walk of it, and a walk of a whole share is minutes
        override fun deleteFolders(activity: BaseSimpleActivity, paths: List<String>, toRecycleBin: Boolean, onDone: () -> Unit) {
            val base = if (toRecycleBin) org.fossify.commons.R.plurals.moving_items_into_bin else org.fossify.commons.R.plurals.deleting_items
            activity.toast(activity.resources.getQuantityString(base, paths.size, paths.size))
            context.writeToShare({ deleteFolders(paths, toRecycleBin) }) {
                activity.runOnUiThread { onDone() }
            }
        }

        // asked of the share itself rather than of the cache: a share holds files this app
        // never scanned
        override fun isNameTaken(path: String) = SmbClient.fileExists(context, path)

        // through the stash and replace of SmbWriter.overwriteFile(), so what is on the share
        // afterwards is either the old medium or the new one. Nothing is rescanned, the writer
        // carries the rows and the cached copies along
        override fun overwriteMedium(activity: BaseSimpleActivity, path: String, localPath: String, onDone: (written: Boolean) -> Unit) {
            context.writeToShare({ overwriteFile(path, localPath) }) { written ->
                activity.runOnUiThread { onDone(written) }
            }
        }

        // A name that is free is a new file, streamed out with its modification time put back
        // on afterwards, and one the user has agreed to write over goes through the stash and
        // replace (#28). Which of the two it is, is asked of the share -- the same question
        // ensureWritable() asked a moment ago. A new file has the folder walked again
        // afterwards, which is the only way the cache learns of it; an overwrite carries its
        // own rows, so nothing is walked for it
        override fun writeInto(activity: BaseSimpleActivity, path: String, write: (localTarget: String, wrote: (Boolean) -> Unit) -> Unit, onDone: (written: Boolean) -> Unit) {
            ensureBackgroundThread {
                val staged = stagingFileFor(path)
                write(staged.absolutePath) { wrote ->
                    if (!wrote) {
                        activity.runOnUiThread { onDone(false) }
                        return@write
                    }

                    val taken = try {
                        isNameTaken(path)
                    } catch (e: Exception) {
                        Log.w("SmbWrite", "Could not ask the share whether it already has $path", e)
                        activity.runOnUiThread {
                            activity.showErrorToast(e)
                            onDone(false)
                        }
                        return@write
                    }

                    if (taken) {
                        overwriteMedium(activity, path, staged.absolutePath, onDone)
                        return@write
                    }

                    try {
                        SmbClient.create(context, path) { output -> staged.inputStream().use { it.copyTo(output) } }
                        SmbClient.setModified(context, path, staged.lastModified())
                    } catch (e: Exception) {
                        Log.w("SmbWrite", "Could not save $path onto the share", e)
                        activity.runOnUiThread {
                            activity.showErrorToast(e)
                            onDone(false)
                        }
                        return@write
                    }

                    context.rescanSmbFolders(listOf(path.getParentPath()), reportCounts = false, priority = RemoteScanScheduler.PRIORITY_TRANSFER) {
                        activity.runOnUiThread { onDone(true) }
                    }
                }
            }
        }

        // not offered, see canRotate
        override fun rotateMediumAndWait(activity: BaseSimpleActivity, path: String, degrees: Int) {
            throw UnsupportedOperationException("a medium of the share is not rotated in place, see canRotate")
        }

        override fun writingBackMessage(activity: BaseSimpleActivity): String = activity.getString(R.string.smb_writing_back)
        override fun editNotWrittenMessage(activity: BaseSimpleActivity): String = activity.getString(R.string.smb_edit_not_written)

        // SmbFileCache already holds a copy of everything the photo view has drawn, so a fetch
        // is usually a file copy rather than a download
        override fun fetchedCopy(path: String): File = SmbFileCache(context).fetch(path)

        // the cache names a copy by the path's hash, the size and the modification time the
        // scan saw, which is what the link directory is named after too, so a link's directory
        // says whether its copy is still there
        override fun fetchForReading(path: String): String {
            val cache = SmbFileCache(context)
            return linkUnderOwnName(cache.fetch(path), path, File(context.cacheDir, SMB_WORK_DIR)) { cache.holds(it) }
        }

        override fun fetchForEditing(path: String) = copyForEditing(SmbFileCache(context).fetch(path), path, File(context.cacheDir, SMB_EDIT_DIR))
        override fun fetchingMessage(): String = context.getString(R.string.smb_fetching)
        override fun fetchFailedMessage(): String = context.getString(R.string.smb_fetch_failed)

        override fun showProperties(activity: BaseSimpleActivity, media: List<Medium>) {
            RemotePropertiesDialog(activity, media)
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
