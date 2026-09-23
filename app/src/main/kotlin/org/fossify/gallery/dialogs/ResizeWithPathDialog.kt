package org.fossify.gallery.dialogs

import android.graphics.Point
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.R
import org.fossify.gallery.databinding.DialogResizeImageWithPathBinding
import org.fossify.gallery.extensions.config
import org.fossify.gallery.extensions.humanizeAnyPath
import org.fossify.gallery.helpers.MediaStorage

// The destination is picked with the gallery's own folder picker rather than commons', so that
// a pCloud folder can be picked: a resized pCloud image belongs back on pCloud, and the commons
// picker only walks the device. A local image can go to pCloud the same way
class ResizeWithPathDialog(val activity: BaseSimpleActivity, val size: Point, val path: String, val callback: (newSize: Point, newPath: String) -> Unit) {
    init {
        var realPath = path.getParentPath()
        val binding = DialogResizeImageWithPathBinding.inflate(activity.layoutInflater).apply {
            folder.setText("${displayPath(realPath).trimEnd('/')}/")

            val fullName = path.getFilenameFromPath()
            val dotAt = fullName.lastIndexOf(".")
            var name = fullName

            if (dotAt > 0) {
                name = fullName.substring(0, dotAt)
                val extension = fullName.substring(dotAt + 1)
                extensionValue.setText(extension)
            }

            filenameValue.setText(name)
            folder.setOnClickListener {
                FolderPickerDialog(activity, realPath, activity.config.shouldShowHidden, showFAB = true, canAddShowHiddenButton = true) {
                    folder.setText(displayPath(it))
                    realPath = it
                }
            }
        }

        val widthView = binding.resizeImageWidth
        val heightView = binding.resizeImageHeight

        widthView.setText(size.x.toString())
        heightView.setText(size.y.toString())

        val ratio = size.x / size.y.toFloat()

        widthView.onTextChangeListener {
            if (widthView.hasFocus()) {
                var width = getViewValue(widthView)
                if (width > size.x) {
                    widthView.setText(size.x.toString())
                    width = size.x
                }

                heightView.setText((width / ratio).toInt().toString())
            }
        }

        heightView.onTextChangeListener {
            if (heightView.hasFocus()) {
                var height = getViewValue(heightView)
                if (height > size.y) {
                    heightView.setText(size.y.toString())
                    height = size.y
                }

                widthView.setText((height * ratio).toInt().toString())
            }
        }

        activity.getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this) { alertDialog ->
                    alertDialog.showKeyboard(binding.resizeImageWidth)
                    alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val width = getViewValue(widthView)
                        val height = getViewValue(heightView)
                        if (width <= 0 || height <= 0) {
                            activity.toast(R.string.invalid_values)
                            return@setOnClickListener
                        }

                        val newSize = Point(getViewValue(widthView), getViewValue(heightView))

                        val filename = binding.filenameValue.value
                        val extension = binding.extensionValue.value
                        if (filename.isEmpty()) {
                            activity.toast(org.fossify.commons.R.string.filename_cannot_be_empty)
                            return@setOnClickListener
                        }

                        if (extension.isEmpty()) {
                            activity.toast(org.fossify.commons.R.string.extension_cannot_be_empty)
                            return@setOnClickListener
                        }

                        val newFilename = "$filename.$extension"
                        val newPath = "${realPath.trimEnd('/')}/$newFilename"
                        if (!newFilename.isAValidFilename()) {
                            activity.toast(org.fossify.commons.R.string.filename_invalid_characters)
                            return@setOnClickListener
                        }

                        confirmOverwriteAndFinish(newSize, newPath, newFilename, alertDialog)
                    }
                }
            }
    }

    // Whether the name is taken is the destination's storage's to say -- a look at the
    // filesystem on the device, a database lookup on pCloud, a question to the share -- so it
    // goes off the main thread and comes back to ask. An overwritten medium does not pass
    // through the recycle bin on any storage, so the question is asked for real
    private fun confirmOverwriteAndFinish(newSize: Point, newPath: String, newFilename: String, alertDialog: AlertDialog) {
        val storage = MediaStorage.of(activity, newPath)
        ensureBackgroundThread {
            val exists = try {
                storage.isNameTaken(newPath)
            } catch (e: Exception) {
                activity.showErrorToast(e)
                return@ensureBackgroundThread
            }

            activity.runOnUiThread {
                finish(newSize, newPath, newFilename, exists, alertDialog)
            }
        }
    }

    private fun finish(newSize: Point, newPath: String, newFilename: String, nameIsTaken: Boolean, alertDialog: AlertDialog) {
        if (!nameIsTaken) {
            callback(newSize, newPath)
            alertDialog.dismiss()
            return
        }

        val title = String.format(activity.getString(org.fossify.commons.R.string.file_already_exists_overwrite), newFilename)
        ConfirmationDialog(activity, title) {
            callback(newSize, newPath)
            alertDialog.dismiss()
        }
    }

    // the pseudo path the gallery uses inside itself is "pcloud:/Photos" or "smb:/Photos"; what
    // belongs in front of the user is where it is, said the way the folder list says it
    private fun displayPath(path: String) = activity.humanizeAnyPath(path)

    private fun getViewValue(view: EditText): Int {
        val textValue = view.value
        return if (textValue.isEmpty()) 0 else textValue.toInt()
    }
}
