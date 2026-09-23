package org.fossify.gallery.helpers

import android.content.Context
import org.fossify.commons.extensions.getFilenameExtension
import org.fossify.gallery.extensions.mediaDB
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

// Local copies of SMB files, for the parts of the app that need a real file: the zoomable photo
// view, GIF and SVG decoding, the editor. The same shape as PCloudFileCache, with one
// difference: a share tells no content hash, so the copy is named after the size and the
// modification time the scan saw. A file replaced on the share keeps its name only while both
// of those are unchanged, which is as far as SMB lets us tell
class SmbFileCache(private val context: Context) {
    companion object {
        private const val MAX_BYTES = 512L * 1024 * 1024

        // the copy is written under this and renamed into place, so that a download that broke
        // off can never be picked up as a whole file
        private const val PARTIAL_SUFFIX = ".part"

        // what the cached copy of a medium is called. Only the scanned rows know the size and
        // the modification time, so a medium that is not in the cache has no name here
        fun nameOf(path: String, size: Long, modified: Long): String {
            val stem = "${path.hashCode().toUInt()}-$size-$modified"
            val extension = path.getFilenameExtension()
            return if (extension.isEmpty()) stem else "$stem.$extension"
        }
    }

    private val dir = File(context.cacheDir, SMB_CACHE_DIR)

    // Blocks and talks to the network, so call it off the main thread. Throws FileNotFoundException
    // for a path no scan has seen and an IOException when the share refused or the read was short
    fun fetch(path: String): File {
        val target = targetOf(path) ?: throw FileNotFoundException(path)
        if (target.isFile && target.length() > 0) {
            target.setLastModified(System.currentTimeMillis())
            return target
        }

        dir.mkdirs()
        val partial = File(dir, "${target.name}$PARTIAL_SUFFIX")
        try {
            SmbClient.open(context, path).use { open ->
                val expected = open.size
                val written = partial.outputStream().use { out ->
                    open.inputStream().copyTo(out)
                }

                // a connection that drops part way through just ends the stream; renaming a
                // short read into place would cache a torn image under a name that only changes
                // when the file changes on the share, so it would be served from then on
                if (written != expected) {
                    throw IOException("The share sent $written bytes of $expected for $path")
                }
            }

            if (!partial.renameTo(target)) {
                throw IOException("Could not move the downloaded file into place")
            }
        } finally {
            partial.delete()
        }

        trim(keep = target)
        return target
    }

    // the copy that is already there, or null without touching the network
    fun peek(path: String): File? = targetOf(path)?.takeIf { it.isFile && it.length() > 0 }

    // whether a copy of this name -- "<hash>-<size>-<modified>", whatever its extension -- is
    // still here. A hard link made from a copy keeps its bytes alive after the cache has let
    // go of it, so the links are cleared out by asking this; the same as PCloudFileCache
    fun holds(name: String): Boolean {
        return dir.listFiles()?.any { it.isFile && it.nameWithoutExtension == name } == true
    }

    // Carries the copy of a renamed medium over to its new name.
    //
    // The copy is named after the path, so a rename on the share would otherwise leave it behind
    // under a name nothing asks for any more, and the same bytes would be fetched again. The size
    // and the modification time are the caller's because the rows have usually moved by the time
    // this runs. Nothing is lost when there is no copy, or when the rename of it fails: the next
    // fetch downloads it
    fun renameCopy(oldPath: String, newPath: String, size: Long, modified: Long) {
        val from = File(dir, nameOf(oldPath, size, modified))
        if (from.isFile) {
            from.renameTo(File(dir, nameOf(newPath, size, modified)))
        }
    }

    // Drops the copy of a medium whose bytes on the share have just been replaced.
    //
    // Nothing would serve it again in any case -- the name carries the size and the modification
    // time, and the row has moved on to the new pair -- so this is about the bytes rather than
    // about what is shown: an overwritten photo is one nobody asked to keep, and leaving it to
    // the 512MB trim would let it push out a copy somebody is using
    fun deleteCopy(path: String, size: Long, modified: Long) {
        File(dir, nameOf(path, size, modified)).delete()
    }

    // null for a path no scan has seen: without the size and the modification time there is no
    // name to look for
    private fun targetOf(path: String): File? {
        val medium = context.mediaDB.getMediumByPath(path) ?: return null
        return File(dir, nameOf(path, medium.size, medium.modified))
    }

    private fun trim(keep: File) {
        val files = dir.listFiles()?.filter { it.isFile && it != keep } ?: return
        var total = files.sumOf { it.length() } + keep.length()
        if (total <= MAX_BYTES) {
            return
        }

        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= MAX_BYTES) {
                break
            }

            val size = file.length()
            if (file.delete()) {
                total -= size
            }
        }
    }
}
