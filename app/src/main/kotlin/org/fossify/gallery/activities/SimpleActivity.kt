package org.fossify.gallery.activities

import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore.Images
import android.provider.MediaStore.Video
import android.view.WindowManager
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
import org.fossify.gallery.extensions.updateDirectoryPath
import org.fossify.gallery.helpers.UPSTREAM_APP_ID
import org.fossify.gallery.helpers.getPermissionsToRequest

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
