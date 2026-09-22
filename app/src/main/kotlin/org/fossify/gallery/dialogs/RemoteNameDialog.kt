package org.fossify.gallery.dialogs

import androidx.appcompat.app.AlertDialog
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.showKeyboard
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.value
import org.fossify.gallery.databinding.DialogRemoteNameBinding

// Asks for the name of a file or folder on a remote storage, for renaming one and for creating
// a folder. The commons rename dialogs want a file on the device to rename, so pCloud and the
// network share share one of their own. When the name is the old one unchanged, or empty,
// nothing is sent. A slash is the one character neither storage will take in a name; anything
// else is the storage's call, and its refusal is reported
class RemoteNameDialog(
    val activity: BaseSimpleActivity,
    val initialName: String = "",
    val titleId: Int,
    val callback: (name: String) -> Unit
) {
    init {
        val binding = DialogRemoteNameBinding.inflate(activity.layoutInflater).apply {
            remoteName.setText(initialName)
            val extensionStart = initialName.lastIndexOf('.')
            remoteName.setSelection(0, if (extensionStart > 0) extensionStart else initialName.length)
        }

        activity.getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, titleId) { alertDialog ->
                    alertDialog.showKeyboard(binding.remoteName)
                    alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val name = binding.remoteName.value.trim()
                        when {
                            name.isEmpty() -> activity.toast(org.fossify.commons.R.string.empty_name)
                            name.contains('/') -> activity.toast(org.fossify.commons.R.string.invalid_name)
                            name == initialName -> alertDialog.dismiss()
                            else -> {
                                callback(name)
                                alertDialog.dismiss()
                            }
                        }
                    }
                }
            }
    }
}
