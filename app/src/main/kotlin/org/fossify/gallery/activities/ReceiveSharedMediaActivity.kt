package org.fossify.gallery.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.MimeTypeMap
import org.fossify.commons.extensions.getFilenameFromUri
import org.fossify.commons.extensions.getMimeTypeFromUri
import org.fossify.commons.extensions.isImageFast
import org.fossify.commons.extensions.isVideoFast
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.FileDirItem
import org.fossify.gallery.R
import org.fossify.gallery.extensions.tryCopyMoveFilesTo
import org.fossify.gallery.helpers.SHARED_MEDIA_DIR
import java.io.File

// Takes the images and videos handed over by Android's share sheet and copies them into a
// folder the user picks. A copy and never a move: the file belongs to the app that shared it,
// and nothing here may delete it.
//
// A share arrives as content:// uris, which the ordinary copy cannot take -- it works on real
// paths. So every uri is first written into a staging folder in the cache under the name it
// is shared as, and those files are what the ordinary copy then moves on. The conflict
// dialog, the progress and the media scan come along with it unchanged.
//
// The destination picker is narrowed to this device: copying into pCloud is #53.
//
// The activity has no layout of its own, so nothing is left on screen once the picker is
// gone. That is why the picker reports a cancel: without it this would sit there invisible,
// swallowing every tap
class ReceiveSharedMediaActivity : SimpleActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uris = getSharedMediaUris()
        if (uris.isEmpty()) {
            toast(R.string.shared_media_none)
            finish()
            return
        }

        requestMediaPermissions(enableRationale = true) {
            stageAndPickDestination(uris)
        }
    }

    // The share sheet hands over one uri for ACTION_SEND and a list for ACTION_SEND_MULTIPLE;
    // a list may hold images and videos together, and anything else in it is dropped here
    @Suppress("DEPRECATION")
    private fun getSharedMediaUris(): List<Uri> {
        val shared = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else -> emptyList()
        }

        return shared.filter { isImageOrVideo(it) }
    }

    private fun isImageOrVideo(uri: Uri): Boolean {
        val mimeType = getMimeTypeFromUri(uri)
        if (mimeType.startsWith("image/") || mimeType.startsWith("video/")) {
            return true
        }

        // a provider that answers no type of its own still gives a name to go by
        val filename = getFilenameFromUri(uri)
        return filename.isImageFast() || filename.isVideoFast()
    }

    private fun stageAndPickDestination(uris: List<Uri>) {
        toast(R.string.shared_media_preparing)
        ensureBackgroundThread {
            val staged = stageSharedMedia(uris)
            runOnUiThread {
                if (staged.isEmpty()) {
                    toast(R.string.shared_media_unreadable)
                    finish()
                    return@runOnUiThread
                }

                tryCopyMoveFilesTo(
                    fileDirItems = staged,
                    isCopyOperation = true,
                    localDestinationOnly = true,
                    onCancelled = ::dropStagedMediaAndFinish
                ) {
                    dropStagedMediaAndFinish()
                }
            }
        }
    }

    // Reads every shared uri into the staging folder. One that cannot be read is left out
    // rather than failing the whole share: the rest of a multiple share is still worth copying
    private fun stageSharedMedia(uris: List<Uri>): ArrayList<FileDirItem> {
        val stagingDir = stagingDir()
        stagingDir.deleteRecursively()
        stagingDir.mkdirs()

        val staged = ArrayList<FileDirItem>()
        uris.forEach { uri ->
            val file = unusedFile(stagingDir, stagedFilename(uri))
            try {
                contentResolver.openInputStream(uri).use { input ->
                    if (input == null) {
                        return@forEach
                    }

                    file.outputStream().use { input.copyTo(it) }
                }
            } catch (e: Exception) {
                Log.w("SharedMedia", "Could not read $uri handed over by the share sheet", e)
                file.delete()
                return@forEach
            }

            staged.add(FileDirItem(file.absolutePath, file.name, false, 0, file.length(), file.lastModified()))
        }

        return staged
    }

    // The copy only takes files it can tell are photos or videos by their name, so a shared
    // name without a usable extension gets one from the type the provider reports
    private fun stagedFilename(uri: Uri): String {
        val sharedName = getFilenameFromUri(uri)
        if (sharedName.isImageFast() || sharedName.isVideoFast()) {
            return sharedName
        }

        val base = sharedName.substringBeforeLast('.').ifEmpty { "shared_${System.currentTimeMillis()}" }
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(getMimeTypeFromUri(uri)).orEmpty()
        return if (extension.isEmpty()) base else "$base.$extension"
    }

    // Two shared files can carry the same name, and the second one must not overwrite the
    // first while it is staged. The destination's own name clashes are the copy's business
    private fun unusedFile(stagingDir: File, filename: String): File {
        var file = File(stagingDir, filename)
        var attempt = 1
        while (file.exists()) {
            val base = filename.substringBeforeLast('.')
            val extension = filename.substringAfterLast('.', "")
            val unused = if (extension.isEmpty()) "${base}_$attempt" else "${base}_$attempt.$extension"
            file = File(stagingDir, unused)
            attempt++
        }

        return file
    }

    // The staged copies are of no use once the copy is through. A share that never got that
    // far leaves them behind; the next share empties the folder before it writes into it
    private fun dropStagedMediaAndFinish() {
        ensureBackgroundThread {
            stagingDir().deleteRecursively()
        }

        finish()
    }

    private fun stagingDir() = File(cacheDir, SHARED_MEDIA_DIR)
}
