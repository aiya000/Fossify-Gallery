package org.fossify.gallery.dialogs

import androidx.appcompat.app.AlertDialog
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.showKeyboard
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.value
import org.fossify.gallery.R
import org.fossify.gallery.databinding.DialogFolderGroupNameBinding

// asks for the name of a virtual folder group, used both for creating and renaming groups
class FolderGroupNameDialog(
    val activity: BaseSimpleActivity,
    val initialName: String = "",
    val titleId: Int = R.string.create_new_group,
    val callback: (name: String) -> Unit
) {
    init {
        val binding = DialogFolderGroupNameBinding.inflate(activity.layoutInflater).apply {
            folderGroupName.setText(initialName)
            folderGroupName.setSelection(initialName.length)
        }

        activity.getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, titleId) { alertDialog ->
                    alertDialog.showKeyboard(binding.folderGroupName)
                    alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val name = binding.folderGroupName.value.trim()
                        if (name.isEmpty()) {
                            activity.toast(R.string.group_name_empty)
                            return@setOnClickListener
                        }

                        callback(name)
                        alertDialog.dismiss()
                    }
                }
            }
    }
}
