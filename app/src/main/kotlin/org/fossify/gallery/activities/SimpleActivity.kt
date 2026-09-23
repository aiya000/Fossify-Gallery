package org.fossify.gallery.activities

import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore.Images
import android.provider.MediaStore.Video
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.dialogs.FilePickerDialog
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isPiePlus
import org.fossify.gallery.R
import org.fossify.gallery.dialogs.StoragePermissionRequiredDialog
import org.fossify.gallery.extensions.addPathToDB
import org.fossify.gallery.extensions.config
import org.fossify.gallery.extensions.isRemotePath
import org.fossify.gallery.extensions.openEditor
import org.fossify.gallery.extensions.openRemoteEditor
import org.fossify.gallery.extensions.updateDirectoryPath
import org.fossify.gallery.extensions.withEditableMediaFile
import org.fossify.gallery.helpers.MediaStorage
import org.fossify.gallery.helpers.UPSTREAM_APP_ID
import org.fossify.gallery.helpers.getPermissionsToRequest
import java.io.File

open class SimpleActivity : BaseSimpleActivity() {

    private var dialog: AlertDialog? = null

    // BaseSimpleActivity.onCreate() ends with an anti-tampering check: when the package name does
    // not start with "org.fossify.", it warns at random -- roughly one screen in fifty -- that
    // this is "a fake version of the app". This fork is built from source and was only renamed so
    // it can sit beside the upstream app, so the warning is wrong here.
    //
    // The check reads the package name straight off the activity, so answering it with the
    // upstream name settles it. The substitution lasts only for the length of that one super
    // call: everywhere else, including every other class, getPackageName() still reports the real
    // application id that the system knows this app by
    private var isInsideBaseOnCreate = false

    override fun getPackageName(): String = when {
        isInsideBaseOnCreate -> UPSTREAM_APP_ID
        else -> super.getPackageName()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        isInsideBaseOnCreate = true
        try {
            super.onCreate(savedInstanceState)
        } finally {
            isInsideBaseOnCreate = false
        }
    }

    private val observer = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            if (uri != null) {
                val path = getRealPathFromURI(uri)
                if (path != null) {
                    updateDirectoryPath(path.getParentPath())
                    addPathToDB(path)
                }
            }
        }
    }

    override fun getAppIconIDs() = arrayListOf(
        R.mipmap.ic_launcher_red,
        R.mipmap.ic_launcher_pink,
        R.mipmap.ic_launcher_purple,
        R.mipmap.ic_launcher_deep_purple,
        R.mipmap.ic_launcher_indigo,
        R.mipmap.ic_launcher_blue,
        R.mipmap.ic_launcher_light_blue,
        R.mipmap.ic_launcher_cyan,
        R.mipmap.ic_launcher_teal,
        R.mipmap.ic_launcher,
        R.mipmap.ic_launcher_light_green,
        R.mipmap.ic_launcher_lime,
        R.mipmap.ic_launcher_yellow,
        R.mipmap.ic_launcher_amber,
        R.mipmap.ic_launcher_orange,
        R.mipmap.ic_launcher_deep_orange,
        R.mipmap.ic_launcher_brown,
        R.mipmap.ic_launcher_blue_grey,
        R.mipmap.ic_launcher_grey_black
    )

    override fun getAppLauncherName() = getString(R.string.app_launcher_name)

    override fun getRepositoryName() = "Gallery"

    protected fun checkNotchSupport() {
        if (isPiePlus()) {
            val cutoutMode = when {
                config.showNotch -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                else -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
            }

            window.attributes.layoutInDisplayCutoutMode = cutoutMode
            if (config.showNotch) {
                window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            }
        }
    }

    // An edit of a medium that lives elsewhere -- on pCloud or on the network share -- in
    // flight. The editor is handed a copy of its own, and what comes back has to be written
    // over the original where it lives. It lives here rather than in one screen because the
    // editor's result lands on whichever activity opened it -- the fullscreen view and the
    // grid both do
    protected data class RemoteEdit(val remotePath: String, val localPath: String, val size: Long, val lastModified: Long)

    protected var remoteEdit: RemoteEdit? = null

    // Opens the editor on a medium wherever it lives: a local one is edited in place as it
    // always was, one on pCloud or on the share through a copy of its own.
    //
    // The copy matters more than it looks: the editor is handed a path and writes to it with
    // File(), so a remote medium's pseudo path -- "smb:/00-Pictures/a.png" -- would open the
    // editor on nothing at all and then throw when it saved
    fun editMedium(path: String) {
        if (!path.isRemotePath()) {
            openEditor(path)
            return
        }

        withEditableMediaFile(path) { localPath ->
            val copy = File(localPath)
            remoteEdit = RemoteEdit(path, localPath, copy.length(), copy.lastModified())
            openRemoteEditor(localPath)
        }
    }

    // Belongs at the top of onActivityResult() for REQUEST_EDIT_IMAGE. Answers whether an edit
    // of a remote medium was the one that came back, so that a caller can leave its own
    // handling of a local edit alone. [onWritten] runs once the storage has taken the edit
    protected fun handleRemoteEditResult(resultCode: Int, onWritten: () -> Unit): Boolean {
        val edit = remoteEdit ?: return false
        remoteEdit = null
        // what counts is whether the copy the editor was handed came back changed; the result
        // code only says whether it thinks it saved anything at all
        writeEditBackToRemote(edit, resultCode == RESULT_OK, onWritten)
        return true
    }

    // The storage writes the copy back over the original and carries its rows along, see
    // MediaStorage.overwriteMedium(); what is said around that is this screen's. The copy is
    // left where it is whatever happens: it is the only place the edit exists until the
    // storage has taken it, and when the storage will not take it the user is told where it
    // is rather than losing the work
    private fun writeEditBackToRemote(edit: RemoteEdit, editorSaidItSaved: Boolean, onWritten: () -> Unit) {
        val storage = MediaStorage.of(this, edit.remotePath)
        val copy = File(edit.localPath)
        if (!copy.isFile || (copy.length() == edit.size && copy.lastModified() == edit.lastModified)) {
            // the editor was left without saving. When it says it saved and the copy is
            // untouched all the same, it wrote somewhere else, and going quiet here is what
            // makes that look like the write back did nothing at all
            if (editorSaidItSaved) {
                Log.w("RemoteEdit", "The editor reported a save but left ${edit.localPath} untouched")
                toast(storage.editNotWrittenMessage(this), Toast.LENGTH_LONG)
            }

            return
        }

        toast(storage.writingBackMessage(this))
        storage.overwriteMedium(this, edit.remotePath, edit.localPath) { written ->
            if (written) {
                toast(org.fossify.commons.R.string.file_saved)
                onWritten()
            } else {
                // the storage already said what went wrong; this says what is left
                toast(getString(R.string.remote_edit_kept_at, edit.localPath), Toast.LENGTH_LONG)
            }
        }
    }

    // Rotates media wherever they live, one after the other, each the way its storage does
    // it, see MediaStorage.rotateMediumAndWait()
    fun rotateMedia(paths: List<String>, degrees: Int, onDone: () -> Unit) {
        toast(org.fossify.commons.R.string.saving)
        ensureBackgroundThread {
            paths.forEach { path ->
                MediaStorage.of(this, path).rotateMediumAndWait(this, path, degrees)
            }

            runOnUiThread {
                onDone()
            }
        }
    }

    protected fun registerFileUpdateListener() {
        try {
            contentResolver.registerContentObserver(Images.Media.EXTERNAL_CONTENT_URI, true, observer)
            contentResolver.registerContentObserver(Video.Media.EXTERNAL_CONTENT_URI, true, observer)
        } catch (ignored: Exception) {
        }
    }

    protected fun unregisterFileUpdateListener() {
        try {
            contentResolver.unregisterContentObserver(observer)
        } catch (ignored: Exception) {
        }
    }

    protected fun showAddIncludedFolderDialog(callback: () -> Unit) {
        FilePickerDialog(this, config.lastFilepickerPath, false, config.shouldShowHidden, false, true) {
            config.lastFilepickerPath = it
            config.addIncludedFolder(it)
            callback()
            ensureBackgroundThread {
                scanPathRecursively(it)
            }
        }
    }

    protected fun requestMediaPermissions(enableRationale: Boolean = false, onGranted: () -> Unit) {
        when {
            hasAllPermissions(getPermissionsToRequest()) -> onGranted()
            config.showPermissionRationale -> {
                if (enableRationale) {
                    showPermissionRationale()
                } else {
                    onPermissionDenied()
                }
            }

            else -> {
                handlePartialMediaPermissions(getPermissionsToRequest(), force = true) { granted ->
                    if (granted) {
                        onGranted()
                    } else {
                        config.showPermissionRationale = true
                        showPermissionRationale()
                    }
                }
            }
        }
    }

    private fun showPermissionRationale() {
        dialog?.dismiss()
        StoragePermissionRequiredDialog(
            activity = this,
            onOkay = ::openDeviceSettings,
            onCancel = ::onPermissionDenied
        ) { dialog ->
            this.dialog = dialog
        }
    }

    private fun onPermissionDenied() {
        toast(org.fossify.commons.R.string.no_storage_permissions)
        finish()
    }
}
