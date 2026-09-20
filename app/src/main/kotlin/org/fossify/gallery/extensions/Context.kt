package org.fossify.gallery.extensions

import android.annotation.SuppressLint
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.PictureDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Process
import android.provider.MediaStore.Files
import android.provider.MediaStore.Images
import android.util.Log
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.Priority
import com.bumptech.glide.integration.webp.WebpBitmapFactory
import com.bumptech.glide.integration.webp.decoder.WebpDownsampler
import com.bumptech.glide.integration.webp.decoder.WebpDrawable
import com.bumptech.glide.integration.webp.decoder.WebpDrawableTransformation
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.MultiTransformation
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.FitCenter
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.signature.ObjectKey
import com.squareup.picasso.Picasso
import org.fossify.commons.extensions.doesThisOrParentHaveNoMedia
import org.fossify.commons.extensions.getDocumentFile
import org.fossify.commons.extensions.getDoesFilePathExist
import org.fossify.commons.extensions.getDuration
import org.fossify.commons.extensions.getFilenameFromPath
import org.fossify.commons.extensions.getLongValue
import org.fossify.commons.extensions.getMimeTypeFromUri
import org.fossify.commons.extensions.getOTGPublicPath
import org.fossify.commons.extensions.getParentPath
import org.fossify.commons.extensions.getStringValue
import org.fossify.commons.extensions.humanizePath
import org.fossify.commons.extensions.internalStoragePath
import org.fossify.commons.extensions.isGif
import org.fossify.commons.extensions.isPathOnOTG
import org.fossify.commons.extensions.isPathOnSD
import org.fossify.commons.extensions.isPng
import org.fossify.commons.extensions.isPortrait
import org.fossify.commons.extensions.isRawFast
import org.fossify.commons.extensions.isSvg
import org.fossify.commons.extensions.isVideoFast
import org.fossify.commons.extensions.isWebP
import org.fossify.commons.extensions.normalizeString
import org.fossify.commons.extensions.otgPath
import org.fossify.commons.extensions.recycleBinPath
import org.fossify.commons.extensions.sdCardPath
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.AlphanumericComparator
import org.fossify.commons.helpers.FAVORITES
import org.fossify.commons.helpers.NOMEDIA
import org.fossify.commons.helpers.SORT_BY_COUNT
import org.fossify.commons.helpers.SORT_BY_CUSTOM
import org.fossify.commons.helpers.SORT_BY_DATE_MODIFIED
import org.fossify.commons.helpers.SORT_BY_DATE_TAKEN
import org.fossify.commons.helpers.SORT_BY_NAME
import org.fossify.commons.helpers.SORT_BY_PATH
import org.fossify.commons.helpers.SORT_BY_RANDOM
import org.fossify.commons.helpers.SORT_BY_SIZE
import org.fossify.commons.helpers.SORT_DESCENDING
import org.fossify.commons.helpers.SORT_USE_NUMERIC_VALUE
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.sumByLong
import org.fossify.commons.views.MySquareImageView
import org.fossify.gallery.R
import org.fossify.gallery.asynctasks.GetMediaAsynctask
import org.fossify.gallery.databases.GalleryDatabase
import org.fossify.gallery.helpers.CUSTOM_FOLDERS_ORDER_SEPARATOR
import org.fossify.gallery.helpers.Config
import org.fossify.gallery.helpers.GROUP_BY_DATE_TAKEN_DAILY
import org.fossify.gallery.helpers.GROUP_BY_DATE_TAKEN_MONTHLY
import org.fossify.gallery.helpers.GROUP_BY_LAST_MODIFIED_DAILY
import org.fossify.gallery.helpers.GROUP_BY_LAST_MODIFIED_MONTHLY
import org.fossify.gallery.helpers.IsoTypeReader
import org.fossify.gallery.helpers.LOCATION_INTERNAL
import org.fossify.gallery.helpers.LOCATION_OTG
import org.fossify.gallery.helpers.LOCATION_PCLOUD
import org.fossify.gallery.helpers.LOCATION_SD
import org.fossify.gallery.helpers.MediaFetcher
import org.fossify.gallery.helpers.MyWidgetProvider
import org.fossify.gallery.helpers.PCLOUD_PATH_SCHEME
import org.fossify.gallery.helpers.PCLOUD_RECYCLE_BIN
import org.fossify.gallery.helpers.PCLOUD_RESULT_ALREADY_EXISTS
import org.fossify.gallery.helpers.PCloudException
import org.fossify.gallery.helpers.PCloudScanAbortedException
import org.fossify.gallery.helpers.PCloudScanner
import org.fossify.gallery.helpers.PCloudSyncPolicy
import org.fossify.gallery.helpers.PCloudWriter
import org.fossify.gallery.helpers.PicassoRoundedCornersTransformation
import org.fossify.gallery.helpers.RECYCLE_BIN
import org.fossify.gallery.helpers.ROUNDED_CORNERS_NONE
import org.fossify.gallery.helpers.ROUNDED_CORNERS_SMALL
import org.fossify.gallery.helpers.SHOW_ALL
import org.fossify.gallery.helpers.STORAGE_FILTER_ALL
import org.fossify.gallery.helpers.STORAGE_FILTER_PCLOUD
import org.fossify.gallery.helpers.THUMBNAIL_FADE_DURATION_MS
import org.fossify.gallery.helpers.TYPE_GIFS
import org.fossify.gallery.helpers.TYPE_IMAGES
import org.fossify.gallery.helpers.TYPE_PORTRAITS
import org.fossify.gallery.helpers.TYPE_RAWS
import org.fossify.gallery.helpers.TYPE_SVGS
import org.fossify.gallery.helpers.TYPE_VIDEOS
import org.fossify.gallery.interfaces.DateTakensDao
import org.fossify.gallery.interfaces.DirectoryDao
import org.fossify.gallery.interfaces.FavoritesDao
import org.fossify.gallery.interfaces.MediumDao
import org.fossify.gallery.interfaces.PCloudItemDao
import org.fossify.gallery.interfaces.WidgetsDao
import org.fossify.gallery.models.AlbumCover
import org.fossify.gallery.models.Directory
import org.fossify.gallery.models.Favorite
import org.fossify.gallery.models.Medium
import org.fossify.gallery.models.ThumbnailItem
import org.fossify.gallery.svg.SvgSoftwareLayerSetter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

val Context.audioManager get() = getSystemService(Context.AUDIO_SERVICE) as AudioManager

fun Context.getHumanizedFilename(path: String): String {
    val humanized = humanizePath(path)
    return humanized.substring(humanized.lastIndexOf("/") + 1)
}

val Context.config: Config get() = Config.newInstance(applicationContext)

val Context.widgetsDB: WidgetsDao
    get() = GalleryDatabase.getInstance(applicationContext).WidgetsDao()

val Context.mediaDB: MediumDao get() = GalleryDatabase.getInstance(applicationContext).MediumDao()

val Context.directoryDB: DirectoryDao
    get() = GalleryDatabase.getInstance(applicationContext).DirectoryDao()

val Context.favoritesDB: FavoritesDao
    get() = GalleryDatabase.getInstance(applicationContext).FavoritesDao()

val Context.dateTakensDB: DateTakensDao
    get() = GalleryDatabase.getInstance(applicationContext).DateTakensDao()

val Context.pCloudItemsDB: PCloudItemDao
    get() = GalleryDatabase.getInstance(applicationContext).PCloudItemDao()

val Context.recycleBin: File get() = filesDir

fun Context.movePinnedDirectoriesToFront(dirs: ArrayList<Directory>): ArrayList<Directory> {
    val foundFolders = ArrayList<Directory>()
    val pinnedFolders = config.pinnedFolders

    dirs.forEach {
        if (pinnedFolders.contains(it.path)) {
            foundFolders.add(it)
        }
    }

    dirs.removeAll(foundFolders)
    dirs.addAll(0, foundFolders)
    if (config.tempFolderPath.isNotEmpty()) {
        val newFolder = dirs.firstOrNull { it.path == config.tempFolderPath }
        if (newFolder != null) {
            dirs.remove(newFolder)
            dirs.add(0, newFolder)
        }
    }

    if (config.showRecycleBinAtFolders && config.showRecycleBinLast) {
        val binIndex = dirs.indexOfFirst { it.isRecycleBin() }
        if (binIndex != -1) {
            val bin = dirs.removeAt(binIndex)
            dirs.add(bin)
        }

        // the pCloud bin goes last too, after the device's one
        val pCloudBinIndex = dirs.indexOfFirst { it.isPCloudRecycleBin() }
        if (pCloudBinIndex != -1) {
            val bin = dirs.removeAt(pCloudBinIndex)
            dirs.add(bin)
        }
    }
    return dirs
}

@Suppress("UNCHECKED_CAST")
fun Context.getSortedDirectories(source: ArrayList<Directory>): ArrayList<Directory> {
    val sorting = config.directorySorting
    val dirs = source.clone() as ArrayList<Directory>

    if (sorting and SORT_BY_RANDOM != 0) {
        dirs.shuffle()
        return movePinnedDirectoriesToFront(dirs)
    } else if (sorting and SORT_BY_CUSTOM != 0) {
        val newDirsOrdered = ArrayList<Directory>()
        config.customFoldersOrder.split(CUSTOM_FOLDERS_ORDER_SEPARATOR).forEach { path ->
            val index = dirs.indexOfFirst { it.path == path }
            if (index != -1) {
                val dir = dirs.removeAt(index)
                newDirsOrdered.add(dir)
            }
        }

        dirs.mapTo(newDirsOrdered, { it })
        return newDirsOrdered
    }

    // the folders' own fields are compared rather than their stored sort_value: that value was
    // computed under the sorting in force when the folder was scanned, and the folder list can
    // be sorted per storage now, so it may belong to another sorting than the one applied here
    dirs.sortWith { o1, o2 ->
        o1 as Directory
        o2 as Directory

        var result = when {
            sorting and SORT_BY_NAME != 0 -> {
                val name1 = o1.name.normalizeString().lowercase(Locale.getDefault())
                val name2 = o2.name.normalizeString().lowercase(Locale.getDefault())
                if (sorting and SORT_USE_NUMERIC_VALUE != 0) {
                    AlphanumericComparator().compare(name1, name2)
                } else {
                    name1.compareTo(name2)
                }
            }

            sorting and SORT_BY_PATH != 0 -> {
                val path1 = o1.path.lowercase(Locale.getDefault())
                val path2 = o2.path.lowercase(Locale.getDefault())
                if (sorting and SORT_USE_NUMERIC_VALUE != 0) {
                    AlphanumericComparator().compare(path1, path2)
                } else {
                    path1.compareTo(path2)
                }
            }

            sorting and SORT_BY_SIZE != 0 -> o1.size.compareTo(o2.size)
            sorting and SORT_BY_COUNT != 0 -> o1.mediaCnt.compareTo(o2.mediaCnt)
            sorting and SORT_BY_DATE_MODIFIED != 0 -> o1.modified.compareTo(o2.modified)
            else -> o1.taken.compareTo(o2.taken)
        }

        if (sorting and SORT_DESCENDING != 0) {
            result *= -1
        }
        result
    }

    return movePinnedDirectoriesToFront(dirs)
}

fun Context.getDirsToShow(
    dirs: ArrayList<Directory>,
    allDirs: ArrayList<Directory>,
    currentPathPrefix: String
): ArrayList<Directory> {
    return if (config.groupDirectSubfolders) {
        dirs.forEach {
            it.subfoldersCount = 0
            it.subfoldersMediaCount = it.mediaCnt
        }

        val filledDirs = fillWithSharedDirectParents(dirs)
        val parentDirs = getDirectParentSubfolders(filledDirs, currentPathPrefix)
        updateSubfolderCounts(filledDirs, parentDirs)

        // show the current folder as an available option too, not just subfolders
        if (currentPathPrefix.isNotEmpty()) {
            val currentFolder = allDirs.firstOrNull {
                parentDirs.firstOrNull {
                    it.path.equals(currentPathPrefix, true)
                } == null && it.path.equals(currentPathPrefix, true)
            }

            currentFolder?.apply {
                subfoldersCount = 1
                parentDirs.add(this)
            }
        }

        getSortedDirectories(parentDirs)
    } else {
        dirs.forEach { it.subfoldersMediaCount = it.mediaCnt }
        dirs
    }
}

private fun Context.addParentWithoutMediaFiles(into: ArrayList<Directory>, path: String): Boolean {
    val isSortingAscending = config.sorting.isSortingAscending()
    val subDirs = into.filter { File(it.path).parent.equals(path, true) } as ArrayList<Directory>
    val newDirId = max(1000L, into.maxOf { it.id ?: 0L })
    if (subDirs.isNotEmpty()) {
        val lastModified = if (isSortingAscending) {
            subDirs.minByOrNull { it.modified }?.modified
        } else {
            subDirs.maxByOrNull { it.modified }?.modified
        } ?: 0

        val dateTaken = if (isSortingAscending) {
            subDirs.minByOrNull { it.taken }?.taken
        } else {
            subDirs.maxByOrNull { it.taken }?.taken
        } ?: 0

        var mediaTypes = 0
        subDirs.forEach {
            mediaTypes = mediaTypes or it.types
        }

        val directory = Directory(
            id = newDirId + 1,
            path = path,
            tmb = subDirs.first().tmb,
            name = getFolderNameFromPath(path),
            mediaCnt = subDirs.sumOf { it.mediaCnt },
            modified = lastModified,
            taken = dateTaken,
            size = subDirs.sumByLong { it.size },
            location = getPathLocation(path),
            types = mediaTypes,
            sortValue = ""
        )

        directory.containsMediaFilesDirectly = false
        into.add(directory)
        return true
    }
    return false
}

fun Context.fillWithSharedDirectParents(dirs: ArrayList<Directory>): ArrayList<Directory> {
    val allDirs = ArrayList<Directory>(dirs)
    val childCounts = mutableMapOf<String, Int>()
    for (dir in dirs) {
        File(dir.path).parent?.let {
            val current = childCounts[it] ?: 0
            childCounts.put(it, current + 1)
        }
    }

    childCounts
        .filter { dir -> dir.value > 1 && dirs.none { it.path.equals(dir.key, true) } }
        .toList()
        .sortedByDescending { it.first.length }
        .forEach { (parent, _) ->
            addParentWithoutMediaFiles(allDirs, parent)
        }
    return allDirs
}

fun Context.getDirectParentSubfolders(
    dirs: ArrayList<Directory>,
    currentPathPrefix: String
): ArrayList<Directory> {
    val folders = dirs.map { it.path }.sorted().toMutableSet() as HashSet<String>
    val currentPaths = LinkedHashSet<String>()
    val foldersWithoutMediaFiles = ArrayList<String>()

    for (path in folders) {
        if (path == RECYCLE_BIN || path == PCLOUD_RECYCLE_BIN || path == FAVORITES) {
            continue
        }

        if (currentPathPrefix.isNotEmpty()) {
            if (!path.startsWith(currentPathPrefix, true)) {
                continue
            }

            if (!File(path).parent.equals(currentPathPrefix, true)) {
                continue
            }
        }

        if (
            currentPathPrefix.isNotEmpty() &&
            path.equals(currentPathPrefix, true)
            || File(path).parent.equals(currentPathPrefix, true)
        ) {
            currentPaths.add(path)
        } else if (
            folders.any {
                !it.equals(path, true) && (File(path).parent.equals(it, true)
                        || File(it).parent.equals(File(path).parent, true))
            }
        ) {
            // if we have folders like
            // /storage/emulated/0/Pictures/Images and
            // /storage/emulated/0/Pictures/Screenshots,
            // but /storage/emulated/0/Pictures is empty, still Pictures with the first folders thumbnails and proper other info
            val parent = File(path).parent
            if (
                parent != null
                && !folders.contains(parent)
                && dirs.none { it.path.equals(parent, true) }
            ) {
                currentPaths.add(parent)
                if (addParentWithoutMediaFiles(dirs, parent)) {
                    foldersWithoutMediaFiles.add(parent)
                }
            }
        } else {
            currentPaths.add(path)
        }
    }

    var areDirectSubfoldersAvailable = false
    currentPaths.forEach {
        val path = it
        currentPaths.forEach {
            if (
                !foldersWithoutMediaFiles.contains(it)
                && !it.equals(path, true)
                && File(it).parent?.equals(path, true) == true
            ) {
                areDirectSubfoldersAvailable = true
            }
        }
    }

    if (currentPathPrefix.isEmpty() && folders.contains(RECYCLE_BIN)) {
        currentPaths.add(RECYCLE_BIN)
    }

    if (currentPathPrefix.isEmpty() && folders.contains(PCLOUD_RECYCLE_BIN)) {
        currentPaths.add(PCLOUD_RECYCLE_BIN)
    }

    if (currentPathPrefix.isEmpty() && folders.contains(FAVORITES)) {
        currentPaths.add(FAVORITES)
    }

    if (folders.size == currentPaths.size) {
        return dirs.filter { currentPaths.contains(it.path) } as ArrayList<Directory>
    }

    folders.clear()
    folders.addAll(currentPaths)

    val dirsToShow = dirs.filter { folders.contains(it.path) } as ArrayList<Directory>
    return if (areDirectSubfoldersAvailable) {
        getDirectParentSubfolders(dirsToShow, currentPathPrefix)
    } else {
        dirsToShow
    }
}

fun Context.updateSubfolderCounts(
    children: ArrayList<Directory>,
    parentDirs: ArrayList<Directory>
) {
    for (child in children) {
        var longestSharedPath = ""
        for (parentDir in parentDirs) {
            if (parentDir.path == child.path) {
                longestSharedPath = child.path
                continue
            }

            if (
                child.path.startsWith(parentDir.path, true)
                && parentDir.path.length > longestSharedPath.length
            ) {
                longestSharedPath = parentDir.path
            }
        }

        // make sure we count only the proper direct subfolders, grouped the same way as on the main screen
        parentDirs.firstOrNull { it.path == longestSharedPath }?.apply {
            if (
                path.equals(child.path, true)
                || path.equals(File(child.path).parent, true)
                || children.any { it.path.equals(File(child.path).parent, true) }
            ) {
                if (child.containsMediaFilesDirectly) {
                    subfoldersCount++
                }

                if (path != child.path) {
                    subfoldersMediaCount += child.mediaCnt
                }
            }
        }
    }
}

fun Context.getNoMediaFolders(callback: (folders: ArrayList<String>) -> Unit) {
    ensureBackgroundThread {
        callback(getNoMediaFoldersSync())
    }
}

fun Context.getNoMediaFoldersSync(): ArrayList<String> {
    val folders = ArrayList<String>()

    val uri = Files.getContentUri("external")
    val projection = arrayOf(Files.FileColumns.DATA)
    val selection = "${Files.FileColumns.MEDIA_TYPE} = ? AND ${Files.FileColumns.TITLE} LIKE ?"
    val selectionArgs = arrayOf(Files.FileColumns.MEDIA_TYPE_NONE.toString(), "%$NOMEDIA%")
    val sortOrder = "${Files.FileColumns.DATE_MODIFIED} DESC"
    val OTGPath = config.OTGPath

    var cursor: Cursor? = null
    try {
        cursor = contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
        if (cursor?.moveToFirst() == true) {
            do {
                val path = cursor.getStringValue(Files.FileColumns.DATA) ?: continue
                val noMediaFile = File(path)
                if (
                    getDoesFilePathExist(noMediaFile.absolutePath, OTGPath)
                    && noMediaFile.name == NOMEDIA
                ) {
                    folders.add(noMediaFile.parent)
                }
            } while (cursor.moveToNext())
        }
    } catch (ignored: Exception) {
    } finally {
        cursor?.close()
    }

    // a pCloud folder hidden in this app counts as one with a .nomedia in it, so that
    // everything that hides, names and shows folders by that file treats it the same way
    if (config.isPCloudLoggedIn) {
        folders.addAll(config.pCloudHiddenFolders)
    }

    return folders
}

// whether the pCloud folder, or one above it, is hidden in this app, see Config.pCloudHiddenFolders
fun Context.isPCloudFolderHidden(path: String): Boolean {
    return config.pCloudHiddenFolders.any { path == it || path.startsWith("$it/") }
}

fun Context.rescanFolderMedia(path: String) {
    ensureBackgroundThread {
        rescanFolderMediaSync(path)
    }
}

fun Context.rescanFolderMediaSync(path: String) {
    getCachedMedia(path) { cached ->
        GetMediaAsynctask(
            context = applicationContext,
            mPath = path,
            isPickImage = false,
            isPickVideo = false,
            showAll = false
        ) { newMedia ->
            ensureBackgroundThread {
                val media = newMedia.filterIsInstance<Medium>() as ArrayList<Medium>
                try {
                    mediaDB.insertAll(media)

                    cached.forEach { thumbnailItem ->
                        if (!newMedia.contains(thumbnailItem)) {
                            val mediumPath = (thumbnailItem as? Medium)?.path
                            if (mediumPath != null) {
                                deleteDBPath(mediumPath)
                            }
                        }
                    }
                } catch (ignored: Exception) {
                }
            }
        }.execute()
    }
}

fun Context.storeDirectoryItems(items: ArrayList<Directory>) {
    ensureBackgroundThread {
        directoryDB.insertAll(items)
    }
}

fun Context.checkAppendingHidden(
    path: String,
    hidden: String,
    includedFolders: MutableSet<String>,
    noMediaFolders: ArrayList<String>
): String {
    val dirName = getFolderNameFromPath(path)
    val folderNoMediaStatuses = HashMap<String, Boolean>()
    noMediaFolders.forEach { folder ->
        folderNoMediaStatuses["$folder/$NOMEDIA"] = true
    }

    return if (
        path.doesThisOrParentHaveNoMedia(folderNoMediaStatuses, null)
        && !path.isThisOrParentIncluded(includedFolders)
    ) {
        "$dirName $hidden"
    } else {
        dirName
    }
}

fun Context.getFolderNameFromPath(path: String): String {
    return when (path) {
        internalStoragePath -> getString(org.fossify.commons.R.string.internal)
        sdCardPath -> getString(org.fossify.commons.R.string.sd_card)
        otgPath -> getString(org.fossify.commons.R.string.usb)
        FAVORITES -> getString(org.fossify.commons.R.string.favorites)
        RECYCLE_BIN -> getString(org.fossify.commons.R.string.recycle_bin)
        PCLOUD_RECYCLE_BIN -> getString(R.string.pcloud_recycle_bin)
        PCLOUD_PATH_SCHEME -> getString(R.string.pcloud)
        else -> path.getFilenameFromPath()
    }
}

fun Context.loadImage(
    type: Int,
    path: String,
    target: MySquareImageView,
    horizontalScroll: Boolean,
    animateGifs: Boolean,
    cropThumbnails: Boolean,
    roundCorners: Int,
    signature: ObjectKey,
    skipMemoryCacheAtPaths: ArrayList<String>? = null,
    onError: (() -> Unit)? = null
) {
    target.isHorizontalScrolling = horizontalScroll
    if (type == TYPE_SVGS) {
        loadSVG(
            path = path,
            target = target,
            cropThumbnails = cropThumbnails,
            roundCorners = roundCorners,
            signature = signature
        )
    } else {
        loadImageBase(
            path = path,
            target = target,
            cropThumbnails = cropThumbnails,
            roundCorners = roundCorners,
            signature = signature,
            skipMemoryCacheAtPaths = skipMemoryCacheAtPaths,
            animate = animateGifs,
            // Picasso reads files off the disk, which a pCloud thumbnail never is
            tryLoadingWithPicasso = type == TYPE_IMAGES && path.isPng() && !path.isPCloudPath(),
            onError = onError
        )
    }
}

// The temporary tile for a folder that was just created and is still empty. A folder that
// has grown a row of its own since is not doubled, and a pCloud folder stays off the list
// while the storage filter shows the device only
fun Context.addTempFolderIfNeeded(dirs: ArrayList<Directory>): ArrayList<Directory> {
    val tempFolderPath = config.tempFolderPath
    if (tempFolderPath.isEmpty() || dirs.any { it.path == tempFolderPath }) {
        return dirs
    }

    val newFolder = Directory(
        id = null,
        path = tempFolderPath,
        tmb = "",
        name = tempFolderPath.getFilenameFromPath(),
        mediaCnt = 0,
        modified = 0,
        taken = 0,
        size = 0L,
        location = getPathLocation(tempFolderPath),
        types = 0,
        sortValue = ""
    )
    if (!isShownByStorageFilter(newFolder)) {
        return dirs
    }

    val directories = ArrayList<Directory>()
    directories.add(newFolder)
    directories.addAll(dirs)
    return directories
}

fun Context.getPathLocation(path: String): Int {
    return when {
        path.isPCloudPath() -> LOCATION_PCLOUD
        isPathOnSD(path) -> LOCATION_SD
        isPathOnOTG(path) -> LOCATION_OTG
        else -> LOCATION_INTERNAL
    }
}

// Which folders the storage filter lets through. Favorites, the recycle bin and virtual groups
// belong to no storage and always pass. Without a pCloud account the filter means local
// storage whatever it says, so a stale pCloud cache never shows up after signing out
fun Context.isShownByStorageFilter(directory: Directory): Boolean {
    val isPCloud = directory.path.isPCloudPath()
    return when {
        directory.areFavorites() || directory.isGroup() -> true
        // the device's bin goes with the device's folders; the pCloud bin has a pCloud path
        // and follows the pCloud rule below, so the "both" filter shows the two side by side
        directory.isRecycleBin() -> !config.isPCloudLoggedIn || config.storageFilter != STORAGE_FILTER_PCLOUD
        !config.isPCloudLoggedIn -> !isPCloud
        config.storageFilter == STORAGE_FILTER_PCLOUD -> isPCloud
        config.storageFilter == STORAGE_FILTER_ALL -> true
        else -> !isPCloud
    }
}

// Brings the pCloud cache up to date off the main thread and tells the user in a toast how it
// went: the counts when asked for, otherwise only what failed. What is fetched is the changes
// since the last scan (PCloudScanner.sync()); full lists the whole account again instead. A
// token pCloud no longer accepts signs the account out. onDone runs in every case but one,
// also when a scan was already running and this one was skipped; it is called on whatever
// thread the scan ended on, so hop to the UI thread in it. The one case is a scan that
// PCloudScanner.abortCurrent() called off: the screen that did so is reloading on its own,
// and the reload onDone would do is the very thing it wanted to be spared
fun Context.rescanPCloud(reportCounts: Boolean, full: Boolean = false, onDone: () -> Unit = {}) {
    if (!config.isPCloudLoggedIn) {
        onDone()
        return
    }

    runPCloudScan(onDone) { scanner ->
        val result = if (full) scanner.scanAll() else scanner.sync()
        if (reportCounts) {
            toast(getString(R.string.pcloud_rescan_done, result.folderCount, result.mediaCount))
        }
    }
}

// Refreshes the given pCloud folders one by one, non-recursively, the way rescanPCloud()
// refreshes the whole account; the counts toast sums them up. It shares the scanner's lock
// with the full scan, so nothing happens while either is running. onDone runs the way it
// does for rescanPCloud()
fun Context.rescanPCloudFolders(paths: List<String>, reportCounts: Boolean, onDone: () -> Unit = {}) {
    if (paths.isEmpty() || !config.isPCloudLoggedIn) {
        onDone()
        return
    }

    runPCloudScan(onDone) { scanner ->
        var folderCount = 0
        var mediaCount = 0
        paths.forEach { path ->
            scanner.scanFolder(path)?.let {
                folderCount += it.folderCount
                mediaCount += it.mediaCount
            }
        }

        if (reportCounts) {
            toast(getString(R.string.pcloud_rescan_done, folderCount, mediaCount))
        }
    }
}

// Claims the scanner for scan and runs it off the main thread, registered so that a screen
// can call it off; failure is toasted, a dead token signs the account out, and a scan that
// was called off is neither reported nor followed by onDone. The scanner is claimed here,
// before the thread starts, so that an abort cannot slip in between the claim and the
// registration
private fun Context.runPCloudScan(onDone: () -> Unit, scan: (PCloudScanner) -> Unit) {
    val scanner = PCloudScanner(this)
    if (!PCloudScanner.start(scanner)) {
        onDone()
        return
    }

    ensureBackgroundThread {
        var aborted = false
        try {
            scan(scanner)
        } catch (e: PCloudScanAbortedException) {
            aborted = true
        } catch (e: PCloudException) {
            if (e.requiresLogIn) {
                config.clearPCloudAccount()
                toast(R.string.pcloud_log_in_required)
            } else {
                showErrorToast(e)
            }
        } catch (e: Exception) {
            showErrorToast(e)
        } finally {
            PCloudScanner.finish()
        }

        if (!aborted) {
            onDone()
        }
    }
}

// Runs one write to pCloud off the main thread and reports failure the way the scans do; a
// token pCloud no longer accepts signs the account out. Once the write went through and the
// "rescan after write" setting is on, the folders it names are refreshed from pCloud before
// onDone runs, so that a list read in onDone shows the folder as pCloud has it now; the
// throttle does not apply, the user just changed the folder. onDone gets whether the write
// went through, on whatever thread that was decided on
fun Context.writeToPCloud(foldersToRescan: List<String>, write: PCloudWriter.() -> Unit, onDone: (success: Boolean) -> Unit = {}) {
    if (!config.isPCloudLoggedIn) {
        toast(R.string.pcloud_log_in_required)
        onDone(false)
        return
    }

    ensureBackgroundThread {
        val success = try {
            PCloudWriter(this).write()
            true
        } catch (e: PCloudException) {
            // a toast is gone the moment it is read, and a write that failed is exactly what
            // someone comes back to look into later, so every failure goes to the log too
            Log.w("PCloudTransfer", "A write to pCloud failed", e)
            when {
                e.requiresLogIn -> {
                    config.clearPCloudAccount()
                    toast(R.string.pcloud_log_in_required)
                }

                e.result == PCLOUD_RESULT_ALREADY_EXISTS -> toast(R.string.pcloud_already_exists)
                else -> showErrorToast(e)
            }
            false
        } catch (e: Exception) {
            Log.w("PCloudTransfer", "A write to pCloud failed", e)
            showErrorToast(e)
            false
        }

        if (success && PCloudSyncPolicy(this).rescanAfterWrite) {
            rescanPCloudFolders(foldersToRescan, reportCounts = false) { onDone(true) }
        } else {
            onDone(success)
        }
    }
}

// the folders among the given pCloud paths whose last scan is older than the rescan interval;
// the throttle is per folder, see PCloudSyncPolicy.isFolderScanDue(). Reads the database, so
// call it off the main thread
fun Context.getPCloudFoldersDueForRescan(paths: List<String>): List<String> {
    val policy = PCloudSyncPolicy(this)
    return paths.filter { policy.isFolderScanDue(pCloudItemsDB.getItem(it)?.lastScannedAt ?: 0L) }
}

@SuppressLint("CheckResult")
fun Context.loadImageBase(
    path: String,
    target: MySquareImageView,
    cropThumbnails: Boolean,
    roundCorners: Int,
    signature: ObjectKey,
    skipMemoryCacheAtPaths: ArrayList<String>? = null,
    animate: Boolean = false,
    tryLoadingWithPicasso: Boolean = false,
    crossFadeDuration: Int = THUMBNAIL_FADE_DURATION_MS,
    onError: (() -> Unit)? = null
) {
    val options = RequestOptions()
        .signature(signature)
        .skipMemoryCache(skipMemoryCacheAtPaths?.contains(path) == true)
        .priority(Priority.LOW)
        .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
        .format(DecodeFormat.PREFER_ARGB_8888)

    if (cropThumbnails) {
        options.optionalTransform(CenterCrop())
        options.optionalTransform(
            WebpDrawable::class.java,
            WebpDrawableTransformation(CenterCrop())
        )
    } else {
        options.optionalTransform(FitCenter())
        options.optionalTransform(WebpDrawable::class.java, WebpDrawableTransformation(FitCenter()))
    }

    // animation is only supported without rounded corners and the file must be a GIF or WEBP.
    if (animate && roundCorners == ROUNDED_CORNERS_NONE && (path.isGif() || path.isWebP())) {
        // this is required to make glide cache aware of changes
        options.decode(Drawable::class.java)
    } else {
        options.dontAnimate()
        // don't animate is not enough for webp files, decode as bitmap forces first frame use in animated webps
        options.decode(Bitmap::class.java)
    }

    if (roundCorners != ROUNDED_CORNERS_NONE) {
        val cornerSize =
            if (roundCorners == ROUNDED_CORNERS_SMALL) org.fossify.commons.R.dimen.rounded_corner_radius_small else org.fossify.commons.R.dimen.rounded_corner_radius_big
        val cornerRadius = resources.getDimension(cornerSize).toInt()
        val roundedCornersTransform = RoundedCorners(cornerRadius)
        options.optionalTransform(MultiTransformation(CenterCrop(), roundedCornersTransform))
        options.optionalTransform(
            WebpDrawable::class.java,
            MultiTransformation(
                WebpDrawableTransformation(CenterCrop()),
                WebpDrawableTransformation(roundedCornersTransform)
            )
        )
    }

    WebpBitmapFactory.sUseSystemDecoder = false // CVE-2023-4863
    var builder = Glide.with(applicationContext)
        .load(path)
        .apply(options)
        .set(WebpDownsampler.USE_SYSTEM_DECODER, false) // CVE-2023-4863
        .transition(getOptionalCrossFadeTransition(crossFadeDuration))

    builder = builder.listener(object : RequestListener<Drawable> {
        override fun onLoadFailed(
            e: GlideException?,
            model: Any?,
            targetBitmap: Target<Drawable>,
            isFirstResource: Boolean
        ): Boolean {
            if (tryLoadingWithPicasso) {
                tryLoadingWithPicasso(path, target, cropThumbnails, roundCorners, signature)
            } else {
                onError?.invoke()
            }

            return true
        }

        override fun onResourceReady(
            resource: Drawable,
            model: Any,
            targetBitmap: Target<Drawable>,
            dataSource: DataSource,
            isFirstResource: Boolean,
        ) = false
    })

    builder.into(target)
}

fun Context.loadSVG(
    path: String,
    target: MySquareImageView,
    cropThumbnails: Boolean,
    roundCorners: Int,
    signature: ObjectKey,
    crossFadeDuration: Int = THUMBNAIL_FADE_DURATION_MS,
) {
    target.scaleType = if (cropThumbnails) {
        ImageView.ScaleType.CENTER_CROP
    } else {
        ImageView.ScaleType.FIT_CENTER
    }

    val options = RequestOptions().signature(signature)
    var builder = Glide.with(applicationContext)
        .`as`(PictureDrawable::class.java)
        .listener(SvgSoftwareLayerSetter())
        .load(path)
        .apply(options)
        .transition(getOptionalCrossFadeTransition(crossFadeDuration))

    if (roundCorners != ROUNDED_CORNERS_NONE) {
        val cornerSize = when (roundCorners) {
            ROUNDED_CORNERS_SMALL -> org.fossify.commons.R.dimen.rounded_corner_radius_small
            else -> org.fossify.commons.R.dimen.rounded_corner_radius_big
        }

        val cornerRadius = resources.getDimension(cornerSize).toInt()
        builder = builder.transform(CenterCrop(), RoundedCorners(cornerRadius))
    }

    builder.into(target)
}

// intended mostly for Android 11 issues, that fail loading PNG files bigger than 10 MB
fun Context.tryLoadingWithPicasso(
    path: String,
    view: MySquareImageView,
    cropThumbnails: Boolean,
    roundCorners: Int,
    signature: ObjectKey
) {
    var pathToLoad = "file://$path"
    pathToLoad = pathToLoad.replace("%", "%25").replace("#", "%23")

    try {
        var builder = Picasso.get()
            .load(pathToLoad)
            .stableKey(signature.toString())

        builder = if (cropThumbnails) {
            builder.centerCrop().fit()
        } else {
            builder.centerInside()
        }

        if (roundCorners != ROUNDED_CORNERS_NONE) {
            val cornerSize =
                if (roundCorners == ROUNDED_CORNERS_SMALL) org.fossify.commons.R.dimen.rounded_corner_radius_small else org.fossify.commons.R.dimen.rounded_corner_radius_big
            val cornerRadius = resources.getDimension(cornerSize).toInt()
            builder = builder.transform(PicassoRoundedCornersTransformation(cornerRadius.toFloat()))
        }

        builder.into(view)
    } catch (e: Exception) {
    }
}

// forceShowAllStorages lets the folders of every storage through whatever the storage filter
// says, for a picker that offers both as a copy destination and narrows them itself; without
// an account the pCloud folders stay out, like they do for the filter
fun Context.getCachedDirectories(
    getVideosOnly: Boolean = false,
    getImagesOnly: Boolean = false,
    forceShowHidden: Boolean = false,
    forceShowExcluded: Boolean = false,
    forceShowAllStorages: Boolean = false,
    callback: (ArrayList<Directory>) -> Unit,
) {
    ensureBackgroundThread {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_MORE_FAVORABLE)
        } catch (ignored: Exception) {
        }

        val directories = try {
            directoryDB.getAll() as ArrayList<Directory>
        } catch (e: Exception) {
            ArrayList()
        }

        if (!config.showRecycleBinAtFolders) {
            directories.removeAll { it.isRecycleBin() || it.isPCloudRecycleBin() }
        }

        val shouldShowHidden = config.shouldShowHidden || forceShowHidden
        val excludedPaths = if (config.temporarilyShowExcluded || forceShowExcluded) {
            HashSet()
        } else {
            config.excludedFolders
        }

        val includedPaths = config.includedFolders

        val folderNoMediaStatuses = HashMap<String, Boolean>()
        val noMediaFolders = getNoMediaFoldersSync()
        noMediaFolders.forEach { folder ->
            folderNoMediaStatuses["$folder/$NOMEDIA"] = true
        }

        // the pCloud bin's pseudo path is a dot folder's, which is not to hide it
        var filteredDirectories = directories.filter {
            it.isPCloudRecycleBin() || it.path.shouldFolderBeVisible(
                excludedPaths = excludedPaths,
                includedPaths = includedPaths,
                showHidden = shouldShowHidden,
                folderNoMediaStatuses = folderNoMediaStatuses
            ) { path, hasNoMedia ->
                folderNoMediaStatuses[path] = hasNoMedia
            }
        } as ArrayList<Directory>

        val filterMedia = config.filterMedia
        filteredDirectories = (when {
            getVideosOnly -> filteredDirectories.filter { it.types and TYPE_VIDEOS != 0 }
            getImagesOnly -> filteredDirectories.filter { it.types and TYPE_IMAGES != 0 }
            else -> filteredDirectories.filter {
                (filterMedia and TYPE_IMAGES != 0 && it.types and TYPE_IMAGES != 0)
                        || (filterMedia and TYPE_VIDEOS != 0 && it.types and TYPE_VIDEOS != 0)
                        || (filterMedia and TYPE_GIFS != 0 && it.types and TYPE_GIFS != 0)
                        || (filterMedia and TYPE_RAWS != 0 && it.types and TYPE_RAWS != 0)
                        || (filterMedia and TYPE_SVGS != 0 && it.types and TYPE_SVGS != 0)
                        || (filterMedia and TYPE_PORTRAITS != 0 && it.types and TYPE_PORTRAITS != 0)
            }
        }) as ArrayList<Directory>

        filteredDirectories = filteredDirectories.filter {
            (forceShowAllStorages && (config.isPCloudLoggedIn || !it.path.isPCloudPath())) || isShownByStorageFilter(it)
        } as ArrayList<Directory>

        if (shouldShowHidden) {
            val hiddenString = resources.getString(R.string.hidden)
            filteredDirectories.forEach {
                val noMediaPath = "${it.path}/$NOMEDIA"
                val hasNoMedia = if (folderNoMediaStatuses.keys.contains(noMediaPath)) {
                    folderNoMediaStatuses[noMediaPath]!!
                } else {
                    it.path.doesThisOrParentHaveNoMedia(folderNoMediaStatuses) { path, hasNoMedia ->
                        val newPath = "$path/$NOMEDIA"
                        folderNoMediaStatuses[newPath] = hasNoMedia
                    }
                }

                it.name = if (hasNoMedia && !it.path.isThisOrParentIncluded(includedPaths)) {
                    "${it.name.removeSuffix(hiddenString).trim()} $hiddenString"
                } else {
                    it.name.removeSuffix(hiddenString).trim()
                }
            }
        }

        val clone = filteredDirectories.clone() as ArrayList<Directory>
        callback(clone.distinctBy { it.path.getDistinctPath() } as ArrayList<Directory>)
        removeInvalidDBDirectories(filteredDirectories)
    }
}

fun Context.getCachedMedia(
    path: String,
    getVideosOnly: Boolean = false,
    getImagesOnly: Boolean = false,
    callback: (ArrayList<ThumbnailItem>) -> Unit
) {
    ensureBackgroundThread {
        val mediaFetcher = MediaFetcher(this)
        val foldersToScan = if (path.isEmpty()) {
            mediaFetcher.getFoldersToScan().apply { addAll(mediaFetcher.getPCloudFoldersToShow()) }
        } else {
            arrayListOf(path)
        }

        var media = ArrayList<Medium>()
        if (path == FAVORITES) {
            media.addAll(mediaDB.getFavorites())
        }

        if (path == RECYCLE_BIN) {
            media.addAll(getUpdatedDeletedMedia())
        }

        if (path == PCLOUD_RECYCLE_BIN) {
            media.addAll(mediaDB.getPCloudDeletedMedia())
        }

        if (config.filterMedia and TYPE_PORTRAITS != 0) {
            val foldersToAdd = ArrayList<String>()
            for (folder in foldersToScan) {
                val allFiles = File(folder).listFiles() ?: continue
                allFiles.filter { it.name.startsWith("img_", true) && it.isDirectory }.forEach {
                    foldersToAdd.add(it.absolutePath)
                }
            }
            foldersToScan.addAll(foldersToAdd)
        }

        val shouldShowHidden = config.shouldShowHidden
        foldersToScan.filter { path.isNotEmpty() || !config.isFolderProtected(it) }.forEach {
            try {
                val currMedia = mediaDB.getMediaFromPath(it)
                media.addAll(currMedia)
            } catch (ignored: Exception) {
            }
        }

        // the pCloud bin's pseudo path is a dot folder's; what is in the bin is shown whatever folder it came from
        if (!shouldShowHidden) {
            media = media.filter { it.path.isPCloudRecycleBinPath() || !it.path.contains("/.") } as ArrayList<Medium>
        }

        val filterMedia = config.filterMedia
        media = (when {
            getVideosOnly -> media.filter { it.type == TYPE_VIDEOS }
            getImagesOnly -> media.filter { it.type == TYPE_IMAGES }
            else -> media.filter {
                (filterMedia and TYPE_IMAGES != 0 && it.type == TYPE_IMAGES)
                        || (filterMedia and TYPE_VIDEOS != 0 && it.type == TYPE_VIDEOS)
                        || (filterMedia and TYPE_GIFS != 0 && it.type == TYPE_GIFS)
                        || (filterMedia and TYPE_RAWS != 0 && it.type == TYPE_RAWS)
                        || (filterMedia and TYPE_SVGS != 0 && it.type == TYPE_SVGS)
                        || (filterMedia and TYPE_PORTRAITS != 0 && it.type == TYPE_PORTRAITS)
            }
        }) as ArrayList<Medium>

        val pathToUse = path.ifEmpty { SHOW_ALL }
        mediaFetcher.sortMedia(media, config.getFolderSorting(pathToUse), pathToUse)
        val grouped = mediaFetcher.groupMedia(media, pathToUse)
        callback(grouped.clone() as ArrayList<ThumbnailItem>)
        val OTGPath = config.OTGPath

        try {
            val mediaToDelete = ArrayList<Medium>()
            // creating a new thread intentionally, do not reuse the common background thread
            Thread {
                // pCloud media has no file behind it, the cache row is all there is. Only a rescan may drop it
                media.filter { !it.path.isPCloudPath() && !getDoesFilePathExist(it.path, OTGPath) }.forEach {
                    if (it.path.startsWith(recycleBinPath)) {
                        deleteDBPath(it.path)
                    } else {
                        mediaToDelete.add(it)
                    }
                }

                if (mediaToDelete.isNotEmpty()) {
                    try {
                        mediaDB.deleteMedia(*mediaToDelete.toTypedArray())

                        mediaToDelete.filter { it.isFavorite }.forEach {
                            favoritesDB.deleteFavoritePath(it.path)
                        }
                    } catch (ignored: Exception) {
                    }
                }
            }.start()
        } catch (ignored: Exception) {
        }
    }
}

fun Context.removeInvalidDBDirectories(dirs: ArrayList<Directory>? = null) {
    val dirsToCheck = dirs ?: directoryDB.getAll()
    val OTGPath = config.OTGPath
    dirsToCheck.filter {
        !it.areFavorites()
                && !it.isRecycleBin()
                && !it.path.isPCloudPath()
                && !getDoesFilePathExist(it.path, OTGPath)
                && it.path != config.tempFolderPath
    }.forEach {
        try {
            directoryDB.deleteDirPath(it.path)
        } catch (ignored: Exception) {
        }
    }
}

fun Context.updateDBMediaPath(oldPath: String, newPath: String) {
    val newFilename = newPath.getFilenameFromPath()
    val newParentPath = newPath.getParentPath()
    try {
        mediaDB.updateMedium(newFilename, newPath, newParentPath, oldPath)
        favoritesDB.updateFavorite(newFilename, newPath, newParentPath, oldPath)
    } catch (ignored: Exception) {
    }
}

fun Context.updateDBDirectory(directory: Directory) {
    try {
        directoryDB.updateDirectory(
            path = directory.path,
            thumbnail = directory.tmb,
            mediaCnt = directory.mediaCnt,
            lastModified = directory.modified,
            dateTaken = directory.taken,
            size = directory.size,
            mediaTypes = directory.types,
            sortValue = directory.sortValue
        )
    } catch (ignored: Exception) {
    }
}

fun Context.getOTGFolderChildren(path: String) = getDocumentFile(path)?.listFiles()

fun Context.getOTGFolderChildrenNames(path: String): MutableList<String?>? {
    return getOTGFolderChildren(path)?.map { it.name }?.toMutableList()
}

fun Context.getFavoritePaths(): ArrayList<String> {
    return try {
        favoritesDB.getValidFavoritePaths() as ArrayList<String>
    } catch (e: Exception) {
        ArrayList()
    }
}

fun Context.getFavoriteFromPath(path: String): Favorite {
    return Favorite(null, path, path.getFilenameFromPath(), path.getParentPath())
}

fun Context.updateFavorite(path: String, isFavorite: Boolean) {
    try {
        if (isFavorite) {
            favoritesDB.insert(getFavoriteFromPath(path))
        } else {
            favoritesDB.deleteFavoritePath(path)
        }

        // a local row is rewritten with its flag the next time its folder is listed, a pCloud row
        // only by a rescan, so the flag is set right away: the favorites count and the cached
        // Favorites folder read it
        if (path.isPCloudPath()) {
            mediaDB.updateFavorite(path, isFavorite)
        }
    } catch (e: Exception) {
        toast(org.fossify.commons.R.string.unknown_error_occurred)
    }
}

// remove the "recycle_bin" from the file path prefix, replace it with real bin path /data/user...
fun Context.getUpdatedDeletedMedia(): ArrayList<Medium> {
    val media = try {
        mediaDB.getDeletedMedia() as ArrayList<Medium>
    } catch (ignored: Exception) {
        ArrayList()
    }

    media.forEach {
        it.path = File(recycleBinPath, it.path.removePrefix(RECYCLE_BIN)).toString()
    }
    return media
}

fun Context.deleteDBPath(path: String) {
    deleteMediumWithPath(path.replaceFirst(recycleBinPath, RECYCLE_BIN))
}

fun Context.deleteMediumWithPath(path: String) {
    try {
        mediaDB.deleteMediumPath(path)
    } catch (ignored: Exception) {
    }
}

fun Context.updateWidgets() {
    val widgetIDs = AppWidgetManager.getInstance(applicationContext)
        ?.getAppWidgetIds(ComponentName(applicationContext, MyWidgetProvider::class.java))
        ?: return

    if (widgetIDs.isNotEmpty()) {
        Intent(applicationContext, MyWidgetProvider::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, widgetIDs)
            sendBroadcast(this)
        }
    }
}

// based on https://github.com/sannies/mp4parser/blob/master/examples/src/main/java/com/google/code/mp4parser/example/PrintStructure.java
fun Context.parseFileChannel(
    path: String,
    fc: FileChannel,
    level: Int,
    start: Long,
    end: Long,
    callback: () -> Unit
) {
    val fileChannelContainers = arrayListOf("moov", "trak", "mdia", "minf", "udta", "stbl")
    try {
        var iteration = 0
        var currEnd = end
        fc.position(start)
        if (currEnd <= 0) {
            currEnd = start + fc.size()
        }

        while (currEnd - fc.position() > 8) {
            // just a check to avoid deadloop at some videos
            if (iteration++ > 50) {
                return
            }

            val begin = fc.position()
            val byteBuffer = ByteBuffer.allocate(8)
            fc.read(byteBuffer)
            byteBuffer.rewind()
            val size = IsoTypeReader.readUInt32(byteBuffer)
            val type = IsoTypeReader.read4cc(byteBuffer)
            val newEnd = begin + size

            if (type == "uuid") {
                val fis = FileInputStream(File(path))
                fis.skip(begin)

                val sb = StringBuilder()
                val buffer = ByteArray(1024)
                while (sb.length < size) {
                    val n = fis.read(buffer)
                    if (n != -1) {
                        sb.append(String(buffer, 0, n))
                    } else {
                        break
                    }
                }

                val xmlString = sb.toString().lowercase(Locale.getDefault())
                if (
                    xmlString.contains("gspherical:projectiontype>equirectangular")
                    || xmlString.contains("gspherical:projectiontype=\"equirectangular\"")
                ) {
                    callback.invoke()
                }
                return
            }

            if (fileChannelContainers.contains(type)) {
                parseFileChannel(path, fc, level + 1, begin + 8, newEnd, callback)
            }

            fc.position(newEnd)
        }
    } catch (ignored: Exception) {
    }
}

fun Context.addPathToDB(path: String) {
    ensureBackgroundThread {
        if (!getDoesFilePathExist(path)) {
            return@ensureBackgroundThread
        }

        val type = when {
            path.isVideoFast() -> TYPE_VIDEOS
            path.isGif() -> TYPE_GIFS
            path.isRawFast() -> TYPE_RAWS
            path.isSvg() -> TYPE_SVGS
            path.isPortrait() -> TYPE_PORTRAITS
            else -> TYPE_IMAGES
        }

        try {
            val isFavorite = favoritesDB.isFavorite(path)
            val videoDuration = if (type == TYPE_VIDEOS) getDuration(path) ?: 0 else 0
            val medium = Medium(
                id = null,
                name = path.getFilenameFromPath(),
                path = path,
                parentPath = path.getParentPath(),
                modified = System.currentTimeMillis(),
                taken = System.currentTimeMillis(),
                size = File(path).length(),
                type = type,
                videoDuration = videoDuration,
                isFavorite = isFavorite,
                deletedTS = 0L,
                mediaStoreId = 0L
            )

            mediaDB.insert(medium)
        } catch (ignored: Exception) {
        }
    }
}

fun Context.createDirectoryFromMedia(
    path: String,
    curMedia: ArrayList<Medium>,
    albumCovers: ArrayList<AlbumCover>,
    hiddenString: String,
    includedFolders: MutableSet<String>,
    getProperFileSize: Boolean,
    noMediaFolders: ArrayList<String>,
): Directory {
    val OTGPath = config.OTGPath
    val grouped = MediaFetcher(this).groupMedia(curMedia, path)
    var thumbnail: String? = null

    // a pCloud thumbnail has no file to check for, its cache row is the proof that it exists
    albumCovers.forEach {
        if (it.path == path && (it.tmb.isPCloudPath() || getDoesFilePathExist(it.tmb, OTGPath))) {
            thumbnail = it.tmb
        }
    }

    if (thumbnail == null) {
        val sortedMedia = grouped.filter { it is Medium }.toMutableList() as ArrayList<Medium>
        thumbnail = sortedMedia.firstOrNull { it.path.isPCloudPath() || getDoesFilePathExist(it.path, OTGPath) }?.path ?: ""
    }

    if (config.OTGPath.isNotEmpty() && thumbnail!!.startsWith(config.OTGPath)) {
        thumbnail = thumbnail!!.getOTGPublicPath(applicationContext)
    }

    val isSortingAscending = config.directorySorting.isSortingAscending()
    val defaultMedium = Medium(0, "", "", "", 0L, 0L, 0L, 0, 0, false, 0L, 0L)
    val firstItem = curMedia.firstOrNull() ?: defaultMedium
    val lastItem = curMedia.lastOrNull() ?: defaultMedium
    val dirName = checkAppendingHidden(path, hiddenString, includedFolders, noMediaFolders)
    val lastModified = if (isSortingAscending) {
        min(firstItem.modified, lastItem.modified)
    } else {
        max(firstItem.modified, lastItem.modified)
    }

    val dateTaken = if (isSortingAscending) {
        min(firstItem.taken, lastItem.taken)
    } else {
        max(firstItem.taken, lastItem.taken)
    }

    val size = if (getProperFileSize) curMedia.sumByLong { it.size } else 0L
    val mediaTypes = curMedia.getDirMediaTypes()
    val count = curMedia.size
    val sortValue = getDirectorySortingValue(curMedia, path, dirName, size, count)
    return Directory(
        id = null,
        path = path,
        tmb = thumbnail!!,
        name = dirName,
        mediaCnt = curMedia.size,
        modified = lastModified,
        taken = dateTaken,
        size = size,
        location = getPathLocation(path),
        types = mediaTypes,
        sortValue = sortValue
    )
}

fun Context.getDirectorySortingValue(
    media: ArrayList<Medium>,
    path: String,
    name: String,
    size: Long,
    count: Int
): String {
    val sorting = config.directorySorting
    val sorted = when {
        sorting and SORT_BY_NAME != 0 -> return name
        sorting and SORT_BY_PATH != 0 -> return path
        sorting and SORT_BY_SIZE != 0 -> return size.toString()
        sorting and SORT_BY_COUNT != 0 -> return count.toString()
        sorting and SORT_BY_DATE_MODIFIED != 0 -> media.sortedBy { it.modified }
        sorting and SORT_BY_DATE_TAKEN != 0 -> media.sortedBy { it.taken }
        else -> media
    }

    val relevantMedium = if (sorting.isSortingAscending()) {
        sorted.firstOrNull() ?: return ""
    } else {
        sorted.lastOrNull() ?: return ""
    }

    val result: Any = when {
        sorting and SORT_BY_DATE_MODIFIED != 0 -> relevantMedium.modified
        sorting and SORT_BY_DATE_TAKEN != 0 -> relevantMedium.taken
        else -> 0
    }

    return result.toString()
}

fun Context.updateDirectoryPath(path: String) {
    val mediaFetcher = MediaFetcher(applicationContext)
    val getImagesOnly = false
    val getVideosOnly = false
    val hiddenString = getString(R.string.hidden)
    val albumCovers = config.parseAlbumCovers()
    val includedFolders = config.includedFolders
    val noMediaFolders = getNoMediaFoldersSync()

    val sorting = config.getFolderSorting(path)
    val grouping = config.getFolderGrouping(path)
    val getProperDateTaken = config.directorySorting and SORT_BY_DATE_TAKEN != 0
            || sorting and SORT_BY_DATE_TAKEN != 0
            || grouping and GROUP_BY_DATE_TAKEN_DAILY != 0
            || grouping and GROUP_BY_DATE_TAKEN_MONTHLY != 0

    val getProperLastModified = config.directorySorting and SORT_BY_DATE_MODIFIED != 0
            || sorting and SORT_BY_DATE_MODIFIED != 0
            || grouping and GROUP_BY_LAST_MODIFIED_DAILY != 0
            || grouping and GROUP_BY_LAST_MODIFIED_MONTHLY != 0

    val getProperFileSize = config.directorySorting and SORT_BY_SIZE != 0

    val lastModifieds = if (getProperLastModified) {
        mediaFetcher.getFolderLastModifieds(path)
    } else {
        HashMap()
    }

    val dateTakens = mediaFetcher.getFolderDateTakens(path)
    val favoritePaths = getFavoritePaths()
    val curMedia = mediaFetcher.getFilesFrom(
        curPath = path,
        isPickImage = getImagesOnly,
        isPickVideo = getVideosOnly,
        getProperDateTaken = getProperDateTaken,
        getProperLastModified = getProperLastModified,
        getProperFileSize = getProperFileSize,
        favoritePaths = favoritePaths,
        getVideoDurations = false,
        lastModifieds = lastModifieds,
        dateTakens = dateTakens,
        android11Files = null
    )
    val directory = createDirectoryFromMedia(
        path = path,
        curMedia = curMedia,
        albumCovers = albumCovers,
        hiddenString = hiddenString,
        includedFolders = includedFolders,
        getProperFileSize = getProperFileSize,
        noMediaFolders = noMediaFolders
    )
    updateDBDirectory(directory)
}

fun Context.getFileDateTaken(path: String): Long {
    val projection = arrayOf(
        Images.Media.DATE_TAKEN
    )

    val uri = Files.getContentUri("external")
    val selection = "${Images.Media.DATA} = ?"
    val selectionArgs = arrayOf(path)

    try {
        val cursor = contentResolver.query(uri, projection, selection, selectionArgs, null)
        cursor?.use {
            if (cursor.moveToFirst()) {
                return cursor.getLongValue(Images.Media.DATE_TAKEN)
            }
        }
    } catch (ignored: Exception) {
    }

    return 0L
}

fun Context.getCompressionFormatFromUri(uri: Uri): CompressFormat {
    val type = getMimeTypeFromUri(uri)
    return when {
        type.equals("image/png", true) -> CompressFormat.PNG
        type.equals("image/webp", true) -> CompressFormat.WEBP
        else -> CompressFormat.JPEG
    }
}

fun Context.resolveUriScheme(
    uri: Uri,
    onPath: (String) -> Unit,
    onContentUri: (Uri) -> Unit,
    onUnknown: () -> Unit = { toast(R.string.unknown_file_location) }
) {
    when (uri.scheme) {
        "file" -> onPath(uri.path!!)
        "content" -> onContentUri(uri)
        else -> onUnknown()
    }
}
