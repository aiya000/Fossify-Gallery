package org.fossify.gallery.dialogs

import androidx.appcompat.app.AlertDialog
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.databinding.DialogMessageBinding
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.internalStoragePath
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.toast
import org.fossify.gallery.R
import org.fossify.gallery.helpers.MediaStorage
import org.fossify.gallery.helpers.PCLOUD_PATH_SCHEME
import org.fossify.gallery.helpers.PCLOUD_RECYCLE_BIN
import org.fossify.gallery.helpers.SMB_PATH_SCHEME
import org.fossify.gallery.helpers.SMB_RECYCLE_BIN

// Asks before media come back out of the recycle bin, and says where they will land: the
// folder they were deleted from, which is made again when the storage no longer has it. With
// the selection all on one storage, the third button opens the folder picker on that storage
// instead, for restoring somewhere else -- a medium stays on the storage it is on, a restore
// onto another one would be a copy. A selection across storages ([storage] null) goes back to
// where each came from and is offered no other folder. callback gets null for the original
// folder, or the path of the picked one
class RestoreFromBinDialog(
    private val activity: BaseSimpleActivity,
    private val storage: MediaStorage?,
    count: Int,
    destinationFolder: String,
    willCreateFolder: Boolean,
    private val callback: (destinationFolder: String?) -> Unit
) {
    private var dialog: AlertDialog? = null

    init {
        val binding = DialogMessageBinding.inflate(activity.layoutInflater)
        val question = activity.resources.getQuantityString(R.plurals.restore_confirmation, count, count)
        val destination = if (storage == null) {
            activity.getString(R.string.restore_to_own_folders)
        } else {
            activity.getString(R.string.restore_destination, storage.humanizedPath(destinationFolder))
        }
        val recreated = if (willCreateFolder && storage != null) "\n${activity.getString(R.string.restore_folder_recreated)}" else ""
        binding.message.text = "$question\n\n$destination$recreated"

        activity.getAlertDialogBuilder()
            .setPositiveButton(R.string.restore) { _, _ -> callback(null) }
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                if (storage != null) {
                    setNeutralButton(R.string.restore_other_folder, null)
                }

                activity.setupDialogStuff(binding.root, this) { alertDialog ->
                    dialog = alertDialog
                    // set here rather than on the builder so that the dialog stays up
                    // while the picker is open, and closes only once a folder was picked
                    alertDialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                        pickOtherFolder()
                    }
                }
            }
    }

    // the picker walks the storages; a folder of another one, or of the bin itself, is turned
    // away rather than restored into
    private fun pickOtherFolder() {
        val storage = storage ?: return
        val root = when (storage) {
            is MediaStorage.Device -> activity.internalStoragePath
            is MediaStorage.PCloud -> PCLOUD_PATH_SCHEME
            is MediaStorage.Smb -> SMB_PATH_SCHEME
        }

        FolderPickerDialog(activity, root, showHidden = false, showFAB = true, canAddShowHiddenButton = false) { picked ->
            val isABin = picked == PCLOUD_RECYCLE_BIN || picked == SMB_RECYCLE_BIN || storage.isInRecycleBin(picked)
            if (!storage.holds(picked) || isABin) {
                activity.toast(R.string.restore_pick_same_storage_folder)
                return@FolderPickerDialog
            }

            dialog?.dismiss()
            callback(picked)
        }
    }
}
