@file:androidx.annotation.OptIn(markerClass = [UnstableApi::class])

package org.fossify.gallery.helpers

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec

// Lets the player read a video straight off the share, so that watching one does not first
// download it. The player asks for ranges and SMB can answer a read at an offset, so seeking
// costs one request rather than the whole file.
//
// The medium's pseudo path is carried as an opaque uri ("smb:/folder/clip.mp4"), built by
// toMediaUri(): a name with a "#" or a "?" in it would otherwise be cut short by Uri parsing
class SmbDataSource(private val context: Context) : BaseDataSource(true) {
    companion object {
        // the uri the player is given for a medium, and what open() takes apart again
        fun toMediaUri(path: String): Uri = Uri.fromParts(SMB_URI_SCHEME, path.removePrefix(SMB_PATH_SCHEME), null)

        private const val SMB_URI_SCHEME = "smb"
    }

    class Factory(private val context: Context) : DataSource.Factory {
        override fun createDataSource() = SmbDataSource(context)
    }

    private var spec: DataSpec? = null
    private var file: SmbClient.OpenFile? = null
    private var position = 0L
    private var bytesRemaining = 0L

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val path = "$SMB_PATH_SCHEME${dataSpec.uri.schemeSpecificPart}"
        val open = SmbClient.open(context, path)
        file = open
        spec = dataSpec
        position = dataSpec.position
        bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) open.size - dataSpec.position else dataSpec.length
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) {
            return 0
        }

        if (bytesRemaining == 0L) {
            return C.RESULT_END_OF_INPUT
        }

        val wanted = minOf(length.toLong(), bytesRemaining).toInt()
        val read = file?.readAt(position, buffer, offset, wanted) ?: return C.RESULT_END_OF_INPUT
        if (read < 0) {
            return C.RESULT_END_OF_INPUT
        }

        position += read
        bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = spec?.uri

    override fun close() {
        val wasOpen = file != null
        file?.close()
        file = null
        spec = null
        if (wasOpen) {
            transferEnded()
        }
    }
}
