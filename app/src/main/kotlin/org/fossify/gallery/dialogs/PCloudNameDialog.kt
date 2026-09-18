package org.fossify.gallery.dialogs

import androidx.appcompat.app.AlertDialog
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.showKeyboard
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.value
import org.fossify.gallery.databinding.DialogPcloudNameBinding

// Asks for the name of a pCloud file or folder, for renaming one and for creating a folder.
// The commons rename dialogs want a file to rename, so pCloud gets one of its own. When the
// name is the old one unchanged, or empty, nothing is sent. A slash is the one character
// pCloud will not take in a name; anything else is its call, and its refusal is toasted
class PCloudNameDialog(
    val activity: BaseSimpleActivity,
    val initialName: String = "",
    val titleId: Int,
    val callback: (name: String) -> Unit
) {
    init {
        val binding = DialogPcloudNameBinding.inflate(activity.layoutInflater).apply {
            pcloudName.setText(initialName)
            val extensionStart = initialName.lastIndexOf('.')
            pcloudName.setSelection(0, if (extensionStart > 0) extensionStart else initialName.length)
        }

        activity.getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, titleId) { alertDialog ->
                    alertDialog.showKeyboard(binding.pcloudName)
                    alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val name = binding.pcloudName.value.trim()
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
