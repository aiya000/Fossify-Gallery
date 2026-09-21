package org.fossify.gallery.dialogs

import androidx.appcompat.app.AlertDialog
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.value
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.R
import org.fossify.gallery.databinding.DialogSmbShareBinding
import org.fossify.gallery.extensions.config
import org.fossify.gallery.helpers.SMB_DEFAULT_PORT
import org.fossify.gallery.helpers.SmbClient

// Where the SMB share is typed in: host, port, share name, an optional folder inside it, and
// the credentials. There is no discovery of hosts on the network, so everything comes from
// here.
//
// The test button connects with what is in the fields right now, without saving them: it is
// there so that a share can be corrected before the gallery starts walking it. Saving writes
// the fields to the config and hands them back, and the caller is the one that decides what
// to do with a share that has moved
class SmbShareDialog(val activity: BaseSimpleActivity, val callback: () -> Unit) {
    private val config = activity.config
    private val binding = DialogSmbShareBinding.inflate(activity.layoutInflater)

    init {
        binding.apply {
            smbHost.setText(config.smbHost)
            smbPort.setText(config.smbPort.toString())
            smbShareName.setText(config.smbShare)
            smbRootPath.setText(config.smbRootPath)
            smbUser.setText(config.smbUser)
            smbPassword.setText(config.smbPassword)
            smbDomain.setText(config.smbDomain)
            smbTestConnection.setOnClickListener { testConnection() }
        }

        activity.getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, R.string.smb_share) { alertDialog ->
                    alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        if (!hasHostAndShare()) {
                            activity.toast(R.string.smb_host_required)
                            return@setOnClickListener
                        }

                        save()
                        alertDialog.dismiss()
                        callback()
                    }
                }
            }
    }

    private fun hasHostAndShare() = binding.smbHost.value.trim().isNotEmpty() && binding.smbShareName.value.trim().isNotEmpty()

    // Saves what is in the fields and connects with it, off the main thread. The settings have
    // to be saved first because SmbClient reads them rather than taking them as arguments; a
    // test of a share that turns out to be wrong therefore leaves the wrong one saved, which is
    // the same as what pressing OK on it would have done
    private fun testConnection() {
        if (!hasHostAndShare()) {
            activity.toast(R.string.smb_host_required)
            return
        }

        save()
        activity.toast(R.string.smb_rescanning)
        ensureBackgroundThread {
            try {
                SmbClient.test(activity)
                activity.toast(R.string.smb_connection_ok)
            } catch (e: Exception) {
                SmbClient.disconnect()
                activity.showErrorToast(e)
            }
        }
    }

    // a new host or share means the cached folders belong to something else; dropping the
    // connection is what makes the next call pick the new settings up
    private fun save() {
        binding.apply {
            config.smbHost = smbHost.value.trim()
            config.smbPort = smbPort.value.trim().toIntOrNull() ?: SMB_DEFAULT_PORT
            config.smbShare = smbShareName.value.trim().trim('/', '\\')
            config.smbRootPath = smbRootPath.value.trim()
            config.smbUser = smbUser.value.trim()
            config.smbPassword = smbPassword.value
            config.smbDomain = smbDomain.value.trim()
        }

        SmbClient.disconnect()
    }
}
