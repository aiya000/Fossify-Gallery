package org.fossify.gallery.helpers

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import org.fossify.commons.extensions.isGif
import org.fossify.commons.extensions.isImageFast
import org.fossify.commons.extensions.isRawFast
import org.fossify.commons.extensions.isSvg
import org.fossify.commons.extensions.isVideoFast
import org.fossify.commons.helpers.SORT_BY_SIZE
import org.fossify.gallery.R
import org.fossify.gallery.databases.GalleryDatabase
import org.fossify.gallery.extensions.config
import org.fossify.gallery.extensions.createDirectoryFromMedia
import org.fossify.gallery.extensions.directoryDB
import org.fossify.gallery.extensions.favoritesDB
import org.fossify.gallery.extensions.getFavoritePaths
import org.fossify.gallery.extensions.getNoMediaFoldersSync
import org.fossify.gallery.extensions.mediaDB
import org.fossify.gallery.extensions.pCloudItemsDB
import org.fossify.gallery.models.Directory
import org.fossify.gallery.models.Medium
import org.fossify.gallery.models.PCloudItem
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

// Pulls the whole pCloud tree down in one listfolder call and rewrites the pCloud half of the
// media and directories cache from it. Folders without an image or video in them are left out,
// the same way MediaFetcher leaves out local folders without media.
//
// The answer is parsed as a stream: an account with thousands of files answers with megabytes
// of JSON, and holding that as one string plus an org.json tree costs several times its size.
// What is kept per entry is a few fields, and files that are not media are dropped as soon as
// they are parsed, so memory follows the number of media files rather than the answer size
class PCloudScanner(private val context: Context) {
    companion object {
        // one scan at a time. Context.rescanPCloud() claims this before it starts one
        val isRunning = AtomicBoolean(false)
    }

    class Result(val folderCount: Int, val mediaCount: Int)

    // one entry of a listfolder answer, trimmed to what the cache needs. children holds only
    // folders and media files. type is 0 for a folder and for a file that is not media
    private class Entry(
        val name: String, val isFolder: Boolean, val itemId: Long, val modified: Long, val size: Long,
        val type: Int, val hash: Long, val hasThumb: Boolean, val duration: Int, val children: List<Entry>
    )

    private val config = context.config

    // pCloud sends dates like "Thu, 25 Sep 2014 10:31:14 +0000"
    private val dateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US)

    // blocks and talks to the network, so call it off the main thread. Throws PCloudException
    // when pCloud refused and an IOException when it could not be reached; the cache is left
    // untouched in both cases, it is only rewritten once the whole tree has arrived
    fun scanAll(): Result {
        val root = fetchTree()

        val media = ArrayList<Medium>()
        val directories = ArrayList<Directory>()
        val items = ArrayList<PCloudItem>()
        val collector = Collector(media, directories, items)
        collector.collect(root, PCLOUD_PATH_SCHEME)

        store(media, directories, items)
        config.pCloudLastFullScanAt = System.currentTimeMillis()
        return Result(directories.size, media.size)
    }

    private fun fetchTree(): Entry {
        var root: Entry? = null
        val params = mapOf("path" to "/", "recursive" to "1")
        PCloudApi.stream(config.pCloudApiHost, config.pCloudAccessToken, "listfolder", params) { name, reader ->
            if (name == "metadata") {
                root = readEntry(reader)
            } else {
                reader.skipValue()
            }
        }

        return root ?: throw IllegalStateException("pCloud answered listfolder without metadata")
    }

    private fun readEntry(reader: JsonReader): Entry {
        var name = ""
        var isFolder = false
        var itemId = 0L
        var modified = 0L
        var size = 0L
        var category = 0
        var hash = 0L
        var hasThumb = false
        var duration = 0
        var children = emptyList<Entry>()

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "name" -> name = reader.nextString()
                "isfolder" -> isFolder = reader.nextBoolean()
                "folderid", "fileid" -> itemId = reader.nextLong()
                "modified" -> modified = parseDate(reader.nextString())
                "size" -> size = reader.nextLong()
                "category" -> category = reader.nextInt()
                // an unsigned 64 bit number, which may not fit nextLong(); the bit pattern is
                // all that matters, it is only ever compared for equality
                "hash" -> hash = readStringOrNull(reader)?.let { java.lang.Long.parseUnsignedLong(it) } ?: 0L
                "thumb" -> hasThumb = reader.nextBoolean()
                // seconds, sent for videos only, as a string like "12.34"
                "duration" -> duration = readStringOrNull(reader)?.toDoubleOrNull()?.toInt() ?: 0
                "contents" -> children = readContents(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        val type = if (isFolder) 0 else getMediaType(name, category)
        return Entry(name, isFolder, itemId, modified, size, type, hash, hasThumb, duration, children)
    }

    private fun readContents(reader: JsonReader): List<Entry> {
        val entries = ArrayList<Entry>()
        reader.beginArray()
        while (reader.hasNext()) {
            val entry = readEntry(reader)
            if (entry.isFolder || entry.type != 0) {
                entries.add(entry)
            }
        }
        reader.endArray()
        return entries
    }

    // JsonReader gives a number's text through nextString() too
    private fun readStringOrNull(reader: JsonReader): String? {
        return if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            null
        } else {
            reader.nextString()
        }
    }

    private fun parseDate(raw: String): Long {
        return try {
            dateFormat.parse(raw)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    // classified by the filename like local files are, with pCloud's own category as the
    // fallback for an extension it does not know. 0 means the file is not media
    private fun getMediaType(name: String, category: Int) = when {
        name.isImageFast() -> TYPE_IMAGES
        name.isVideoFast() -> TYPE_VIDEOS
        name.isGif() -> TYPE_GIFS
        name.isRawFast() -> TYPE_RAWS
        name.isSvg() -> TYPE_SVGS
        category == PCLOUD_CATEGORY_IMAGE -> TYPE_IMAGES
        category == PCLOUD_CATEGORY_VIDEO -> TYPE_VIDEOS
        else -> 0
    }

    // walks the parsed tree and turns it into the rows the cache holds. The Directory rows are
    // built by the same createDirectoryFromMedia() that builds local folders, so MainActivity's
    // recheck of the displayed folders finds nothing to change in them
    private inner class Collector(
        private val media: ArrayList<Medium>,
        private val directories: ArrayList<Directory>,
        private val items: ArrayList<PCloudItem>
    ) {
        private val scannedAt = System.currentTimeMillis()
        private val favoritePaths = context.getFavoritePaths()
        private val albumCovers = config.parseAlbumCovers()
        private val hiddenString = context.getString(R.string.hidden)
        private val includedFolders = config.includedFolders
        private val noMediaFolders = context.getNoMediaFoldersSync()
        private val getProperFileSize = config.directorySorting and SORT_BY_SIZE != 0
        private val mediaFetcher = MediaFetcher(context)

        fun collect(folder: Entry, path: String) {
            val folderMedia = ArrayList<Medium>()
            folder.children.forEach { child ->
                val childPath = "$path/${child.name}"
                if (child.isFolder) {
                    collect(child, childPath)
                } else {
                    // pCloud knows when a file was uploaded or changed, not when the photo was
                    // taken, so the modification time stands in for both like it does on OTG
                    val medium = Medium(
                        id = null,
                        name = child.name,
                        path = childPath,
                        parentPath = path,
                        modified = child.modified,
                        taken = child.modified,
                        size = child.size,
                        type = child.type,
                        videoDuration = child.duration,
                        isFavorite = favoritePaths.contains(childPath),
                        deletedTS = 0L,
                        mediaStoreId = 0L
                    )
                    folderMedia.add(medium)
                    items.add(PCloudItem(null, childPath, child.itemId, false, child.hash, child.hasThumb, scannedAt))
                }
            }

            if (folderMedia.isEmpty()) {
                return
            }

            mediaFetcher.sortMedia(folderMedia, config.getFolderSorting(path), path)
            val directory = context.createDirectoryFromMedia(
                path = path,
                curMedia = folderMedia,
                albumCovers = albumCovers,
                hiddenString = hiddenString,
                includedFolders = includedFolders,
                getProperFileSize = getProperFileSize,
                noMediaFolders = noMediaFolders
            )

            media.addAll(folderMedia)
            directories.add(directory)
            items.add(PCloudItem(null, path, folder.itemId, true, 0L, false, scannedAt))
        }
    }

    // replaces the pCloud rows in one transaction, so a folder list read in between never sees
    // half of a scan. Rows pCloud no longer has are dropped one by one: the list is short, and
    // a NOT IN over thousands of paths would trip SQLite's argument limit
    private fun store(media: List<Medium>, directories: List<Directory>, items: List<PCloudItem>) {
        GalleryDatabase.getInstance(context).runInTransaction {
            val keptMediaPaths = media.map { it.path }.toHashSet()
            context.mediaDB.getPathsWithPrefix(PCLOUD_PATH_SCHEME).filter { it !in keptMediaPaths }.forEach { path ->
                context.mediaDB.deleteMediumPath(path)
                context.favoritesDB.deleteFavoritePath(path)
            }

            val keptDirectoryPaths = directories.map { it.path }.toHashSet()
            context.directoryDB.getPathsWithPrefix(PCLOUD_PATH_SCHEME).filter { it !in keptDirectoryPaths }.forEach { path ->
                context.directoryDB.deleteDirPath(path)
            }

            context.pCloudItemsDB.deleteAll()
            context.pCloudItemsDB.insertAll(items)
            context.mediaDB.insertAll(media)
            context.directoryDB.insertAll(directories)
        }
    }
}
