package org.fossify.gallery.adapters

import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.allViews
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.bumptech.glide.Glide
import com.qtalk.recyclerviewfastscroller.RecyclerViewFastScroller
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.adapters.MyRecyclerViewAdapter
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.convertToBitmap
import org.fossify.commons.extensions.formatSize
import org.fossify.commons.extensions.getFilenameFromPath
import org.fossify.commons.extensions.getFormattedDuration
import org.fossify.commons.extensions.getOTGPublicPath
import org.fossify.commons.extensions.getParentPath
import org.fossify.commons.extensions.getTimeFormat
import org.fossify.commons.extensions.handleDeletePasswordProtection
import org.fossify.commons.extensions.hasOTGConnected
import org.fossify.commons.extensions.isExternalStorageManager
import org.fossify.commons.extensions.isImageFast
import org.fossify.commons.extensions.isPathOnOTG
import org.fossify.commons.extensions.isRestrictedWithSAFSdk30
import org.fossify.commons.extensions.needsStupidWritePermissions
import org.fossify.commons.extensions.recycleBinPath
import org.fossify.commons.extensions.rescanPaths
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.FAVORITES
import org.fossify.commons.helpers.SORT_BY_CUSTOM
import org.fossify.commons.helpers.VIEW_TYPE_LIST
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isRPlus
import org.fossify.commons.helpers.sumByLong
import org.fossify.commons.interfaces.ItemMoveCallback
import org.fossify.commons.interfaces.ItemTouchHelperContract
import org.fossify.commons.interfaces.StartReorderDragListener
import org.fossify.commons.models.FileDirItem
import org.fossify.commons.views.MyRecyclerView
import org.fossify.gallery.R
import org.fossify.gallery.activities.SimpleActivity
import org.fossify.gallery.activities.ViewPagerActivity
import org.fossify.gallery.databinding.PhotoItemGridBinding
import org.fossify.gallery.databinding.PhotoItemListBinding
import org.fossify.gallery.databinding.ThumbnailSectionBinding
import org.fossify.gallery.databinding.VideoItemGridBinding
import org.fossify.gallery.databinding.VideoItemListBinding
import org.fossify.gallery.dialogs.PCloudRestoreDialog
import org.fossify.gallery.extensions.config
import org.fossify.gallery.extensions.fixDateTaken
import org.fossify.gallery.extensions.getShortcutImage
import org.fossify.gallery.extensions.isPCloudRecycleBinPath
import org.fossify.gallery.extensions.launchResizeImageDialog
import org.fossify.gallery.extensions.launchResizeMultipleImagesDialog
import org.fossify.gallery.extensions.loadImage
import org.fossify.gallery.extensions.openEditor
import org.fossify.gallery.extensions.openPath
import org.fossify.gallery.extensions.rescanFolderMedia
import org.fossify.gallery.extensions.restoreRecycleBinPaths
import org.fossify.gallery.extensions.saveRotatedImageToFile
import org.fossify.gallery.extensions.setAs
import org.fossify.gallery.extensions.shareMediaPaths
import org.fossify.gallery.extensions.shareMediumPath
import org.fossify.gallery.extensions.showRestoreConfirmationDialog
import org.fossify.gallery.extensions.toggleFileVisibility
import org.fossify.gallery.extensions.tryCopyMoveFilesTo
import org.fossify.gallery.extensions.updateFavorite
import org.fossify.gallery.extensions.updateFavoritePaths
import org.fossify.gallery.extensions.withLocalMediaFile
import org.fossify.gallery.extensions.writeToPCloud
import org.fossify.gallery.helpers.MediaStorage
import org.fossify.gallery.helpers.PATH
import org.fossify.gallery.helpers.PCloudWriter
import org.fossify.gallery.helpers.RECYCLE_BIN
import org.fossify.gallery.helpers.ROUNDED_CORNERS_BIG
import org.fossify.gallery.helpers.ROUNDED_CORNERS_NONE
import org.fossify.gallery.helpers.ROUNDED_CORNERS_SMALL
import org.fossify.gallery.helpers.SET_WALLPAPER_INTENT
import org.fossify.gallery.helpers.SmbVideoCache
import org.fossify.gallery.helpers.SHOW_ALL
import org.fossify.gallery.helpers.SHOW_FAVORITES
import org.fossify.gallery.helpers.SHOW_RECYCLE_BIN
import org.fossify.gallery.helpers.TYPE_GIFS
import org.fossify.gallery.helpers.TYPE_RAWS
import org.fossify.gallery.interfaces.MediaOperationsListener
import org.fossify.gallery.jobs.SmbDownloadService
import org.fossify.gallery.models.Medium
import org.fossify.gallery.models.ThumbnailItem
import org.fossify.gallery.models.ThumbnailSection
import java.util.Collections
import kotlin.math.min

class MediaAdapter(
    activity: BaseSimpleActivity,
    var media: ArrayList<ThumbnailItem>,
    val listener: MediaOperationsListener?,
    val isAGetIntent: Boolean,
    val allowMultiplePicks: Boolean,
    val path: String,
    recyclerView: MyRecyclerView,
    val swipeRefreshLayout: SwipeRefreshLayout? = null,
    // only the media of a single folder have an order of their own to drag around, search
    // results and picker dialogs are built from whatever matches instead
    val allowReordering: Boolean = false,
    itemClick: (Any) -> Unit
) : MyRecyclerViewAdapter(activity, recyclerView, itemClick), ItemTouchHelperContract,
    RecyclerViewFastScroller.OnPopupTextUpdate {

    private val ITEM_SECTION = 0
    private val ITEM_MEDIUM_VIDEO_PORTRAIT = 1
    private val ITEM_MEDIUM_PHOTO = 2

    private val config = activity.config
    private val viewType = config.getFolderViewType(if (config.showAll) SHOW_ALL else path)
    private val isListViewType = viewType == VIEW_TYPE_LIST
    private var rotatedImagePaths = ArrayList<String>()

    // The editor and the rotation write a remote medium back through the hosting activity,
    // which holds the edit while the editor has it. A picker dialog's grid is hosted by
    // something else, and there the two stay local as they were
    private val galleryActivity = activity as? SimpleActivity
    private val canWriteBackToRemote = galleryActivity != null
    private var currentMediaHash = media.hashCode()
    private val hasOTGConnected = activity.hasOTGConnected()

    private var scrollHorizontally = config.scrollHorizontally
    private var animateGifs = config.animateGifs
    private var cropThumbnails = config.cropThumbnails
    private var displayFilenames = config.displayFileNames
    private var showFileTypes = config.showThumbnailFileTypes

    // tapping an item while selecting toggles the selection, so items get their own button to
    // open the medium fullscreen. Picker and wallpaper intents are left out, there a tap
    // confirms the pick instead of opening anything
    private val canPreviewWhileSelecting = !isAGetIntent &&
        !activity.intent.getBooleanExtra(SET_WALLPAPER_INTENT, false)

    private var isDragAndDropping = false
    private var startReorderDragListener: StartReorderDragListener? = null

    // the folder the sorting and the custom order of these items belong to
    private val sortingPath = if (config.showAll) SHOW_ALL else path

    var sorting = config.getFolderSorting(sortingPath)
    var dateFormat = config.dateFormat
    var timeFormat = activity.getTimeFormat()

    init {
        setupDragListener(true)
    }

    override fun getActionMenuId() = R.menu.cab_media

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = if (viewType == ITEM_SECTION) {
            ThumbnailSectionBinding.inflate(layoutInflater, parent, false)
        } else {
            if (isListViewType) {
                if (viewType == ITEM_MEDIUM_PHOTO) {
                    PhotoItemListBinding.inflate(layoutInflater, parent, false)
                } else {
                    VideoItemListBinding.inflate(layoutInflater, parent, false)
                }
            } else {
                if (viewType == ITEM_MEDIUM_PHOTO) {
                    PhotoItemGridBinding.inflate(layoutInflater, parent, false)
                } else {
                    VideoItemGridBinding.inflate(layoutInflater, parent, false)
                }
            }
        }
        return createViewHolder(binding.root)
    }

    override fun onBindViewHolder(holder: MyRecyclerViewAdapter.ViewHolder, position: Int) {
        val tmbItem = media.getOrNull(position) ?: return
        val allowLongPress = (!isAGetIntent || allowMultiplePicks) && tmbItem is Medium
        holder.bindView(tmbItem, tmbItem is Medium, allowLongPress) { itemView, adapterPosition ->
            if (tmbItem is Medium) {
                setupThumbnail(itemView, tmbItem, holder)
            } else {
                setupSection(itemView, tmbItem as ThumbnailSection)
            }
        }
        bindViewHolder(holder)
    }

    override fun getItemCount() = media.size

    override fun getItemViewType(position: Int): Int {
        val tmbItem = media[position]
        return when {
            tmbItem is ThumbnailSection -> ITEM_SECTION
            (tmbItem as Medium).isVideo() || tmbItem.isPortrait() -> ITEM_MEDIUM_VIDEO_PORTRAIT
            else -> ITEM_MEDIUM_PHOTO
        }
    }

    override fun prepareActionMode(menu: Menu) {
        val selectedItems = getSelectedItems()
        if (selectedItems.isEmpty()) {
            return
        }

        val isOneItemSelected = isOneItemSelected()
        val selectedPaths = selectedItems.map { it.path } as ArrayList<String>
        val isInRecycleBin = selectedItems.firstOrNull()?.getIsInRecycleBin() == true

        // what a selection is offered is what its storage says it can do, see MediaStorage. A
        // selection mixing storages is no storage, and is offered nothing that goes through one:
        // favorites, the confirmation of a pick and the video download are all that is left
        val storage = MediaStorage.ofAll(activity, selectedPaths)
        // a medium in the pCloud bin is restored or deleted for good, nothing else: copying or
        // sharing it would go by a remote path the bin does not keep
        val isInPCloudBin = isInRecycleBin && storage is MediaStorage.PCloud
        menu.apply {
            findItem(R.id.cab_change_order).isVisible = canReorder()
            findItem(R.id.cab_move_to_top).isVisible = isDragAndDropping
            findItem(R.id.cab_move_to_bottom).isVisible = isDragAndDropping

            findItem(R.id.cab_rename).isVisible = storage != null && (storage.canRenameSeveral || isOneItemSelected) && !isInRecycleBin
            findItem(R.id.cab_add_to_favorites).isVisible = !isInRecycleBin
            findItem(R.id.cab_fix_date_taken).isVisible = storage?.canFixDateTaken == true && !isInRecycleBin
            findItem(R.id.cab_move_to).isVisible = storage != null && !isInRecycleBin
            findItem(R.id.cab_open_with).isVisible = storage?.canOpenWith == true && isOneItemSelected && !isInRecycleBin
            // a remote medium is edited through a copy of its own and written back over the
            // original, which only a screen of the gallery can do; the same as the fullscreen
            // view does it
            findItem(R.id.cab_edit).isVisible =
                storage != null && (!storage.isRemote || canWriteBackToRemote) && isOneItemSelected && !isInRecycleBin
            findItem(R.id.cab_set_as).isVisible = storage?.canSetAs == true && isOneItemSelected && !isInRecycleBin
            // offered whenever the selection holds a video that can be had in hand first,
            // whatever else is in it: the run takes those out of the selection and says how many
            // that was
            findItem(R.id.cab_smb_download_videos).isVisible = selectedItems.any { it.isVideo() && MediaStorage.of(activity, it.path).streamsVideos }
            findItem(R.id.cab_resize).isVisible =
                storage != null && storage.canResize && (storage.canResizeSeveral || isOneItemSelected) && canResize(selectedItems)
            findItem(R.id.cab_confirm_selection).isVisible = isAGetIntent && allowMultiplePicks && selectedKeys.isNotEmpty()
            findItem(R.id.cab_restore_recycle_bin_files).isVisible =
                selectedPaths.all { it.startsWith(activity.recycleBinPath) } || selectedPaths.all { it.isPCloudRecycleBinPath() }
            findItem(R.id.cab_create_shortcut).isVisible = storage?.canCreateShortcut == true && isOneItemSelected
            // a medium of the share is deleted from it for good; there is no recycle bin on a
            // share, and the confirmation says so
            findItem(R.id.cab_delete).isVisible = storage != null
            findItem(R.id.cab_share).isVisible = storage?.canShare == true && !isInPCloudBin
            // rotating a remote image writes it back over itself, the same as editing does
            findItem(R.id.cab_rotate).isVisible =
                storage != null && storage.canRotate && (!storage.isRemote || canWriteBackToRemote) && !isInRecycleBin
            // a medium of a remote storage gets a properties dialog of its own, built from what
            // the gallery knows rather than from a file on the device (#60)
            findItem(R.id.cab_properties).isVisible = storage != null
            // media of the share are copied off it by SmbTransferService
            findItem(R.id.cab_copy_to).isVisible = storage != null && !isInPCloudBin

            checkHideBtnVisibility(this, selectedItems)
            checkFavoriteBtnVisibility(this, selectedItems)
        }
    }

    override fun actionItemPressed(id: Int) {
        if (selectedKeys.isEmpty()) {
            return
        }

        when (id) {
            R.id.cab_change_order -> changeOrder()
            R.id.cab_move_to_top -> moveSelectedItemsToTop()
            R.id.cab_move_to_bottom -> moveSelectedItemsToBottom()
            R.id.cab_confirm_selection -> confirmSelection()
            R.id.cab_properties -> showProperties()
            R.id.cab_rename -> checkMediaManagementAndRename()
            R.id.cab_edit -> editFile()
            R.id.cab_hide -> toggleFileVisibility(true)
            R.id.cab_unhide -> toggleFileVisibility(false)
            R.id.cab_add_to_favorites -> toggleFavorites(true)
            R.id.cab_remove_from_favorites -> toggleFavorites(false)
            R.id.cab_restore_recycle_bin_files -> restoreFiles()
            R.id.cab_share -> shareMedia()
            R.id.cab_smb_download_videos -> downloadSelectedSmbVideos()
            R.id.cab_rotate_right -> rotateSelection(90)
            R.id.cab_rotate_left -> rotateSelection(270)
            R.id.cab_rotate_one_eighty -> rotateSelection(180)
            R.id.cab_copy_to -> checkMediaManagementAndCopy(true)
            R.id.cab_move_to -> moveFilesTo()
            R.id.cab_create_shortcut -> createShortcut()
            R.id.cab_select_all -> selectAll()
            R.id.cab_open_with -> openPath()
            R.id.cab_fix_date_taken -> fixDateTaken()
            R.id.cab_set_as -> setAs()
            R.id.cab_resize -> resize()
            R.id.cab_delete -> checkDeleteConfirmation()
        }
    }

    override fun getSelectableItemCount() = media.filter { it is Medium }.size

    override fun getIsItemSelectable(position: Int) = !isASectionTitle(position)

    override fun getItemSelectionKey(position: Int) = (media.getOrNull(position) as? Medium)?.path?.hashCode()

    override fun getItemKeyPosition(key: Int) = media.indexOfFirst { (it as? Medium)?.path?.hashCode() == key }

    override fun onActionModeCreated() {
        refreshPreviewButtons()
    }

    override fun onActionModeDestroyed() {
        if (isDragAndDropping) {
            isDragAndDropping = false
            config.saveCustomMediaOrder(sortingPath, media.mapNotNull { (it as? Medium)?.path })
            config.saveCustomSorting(sortingPath, SORT_BY_CUSTOM)
            sorting = SORT_BY_CUSTOM
            notifyDataSetChanged()
            listener?.refreshItems()
        }

        refreshPreviewButtons()
    }

    // grouping splits the items up with section titles that must not be dragged around, so a
    // custom order can only be built while the thumbnails are one plain list
    private fun canReorder() =
        allowReordering && !isAGetIntent && media.none { it is ThumbnailSection }

    private fun changeOrder() {
        isDragAndDropping = true
        notifyDataSetChanged()
        actMode?.invalidate()

        if (startReorderDragListener == null) {
            val touchHelper = ItemTouchHelper(ItemMoveCallback(this, true))
            touchHelper.attachToRecyclerView(recyclerView)

            startReorderDragListener = object : StartReorderDragListener {
                override fun requestDrag(viewHolder: RecyclerView.ViewHolder) {
                    touchHelper.startDrag(viewHolder)
                }
            }
        }
    }

    private fun moveSelectedItemsToTop() {
        selectedKeys.toMutableList().reversed().forEach { key ->
            val position = media.indexOfFirst { (it as? Medium)?.path?.hashCode() == key }
            if (position != -1) {
                val tempItem = media[position]
                media.removeAt(position)
                media.add(0, tempItem)
            }
        }

        notifyDataSetChanged()
    }

    private fun moveSelectedItemsToBottom() {
        selectedKeys.forEach { key ->
            val position = media.indexOfFirst { (it as? Medium)?.path?.hashCode() == key }
            if (position != -1) {
                val tempItem = media[position]
                media.removeAt(position)
                media.add(media.size, tempItem)
            }
        }

        notifyDataSetChanged()
    }

    override fun onRowMoved(fromPosition: Int, toPosition: Int) {
        if (fromPosition < toPosition) {
            for (i in fromPosition until toPosition) {
                Collections.swap(media, i, i + 1)
            }
        } else {
            for (i in fromPosition downTo toPosition + 1) {
                Collections.swap(media, i, i - 1)
            }
        }

        notifyItemMoved(fromPosition, toPosition)
    }

    override fun onRowSelected(myViewHolder: ViewHolder?) {
        swipeRefreshLayout?.isEnabled = false
    }

    override fun onRowClear(myViewHolder: ViewHolder?) {
        swipeRefreshLayout?.isEnabled = activity.config.enablePullToRefresh
    }

    // the preview buttons only show up while selecting, so every visible item has to be
    // rebound when the selection mode starts or ends
    private fun refreshPreviewButtons() {
        if (canPreviewWhileSelecting) {
            recyclerView.post { notifyDataSetChanged() }
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        if (!activity.isDestroyed) {
            val itemView = holder.itemView
            val tmb = itemView.allViews.firstOrNull { it.id == R.id.medium_thumbnail }
            if (tmb != null) {
                Glide.with(activity).clear(tmb)
            }
        }
    }

    fun isASectionTitle(position: Int) = media.getOrNull(position) is ThumbnailSection

    // a medium is hidden by renaming its file with a leading dot, which is a write into the
    // device's file system, so it is for the storage to say (MediaStorage.canHide) and for the
    // storage permission to allow
    private fun checkHideBtnVisibility(menu: Menu, selectedItems: ArrayList<Medium>) {
        val isInRecycleBin = selectedItems.firstOrNull()?.getIsInRecycleBin() == true
        val storage = MediaStorage.ofAll(activity, selectedItems.map { it.path })
        val canHide = storage?.canHide == true && (!isRPlus() || isExternalStorageManager()) && !isInRecycleBin
        menu.findItem(R.id.cab_hide).isVisible = canHide && selectedItems.any { !it.isHidden() }
        menu.findItem(R.id.cab_unhide).isVisible = canHide && selectedItems.any { it.isHidden() }
    }

    private fun checkFavoriteBtnVisibility(menu: Menu, selectedItems: ArrayList<Medium>) {
        menu.findItem(R.id.cab_add_to_favorites).isVisible = selectedItems.none { it.getIsInRecycleBin() } && selectedItems.any { !it.isFavorite }
        menu.findItem(R.id.cab_remove_from_favorites).isVisible = selectedItems.none { it.getIsInRecycleBin() } && selectedItems.any { it.isFavorite }
    }

    private fun confirmSelection() {
        listener?.selectedPaths(getSelectedPaths())
    }

    // the storage reads them off its files or its rows, see MediaStorage.showProperties()
    private fun showProperties() {
        val selectedItems = getSelectedItems()
        val storage = MediaStorage.ofAll(activity, selectedItems.map { it.path }) ?: return
        storage.showProperties(activity, selectedItems)
    }

    // the storage asks for the name and carries its rows along, see MediaStorage; the list is
    // read again afterwards, whether it took the name or refused it. Several at once is on the
    // menu for the device only, see canRenameSeveral
    private fun checkMediaManagementAndRename() {
        val paths = getSelectedPaths()
        val storage = MediaStorage.ofAll(activity, paths) ?: return
        if (paths.size == 1) {
            storage.renameMedium(activity, paths.first()) {
                listener?.refreshItems()
                finishActMode()
            }
        } else {
            storage.renameSeveralMedia(activity, paths) {
                listener?.refreshItems()
                finishActMode()
            }
        }
    }

    private fun editFile() {
        val path = getFirstSelectedItemPath() ?: return
        val host = galleryActivity
        if (host == null) {
            activity.openEditor(path)
            return
        }

        host.editMedium(path)
    }

    private fun openPath() {
        val path = getFirstSelectedItemPath() ?: return
        activity.withLocalMediaFile(path) { activity.openPath(it, true) }
    }

    private fun setAs() {
        val path = getFirstSelectedItemPath() ?: return
        activity.withLocalMediaFile(path) { activity.setAs(it) }
    }

    private fun resize() {
        val paths = getSelectedItems().filter { it.isImage() }.map { it.path }
        if (isOneItemSelected()) {
            val path = paths.first()
            activity.launchResizeImageDialog(path) {
                finishActMode()
                listener?.refreshItems()
            }
        } else {
            activity.launchResizeMultipleImagesDialog(paths) {
                finishActMode()
                listener?.refreshItems()
            }
        }
    }

    private fun canResize(selectedItems: ArrayList<Medium>): Boolean {
        val selectionContainsImages = selectedItems.any { it.isImage() }
        if (!selectionContainsImages) {
            return false
        }

        val parentPath = selectedItems.first { it.isImage() }.parentPath
        val isCommonParent = selectedItems.all { parentPath == it.parentPath }
        val isRestrictedDir = activity.isRestrictedWithSAFSdk30(parentPath)
        return isExternalStorageManager() || (isCommonParent && !isRestrictedDir)
    }

    private fun toggleFileVisibility(hide: Boolean) {
        ensureBackgroundThread {
            getSelectedItems().forEach {
                activity.toggleFileVisibility(it.path, hide)
            }
            activity.runOnUiThread {
                listener?.refreshItems()
                finishActMode()
            }
        }
    }

    private fun toggleFavorites(add: Boolean) {
        ensureBackgroundThread {
            getSelectedItems().forEach {
                it.isFavorite = add
                activity.updateFavorite(it.path, add)
            }
            activity.runOnUiThread {
                listener?.refreshItems()
                finishActMode()
            }
        }
    }

    private fun restoreFiles() {
        val paths = getSelectedPaths()
        if (paths.firstOrNull()?.isPCloudRecycleBinPath() == true) {
            restorePCloudFiles(paths)
            return
        }

        if (paths.size > 1) {
            activity.showRestoreConfirmationDialog(paths.size) {
                doRestoreFiles(paths)
            }
        } else {
            doRestoreFiles(paths)
        }
    }

    private fun doRestoreFiles(paths: ArrayList<String>) {
        activity.restoreRecycleBinPaths(paths) {
            listener?.refreshItems()
            finishActMode()
        }
    }

    // the dialog names where the first one goes back to, as the example for the selection,
    // and can send the lot somewhere else
    private fun restorePCloudFiles(paths: ArrayList<String>) {
        ensureBackgroundThread {
            val (folder, exists) = PCloudWriter(activity).restoreDestinationOf(paths.first())
            activity.runOnUiThread {
                PCloudRestoreDialog(activity, paths.size, folder, !exists) { destination ->
                    activity.writeToPCloud(emptyList(), { restoreFromRecycleBin(paths, destination) }) {
                        activity.runOnUiThread {
                            listener?.refreshItems()
                            finishActMode()
                        }
                    }
                }
            }
        }
    }

    private fun shareMedia() {
        if (selectedKeys.size == 1 && selectedKeys.first() != -1) {
            activity.shareMediumPath(getSelectedItems().first().path)
        } else if (selectedKeys.size > 1) {
            activity.shareMediaPaths(getSelectedPaths())
        }
    }

    private fun handleRotate(paths: List<String>, degrees: Int) {
        rotatedImagePaths.clear()
        rotatedImagePaths.addAll(paths)

        val host = galleryActivity
        if (host == null) {
            rotateLocalFiles(paths, degrees)
            return
        }

        // a pCloud image is fetched, turned and written back, one after the other; a local
        // one is turned where it lies, the way it always was
        host.rotateMedia(paths, degrees) {
            listener?.refreshItems()
            finishActMode()
        }
    }

    private fun rotateLocalFiles(paths: List<String>, degrees: Int) {
        var fileCnt = paths.size
        activity.toast(org.fossify.commons.R.string.saving)
        ensureBackgroundThread {
            paths.forEach {
                activity.saveRotatedImageToFile(it, it, degrees, true) {
                    fileCnt--
                    if (fileCnt == 0) {
                        activity.runOnUiThread {
                            listener?.refreshItems()
                            finishActMode()
                        }
                    }
                }
            }
        }
    }

    private fun rotateSelection(degrees: Int) {
        val paths = getSelectedPaths().filter { it.isImageFast() }

        // a remote medium has no file on the device that could want the permission
        val needsPermission = paths.firstOrNull { !MediaStorage.of(activity, it).isRemote && activity.needsStupidWritePermissions(it) }
        if (needsPermission != null) {
            activity.handleSAFDialog(needsPermission) {
                if (it) {
                    handleRotate(paths, degrees)
                }
            }
        } else {
            handleRotate(paths, degrees)
        }
    }

    private fun moveFilesTo() {
        activity.handleDeletePasswordProtection {
            checkMediaManagementAndCopy(false)
        }
    }

    private fun checkMediaManagementAndCopy(isCopyOperation: Boolean) {
        val firstPath = getFirstSelectedItemPath() ?: return
        MediaStorage.of(activity, firstPath).onceAllowedToChangeMedia(activity) {
            copyMoveTo(isCopyOperation)
        }
    }

    private fun copyMoveTo(isCopyOperation: Boolean) {
        val paths = getSelectedPaths()

        val recycleBinPath = activity.recycleBinPath
        val fileDirItems = paths.asSequence().filter { isCopyOperation || !it.startsWith(recycleBinPath) }.map {
            FileDirItem(it, it.getFilenameFromPath())
        }.toMutableList() as ArrayList

        if (!isCopyOperation && paths.any { it.startsWith(recycleBinPath) }) {
            activity.toast(org.fossify.commons.R.string.moving_recycle_bin_items_disabled, Toast.LENGTH_LONG)
        }

        if (fileDirItems.isEmpty()) {
            return
        }

        activity.tryCopyMoveFilesTo(fileDirItems, isCopyOperation) {
            val destinationPath = it
            config.tempFolderPath = ""
            activity.applicationContext.rescanFolderMedia(destinationPath)
            activity.applicationContext.rescanFolderMedia(fileDirItems.first().getParentPath())

            val newPaths = fileDirItems.map { "$destinationPath/${it.name}" }.toMutableList() as ArrayList<String>
            activity.rescanPaths(newPaths) {
                activity.fixDateTaken(newPaths, false)
            }

            if (!isCopyOperation) {
                listener?.refreshItems()
                activity.updateFavoritePaths(fileDirItems, destinationPath)
            }
        }
    }

    private fun createShortcut() {
        val manager = activity.getSystemService(ShortcutManager::class.java)
        if (manager.isRequestPinShortcutSupported) {
            val path = getSelectedPaths().first()
            val drawable = resources.getDrawable(R.drawable.shortcut_image).mutate()
            activity.getShortcutImage(path, drawable) {
                val intent = Intent(activity, ViewPagerActivity::class.java).apply {
                    putExtra(PATH, path)
                    putExtra(SHOW_ALL, config.showAll)
                    putExtra(SHOW_FAVORITES, path == FAVORITES)
                    putExtra(SHOW_RECYCLE_BIN, path == RECYCLE_BIN)
                    action = Intent.ACTION_VIEW
                    flags = flags or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }

                val shortcut = ShortcutInfo.Builder(activity, path)
                    .setShortLabel(path.getFilenameFromPath())
                    .setIcon(Icon.createWithBitmap(drawable.convertToBitmap()))
                    .setIntent(intent)
                    .build()

                manager.requestPinShortcut(shortcut, null)
            }
        }
    }

    private fun fixDateTaken() {
        ensureBackgroundThread {
            activity.fixDateTaken(getSelectedPaths(), true) {
                listener?.refreshItems()
                finishActMode()
            }
        }
    }

    // the storage asks, in its own words and with the bin where it has one, see
    // MediaStorage.confirmDeleteMedia(); the items leave the grid once the storage may delete
    // them, and the screen that hosts the grid does the deleting through the storage
    private fun checkDeleteConfirmation() {
        val selectedItems = getSelectedItems()
        val storage = MediaStorage.ofAll(activity, selectedItems.map { it.path }) ?: return
        storage.confirmDeleteMedia(activity, selectedItems) { skipRecycleBin ->
            deleteFiles(storage, skipRecycleBin)
        }
    }

    private fun deleteFiles(storage: MediaStorage, skipRecycleBin: Boolean) {
        val selectedItems = getSelectedItems()
        if (selectedItems.isEmpty()) {
            return
        }

        val fileDirItems = selectedItems.map { it.toFileDirItem() } as ArrayList<FileDirItem>
        storage.onceMayDeleteMedia(activity, fileDirItems) {
            val positions = getSelectedItemPositions()
            media.removeAll(selectedItems)
            listener?.tryDeleteFiles(fileDirItems, skipRecycleBin)
            listener?.updateMediaGridDecoration(media)
            removeSelectedItems(positions)
            currentMediaHash = media.hashCode()
        }
    }

    // "Download the selected videos": the same fetch the viewer's menu does for one video, over
    // as many as were picked. The share is read one video at a time, so the order they go in
    // matters, and it is the order they were tapped in -- selectedKeys is a LinkedHashSet, so
    // the selection keeps it, and picking one again moves it to the end
    private fun downloadSelectedSmbVideos() {
        val videos = getSelectedItems().filter { it.isVideo() && MediaStorage.of(activity, it.path).streamsVideos }
        if (videos.isEmpty()) {
            activity.toast(R.string.smb_download_videos_no_videos)
            return
        }

        ensureBackgroundThread {
            // asking the file system whether each copy is already there, which a selection of
            // hundreds makes worth keeping off the main thread
            val cache = SmbVideoCache(activity)
            val wanted = videos.filter { cache.peek(it.path, it.size, it.modified) == null }
            val totalSize = wanted.sumByLong { it.size }
            activity.runOnUiThread {
                if (wanted.isEmpty()) {
                    activity.toast(R.string.smb_download_videos_none)
                    finishActMode()
                    return@runOnUiThread
                }

                // a selection can run to tens of gigabytes, so the size is named as well as the
                // count -- the count alone does not say what is about to be pulled over the network
                val question = activity.getString(R.string.smb_download_videos_confirmation, wanted.size, totalSize.formatSize())
                ConfirmationDialog(activity, question) {
                    if (SmbDownloadService.start(activity, wanted.map { it.path })) {
                        finishActMode()
                    } else {
                        activity.toast(R.string.smb_download_busy)
                    }
                }
            }
        }
    }

    private fun getSelectedItems() = selectedKeys.mapNotNull { getItemWithKey(it) } as ArrayList<Medium>

    private fun getSelectedPaths() = getSelectedItems().map { it.path } as ArrayList<String>

    private fun getFirstSelectedItemPath() = getItemWithKey(selectedKeys.first())?.path

    private fun getItemWithKey(key: Int): Medium? = media.firstOrNull { (it as? Medium)?.path?.hashCode() == key } as? Medium

    fun updateMedia(newMedia: ArrayList<ThumbnailItem>) {
        val thumbnailItems = newMedia.clone() as ArrayList<ThumbnailItem>
        if (thumbnailItems.hashCode() != currentMediaHash) {
            currentMediaHash = thumbnailItems.hashCode()
            media = thumbnailItems
            notifyDataSetChanged()
            dropSelectedItemsThatAreGone()
        }
    }

    // the list is rebuilt whenever anything about it changed, for example after deleting an item
    // from the fullscreen view. Everything that is still there stays selected, instead of the
    // whole selection being thrown away because one item went missing
    private fun dropSelectedItemsThatAreGone() {
        if (actMode == null) {
            return
        }

        val existingKeys = media.mapNotNull { (it as? Medium)?.path?.hashCode() }.toHashSet()
        selectedKeys.removeAll { !existingKeys.contains(it) }
        if (selectedKeys.isEmpty()) {
            finishActMode()
        } else {
            updateActModeTitle()
        }
    }

    // the fullscreen view can be opened out of a selection and toggle items while it is up, so
    // what it hands back on the way out becomes the selection of the grid
    fun applySelection(paths: Collection<String>) {
        if (actMode == null) {
            return
        }

        val wantedPaths = paths.toHashSet()

        // selecting comes first: dropping what was the last selected item would end the action
        // mode before the newly selected ones are in
        media.forEachIndexed { position, item ->
            val itemPath = (item as? Medium)?.path ?: return@forEachIndexed
            if (wantedPaths.contains(itemPath)) {
                toggleItemSelection(true, position, false)
            }
        }

        media.forEachIndexed { position, item ->
            val itemPath = (item as? Medium)?.path ?: return@forEachIndexed
            if (!wantedPaths.contains(itemPath)) {
                toggleItemSelection(false, position, false)
            }
        }

        if (selectedKeys.isEmpty()) {
            finishActMode()
        } else {
            updateActModeTitle()
        }
    }

    fun getSelectedMediaPaths() = getSelectedPaths()

    fun isSelecting() = actMode != null && selectedKeys.isNotEmpty()

    // MyRecyclerViewAdapter keeps its own title updating private, so the same "x / y" has to be
    // written here whenever the selection is changed without going through toggleItemSelection()
    private fun updateActModeTitle() {
        val selectableItemCount = getSelectableItemCount()
        actMode?.title = "${min(selectedKeys.size, selectableItemCount)} / $selectableItemCount"
        actMode?.invalidate()
    }

    fun updateDisplayFilenames(displayFilenames: Boolean) {
        this.displayFilenames = displayFilenames
        notifyDataSetChanged()
    }

    fun updateAnimateGifs(animateGifs: Boolean) {
        this.animateGifs = animateGifs
        notifyDataSetChanged()
    }

    fun updateCropThumbnails(cropThumbnails: Boolean) {
        this.cropThumbnails = cropThumbnails
        notifyDataSetChanged()
    }

    fun updateShowFileTypes(showFileTypes: Boolean) {
        this.showFileTypes = showFileTypes
        notifyDataSetChanged()
    }

    private fun setupThumbnail(
        view: View,
        medium: Medium,
        holder: MyRecyclerViewAdapter.ViewHolder
    ) {
        val isSelected = selectedKeys.contains(medium.path.hashCode())
        bindItem(view, medium).apply {
            val padding = if (config.thumbnailSpacing <= 1) {
                config.thumbnailSpacing
            } else {
                0
            }

            mediaItemHolder.setPadding(padding, padding, padding, padding)

            favorite.beVisibleIf(medium.isFavorite && config.markFavoriteItems)

            playPortraitOutline?.beVisibleIf(medium.isVideo() || medium.isPortrait())
            if (medium.isVideo()) {
                playPortraitOutline?.setImageResource(
                    if (isListViewType) {
                        org.fossify.commons.R.drawable.ic_play_outline_vector
                    } else {
                        org.fossify.commons.R.drawable.ic_play_vector
                    }
                )
                playPortraitOutline?.beVisible()
            } else if (medium.isPortrait()) {
                playPortraitOutline?.setImageResource(R.drawable.ic_portrait_photo_vector)
                playPortraitOutline?.beVisibleIf(showFileTypes)
            }

            if (showFileTypes && (medium.isGIF() || medium.isRaw() || medium.isSVG())) {
                fileType?.setText(
                    when (medium.type) {
                        TYPE_GIFS -> R.string.gif
                        TYPE_RAWS -> R.string.raw
                        else -> R.string.svg
                    }
                )
                fileType?.beVisible()
            } else {
                fileType?.beGone()
            }

            mediumName.beVisibleIf(displayFilenames || isListViewType)
            mediumName.text = medium.name
            mediumName.tag = medium.path

            // A video on a remote storage is scanned from a directory listing alone, so nothing
            // is known about how long it is until something opens the file -- the grid asking
            // for a thumbnail frame is what first does, and it writes the length back. Until
            // then the length is zero, which is not a length any video has: it means "not known
            // yet", and "00:00" is the one thing it must not be shown as
            val showVideoDuration = medium.isVideo() && config.showThumbnailVideoDuration && medium.videoDuration > 0
            if (showVideoDuration) {
                videoDuration?.text = medium.videoDuration.getFormattedDuration()
            }
            videoDuration?.beVisibleIf(showVideoDuration)
            if (isListViewType) {
                videoDuration?.setTextColor(textColor)
            }

            mediumCheck.beVisibleIf(isSelected)
            if (isSelected) {
                mediumCheck.background?.applyColorFilter(properPrimaryColor)
                mediumCheck.applyColorFilter(contrastColor)
            }

            val showPreview = canPreviewWhileSelecting && actModeCallback.isSelectable && !isDragAndDropping
            mediumPreview.beVisibleIf(showPreview)
            mediumPreview.setOnClickListener(if (showPreview) View.OnClickListener { itemClick(medium) } else null)

            mediumDragHandleWrapper.beVisibleIf(isDragAndDropping)
            if (isDragAndDropping) {
                mediumDragHandle.setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_DOWN) {
                        startReorderDragListener?.requestDrag(holder)
                    }

                    false
                }
            }

            if (isListViewType) {
                mediaItemHolder.isSelected = isSelected
            }

            var path = medium.path
            if (hasOTGConnected && root.context.isPathOnOTG(path)) {
                path = path.getOTGPublicPath(root.context)
            }

            val roundedCorners = when {
                isListViewType -> ROUNDED_CORNERS_SMALL
                config.fileRoundedCorners -> ROUNDED_CORNERS_BIG
                else -> ROUNDED_CORNERS_NONE
            }

            mediumThumbnail.setBackgroundResource(
                when (roundedCorners) {
                    ROUNDED_CORNERS_SMALL -> R.drawable.placeholder_rounded_small
                    ROUNDED_CORNERS_BIG -> R.drawable.placeholder_rounded_big
                    else -> R.drawable.placeholder_square
                }
            )

            activity.loadImage(
                type = medium.type,
                path = path,
                target = mediumThumbnail,
                horizontalScroll = scrollHorizontally,
                animateGifs = animateGifs,
                cropThumbnails = cropThumbnails,
                roundCorners = roundedCorners,
                signature = medium.getKey(),
                skipMemoryCacheAtPaths = rotatedImagePaths,
                onError = {
                    mediumThumbnail.scaleType = ImageView.ScaleType.CENTER
                    mediumThumbnail.setImageDrawable(AppCompatResources.getDrawable(activity, R.drawable.ic_vector_warning_colored))
                }
            )

            if (isListViewType) {
                mediumName.setTextColor(textColor)
                playPortraitOutline?.applyColorFilter(textColor)
                (mediumPreview as? TextView)?.setTextColor(textColor)
                mediumPreview.background?.applyColorFilter(textColor)
            }
        }
    }

    private fun setupSection(view: View, section: ThumbnailSection) {
        ThumbnailSectionBinding.bind(view).apply {
            thumbnailSection.text = section.title
            thumbnailSection.setTextColor(textColor)
        }
    }

    override fun onChange(position: Int): String {
        var realIndex = position
        if (isASectionTitle(position)) {
            realIndex++
        }

        return (media[realIndex] as? Medium)?.getBubbleText(sorting, activity, dateFormat, timeFormat) ?: ""
    }

    private fun bindItem(view: View, medium: Medium): MediaItemBinding {
        return if (isListViewType) {
            if (!medium.isVideo() && !medium.isPortrait()) {
                PhotoItemListBinding.bind(view).toMediaItemBinding()
            } else {
                VideoItemListBinding.bind(view).toMediaItemBinding()
            }
        } else {
            if (!medium.isVideo() && !medium.isPortrait()) {
                PhotoItemGridBinding.bind(view).toMediaItemBinding()
            } else {
                VideoItemGridBinding.bind(view).toMediaItemBinding()
            }
        }
    }
}
