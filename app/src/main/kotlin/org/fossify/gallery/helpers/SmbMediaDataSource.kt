package org.fossify.gallery.helpers

import android.content.Context
import android.media.MediaDataSource

// A video on the share read through MediaMetadataRetriever without downloading it: the
// retriever asks for the few kilobytes it needs -- the container's index and one frame -- and
// SMB can answer a read at an offset, so a thumbnail of a 2GB video costs a handful of reads.
//
// Blocks on the network for every read, so it may only be handed to a retriever off the main
// thread. close() closes the handle on the share
class SmbMediaDataSource(context: Context, path: String) : MediaDataSource() {
    private val file = SmbClient.open(context, path)

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) {
            return 0
        }

        return file.readAt(position, buffer, offset, size)
    }

    override fun getSize() = file.size

    override fun close() = file.close()
}
