package org.fossify.gallery.extensions

import android.app.Activity
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Point
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.MediaStore.Files
import android.provider.MediaStore.Images
import android.provider.Settings
import android.system.Os
import android.util.DisplayMetrics
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.exifinterface.media.ExifInterface
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.squareup.picasso.Picasso
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.dialogs.SecurityDialog
import org.fossify.commons.extensions.*
import org.fossify.commons.extensions.getCurrentFormattedDateTime
import org.fossify.commons.extensions.internalStoragePath
import org.fossify.commons.helpers.*
import org.fossify.commons.models.FAQItem
import org.fossify.commons.models.FileDirItem
import org.fossify.gallery.BuildConfig
import org.fossify.gallery.R
import org.fossify.gallery.activities.MediaActivity
import org.fossify.gallery.activities.SettingsActivity
import org.fossify.gallery.activities.SimpleActivity
import org.fossify.gallery.activities.VideoPlayerActivity
import org.fossify.gallery.dialogs.AllFilesPermissionDialog
import org.fossify.gallery.dialogs.PickDirectoryDialog
import org.fossify.gallery.dialogs.ResizeMultipleImagesDialog
import org.fossify.gallery.dialogs.ResizeWithPathDialog
import org.fossify.gallery.helpers.DIRECTORY
import org.fossify.gallery.helpers.PCLOUD_EDIT_DIR
import org.fossify.gallery.helpers.PCLOUD_RESIZE_DIR
import org.fossify.gallery.helpers.PCLOUD_WORK_DIR
import org.fossify.gallery.helpers.PCloudFileCache
import org.fossify.gallery.helpers.RECYCLE_BIN
import org.fossify.gallery.helpers.TEMP_FOLDER_NAME
import org.fossify.gallery.jobs.PCloudTransferService
import org.fossify.gallery.jobs.SmbTransferService
import org.fossify.gallery.models.DateTaken
import java.io.*
import java.text.SimpleDateFormat
import java.util.Locale
import androidx.core.net.toUri

fun Activity.sharePath(path: String) {
    sharePathIntent(path, BuildConfig.APPLICATION_ID)
}

fun Activity.sharePaths(paths: ArrayList<String>) {
    sharePathsIntent(paths, BuildConfig.APPLICATION_ID)
}

fun Activity.shareMediumPath(path: String) {
    withLocalMediaFile(path) { localPath ->
        sharePath(localPath)
    }
}

fun Activity.shareMediaPaths(paths: ArrayList<String>) {
    withLocalMediaFiles(paths) { localPaths ->
        if (localPaths.size == 1) {
            sharePath(localPaths.first())
        } else {
            sharePaths(localPaths)
        }
    }
}

// Most of what the app does with a medium wants a real file on the device: sharing it,
// printing it, setting it as the wallpaper, opening it in another app, editing it. A pCloud
// medium has none, so it is fetched into the device cache first (once, the fullscreen view's
// copy is reused) and handed over under its own name. A local path is passed straight
// through, so a caller never has to know which storage the medium is on.
//
// The name matters: the cached copy is named by file id and content hash, and whatever
// receives the file shows the name it is given, so what is handed over is a hard link to the
// copy under the medium's own name (a symlink would not do, the provider resolves it to the
// copy's name); where a link cannot be made, a plain copy.
//
// Fetching runs off the main thread with a toast, and a failure is toasted instead of acted
// on. The callback runs on the main thread, on a local path that exists.
fun Activity.withLocalMediaFile(path: String, callback: (localPath: String) -> Unit) {
    withLocalMediaFiles(arrayListOf(path)) { localPaths ->
        callback(localPaths.first())
    }
}

fun Activity.withLocalMediaFiles(paths: List<String>, callback: (localPaths: ArrayList<String>) -> Unit) {
    if (paths.none { it.isPCloudPath() }) {
        callback(ArrayList(paths))
        return
    }

    toast(R.string.pcloud_fetching)
    ensureBackgroundThread {
        val localPaths = try {
            fetchPCloudMediaAsFiles(paths)
        } catch (e: Exception) {
            // the reason goes to the log and onto the toast, a bare "could not fetch" left
            // nothing to go on when it happened once on the device
            Log.w("PCloudFetch", "Could not fetch ${paths.size} pCloud media", e)
            toast("${getString(R.string.pcloud_fetch_failed)}: ${e.message ?: e.javaClass.simpleName}")
            return@ensureBackgroundThread
        }

        runOnUiThread {
            callback(localPaths)
        }
    }
}

// The same for an action that is going to write to the file it is given, the editor above
// all. It gets a copy of its own rather than the link the reading actions get: a link shares
// its bytes with the cached copy, so an edit written over it would make the cache serve the
// edited image for the original even when the write back to pCloud never lands. Only one
// edit is in flight at a time, so the previous copy goes first
fun Activity.withEditableMediaFile(path: String, callback: (localPath: String) -> Unit) {
    if (!path.isPCloudPath()) {
        callback(path)
        return
    }

    toast(R.string.pcloud_fetching)
    ensureBackgroundThread {
        val copy = try {
            fetchPCloudMediumForEditing(path)
        } catch (e: Exception) {
            Log.w("PCloudFetch", "Could not fetch $path for editing", e)
            toast("${getString(R.string.pcloud_fetch_failed)}: ${e.message ?: e.javaClass.simpleName}")
            return@ensureBackgroundThread
        }

        runOnUiThread {
            callback(copy)
        }
    }
}

// The copy itself, for a caller that is already off the main thread and wants to wait for it.
// Talks to the network and throws what goes wrong
fun Activity.fetchPCloudMediumForEditing(path: String): String {
    val cached = PCloudFileCache(this).fetch(path)
    val editDir = File(cacheDir, PCLOUD_EDIT_DIR)
    editDir.deleteRecursively()
    editDir.mkdirs()
    return File(editDir, path.getFilenameFromPath()).also { cached.copyTo(it, overwrite = true) }.absolutePath
}

// Talks to the network, so it belongs off the main thread
private fun Activity.fetchPCloudMediaAsFiles(paths: List<String>): ArrayList<String> {
    val cache = PCloudFileCache(this)
    val workDir = File(cacheDir, PCLOUD_WORK_DIR)
    val localPaths = ArrayList<String>()
    paths.forEach { path ->
        if (!path.isPCloudPath()) {
            localPaths.add(path)
            return@forEach
        }

        val cached = cache.fetch(path)
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

        localPaths.add(link.absolutePath)
    }

    dropOrphanedPCloudLinks(workDir, cache)
    return localPaths
}

// A link keeps the bytes of its cache copy alive after the cache has trimmed the copy away,
// so every link whose copy is gone is dropped whenever a new one is made. The links in use
// are kept: an editor may still be holding one
private fun Activity.dropOrphanedPCloudLinks(workDir: File, cache: PCloudFileCache) {
    // the links of the older, share-only version of this lived here
    File(cacheDir, "pcloud-share").deleteRecursively()
    workDir.listFiles()?.forEach { linkDir ->
        if (linkDir.isDirectory && !cache.holds(linkDir.name)) {
            linkDir.deleteRecursively()
        }
    }
}

fun Activity.setAs(path: String) {
    setAsIntent(path, BuildConfig.APPLICATION_ID)
}

fun Activity.openPath(path: String, forceChooser: Boolean, extras: HashMap<String, Boolean> = HashMap()) {
    openPathIntent(path, forceChooser, BuildConfig.APPLICATION_ID, extras = extras)
}

fun Activity.launchGesturePlayer(path: String, extras: HashMap<String, Boolean> = HashMap()) {
    ensureBackgroundThread {
        val newUri = getFinalUriFromPath(path, BuildConfig.APPLICATION_ID)
        if (newUri == null) {
            toast(org.fossify.commons.R.string.unknown_error_occurred)
            return@ensureBackgroundThread
        }

        val mimeType = getUriMimeType(path, newUri)
        runOnUiThread {
            Intent(applicationContext, VideoPlayerActivity::class.java).apply {
                setDataAndType(newUri, mimeType)
                for ((key, value) in extras) putExtra(key, value)
                startActivity(this)
            }
        }
    }
}

fun Activity.openEditor(path: String, forceChooser: Boolean = false) {
    val newPath = path.removePrefix("file://")
    openEditorIntent(newPath, forceChooser, BuildConfig.APPLICATION_ID)
}

fun Activity.launchCamera() {
    val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
    launchActivityIntent(intent)
}

fun SimpleActivity.launchSettings() {
    hideKeyboard()
    startActivity(Intent(applicationContext, SettingsActivity::class.java))
}

fun SimpleActivity.launchAbout() {
    val licenses = LICENSE_GLIDE or LICENSE_CROPPER or LICENSE_RTL or LICENSE_SUBSAMPLING or LICENSE_PATTERN or LICENSE_REPRINT or LICENSE_GIF_DRAWABLE or
        LICENSE_PICASSO or LICENSE_EXOPLAYER or LICENSE_SANSELAN or LICENSE_FILTERS or LICENSE_GESTURE_VIEWS or LICENSE_APNG

    val faqItems = arrayListOf(
        FAQItem(R.string.faq_3_title, R.string.faq_3_text),
        FAQItem(R.string.faq_12_title, R.string.faq_12_text),
        FAQItem(R.string.faq_7_title, R.string.faq_7_text),
        FAQItem(R.string.faq_14_title, R.string.faq_14_text),
        FAQItem(R.string.faq_1_title, R.string.faq_1_text),
        FAQItem(org.fossify.commons.R.string.faq_5_title_commons, org.fossify.commons.R.string.faq_5_text_commons),
        FAQItem(R.string.faq_5_title, R.string.faq_5_text),
        FAQItem(R.string.faq_4_title, R.string.faq_4_text),
        FAQItem(R.string.faq_6_title, R.string.faq_6_text),
        FAQItem(R.string.faq_8_title, R.string.faq_8_text),
        FAQItem(R.string.faq_10_title, R.string.faq_10_text),
        FAQItem(R.string.faq_11_title, R.string.faq_11_text),
        FAQItem(R.string.faq_13_title, R.string.faq_13_text),
        FAQItem(R.string.faq_15_title, R.string.faq_15_text),
        FAQItem(R.string.faq_2_title, R.string.faq_2_text),
        FAQItem(R.string.faq_18_title, R.string.faq_18_text),
        FAQItem(org.fossify.commons.R.string.faq_9_title_commons, org.fossify.commons.R.string.faq_9_text_commons),
    )

    if (!resources.getBoolean(org.fossify.commons.R.bool.hide_google_relations)) {
        faqItems.add(FAQItem(org.fossify.commons.R.string.faq_2_title_commons, org.fossify.commons.R.string.faq_2_text_commons))
        faqItems.add(FAQItem(org.fossify.commons.R.string.faq_6_title_commons, org.fossify.commons.R.string.faq_6_text_commons))
        faqItems.add(FAQItem(org.fossify.commons.R.string.faq_7_title_commons, org.fossify.commons.R.string.faq_7_text_commons))
        faqItems.add(FAQItem(org.fossify.commons.R.string.faq_10_title_commons, org.fossify.commons.R.string.faq_10_text_commons))
    }

    if (isRPlus() && !isExternalStorageManager()) {
        faqItems.add(0, FAQItem(R.string.faq_16_title, "${getString(R.string.faq_16_text)} ${getString(R.string.faq_16_text_extra)}"))
        faqItems.add(1, FAQItem(R.string.faq_17_title, R.string.faq_17_text))
        faqItems.removeIf { it.text == R.string.faq_7_text }
        faqItems.removeIf { it.text == R.string.faq_14_text }
        faqItems.removeIf { it.text == R.string.faq_8_text }
    }

    startAboutActivity(R.string.app_name, licenses, BuildConfig.VERSION_NAME, faqItems, true)
}

fun BaseSimpleActivity.handleMediaManagementPrompt(callback: () -> Unit) {
    if (canManageMedia() || isExternalStorageManager()) {
        callback()
    } else if (isRPlus() && resources.getBoolean(R.bool.require_all_files_access) && !config.avoidShowingAllFilesPrompt) {
        if (Environment.isExternalStorageManager()) {
            callback()
        } else {
            var messagePrompt = getString(org.fossify.commons.R.string.access_storage_prompt)
            messagePrompt += if (isSPlus()) {
                "\n\n${getString(R.string.media_management_alternative)}"
            } else {
                "\n\n${getString(R.string.alternative_media_access)}"
            }

            AllFilesPermissionDialog(this, messagePrompt, callback = { success ->
                if (success) {
                    launchGrantAllFilesIntent()
                }
            }, neutralPressed = {
                if (isSPlus()) {
                    launchMediaManagementIntent(callback)
                } else {
                    config.avoidShowingAllFilesPrompt = true
                }
            })
        }
    } else {
        callback()
    }
}

fun BaseSimpleActivity.launchGrantAllFilesIntent() {
    try {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
        intent.addCategory("android.intent.category.DEFAULT")
        intent.data = "package:$packageName".toUri()
        startActivity(intent)
    } catch (e: Exception) {
        val intent = Intent()
        intent.action = Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
        try {
            startActivity(intent)
        } catch (e: Exception) {
            showErrorToast(e)
        }
    }
}

fun AppCompatActivity.showSystemUI() {
    window.showBars()
}

fun AppCompatActivity.hideSystemUI() {
    window.hideBars(transient = false)
}

fun BaseSimpleActivity.addNoMedia(path: String, callback: () -> Unit) {
    val file = File(path, NOMEDIA)
    if (getDoesFilePathExist(file.absolutePath)) {
        callback()
        return
    }

    if (needsStupidWritePermissions(path)) {
        handleSAFDialog(file.absolutePath) {
            if (!it) {
                return@handleSAFDialog
            }

            val fileDocument = getDocumentFile(path)
            if (fileDocument?.exists() == true && fileDocument.isDirectory) {
                fileDocument.createFile("", NOMEDIA)
                addNoMediaIntoMediaStore(file.absolutePath)
                callback()
            } else {
                toast(org.fossify.commons.R.string.unknown_error_occurred)
                callback()
            }
        }
    } else {
        try {
            if (file.createNewFile()) {
                ensureBackgroundThread {
                    addNoMediaIntoMediaStore(file.absolutePath)
                }
            } else {
                toast(org.fossify.commons.R.string.unknown_error_occurred)
            }
        } catch (e: Exception) {
            showErrorToast(e)
        }
        callback()
    }
}

fun BaseSimpleActivity.addNoMediaIntoMediaStore(path: String) {
    try {
        val content = ContentValues().apply {
            put(Files.FileColumns.TITLE, NOMEDIA)
            put(Files.FileColumns.DATA, path)
            put(Files.FileColumns.MEDIA_TYPE, Files.FileColumns.MEDIA_TYPE_NONE)
        }
        contentResolver.insert(Files.getContentUri("external"), content)
    } catch (e: Exception) {
        showErrorToast(e)
    }
}

fun BaseSimpleActivity.removeNoMedia(path: String, callback: (() -> Unit)? = null) {
    val file = File(path, NOMEDIA)
    if (!getDoesFilePathExist(file.absolutePath)) {
        callback?.invoke()
        return
    }

    tryDeleteFileDirItem(file.toFileDirItem(applicationContext), false, false) {
        callback?.invoke()
        deleteFromMediaStore(file.absolutePath) { needsRescan ->
            if (needsRescan) {
                rescanAndDeletePath(path) {
                    rescanFolderMedia(path)
                }
            } else {
                rescanFolderMedia(path)
            }
        }
    }
}

fun BaseSimpleActivity.toggleFileVisibility(oldPath: String, hide: Boolean, callback: ((newPath: String) -> Unit)? = null) {
    val path = oldPath.getParentPath()
    var filename = oldPath.getFilenameFromPath()
    if ((hide && filename.startsWith('.')) || (!hide && !filename.startsWith('.'))) {
        callback?.invoke(oldPath)
        return
    }

    filename = if (hide) {
        ".${filename.trimStart('.')}"
    } else {
        filename.substring(1, filename.length)
    }

    val newPath = "$path/$filename"
    renameFile(oldPath, newPath, false) { success, useAndroid30Way ->
        runOnUiThread {
            callback?.invoke(newPath)
        }

        ensureBackgroundThread {
            updateDBMediaPath(oldPath, newPath)
        }
    }
}

// [localDestinationOnly] keeps pCloud out of the picker, for a caller whose files can only go
// to the device. [onCancelled] fires when the picker is left without a destination, and
// [onPCloudTransferQueued] once a pCloud destination's transfer is with the service, both for
// a caller that has nothing else on screen
fun BaseSimpleActivity.tryCopyMoveFilesTo(
    fileDirItems: ArrayList<FileDirItem>,
    isCopyOperation: Boolean,
    localDestinationOnly: Boolean = false,
    onCancelled: (() -> Unit)? = null,
    onPCloudTransferQueued: (() -> Unit)? = null,
    callback: (destinationPath: String) -> Unit
) {
    if (fileDirItems.isEmpty()) {
        toast(org.fossify.commons.R.string.unknown_error_occurred)
        return
    }

    val source = fileDirItems[0].getParentPath()
    PickDirectoryDialog(
        activity = this,
        sourcePath = source,
        showOtherFolderButton = true,
        showFavoritesBin = false,
        isPickingCopyMoveDestination = true,
        isPickingFolderForWidget = false,
        navigateGroups = true,
        localDestinationOnly = localDestinationOnly,
        isCopyOperation = isCopyOperation,
        onCancelled = onCancelled
    ) {
        copyMoveFilesToPickedDestination(fileDirItems, source, it, isCopyOperation, onPCloudTransferQueued, callback)
    }
}

// Copies or moves the files to an already picked real folder, asking for SAF access if
// needed. With a remote storage on either side the transfer goes to a service instead and the
// callback never fires: nothing has moved when this returns, the screens learn of the end
// through the service's listeners
fun BaseSimpleActivity.copyMoveFilesToPickedDestination(
    fileDirItems: ArrayList<FileDirItem>,
    source: String,
    destination: String,
    isCopyOperation: Boolean,
    onPCloudTransferQueued: (() -> Unit)? = null,
    callback: (destinationPath: String) -> Unit
) {
    // The share is copied off and copied onto, and that is all it does so far (#28). A move
    // would have to delete the side it came from once the copy landed, which is not built in
    // either direction; a copy onto the share starts from a file on the device, because
    // anything already remote would have to be staged first. The picker turns both away and
    // the menus offer no move for the share's media; this is the backstop for all of it
    if (source.isSmbPath() || destination.isSmbPath()) {
        if (!isCopyOperation) {
            toast(R.string.smb_no_move_yet, Toast.LENGTH_LONG)
            return
        }

        if (destination.isSmbPath() && source.isRemotePath()) {
            toast(R.string.smb_no_remote_copy_to_share, Toast.LENGTH_LONG)
            return
        }

        startSmbCopy(fileDirItems, destination, onPCloudTransferQueued)
        return
    }

    if (source.isPCloudPath() || destination.isPCloudPath()) {
        startPCloudTransfer(fileDirItems, source, destination, isCopyOperation, onPCloudTransferQueued)
        return
    }

    handleSAFDialog(source) {
        if (it) {
            copyMoveFilesTo(fileDirItems, source.trimEnd('/'), destination, isCopyOperation, true, config.shouldShowHidden, callback)
        }
    }
}

// Queues the transfer once the storage permissions the local side needs are in: a move away
// from the device deletes the sources afterwards, a download writes into the destination.
// The notification permission is asked for so that the progress can be seen; the transfer
// runs without it too.
//
// [onQueued] fires once the job is with the service, which is several dialogs later than this
// returns. A caller whose only business was this transfer waits for it rather than for the
// copy callback, which never comes for a pCloud transfer
fun BaseSimpleActivity.startPCloudTransfer(
    fileDirItems: ArrayList<FileDirItem>,
    source: String,
    destination: String,
    isCopyOperation: Boolean,
    onQueued: (() -> Unit)? = null
) {
    if (!config.isPCloudLoggedIn) {
        toast(R.string.pcloud_log_in_required)
        return
    }

    val paths = fileDirItems.map { it.path }
    if (paths.isEmpty()) {
        return
    }

    val kind = when {
        source.isPCloudPath() && destination.isPCloudPath() -> PCloudTransferService.Kind.WITHIN_PCLOUD
        destination.isPCloudPath() -> PCloudTransferService.Kind.UPLOAD
        else -> PCloudTransferService.Kind.DOWNLOAD
    }

    val enqueue = {
        handleNotificationPermission {
            // a transfer into the temporary folder tile turns it into a real folder, the way
            // a local copy or move drops the tile; the service rebuilds the folder's row
            if (destination == config.tempFolderPath) {
                config.tempFolderPath = ""
            }

            PCloudTransferService.enqueue(this, PCloudTransferService.Job(kind, paths, destination, isCopyOperation))
            toast(R.string.pcloud_transfer_started)
            onQueued?.invoke()
        }
    }

    when (kind) {
        PCloudTransferService.Kind.UPLOAD -> if (isCopyOperation) {
            enqueue()
        } else {
            handleSAFDialog(source) { granted ->
                if (granted) {
                    checkManageMediaOrHandleSAFDialogSdk30(paths.first()) { allowed ->
                        if (allowed) {
                            enqueue()
                        }
                    }
                }
            }
        }

        PCloudTransferService.Kind.DOWNLOAD -> handleSAFDialog(destination) { granted ->
            if (granted) {
                handleSAFDialogSdk30(destination) { allowed ->
                    if (allowed) {
                        enqueue()
                    }
                }
            }
        }

        PCloudTransferService.Kind.WITHIN_PCLOUD -> enqueue()
    }
}

// Queues a copy off the share, or onto it, once the permissions the destination needs are in:
// a folder on the device is written into, a folder on pCloud wants an account, and a folder on
// the share wants neither. The notification permission is asked for so that the progress can be
// seen; the copy runs without it too.
//
// [onQueued] fires once the job is with the service, which is several dialogs later than this
// returns. A caller whose only business was this copy waits for it rather than for the copy
// callback, which never comes for a transfer
fun BaseSimpleActivity.startSmbCopy(
    fileDirItems: ArrayList<FileDirItem>,
    destination: String,
    onQueued: (() -> Unit)? = null
) {
    if (!config.isSmbConfigured) {
        toast(R.string.smb_not_configured)
        return
    }

    val paths = fileDirItems.map { it.path }
    if (paths.isEmpty()) {
        return
    }

    val kind = when {
        destination.isSmbPath() -> SmbTransferService.Kind.FROM_DEVICE
        destination.isPCloudPath() -> SmbTransferService.Kind.TO_PCLOUD
        else -> SmbTransferService.Kind.TO_DEVICE
    }

    if (kind == SmbTransferService.Kind.TO_PCLOUD && !config.isPCloudLoggedIn) {
        toast(R.string.pcloud_log_in_required)
        return
    }

    val enqueue = {
        handleNotificationPermission {
            // a copy into the temporary folder tile turns it into a real folder, the way a
            // local copy drops the tile; the service rebuilds the folder's row
            if (destination == config.tempFolderPath) {
                config.tempFolderPath = ""
            }

            SmbTransferService.enqueue(this, SmbTransferService.Job(kind, paths, destination))
            toast(R.string.smb_transfer_started)
            onQueued?.invoke()
        }
    }

    when (kind) {
        // neither destination is a folder of the device, so there is no SAF access to ask for;
        // what a copy onto the share reads is media the app already lists
        SmbTransferService.Kind.TO_PCLOUD, SmbTransferService.Kind.FROM_DEVICE -> enqueue()
        SmbTransferService.Kind.TO_DEVICE -> handleSAFDialog(destination) { granted ->
            if (granted) {
                handleSAFDialogSdk30(destination) { allowed ->
                    if (allowed) {
                        enqueue()
                    }
                }
            }
        }
    }
}

fun BaseSimpleActivity.tryDeleteFileDirItem(
    fileDirItem: FileDirItem, allowDeleteFolder: Boolean = false, deleteFromDatabase: Boolean,
    callback: ((wasSuccess: Boolean) -> Unit)? = null
) {
    deleteFile(fileDirItem, allowDeleteFolder, isDeletingMultipleFiles = false) {
        if (deleteFromDatabase) {
            ensureBackgroundThread {
                deleteDBPath(fileDirItem.path)
                runOnUiThread {
                    callback?.invoke(it)
                }
            }
        } else {
            callback?.invoke(it)
        }
    }
}

fun BaseSimpleActivity.movePathsInRecycleBin(paths: ArrayList<String>, callback: ((wasSuccess: Boolean) -> Unit)?) {
    ensureBackgroundThread {
        var pathsCnt = paths.size
        val OTGPath = config.OTGPath

        for (source in paths) {
            if (OTGPath.isNotEmpty() && source.startsWith(OTGPath)) {
                var inputStream: InputStream? = null
                var out: OutputStream? = null
                try {
                    val destination = "$recycleBinPath/$source"
                    val fileDocument = getSomeDocumentFile(source)
                    inputStream = applicationContext.contentResolver.openInputStream(fileDocument?.uri!!)
                    out = getFileOutputStreamSync(destination, source.getMimeType())

                    var copiedSize = 0L
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var bytes = inputStream!!.read(buffer)
                    while (bytes >= 0) {
                        out!!.write(buffer, 0, bytes)
                        copiedSize += bytes
                        bytes = inputStream.read(buffer)
                    }

                    out?.flush()

                    if (fileDocument.getItemSize(true) == copiedSize && getDoesFilePathExist(destination)) {
                        mediaDB.updateDeleted("$RECYCLE_BIN$source", System.currentTimeMillis(), source)
                        pathsCnt--
                    }
                } catch (e: Exception) {
                    showErrorToast(e)
                    return@ensureBackgroundThread
                } finally {
                    inputStream?.close()
                    out?.close()
                }
            } else {
                val file = File(source)
                val internalFile = File(recycleBinPath, source)
                val lastModified = file.lastModified()
                try {
                    if (file.copyRecursively(internalFile, true)) {
                        mediaDB.updateDeleted("$RECYCLE_BIN$source", System.currentTimeMillis(), source)
                        pathsCnt--

                        if (config.keepLastModified && lastModified != 0L) {
                            internalFile.setLastModified(lastModified)
                        }
                    }
                } catch (e: Exception) {
                    showErrorToast(e)
                    return@ensureBackgroundThread
                }
            }
        }
        callback?.invoke(pathsCnt == 0)
    }
}

fun BaseSimpleActivity.restoreRecycleBinPath(path: String, callback: () -> Unit) {
    restoreRecycleBinPaths(arrayListOf(path), callback)
}

fun BaseSimpleActivity.restoreRecycleBinPaths(paths: ArrayList<String>, callback: () -> Unit) {
    ensureBackgroundThread {
        val newPaths = ArrayList<String>()
        var shownRestoringToPictures = false
        for (source in paths) {
            var destination = source.removePrefix(recycleBinPath)

            val destinationParent = destination.getParentPath()
            if (isRestrictedWithSAFSdk30(destinationParent) && !isInDownloadDir(destinationParent)) {
                // if the file is not writeable on SDK30+, change it to Pictures
                val picturesDirectory = getPicturesDirectoryPath(destination)
                destination = File(picturesDirectory, destination.getFilenameFromPath()).path
                if (!shownRestoringToPictures) {
                    toast(getString(R.string.restore_to_path, humanizePath(picturesDirectory)))
                    shownRestoringToPictures = true
                }
            }

            val lastModified = File(source).lastModified()

            val isShowingSAF = handleSAFDialog(destination) {}
            if (isShowingSAF) {
                return@ensureBackgroundThread
            }

            val isShowingSAFSdk30 = handleSAFDialogSdk30(destination) {}
            if (isShowingSAFSdk30) {
                return@ensureBackgroundThread
            }

            if (getDoesFilePathExist(destination)) {
                val newFile = getAlternativeFile(File(destination))
                destination = newFile.path
            }

            var inputStream: InputStream? = null
            var out: OutputStream? = null
            try {
                out = getFileOutputStreamSync(destination, source.getMimeType())
                inputStream = getFileInputStreamSync(source)

                var copiedSize = 0L
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var bytes = inputStream!!.read(buffer)
                while (bytes >= 0) {
                    out!!.write(buffer, 0, bytes)
                    copiedSize += bytes
                    bytes = inputStream.read(buffer)
                }

                out?.flush()

                if (File(source).length() == copiedSize) {
                    mediaDB.updateDeleted(destination.removePrefix(recycleBinPath), 0, "$RECYCLE_BIN${source.removePrefix(recycleBinPath)}")
                }
                newPaths.add(destination)

                if (config.keepLastModified && lastModified != 0L) {
                    File(destination).setLastModified(lastModified)
                }
            } catch (e: Exception) {
                showErrorToast(e)
            } finally {
                inputStream?.close()
                out?.close()
            }
        }

        runOnUiThread {
            callback()
        }

        rescanPaths(newPaths) {
            fixDateTaken(newPaths, false)
        }
    }
}

fun BaseSimpleActivity.emptyTheRecycleBin(callback: (() -> Unit)? = null) {
    ensureBackgroundThread {
        try {
            recycleBin.deleteRecursively()
            mediaDB.clearRecycleBin()
            directoryDB.deleteRecycleBin()
            toast(org.fossify.commons.R.string.recycle_bin_emptied)
            callback?.invoke()
        } catch (e: Exception) {
            toast(org.fossify.commons.R.string.unknown_error_occurred)
        }
    }
}

fun BaseSimpleActivity.emptyAndDisableTheRecycleBin(callback: () -> Unit) {
    ensureBackgroundThread {
        emptyTheRecycleBin {
            config.useRecycleBin = false
            callback()
        }
    }
}

fun BaseSimpleActivity.showRecycleBinEmptyingDialog(callback: () -> Unit) {
    ConfirmationDialog(
        this,
        "",
        org.fossify.commons.R.string.empty_recycle_bin_confirmation,
        org.fossify.commons.R.string.yes,
        org.fossify.commons.R.string.no
    ) {
        callback()
    }
}

fun BaseSimpleActivity.showRestoreConfirmationDialog(count: Int, callback: () -> Unit) {
    ConfirmationDialog(
        activity = this,
        message = resources.getQuantityString(R.plurals.restore_confirmation, count, count),
        positive = org.fossify.commons.R.string.yes,
        negative = org.fossify.commons.R.string.no
    ) {
        callback()
    }
}

fun BaseSimpleActivity.updateFavoritePaths(fileDirItems: ArrayList<FileDirItem>, destination: String) {
    ensureBackgroundThread {
        fileDirItems.forEach {
            val newPath = "$destination/${it.name}"
            updateDBMediaPath(it.path, newPath)
        }
    }
}

fun Activity.hasNavBar(): Boolean {
    val display = windowManager.defaultDisplay

    val realDisplayMetrics = DisplayMetrics()
    display.getRealMetrics(realDisplayMetrics)

    val displayMetrics = DisplayMetrics()
    display.getMetrics(displayMetrics)

    return (realDisplayMetrics.widthPixels - displayMetrics.widthPixels > 0) || (realDisplayMetrics.heightPixels - displayMetrics.heightPixels > 0)
}

fun AppCompatActivity.fixDateTaken(
    paths: ArrayList<String>,
    showToasts: Boolean,
    hasRescanned: Boolean = false,
    callback: (() -> Unit)? = null
) {
    val BATCH_SIZE = 50
    if (showToasts && !hasRescanned) {
        toast(R.string.fixing)
    }

    val pathsToRescan = ArrayList<String>()
    try {
        var didUpdateFile = false
        val operations = ArrayList<ContentProviderOperation>()

        ensureBackgroundThread {
            val dateTakens = ArrayList<DateTaken>()

            for (path in paths) {
                try {
                    val dateTime: String = ExifInterface(path).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                        ?: ExifInterface(path).getAttribute(ExifInterface.TAG_DATETIME) ?: continue

                    // some formats contain a "T" in the middle, some don't
                    // sample dates: 2015-07-26T14:55:23, 2018:09:05 15:09:05
                    val t = if (dateTime.substring(10, 11) == "T") "\'T\'" else " "
                    val separator = dateTime.substring(4, 5)
                    val format = "yyyy${separator}MM${separator}dd${t}kk:mm:ss"
                    val formatter = SimpleDateFormat(format, Locale.getDefault())
                    val timestamp = formatter.parse(dateTime).time

                    val uri = getFileUri(path)
                    ContentProviderOperation.newUpdate(uri).apply {
                        val selection = "${Images.Media.DATA} = ?"
                        val selectionArgs = arrayOf(path)
                        withSelection(selection, selectionArgs)
                        withValue(Images.Media.DATE_TAKEN, timestamp)
                        operations.add(build())
                    }

                    if (operations.size % BATCH_SIZE == 0) {
                        contentResolver.applyBatch(MediaStore.AUTHORITY, operations)
                        operations.clear()
                    }

                    mediaDB.updateFavoriteDateTaken(path, timestamp)
                    didUpdateFile = true

                    val dateTaken = DateTaken(
                        null,
                        path,
                        path.getFilenameFromPath(),
                        path.getParentPath(),
                        timestamp,
                        (System.currentTimeMillis() / 1000).toInt(),
                        File(path).lastModified()
                    )
                    dateTakens.add(dateTaken)
                    if (!hasRescanned && getFileDateTaken(path) == 0L) {
                        pathsToRescan.add(path)
                    }
                } catch (e: Exception) {
                    continue
                }
            }

            if (!didUpdateFile) {
                if (showToasts) {
                    toast(R.string.no_date_takens_found)
                }

                runOnUiThread {
                    callback?.invoke()
                }
                return@ensureBackgroundThread
            }

            val resultSize = contentResolver.applyBatch(MediaStore.AUTHORITY, operations).size
            if (resultSize == 0) {
                didUpdateFile = false
            }

            if (hasRescanned || pathsToRescan.isEmpty()) {
                if (dateTakens.isNotEmpty()) {
                    dateTakensDB.insertAll(dateTakens)
                }

                runOnUiThread {
                    if (showToasts) {
                        toast(if (didUpdateFile) R.string.dates_fixed_successfully else org.fossify.commons.R.string.unknown_error_occurred)
                    }

                    callback?.invoke()
                }
            } else {
                rescanPaths(pathsToRescan) {
                    fixDateTaken(paths, showToasts, true, callback)
                }
            }
        }
    } catch (e: Exception) {
        if (showToasts) {
            showErrorToast(e)
        }
    }
}

fun BaseSimpleActivity.saveRotatedImageToFile(oldPath: String, newPath: String, degrees: Int, showToasts: Boolean, callback: () -> Unit) {
    var newDegrees = degrees
    if (newDegrees < 0) {
        newDegrees += 360
    }

    if (oldPath == newPath && oldPath.isJpg()) {
        if (tryRotateByExif(oldPath, newDegrees, showToasts, callback)) {
            return
        }
    }

    val tmpPath = "$recycleBinPath/.tmp_${newPath.getFilenameFromPath()}"
    val tmpFileDirItem = FileDirItem(tmpPath, tmpPath.getFilenameFromPath())
    try {
        getFileOutputStream(tmpFileDirItem) {
            if (it == null) {
                if (showToasts) {
                    toast(org.fossify.commons.R.string.unknown_error_occurred)
                }
                return@getFileOutputStream
            }

            val oldLastModified = File(oldPath).lastModified()
            if (oldPath.isJpg()) {
                copyFile(oldPath, tmpPath)
                saveExifRotation(ExifInterface(tmpPath), newDegrees)
            } else {
                val inputstream = getFileInputStreamSync(oldPath)
                val bitmap = BitmapFactory.decodeStream(inputstream)
                saveFile(tmpPath, bitmap, it as FileOutputStream, newDegrees)
            }

            copyFile(tmpPath, newPath)
            rescanPaths(arrayListOf(newPath))
            fileRotatedSuccessfully(newPath, oldLastModified)

            it.flush()
            it.close()
            callback.invoke()
        }
    } catch (e: OutOfMemoryError) {
        if (showToasts) {
            toast(org.fossify.commons.R.string.out_of_memory_error)
        }
    } catch (e: Exception) {
        if (showToasts) {
            showErrorToast(e)
        }
    } finally {
        tryDeleteFileDirItem(tmpFileDirItem, false, true)
    }
}

fun Activity.tryRotateByExif(path: String, degrees: Int, showToasts: Boolean, callback: () -> Unit): Boolean {
    return try {
        val file = File(path)
        val oldLastModified = file.lastModified()
        if (saveImageRotation(path, degrees)) {
            fileRotatedSuccessfully(path, oldLastModified)
            callback.invoke()
            if (showToasts) {
                toast(org.fossify.commons.R.string.file_saved)
            }
            true
        } else {
            false
        }
    } catch (e: Exception) {
        // lets not show IOExceptions, rotating is saved just fine even with them
        if (showToasts && e !is IOException) {
            showErrorToast(e)
        }
        false
    }
}

fun Activity.fileRotatedSuccessfully(path: String, lastModified: Long) {
    if (config.keepLastModified && lastModified != 0L) {
        File(path).setLastModified(lastModified)
        updateLastModified(path, lastModified)
    }

    Picasso.get().invalidate(path.getFileKey(lastModified))
    // we cannot refresh a specific image in Glide Cache, so just clear it all
    val glide = Glide.get(applicationContext)
    glide.clearDiskCache()
    runOnUiThread {
        glide.clearMemory()
    }
}

fun BaseSimpleActivity.copyFile(source: String, destination: String) {
    var inputStream: InputStream? = null
    var out: OutputStream? = null
    try {
        out = getFileOutputStreamSync(destination, source.getMimeType())
        inputStream = getFileInputStreamSync(source)
        inputStream!!.copyTo(out!!)
    } catch (e: Exception) {
        showErrorToast(e)
    } finally {
        inputStream?.close()
        out?.close()
    }
}

fun BaseSimpleActivity.ensureWriteAccess(path: String, callback: () -> Unit) {
    when {
        isRestrictedSAFOnlyRoot(path) -> {
            handleAndroidSAFDialog(path) {
                if (!it) {
                    return@handleAndroidSAFDialog
                }
                callback.invoke()
            }
        }

        needsStupidWritePermissions(path) -> {
            handleSAFDialog(path) {
                if (!it) {
                    return@handleSAFDialog
                }
                callback()
            }
        }

        isAccessibleWithSAFSdk30(path) -> {
            handleSAFDialogSdk30(path) {
                if (!it) {
                    return@handleSAFDialogSdk30
                }
                callback()
            }
        }

        else -> {
            callback()
        }
    }
}

fun BaseSimpleActivity.launchResizeMultipleImagesDialog(paths: List<String>, callback: (() -> Unit)? = null) {
    ensureBackgroundThread {
        val imagePaths = mutableListOf<String>()
        val imageSizes = mutableListOf<Point>()
        for (path in paths) {
            val size = path.getImageResolution(this)
            if (size != null) {
                imagePaths.add(path)
                imageSizes.add(size)
            }
        }

        runOnUiThread {
            ResizeMultipleImagesDialog(this, imagePaths, imageSizes) {
                callback?.invoke()
            }
        }
    }
}

// Resizing reads real pixels, so a pCloud image is fetched first and the dialog works on the
// copy while it still names the medium where it lives. The destination is either storage: the
// picker offers pCloud too, so a resized image can stay on pCloud or come down to the device
fun BaseSimpleActivity.launchResizeImageDialog(path: String, callback: (() -> Unit)? = null) {
    if (!path.isPCloudPath()) {
        showResizeImageDialog(path, path, callback)
        return
    }

    withLocalMediaFile(path) { localPath ->
        showResizeImageDialog(path, localPath, callback)
    }
}

private fun BaseSimpleActivity.showResizeImageDialog(path: String, localPath: String, callback: (() -> Unit)?) {
    val originalSize = localPath.getImageResolution(this) ?: return
    ResizeWithPathDialog(this, originalSize, path) { newSize, newPath ->
        ensureBackgroundThread {
            if (newPath.isPCloudPath()) {
                resizeImageToPCloud(localPath, newPath, newSize, callback)
            } else {
                resizeImageToDevice(localPath, newPath, newSize, callback)
            }
        }
    }
}

private fun BaseSimpleActivity.resizeImageToDevice(sourcePath: String, newPath: String, newSize: Point, callback: (() -> Unit)?) {
    val file = File(newPath)
    val pathLastModifiedMap = mapOf(file.absolutePath to file.lastModified())
    try {
        resizeImage(sourcePath, newPath, newSize) { success ->
            if (success) {
                toast(org.fossify.commons.R.string.file_saved)

                val paths = arrayListOf(file.absolutePath)
                rescanPathsAndUpdateLastModified(paths, pathLastModifiedMap) {
                    runOnUiThread {
                        callback?.invoke()
                    }
                }
            } else {
                toast(R.string.image_editing_failed)
            }
        }
    } catch (e: OutOfMemoryError) {
        toast(org.fossify.commons.R.string.out_of_memory_error)
    } catch (e: Exception) {
        showErrorToast(e)
    }
}

// The resized image is written into the cache under the name it is to have on pCloud, because
// an upload carries the name of the file it is given, and sent from there. A name already
// taken is overwritten rather than uploaded beside: the dialog asked about that first
private fun BaseSimpleActivity.resizeImageToPCloud(sourcePath: String, newPath: String, newSize: Point, callback: (() -> Unit)?) {
    val resizeDir = File(cacheDir, PCLOUD_RESIZE_DIR)
    resizeDir.deleteRecursively()
    resizeDir.mkdirs()
    val resized = File(resizeDir, newPath.getFilenameFromPath())

    try {
        resizeImage(sourcePath, resized.absolutePath, newSize) { success ->
            if (!success) {
                toast(R.string.image_editing_failed)
                return@resizeImage
            }

            val destinationFolder = newPath.getParentPath()
            toast(R.string.pcloud_writing_back)
            writeToPCloud(listOf(destinationFolder), {
                if (pCloudItemsDB.getItem(newPath) != null) {
                    overwriteFile(newPath, resized.absolutePath)
                } else {
                    uploadFile(resized.absolutePath, destinationFolder)
                }
            }) { uploaded ->
                runOnUiThread {
                    if (uploaded) {
                        toast(org.fossify.commons.R.string.file_saved)
                    }

                    callback?.invoke()
                }
            }
        }
    } catch (e: OutOfMemoryError) {
        toast(org.fossify.commons.R.string.out_of_memory_error)
    } catch (e: Exception) {
        showErrorToast(e)
    }
}

fun BaseSimpleActivity.resizeImage(oldPath: String, newPath: String, size: Point, callback: (success: Boolean) -> Unit) {
    var oldExif: ExifInterface?
    val inputStream = contentResolver.openInputStream(Uri.fromFile(File(oldPath)))
    oldExif = ExifInterface(inputStream!!)

    val newBitmap = Glide.with(applicationContext).asBitmap().load(oldPath).submit(size.x, size.y).get()

    val newFile = File(newPath)
    val newFileDirItem = FileDirItem(newPath, newPath.getFilenameFromPath())
    getFileOutputStream(newFileDirItem, true) { out ->
        if (out != null) {
            out.use {
                try {
                    newBitmap.compress(newFile.absolutePath.getCompressionFormat(), 90, out)

                    val newExif = ExifInterface(newFile.absolutePath)
                    oldExif.copyNonDimensionAttributesTo(newExif)
                } catch (ignored: Exception) {
                }

                callback(true)
            }
        } else {
            callback(false)
        }
    }
}

fun BaseSimpleActivity.rescanPathsAndUpdateLastModified(paths: ArrayList<String>, pathLastModifiedMap: Map<String, Long>, callback: () -> Unit) {
    fixDateTaken(paths, false)
    for (path in paths) {
        val file = File(path)
        val lastModified = pathLastModifiedMap[path]
        if (config.keepLastModified && lastModified != null && lastModified != 0L) {
            File(file.absolutePath).setLastModified(lastModified)
            updateLastModified(file.absolutePath, lastModified)
        }
    }
    rescanPaths(paths, callback)
}

fun saveFile(path: String, bitmap: Bitmap, out: FileOutputStream, degrees: Int) {
    val matrix = Matrix()
    matrix.postRotate(degrees.toFloat())
    val bmp = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    bmp.compress(path.getCompressionFormat(), 90, out)
}

fun Activity.getShortcutImage(tmb: String, drawable: Drawable, callback: () -> Unit) {
    ensureBackgroundThread {
        val options = RequestOptions()
            .format(DecodeFormat.PREFER_ARGB_8888)
            .skipMemoryCache(true)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .fitCenter()

        val size = resources.getDimension(org.fossify.commons.R.dimen.shortcut_size).toInt()
        val builder = Glide.with(this)
            .asDrawable()
            .load(tmb)
            .apply(options)
            .centerCrop()
            .into(size, size)

        try {
            (drawable as LayerDrawable).setDrawableByLayerId(R.id.shortcut_image, builder.get())
        } catch (e: Exception) {
        }

        runOnUiThread {
            callback()
        }
    }
}

fun Activity.showFileOnMap(path: String) {
    val exif = try {
        if (path.startsWith("content://")) {
            ExifInterface(contentResolver.openInputStream(path.toUri())!!)
        } else {
            ExifInterface(path)
        }
    } catch (e: Exception) {
        showErrorToast(e)
        return
    }

    val latLon = FloatArray(2)
    if (exif.getLatLong(latLon)) {
        showLocationOnMap("${latLon[0]}, ${latLon[1]}")
    } else {
        toast(R.string.unknown_location)
    }
}

fun Activity.handleExcludedFolderPasswordProtection(callback: () -> Unit) {
    if (config.isExcludedPasswordProtectionOn) {
        SecurityDialog(this, config.excludedPasswordHash, config.excludedProtectionType) { _, _, success ->
            if (success) {
                callback()
            }
        }
    } else {
        callback()
    }
}

fun Activity.openRecycleBin() {
    Intent(this, MediaActivity::class.java).apply {
        putExtra(DIRECTORY, RECYCLE_BIN)
        startActivity(this)
    }
}

fun BaseSimpleActivity.writeBitmapToCache(
    source: Uri,
    bitmap: Bitmap,
    callback: (path: String?) -> Unit
) {
    val bytes = ByteArrayOutputStream()
    bitmap.compress(CompressFormat.PNG, 0, bytes)

    val folder = File(cacheDir, TEMP_FOLDER_NAME)
    if (!folder.exists()) {
        if (!folder.mkdir()) {
            callback(null)
            return
        }
    }

    val filename = applicationContext.getFilenameFromContentUri(source)
        ?: "tmp-${System.currentTimeMillis()}.jpg"
    val newPath = "$folder/$filename"
    val fileDirItem = FileDirItem(newPath, filename)
    getFileOutputStream(fileDirItem, true) {
        if (it != null) {
            try {
                it.write(bytes.toByteArray())
                callback(newPath)
            } catch (_: Exception) {
                callback(null)
            } finally {
                it.close()
            }
        } else {
            callback(null)
        }
    }
}

fun BaseSimpleActivity.ensureWritablePath(
    targetPath: String,
    confirmOverwrite: Boolean = true,
    onCancel: (() -> Unit)? = null,
    callback: (String) -> Unit,
) {
    fun proceedAfterGrants() {
        handleSAFDialogSdk30(targetPath) { granted ->
            if (!granted) {
                onCancel?.invoke()
                return@handleSAFDialogSdk30
            }
            callback(targetPath)
        }
    }

    fun requestGrantsThenProceed() {
        if (isRPlus() && !isExternalStorageManager()) {
            val fileDirItem = arrayListOf(File(targetPath).toFileDirItem(this))
            val fileUris = getFileUrisFromFileDirItems(fileDirItem)
            updateSDK30Uris(fileUris) { success ->
                if (success) proceedAfterGrants() else onCancel?.invoke()
            }
        } else {
            proceedAfterGrants()
        }
    }

    if (confirmOverwrite && getDoesFilePathExist(targetPath)) {
        val title = String.format(
            getString(org.fossify.commons.R.string.file_already_exists_overwrite),
            targetPath.getFilenameFromPath()
        )
        ConfirmationDialog(this, title) {
            requestGrantsThenProceed()
        }
    } else {
        requestGrantsThenProceed()
    }
}

fun Activity.proposeNewFilePath(uri: Uri): Pair<String, Boolean> {
    var newPath = applicationContext.getRealPathFromURI(uri) ?: ""
    if (newPath.startsWith("/mnt/")) {
        newPath = ""
    }

    var shouldAppendFilename = true
    if (newPath.isEmpty()) {
        val filename = applicationContext.getFilenameFromContentUri(uri) ?: ""
        if (filename.isNotEmpty()) {
            val path = if (intent.extras?.containsKey(REAL_FILE_PATH) == true) {
                intent.getStringExtra(REAL_FILE_PATH)?.getParentPath()
            } else {
                internalStoragePath
            }
            newPath = "$path/$filename"
            shouldAppendFilename = false
        }
    }

    if (newPath.isEmpty()) {
        newPath = "$internalStoragePath/${getCurrentFormattedDateTime()}.${
            uri.toString().getFilenameExtension()
        }"
        shouldAppendFilename = false
    }

    return Pair(newPath, shouldAppendFilename)
}
