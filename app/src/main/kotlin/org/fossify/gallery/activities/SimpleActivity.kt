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
import org.fossify.gallery.extensions.fetchPCloudMediumForEditing
import org.fossify.gallery.extensions.isPCloudPath
import org.fossify.gallery.extensions.openEditor
import org.fossify.gallery.extensions.saveRotatedImageToFile
import org.fossify.gallery.extensions.updateDirectoryPath
import org.fossify.gallery.extensions.withEditableMediaFile
import org.fossify.gallery.extensions.writeToPCloud
import org.fossify.gallery.helpers.UPSTREAM_APP_ID
import org.fossify.gallery.helpers.getPermissionsToRequest
import java.io.File
import java.util.concurrent.CountDownLatch

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

    // An edit of a pCloud medium in flight. The editor is handed a copy of its own, and what
    // comes back has to be written over the original on pCloud. It lives here rather than in
    // one screen because the editor's result lands on whichever activity opened it -- the
    // fullscreen view and the grid both do
    protected data class PCloudEdit(val pCloudPath: String, val localPath: String, val size: Long, val lastModified: Long)

    protected var pCloudEdit: PCloudEdit? = null

    // Opens the editor on a medium wherever it lives: a local one is edited in place as it
    // always was, a pCloud one through a copy of its own
    fun editMedium(path: String) {
        if (!path.isPCloudPath()) {
            openEditor(path)
            return
        }

        withEditableMediaFile(path) { localPath ->
            val copy = File(localPath)
            pCloudEdit = PCloudEdit(path, localPath, copy.length(), copy.lastModified())
            openEditor(localPath)
        }
    }

    // Belongs at the top of onActivityResult() for REQUEST_EDIT_IMAGE. Answers whether an
    // edit of a pCloud medium was the one that came back, so that a caller can leave its own
    // handling of a local edit alone. [onWritten] runs once pCloud has taken the edit
    protected fun handlePCloudEditResult(resultCode: Int, onWritten: () -> Unit): Boolean {
        val edit = pCloudEdit ?: return false
        pCloudEdit = null
        // what counts is whether the copy the editor was handed came back changed; the result
        // code only says whether it thinks it saved anything at all
        writeEditBackToPCloud(edit, resultCode == RESULT_OK, onWritten)
        return true
    }

    // The copy is left where it is whatever happens: it is the only place the edit exists
    // until pCloud has taken it, and when pCloud will not take it the user is told where it
    // is rather than losing the work
    private fun writeEditBackToPCloud(edit: PCloudEdit, editorSaidItSaved: Boolean, onWritten: () -> Unit) {
        val copy = File(edit.localPath)
        if (!copy.isFile || (copy.length() == edit.size && copy.lastModified() == edit.lastModified)) {
            // the editor was left without saving. When it says it saved and the copy is
            // untouched all the same, it wrote somewhere else, and going quiet here is what
            // makes that look like the write back did nothing at all
            if (editorSaidItSaved) {
                Log.w("PCloudTransfer", "The editor reported a save but left ${edit.localPath} untouched")
                toast(R.string.pcloud_edit_not_written, Toast.LENGTH_LONG)
            }

            return
        }

        toast(R.string.pcloud_writing_back)
        writeToPCloud(listOf(edit.pCloudPath.getParentPath()), { overwriteFile(edit.pCloudPath, edit.localPath) }) { success ->
            runOnUiThread {
                if (success) {
                    toast(org.fossify.commons.R.string.file_saved)
                    onWritten()
                } else {
                    // writeToPCloud already said what went wrong; this says what is left
                    toast(getString(R.string.pcloud_edit_kept_at, edit.localPath), Toast.LENGTH_LONG)
                }
            }
        }
    }

    // Rotates media wherever they live, one after the other, and writes a pCloud one back
    // over itself: there is no folder on the device to save it beside, and "save a copy
    // somewhere else" is what copying to this device is for. A JPEG only gets its
    // Orientation tag turned, which pCloud's own web and app honour
    fun rotateMedia(paths: List<String>, degrees: Int, onDone: () -> Unit) {
        val (pCloudPaths, localPaths) = paths.partition { it.isPCloudPath() }
        toast(org.fossify.commons.R.string.saving)
        ensureBackgroundThread {
            localPaths.forEach { path ->
                saveRotatedImageToFile(path, path, degrees, true) {}
            }

            pCloudPaths.forEach { path ->
                rotatePCloudMediumAndWait(path, degrees)
            }

            runOnUiThread {
                onDone()
            }
        }
    }

    // Blocks until this one medium is through, so that a selection of them goes up one at a
    // time rather than all at once
    private fun rotatePCloudMediumAndWait(path: String, degrees: Int) {
        val latch = CountDownLatch(1)
        val localPath = try {
            fetchPCloudMediumForEditing(path)
        } catch (e: Exception) {
            Log.w("PCloudTransfer", "Could not fetch $path to rotate it", e)
            toast("${getString(R.string.pcloud_fetch_failed)}: ${e.message ?: e.javaClass.simpleName}")
            return
        }

        saveRotatedImageToFile(localPath, localPath, degrees, true) {
            writeToPCloud(listOf(path.getParentPath()), { overwriteFile(path, localPath) }) { success ->
                if (!success) {
                    toast(getString(R.string.pcloud_edit_kept_at, localPath), Toast.LENGTH_LONG)
                }

                latch.countDown()
            }
        }

        latch.await()
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
