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
import org.fossify.gallery.helpers.SmbConnection
import org.fossify.gallery.helpers.SmbConnectionChange
import org.fossify.gallery.helpers.smbRootOf

// Where one SMB share is typed in: a name to call it by, host, port, share name, an optional
// folder inside it, and the credentials. There is no discovery of hosts on the network, so everything comes from
// here. [connectionId] is the connection being edited, or the id a new one is to have (#155).
//
// The test button connects with what is in the fields right now, without saving them: it is
// there so that a share can be corrected before the gallery starts walking it. Saving writes
// the fields to the config and hands back what the save changed, and the caller is the one that
// decides what to do with a share that has moved
class SmbShareDialog(
    val activity: BaseSimpleActivity,
    private val connectionId: Int,
    val callback: (connectionId: Int, change: SmbConnectionChange) -> Unit
) {
    private val config = activity.config
    private val binding = DialogSmbShareBinding.inflate(activity.layoutInflater)

    init {
        val connection = config.smbConnection(connectionId)
        binding.apply {
            smbName.setText(connection?.name.orEmpty())
            smbHost.setText(connection?.host.orEmpty())
            smbPort.setText((connection?.port ?: SMB_DEFAULT_PORT).toString())
            smbShareName.setText(connection?.share.orEmpty())
            smbRootPath.setText(connection?.rootPath.orEmpty())
            smbUser.setText(connection?.user.orEmpty())
            smbPassword.setText(connection?.password.orEmpty())
            smbDomain.setText(connection?.domain.orEmpty())
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

                        val change = save()
                        alertDialog.dismiss()
                        callback(connectionId, change)
                    }
                }
            }
    }

    private fun hasHostAndShare() = binding.smbHost.value.trim().isNotEmpty() && binding.smbShareName.value.trim().isNotEmpty()

    // Connects with what is in the fields, off the main thread, and leaves the saved settings as
    // they were: a test of a share that turns out to be wrong is not a reason to lose the one
    // that worked
    private fun testConnection() {
        if (!hasHostAndShare()) {
            activity.toast(R.string.smb_host_required)
            return
        }

        val connection = typedConnection()
        activity.toast(R.string.smb_connecting)
        ensureBackgroundThread {
            try {
                SmbClient.test(connection)
                activity.toast(R.string.smb_connection_ok)
            } catch (e: Exception) {
                // SmbClient logs which step failed and with what; the toast is cut short by the
                // length of a status name, so it says only that something went wrong
                activity.showErrorToast(e)
            } finally {
                // what was tested is not what is saved, and the next call has to connect with
                // the settings rather than find this connection standing
                SmbClient.disconnect(smbRootOf(connectionId))
            }
        }
    }

    // What the fields say, as a connection. A share name typed with a path after it
    // ("photos/2026") is split here: only the first segment is a share, the rest is a folder
    // inside it, and a server answers a connect to "photos/2026" with a share that does not exist
    // rather than with anything useful
    private fun typedConnection(): SmbConnection = binding.run {
        val typedShare = smbShareName.value.trim().replace('\\', '/').trim('/')
        val share = typedShare.substringBefore('/')
        val folderInTypedShare = typedShare.substringAfter('/', "")
        val typedFolder = smbRootPath.value.trim().replace('\\', '/').trim('/')

        SmbConnection(
            id = connectionId,
            // what the storage is called in the menus; empty leaves that to smbLabel()
            name = smbName.value.trim(),
            host = smbHost.value.trim(),
            port = smbPort.value.trim().toIntOrNull() ?: SMB_DEFAULT_PORT,
            share = share,
            rootPath = listOf(folderInTypedShare, typedFolder).filter { it.isNotEmpty() }.joinToString("/"),
            user = smbUser.value.trim(),
            password = smbPassword.value,
            domain = smbDomain.value.trim()
        )
    }

    // Dropping the live connection is what makes the next call pick the new settings up. What
    // the save changed is handed back, see SmbConnection.changeFrom()
    private fun save(): SmbConnectionChange {
        val connection = typedConnection()
        val change = connection.changeFrom(config.smbConnection(connectionId))
        config.saveSmbConnection(connection)
        SmbClient.disconnect(connection.root)
        return change
    }
}
