package org.fossify.gallery.dialogs

import androidx.appcompat.app.AlertDialog
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.databinding.DialogMessageBinding
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.toast
import org.fossify.gallery.R
import org.fossify.gallery.extensions.isPCloudPath
import org.fossify.gallery.extensions.isPCloudRecycleBinPath
import org.fossify.gallery.extensions.toPCloudRemotePath
import org.fossify.gallery.helpers.PCLOUD_PATH_SCHEME
import org.fossify.gallery.helpers.PCLOUD_RECYCLE_BIN

// Asks before media come back out of the pCloud recycle bin, and says where they will land:
// the folder they were deleted from, which is made again when pCloud no longer has it. The
// third button opens the folder picker on pCloud instead, for restoring somewhere else; a
// folder on the device is not a destination here, that is a download. callback gets null
// for the original folder, or the pseudo path of the picked one
class PCloudRestoreDialog(
    private val activity: BaseSimpleActivity,
    count: Int,
    destinationFolder: String,
    willCreateFolder: Boolean,
    private val callback: (destinationFolder: String?) -> Unit
) {
    private var dialog: AlertDialog? = null

    init {
        val binding = DialogMessageBinding.inflate(activity.layoutInflater)
        val question = activity.resources.getQuantityString(R.plurals.restore_confirmation, count, count)
        val destination = activity.getString(R.string.pcloud_restore_destination, destinationFolder.toPCloudRemotePath())
        val recreated = if (willCreateFolder) "\n${activity.getString(R.string.pcloud_restore_folder_recreated)}" else ""
        binding.message.text = "$question\n\n$destination$recreated"

        activity.getAlertDialogBuilder()
            .setPositiveButton(R.string.pcloud_restore) { _, _ -> callback(null) }
            .setNeutralButton(R.string.pcloud_restore_other_folder, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this) { alertDialog ->
                    dialog = alertDialog
                    // set here rather than on the builder so that the dialog stays up
                    // while the picker is open, and closes only once a folder was picked
                    alertDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        pickOtherFolder()
                    }
                }
            }
    }

    private fun pickOtherFolder() {
        FolderPickerDialog(activity, PCLOUD_PATH_SCHEME, showHidden = false, showFAB = true, canAddShowHiddenButton = false) { picked ->
            if (!picked.isPCloudPath() || picked == PCLOUD_RECYCLE_BIN || picked.isPCloudRecycleBinPath()) {
                activity.toast(R.string.pcloud_restore_pick_pcloud_folder)
                return@FolderPickerDialog
            }

            dialog?.dismiss()
            callback(picked)
        }
    }
}
