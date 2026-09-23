package org.fossify.gallery.dialogs

import androidx.appcompat.app.AlertDialog
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getFilenameFromPath
import org.fossify.commons.extensions.getParentPath
import org.fossify.commons.extensions.getPicturesDirectoryPath
import org.fossify.commons.extensions.hideKeyboard
import org.fossify.commons.extensions.isAValidFilename
import org.fossify.commons.extensions.isInDownloadDir
import org.fossify.commons.extensions.isRestrictedWithSAFSdk30
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.showKeyboard
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.value
import org.fossify.gallery.databinding.DialogSaveAsBinding
import org.fossify.gallery.extensions.humanizeAnyPath
import org.fossify.gallery.extensions.isRemotePath
import org.fossify.gallery.helpers.MediaStorage

// Asks where to save and under what name.
//
// [localStorageOnly] says whether the caller can write anywhere but this device. It defaults to
// true because most callers cannot: the editor writes its result with the file APIs, and a
// folder on pCloud or on the share offered to one of those would be a destination it could only
// fail on. A caller that routes by destination passes false, and then the picker offers every
// storage that is set up
class SaveAsDialog(
    val activity: BaseSimpleActivity,
    val path: String,
    val appendFilename: Boolean,
    val localStorageOnly: Boolean = true,
    val cancelCallback: (() -> Unit)? = null,
    val callback: (savePath: String) -> Unit
) {
    private val binding = DialogSaveAsBinding.inflate(activity.layoutInflater)

    // a remote folder is none of SAF's business, and the restricted-folder rules are about
    // directories of this device; asking them of a pseudo path answers nonsense
    private var realPath = path.getParentPath().run {
        when {
            isRemotePath() -> this
            activity.isRestrictedWithSAFSdk30(this) && !activity.isInDownloadDir(this) -> activity.getPicturesDirectoryPath(this)
            else -> this
        }
    }

    init {
        binding.apply {
            folderValue.setText("${activity.humanizeAnyPath(realPath).trimEnd('/')}/")

            val fullName = path.getFilenameFromPath()
            val dotAt = fullName.lastIndexOf(".")
            var name = fullName

            if (dotAt > 0) {
                name = fullName.substring(0, dotAt)
                val extension = fullName.substring(dotAt + 1)
                extensionValue.setText(extension)
            }

            if (appendFilename) {
                name += "_1"
            }

            filenameValue.setText(name)
            folderValue.setOnClickListener {
                activity.hideKeyboard(folderValue)
                FolderPickerDialog(
                    activity = activity,
                    currPath = realPath,
                    showHidden = false,
                    showFAB = true,
                    canAddShowHiddenButton = true,
                    localStorageOnly = localStorageOnly
                ) {
                    folderValue.setText(activity.humanizeAnyPath(it))
                    realPath = it
                }
            }
        }

        activity.getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel) { dialog, which -> cancelCallback?.invoke() }
            .setOnCancelListener { cancelCallback?.invoke() }
            .apply {
                activity.setupDialogStuff(
                    view = binding.root,
                    dialog = this,
                    titleId = org.fossify.commons.R.string.save_as
                ) { alertDialog ->
                    alertDialog.showKeyboard(binding.filenameValue)
                    alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        validateAndConfirmPath(alertDialog::dismiss)
                    }
                }
            }
    }

    private fun validateAndConfirmPath(dismiss: () -> Unit) {
        val filename = binding.filenameValue.value
        val extension = binding.extensionValue.value

        if (filename.isEmpty()) {
            activity.toast(org.fossify.commons.R.string.filename_cannot_be_empty)
            return
        }

        if (extension.isEmpty()) {
            activity.toast(org.fossify.commons.R.string.extension_cannot_be_empty)
            return
        }

        val newFilename = "$filename.$extension"
        val newPath = "${realPath.trimEnd('/')}/$newFilename"
        if (!newFilename.isAValidFilename()) {
            activity.toast(org.fossify.commons.R.string.filename_invalid_characters)
            return
        }

        // whether the name is taken, and may be written over, is the storage's to answer
        MediaStorage.of(activity, newPath).ensureWritable(activity, newPath, confirmOverwrite = true, onCancel = cancelCallback) {
            callback(newPath)
            dismiss()
        }
    }
}
