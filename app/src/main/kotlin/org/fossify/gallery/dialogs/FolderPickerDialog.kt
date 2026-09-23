package org.fossify.gallery.dialogs

import android.os.Parcelable
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.areSystemAnimationsEnabled
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getAndroidSAFFileItems
import org.fossify.commons.extensions.getBasePath
import org.fossify.commons.extensions.getDirectChildrenCount
import org.fossify.commons.extensions.getDoesFilePathExist
import org.fossify.commons.extensions.getFilenameFromPath
import org.fossify.commons.extensions.getFolderLastModifieds
import org.fossify.commons.extensions.getIsPathDirectory
import org.fossify.commons.extensions.getOTGItems
import org.fossify.commons.extensions.getParentPath
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.getSomeAndroidSAFDocument
import org.fossify.commons.extensions.getSomeDocumentFile
import org.fossify.commons.extensions.getSomeDocumentSdk30
import org.fossify.commons.extensions.handleHiddenFolderPasswordProtection
import org.fossify.commons.extensions.handleLockedFolderOpening
import org.fossify.commons.extensions.hasExternalSDCard
import org.fossify.commons.extensions.hasOTGConnected
import org.fossify.commons.extensions.internalStoragePath
import org.fossify.commons.extensions.isAccessibleWithSAFSdk30
import org.fossify.commons.extensions.isInDownloadDir
import org.fossify.commons.extensions.isPathOnOTG
import org.fossify.commons.extensions.isRestrictedSAFOnlyRoot
import org.fossify.commons.extensions.isRestrictedWithSAFSdk30
import org.fossify.commons.extensions.otgPath
import org.fossify.commons.extensions.sdCardPath
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.FileDirItem
import org.fossify.gallery.R
import org.fossify.gallery.adapters.FolderPickerItemsAdapter
import org.fossify.gallery.databinding.DialogFolderPickerBinding
import org.fossify.gallery.extensions.config
import org.fossify.gallery.extensions.isPCloudPath
import org.fossify.gallery.extensions.isSmbPath
import org.fossify.gallery.helpers.MediaStorage
import org.fossify.gallery.helpers.PCLOUD_PATH_SCHEME
import org.fossify.gallery.helpers.PCloudScanner
import org.fossify.gallery.helpers.SMB_PATH_SCHEME
import org.fossify.gallery.helpers.SmbScanner
import org.fossify.gallery.views.StorageChips
import java.io.File

// Picks a folder on the device, on pCloud or on the network share. Commons' FilePickerDialog
// walks the device only and hides its storage switch in a radio dialog behind the first
// breadcrumb; this one lays the storages out as a row of chips above the breadcrumbs -- pCloud
// while signed in, the share while one is configured -- and lists a remote folder straight
// from its storage, so that an empty or never scanned folder can be picked and a new one
// created on the spot. Folders only, the file picking half of the commons dialog is not
// needed here.
//
// A fork of commons 6.1.6 FilePickerDialog rather than a subclass: that class is final and
// keeps its listing private. The device side is kept as it is there.
//
// [localStorageOnly] leaves both remote storages out even while they are set up, for a caller
// that can only write to the device. [onCancelled] tells such a caller that nothing was
// picked, so that a screen standing on the picker alone can close itself
class FolderPickerDialog(
    private val activity: BaseSimpleActivity,
    private var currPath: String,
    private var showHidden: Boolean = false,
    private val showFAB: Boolean = false,
    private val canAddShowHiddenButton: Boolean = false,
    private val localStorageOnly: Boolean = false,
    private val onCancelled: (() -> Unit)? = null,
    private val callback: (pickedPath: String) -> Unit
) {
    private class Storage(val path: String, val label: String)

    private val config = activity.config
    private var mFirstUpdate = true
    private var mPrevPath = ""
    private val mScrollStates = HashMap<String, Parcelable>()
    private var mDialog: AlertDialog? = null
    private val binding = DialogFolderPickerBinding.inflate(activity.layoutInflater, null, false)
    private val storages = availableStorages()

    // a pCloud listing arrives from the network; one for a folder the user has left by then
    // must not overwrite the folder they are in
    private var listingId = 0

    init {
        if (currPath.isPCloudPath()) {
            if (!config.isPCloudLoggedIn || localStorageOnly) {
                currPath = activity.internalStoragePath
            }
        } else if (currPath.isSmbPath()) {
            if (!config.isSmbConfigured || localStorageOnly) {
                currPath = activity.internalStoragePath
            }
        } else {
            if (!activity.getDoesFilePathExist(currPath)) {
                currPath = activity.internalStoragePath
            }

            if (!activity.getIsPathDirectory(currPath)) {
                currPath = currPath.getParentPath()
            }

            // do not allow copying files in the recycle bin manually
            if (currPath.startsWith(activity.filesDir.absolutePath)) {
                currPath = activity.internalStoragePath
            }
        }

        setupStorages()
        binding.folderPickerBreadcrumbs.listener = { path ->
            if (currPath.trimEnd('/') != path.trimEnd('/')) {
                currPath = path
                tryUpdateItems()
            }
        }

        tryUpdateItems()

        val builder = activity.getAlertDialogBuilder()
            .setNegativeButton(org.fossify.commons.R.string.cancel) { _, _ -> onCancelled?.invoke() }
            .setPositiveButton(org.fossify.commons.R.string.ok, null)

        if (showFAB) {
            binding.folderPickerFab.apply {
                beVisible()
                setOnClickListener { createNewFolder() }
            }
        }

        val secondaryFabBottomMargin = activity.resources.getDimension(
            if (showFAB) org.fossify.commons.R.dimen.secondary_fab_bottom_margin else org.fossify.commons.R.dimen.activity_margin
        ).toInt()
        binding.folderPickerFabsHolder.apply {
            (layoutParams as CoordinatorLayout.LayoutParams).bottomMargin = secondaryFabBottomMargin
        }

        binding.folderPickerPlaceholder.setTextColor(activity.getProperTextColor())
        binding.folderPickerFastscroller.updateColors(activity.getProperPrimaryColor())
        binding.folderPickerFabShowHidden.apply {
            beVisibleIf(!showHidden && canAddShowHiddenButton)
            setOnClickListener {
                activity.handleHiddenFolderPasswordProtection {
                    beGone()
                    showHidden = true
                    tryUpdateItems()
                }
            }
        }

        builder.apply {
            activity.setupDialogStuff(binding.root, this, org.fossify.commons.R.string.select_folder) { alertDialog ->
                mDialog = alertDialog
                // only a cancel, never a dismiss(): picking a folder and creating one both
                // dismiss the dialog, and those are not "nothing was picked"
                alertDialog.setOnCancelListener { onCancelled?.invoke() }
                alertDialog.onBackPressedDispatcher.addCallback(alertDialog) {
                    if (currPath.trimEnd('/') != storageRootOf(currPath).trimEnd('/')) {
                        currPath = currPath.getParentPath().ifEmpty { "/" }
                        tryUpdateItems()
                    } else {
                        isEnabled = false
                        alertDialog.onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        }

        mDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            verifyPath()
        }
    }

    private fun availableStorages(): List<Storage> {
        val storages = arrayListOf(Storage(activity.internalStoragePath, activity.getString(org.fossify.commons.R.string.internal)))
        if (activity.hasExternalSDCard()) {
            storages.add(Storage(activity.sdCardPath, activity.getString(org.fossify.commons.R.string.sd_card)))
        }

        if (activity.hasOTGConnected()) {
            storages.add(Storage(OTG_STORAGE, activity.getString(org.fossify.commons.R.string.usb)))
        }

        if (config.isPCloudLoggedIn && !localStorageOnly) {
            storages.add(Storage(PCLOUD_PATH_SCHEME, activity.getString(R.string.pcloud)))
        }

        if (config.isSmbConfigured && !localStorageOnly) {
            storages.add(Storage(SMB_PATH_SCHEME, activity.getString(R.string.smb)))
        }

        return storages
    }

    // the storage a path lives on, "/" for one outside every known storage
    private fun storageRootOf(path: String): String {
        if (path.isPCloudPath()) {
            return PCLOUD_PATH_SCHEME
        }

        if (path.isSmbPath()) {
            return SMB_PATH_SCHEME
        }

        val basePath = path.getBasePath(activity)
        return if (basePath == activity.otgPath && basePath.isNotEmpty()) OTG_STORAGE else basePath
    }

    // one chip per storage; the chip of the storage the list is on is highlighted, tapping
    // any of them goes to that storage's root
    private fun setupStorages() {
        binding.folderPickerStorages.setChips(storages.map { StorageChips.Chip(it.path, it.label) }, storageRootOf(currPath))
        binding.folderPickerStorages.onChipClicked = { tag -> storagePicked(storages.first { it.path == tag }) }
    }

    private fun highlightCurrentStorage() = binding.folderPickerStorages.select(storageRootOf(currPath))

    private fun storagePicked(storage: Storage) {
        when (storage.path) {
            OTG_STORAGE -> activity.handleOTGPermission { granted ->
                if (granted) {
                    goTo(activity.otgPath)
                }
            }

            else -> goTo(storage.path)
        }
    }

    private fun goTo(path: String) {
        if (currPath.trimEnd('/') == path.trimEnd('/')) {
            return
        }

        currPath = path
        tryUpdateItems()
    }

    // the folder is made the way its storage makes one, see MediaStorage.createFolder(), and
    // then picked; what a storage refuses it has reported, and the picker stays open
    private fun createNewFolder() {
        MediaStorage.of(activity, currPath).createFolder(activity, currPath) { newPath ->
            if (newPath != null) {
                callback(newPath)
                mDialog?.dismiss()
            }
        }
    }

    private fun tryUpdateItems() {
        val id = ++listingId
        binding.folderPickerBreadcrumbs.setPath(storageRootOf(currPath), currPath)
        highlightCurrentStorage()
        ensureBackgroundThread {
            getItems(currPath) {
                activity.runOnUiThread {
                    if (id == listingId) {
                        binding.folderPickerPlaceholder.beGone()
                        updateItems(it as ArrayList<FileDirItem>)
                    }
                }
            }
        }
    }

    private fun updateItems(items: ArrayList<FileDirItem>) {
        if (!containsDirectory(items) && !mFirstUpdate && !showFAB) {
            verifyPath()
            return
        }

        val sortedItems = items.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        val adapter = FolderPickerItemsAdapter(activity, sortedItems, binding.folderPickerList) {
            if ((it as FileDirItem).isDirectory) {
                activity.handleLockedFolderOpening(it.path) { success ->
                    if (success) {
                        currPath = it.path
                        tryUpdateItems()
                    }
                }
            }
        }

        val layoutManager = binding.folderPickerList.layoutManager as LinearLayoutManager
        mScrollStates[mPrevPath.trimEnd('/')] = layoutManager.onSaveInstanceState()!!

        binding.apply {
            folderPickerList.adapter = adapter
            if (root.context.areSystemAnimationsEnabled) {
                folderPickerList.scheduleLayoutAnimation()
            }

            layoutManager.onRestoreInstanceState(mScrollStates[currPath.trimEnd('/')])
        }

        mFirstUpdate = false
        mPrevPath = currPath
    }

    private fun verifyPath() {
        when {
            currPath.isPCloudPath() -> sendSuccess()

            currPath.isSmbPath() -> sendSuccess()

            activity.isRestrictedSAFOnlyRoot(currPath) -> {
                val document = activity.getSomeAndroidSAFDocument(currPath) ?: return
                sendSuccessForDocumentFile(document)
            }

            activity.isPathOnOTG(currPath) -> {
                val fileDocument = activity.getSomeDocumentFile(currPath) ?: return
                sendSuccessForDocumentFile(fileDocument)
            }

            activity.isAccessibleWithSAFSdk30(currPath) -> {
                activity.handleSAFDialogSdk30(currPath) {
                    if (it) {
                        val document = activity.getSomeDocumentSdk30(currPath)
                        sendSuccessForDocumentFile(document ?: return@handleSAFDialogSdk30)
                    }
                }
            }

            activity.isRestrictedWithSAFSdk30(currPath) -> {
                if (activity.isInDownloadDir(currPath)) {
                    sendSuccessForDirectFile()
                } else {
                    activity.toast(org.fossify.commons.R.string.system_folder_restriction, Toast.LENGTH_LONG)
                }
            }

            else -> sendSuccessForDirectFile()
        }
    }

    private fun sendSuccessForDocumentFile(document: DocumentFile) {
        if (document.isDirectory) {
            sendSuccess()
        }
    }

    private fun sendSuccessForDirectFile() {
        if (File(currPath).isDirectory) {
            sendSuccess()
        }
    }

    private fun sendSuccess() {
        currPath = if (currPath.length == 1) {
            currPath
        } else {
            currPath.trimEnd('/')
        }

        callback(currPath)
        mDialog?.dismiss()
    }

    private fun getItems(path: String, callback: (List<FileDirItem>) -> Unit) {
        when {
            path.isPCloudPath() -> callback(getPCloudItems(path))

            path.isSmbPath() -> callback(getSmbItems(path))

            activity.isRestrictedSAFOnlyRoot(path) -> {
                activity.handleAndroidSAFDialog(path) {
                    activity.getAndroidSAFFileItems(path, showHidden) {
                        callback(it)
                    }
                }
            }

            activity.isPathOnOTG(path) -> activity.getOTGItems(path, showHidden, false, callback)
            else -> {
                val lastModifieds = activity.getFolderLastModifieds(path)
                getRegularItems(path, lastModifieds, callback)
            }
        }
    }

    // the folders of a pCloud folder, fresh from pCloud; a refusal is toasted and leaves the
    // folder looking empty. A listing names no counts, hence children of -1
    private fun getPCloudItems(path: String): List<FileDirItem> {
        return try {
            PCloudScanner(activity).listFolders(path).map { FileDirItem(it, it.getFilenameFromPath(), true, -1, 0, 0) }
        } catch (e: Exception) {
            activity.showErrorToast(e)
            emptyList()
        }
    }

    // the folders of a folder of the share, listed from the share itself rather than from the
    // rows a scan left: a folder with no media in it never gets a row, and one of those is
    // exactly what somebody picking a destination may be reaching for. A refusal is reported
    // and leaves the folder looking empty, the same as the pCloud side
    private fun getSmbItems(path: String): List<FileDirItem> {
        return try {
            SmbScanner(activity).listFolders(path).map { FileDirItem(it, it.getFilenameFromPath(), true, -1, 0, 0) }
        } catch (e: Exception) {
            activity.showErrorToast(e)
            emptyList()
        }
    }

    private fun getRegularItems(path: String, lastModifieds: HashMap<String, Long>, callback: (List<FileDirItem>) -> Unit) {
        val items = ArrayList<FileDirItem>()
        val files = File(path).listFiles()?.filterNotNull()
        if (files == null) {
            callback(items)
            return
        }

        for (file in files) {
            if (!showHidden && file.name.startsWith('.')) {
                continue
            }

            val curPath = file.absolutePath
            val curName = curPath.getFilenameFromPath()
            val size = file.length()
            var lastModified = lastModifieds.remove(curPath)
            val isDirectory = file.isDirectory
            if (lastModified == null) {
                lastModified = 0    // we don't actually need the real lastModified that badly, do not check file.lastModified()
            }

            val children = if (isDirectory) file.getDirectChildrenCount(activity, showHidden) else 0
            items.add(FileDirItem(curPath, curName, isDirectory, children, size, lastModified))
        }
        callback(items)
    }

    private fun containsDirectory(items: List<FileDirItem>) = items.any { it.isDirectory }

    companion object {
        // the USB storage's path is not known until its permission is granted, so its chip
        // carries this placeholder instead
        private const val OTG_STORAGE = "otg"
    }
}
