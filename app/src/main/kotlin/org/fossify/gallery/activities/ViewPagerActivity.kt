package org.fossify.gallery.activities

import android.animation.Animator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Handler
import android.provider.MediaStore
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.core.graphics.drawable.toDrawable
import androidx.exifinterface.media.ExifInterface
import androidx.print.PrintHelper
import androidx.viewpager.widget.ViewPager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.google.android.material.appbar.AppBarLayout
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.dialogs.PropertiesDialog
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.convertToBitmap
import org.fossify.commons.extensions.formatSize
import org.fossify.commons.extensions.getColoredDrawableWithColor
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getDataColumn
import org.fossify.commons.extensions.getDoesFilePathExist
import org.fossify.commons.extensions.getDuration
import org.fossify.commons.extensions.getFilenameFromPath
import org.fossify.commons.extensions.getFinalUriFromPath
import org.fossify.commons.extensions.getImageResolution
import org.fossify.commons.extensions.getIsPathDirectory
import org.fossify.commons.extensions.getParentPath
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getResolution
import org.fossify.commons.extensions.getUriMimeType
import org.fossify.commons.extensions.handleDeletePasswordProtection
import org.fossify.commons.extensions.handleLockedFolderOpening
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.extensions.hideKeyboard
import org.fossify.commons.extensions.isExternalStorageManager
import org.fossify.commons.extensions.isGif
import org.fossify.commons.extensions.isMediaFile
import org.fossify.commons.extensions.isPortrait
import org.fossify.commons.extensions.isRawFast
import org.fossify.commons.extensions.isSvg
import org.fossify.commons.extensions.isVideoFast
import org.fossify.commons.extensions.needsStupidWritePermissions
import org.fossify.commons.extensions.onGlobalLayout
import org.fossify.commons.extensions.recycleBinPath
import org.fossify.commons.extensions.rescanPaths
import org.fossify.commons.extensions.scanPathRecursively
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.tryGenericMimeType
import org.fossify.commons.extensions.updateBrightness
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.FAVORITES
import org.fossify.commons.helpers.IS_FROM_GALLERY
import org.fossify.commons.helpers.NOMEDIA
import org.fossify.commons.helpers.REAL_FILE_PATH
import org.fossify.commons.helpers.REQUEST_EDIT_IMAGE
import org.fossify.commons.helpers.REQUEST_SET_AS
import org.fossify.commons.helpers.SORT_BY_RANDOM
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isRPlus
import org.fossify.commons.models.FileDirItem
import org.fossify.gallery.BuildConfig
import org.fossify.gallery.R
import org.fossify.gallery.adapters.MyPagerAdapter
import org.fossify.gallery.asynctasks.GetMediaAsynctask
import org.fossify.gallery.databinding.ActivityMediumBinding
import org.fossify.gallery.dialogs.DeleteWithRememberDialog
import org.fossify.gallery.dialogs.PCloudRestoreDialog
import org.fossify.gallery.dialogs.RemotePropertiesDialog
import org.fossify.gallery.dialogs.SaveAsDialog
import org.fossify.gallery.dialogs.SlideshowDialog
import org.fossify.gallery.extensions.config
import org.fossify.gallery.extensions.favoritesDB
import org.fossify.gallery.extensions.fixDateTaken
import org.fossify.gallery.extensions.getFavoritePaths
import org.fossify.gallery.extensions.getShortcutImage
import org.fossify.gallery.extensions.handleMediaManagementPrompt
import org.fossify.gallery.extensions.hideSystemUI
import org.fossify.gallery.extensions.isDownloadsFolder
import org.fossify.gallery.extensions.isPCloudPath
import org.fossify.gallery.extensions.isRemotePath
import org.fossify.gallery.extensions.isSmbPath
import org.fossify.gallery.extensions.isPCloudRecycleBinPath
import org.fossify.gallery.extensions.launchResizeImageDialog
import org.fossify.gallery.extensions.launchSettings
import org.fossify.gallery.extensions.mediaDB
import org.fossify.gallery.extensions.movePathsInRecycleBin
import org.fossify.gallery.extensions.openEditor
import org.fossify.gallery.extensions.openPath
import org.fossify.gallery.extensions.pCloudItemsDB
import org.fossify.gallery.extensions.rescanSmbFolders
import org.fossify.gallery.extensions.restoreRecycleBinPath
import org.fossify.gallery.extensions.saveRotatedImageToFile
import org.fossify.gallery.extensions.setAs
import org.fossify.gallery.extensions.shareMediumPath
import org.fossify.gallery.extensions.showFileOnMap
import org.fossify.gallery.extensions.showSystemUI
import org.fossify.gallery.extensions.toggleFileVisibility
import org.fossify.gallery.extensions.tryCopyMoveFilesTo
import org.fossify.gallery.extensions.tryDeleteFileDirItem
import org.fossify.gallery.extensions.updateFavorite
import org.fossify.gallery.extensions.updateFavoritePaths
import org.fossify.gallery.extensions.withEditableMediaFile
import org.fossify.gallery.extensions.withLocalMediaFile
import org.fossify.gallery.extensions.writeToPCloud
import org.fossify.gallery.extensions.writeToShare
import org.fossify.gallery.fragments.PhotoFragment
import org.fossify.gallery.fragments.VideoFragment
import org.fossify.gallery.fragments.ViewPagerFragment
import org.fossify.gallery.jobs.PCloudTransferService
import org.fossify.gallery.jobs.SmbTransferService
import org.fossify.gallery.helpers.BOTTOM_ACTION_CHANGE_ORIENTATION
import org.fossify.gallery.helpers.BOTTOM_ACTION_COPY
import org.fossify.gallery.helpers.BOTTOM_ACTION_DELETE
import org.fossify.gallery.helpers.BOTTOM_ACTION_EDIT
import org.fossify.gallery.helpers.BOTTOM_ACTION_MOVE
import org.fossify.gallery.helpers.BOTTOM_ACTION_PROPERTIES
import org.fossify.gallery.helpers.BOTTOM_ACTION_RENAME
import org.fossify.gallery.helpers.BOTTOM_ACTION_RESIZE
import org.fossify.gallery.helpers.BOTTOM_ACTION_ROTATE
import org.fossify.gallery.helpers.BOTTOM_ACTION_SET_AS
import org.fossify.gallery.helpers.BOTTOM_ACTION_SHARE
import org.fossify.gallery.helpers.BOTTOM_ACTION_SHOW_ON_MAP
import org.fossify.gallery.helpers.BOTTOM_ACTION_SLIDESHOW
import org.fossify.gallery.helpers.BOTTOM_ACTION_TOGGLE_FAVORITE
import org.fossify.gallery.helpers.BOTTOM_ACTION_TOGGLE_VISIBILITY
import org.fossify.gallery.helpers.ColorModeHelper
import org.fossify.gallery.helpers.DefaultPageTransformer
import org.fossify.gallery.helpers.FadePageTransformer
import org.fossify.gallery.helpers.GO_TO_NEXT_ITEM
import org.fossify.gallery.helpers.GO_TO_PREV_ITEM
import org.fossify.gallery.helpers.HIDE_SYSTEM_UI_DELAY
import org.fossify.gallery.helpers.IS_VIEW_INTENT
import org.fossify.gallery.helpers.MAX_PRINT_SIDE_SIZE
import org.fossify.gallery.helpers.MediaStorage
import org.fossify.gallery.helpers.PATH
import org.fossify.gallery.helpers.QUEUE_PATHS
import org.fossify.gallery.helpers.PORTRAIT_PATH
import org.fossify.gallery.helpers.PCLOUD_RECYCLE_BIN
import org.fossify.gallery.helpers.PCloudWriter
import org.fossify.gallery.helpers.RECYCLE_BIN
import org.fossify.gallery.helpers.ROTATE_BY_ASPECT_RATIO
import org.fossify.gallery.helpers.ROTATE_BY_DEVICE_ROTATION
import org.fossify.gallery.helpers.ROTATE_BY_SYSTEM_SETTING
import org.fossify.gallery.helpers.SELECTED_PATHS
import org.fossify.gallery.helpers.REMOTE_SAVE_DIR
import org.fossify.gallery.helpers.RemoteScanScheduler
import org.fossify.gallery.helpers.SHOW_ALL
import org.fossify.gallery.helpers.SHOW_FAVORITES
import org.fossify.gallery.helpers.SHOW_NEXT_ITEM
import org.fossify.gallery.helpers.SHOW_PREV_ITEM
import org.fossify.gallery.helpers.SHOW_RECYCLE_BIN
import org.fossify.gallery.helpers.SKIP_AUTHENTICATION
import org.fossify.gallery.helpers.SLIDESHOW_ANIMATION_FADE
import org.fossify.gallery.helpers.SLIDESHOW_ANIMATION_NONE
import org.fossify.gallery.helpers.SLIDESHOW_ANIMATION_SLIDE
import org.fossify.gallery.helpers.SLIDESHOW_DEFAULT_INTERVAL
import org.fossify.gallery.helpers.SLIDESHOW_FADE_DURATION
import org.fossify.gallery.helpers.SLIDESHOW_SLIDE_DURATION
import org.fossify.gallery.helpers.SLIDESHOW_START_ON_ENTER
import org.fossify.gallery.helpers.SmbClient
import org.fossify.gallery.helpers.TYPE_GIFS
import org.fossify.gallery.helpers.TYPE_IMAGES
import org.fossify.gallery.helpers.TYPE_PORTRAITS
import org.fossify.gallery.helpers.TYPE_RAWS
import org.fossify.gallery.helpers.TYPE_SVGS
import org.fossify.gallery.helpers.TYPE_VIDEOS
import org.fossify.gallery.helpers.getPermissionToRequest
import org.fossify.gallery.models.Medium
import org.fossify.gallery.models.ThumbnailItem
import java.io.File
import kotlin.math.min

@Suppress("UNCHECKED_CAST")
class ViewPagerActivity : BaseViewerActivity(), ViewPager.OnPageChangeListener, ViewPagerFragment.FragmentListener {
    companion object {
        private const val REQUEST_VIEW_VIDEO = 1
        private const val SAVED_PATH = "current_path"
        private const val SAVED_SELECTED_PATHS = "selected_paths"
    }

    private var mPath = ""
    private var mDirectory = ""
    private var mIsFullScreen = false
    private var mPos = -1
    private var mShowAll = false
    private var mIsSlideshowActive = false
    private var mPrevHashcode = 0

    private var mSlideshowHandler = Handler()
    private var mSlideshowInterval = SLIDESHOW_DEFAULT_INTERVAL
    private var mSlideshowMoveBackwards = false
    private var mSlideshowMedia = mutableListOf<Medium>()
    private var mAreSlideShowMediaVisible = false
    private var mRandomSlideshowStopped = false

    private var mIsOrientationLocked = false

    private var mMediaFiles = ArrayList<Medium>()

    // null while the viewer was not opened from a selection in the media grid, which is what the
    // selection toggle in the toolbar hangs off
    private var mSelectedPaths: ArrayList<String>? = null

    // The media to show, in the order to show them, when the viewer was opened on a queue of
    // downloaded videos rather than on a folder. The queue can cross folders, so the swipe
    // follows it and the folder is not listed at all; see QUEUE_PATHS
    private var mQueuePaths: ArrayList<String>? = null

    private var mFavoritePaths = ArrayList<String>()
    private var mIgnoredPaths = ArrayList<String>()
    private var mOriginalBrightness: Float? = null

    private val binding by viewBinding(ActivityMediumBinding::inflate)

    override val contentHolder: View
        get() = binding.fragmentHolder

    override val appBarLayout: AppBarLayout
        get() = binding.mediumViewerAppbar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(
            padBottomSystem = listOf(binding.bottomActions.bottomActionsWrapper),
        )

        mSelectedPaths = savedInstanceState?.getStringArrayList(SAVED_SELECTED_PATHS)
            ?: intent.getStringArrayListExtra(SELECTED_PATHS)

        setupOptionsMenu()
        refreshMenuItems()

        window.decorView.setBackgroundColor(getProperBackgroundColor())
        // A queue of downloaded videos brings its own list and its own order, which can cross
        // folders; what the grid was last showing has nothing to do with it
        mQueuePaths = intent.getStringArrayListExtra(QUEUE_PATHS)?.takeIf { it.isNotEmpty() }
        if (mQueuePaths == null) {
            (MediaActivity.mMedia.clone() as ArrayList<ThumbnailItem>).filterIsInstanceTo(mMediaFiles, Medium::class.java)
        }

        requestMediaPermissions {
            initViewPager(
                savedPath = savedInstanceState?.getString(SAVED_PATH).orEmpty()
            )
        }

        initFavorites()
    }

    // a move to or from a remote storage ends after this screen was left alone for a while;
    // the list is read again so that a page that went away goes away here too
    private val transferListener: () -> Unit = { refreshViewPager(refetchPosition = true) }

    override fun onResume() {
        super.onResume()
        if (!hasPermission(getPermissionToRequest())) {
            finish()
            return
        }

        PCloudTransferService.addListener(transferListener)
        SmbTransferService.addListener(transferListener)
        initBottomActions()
        mOriginalBrightness = window.updateBrightness(config.maxBrightness, mOriginalBrightness)
        setupOrientation()
        refreshMenuItems()

        val filename = getCurrentMedium()?.name ?: mPath.getFilenameFromPath()
        binding.mediumViewerToolbar.title = filename
    }

    override fun onPause() {
        super.onPause()
        PCloudTransferService.removeListener(transferListener)
        SmbTransferService.removeListener(transferListener)
        stopSlideshow()
    }

    override fun onDestroy() {
        super.onDestroy()
        ColorModeHelper.resetColorMode(this)

        if (intent.extras?.containsKey(IS_VIEW_INTENT) == true) {
            config.temporarilyShowHidden = false
        }

        if (config.isThirdPartyIntent) {
            config.isThirdPartyIntent = false

            if (intent.extras == null || isExternalIntent()) {
                mMediaFiles.clear()
            }
        }
    }

    fun refreshMenuItems() {
        val currentMedium = getCurrentMedium() ?: return
        currentMedium.isFavorite = mFavoritePaths.contains(currentMedium.path)
        val visibleBottomActions = if (config.bottomActions) config.visibleBottomActions else 0

        // a remote medium has no file on the device, but it is fetched into one before
        // anything that needs a file, so those actions are offered for it like they are for a
        // local medium. What stays hidden is what its storage says it cannot do, see
        // MediaStorage: hiding by renaming the file with a leading dot, and pinning a shortcut
        val storage = MediaStorage.of(this, currentMedium.path)
        // a medium in the pCloud bin is restored or deleted for good, nothing else; copying it
        // would go by a remote path the bin does not keep
        val isInPCloudBin = currentMedium.path.isPCloudRecycleBinPath()
        val hasFile = !isInPCloudBin

        runOnUiThread {
            val rotationDegrees = getCurrentPhotoFragment()?.mCurrentRotationDegrees ?: 0
            binding.mediumViewerToolbar.menu.apply {
                findItem(R.id.menu_show_on_map).isVisible = hasFile && visibleBottomActions and BOTTOM_ACTION_SHOW_ON_MAP == 0
                findItem(R.id.menu_slideshow).isVisible = visibleBottomActions and BOTTOM_ACTION_SLIDESHOW == 0
                findItem(R.id.menu_properties).isVisible = hasFile && visibleBottomActions and BOTTOM_ACTION_PROPERTIES == 0
                findItem(R.id.menu_delete).isVisible = visibleBottomActions and BOTTOM_ACTION_DELETE == 0
                findItem(R.id.menu_share).isVisible = !isInPCloudBin && visibleBottomActions and BOTTOM_ACTION_SHARE == 0
                findItem(R.id.menu_edit).isVisible = hasFile && visibleBottomActions and BOTTOM_ACTION_EDIT == 0 && !currentMedium.isSVG()
                findItem(R.id.menu_rename).isVisible = visibleBottomActions and BOTTOM_ACTION_RENAME == 0 && !currentMedium.getIsInRecycleBin()
                findItem(R.id.menu_rotate).isVisible = hasFile && currentMedium.isImage() && visibleBottomActions and BOTTOM_ACTION_ROTATE == 0
                findItem(R.id.menu_set_as).isVisible = hasFile && visibleBottomActions and BOTTOM_ACTION_SET_AS == 0
                findItem(R.id.menu_copy_to_clipboard).isVisible = hasFile && currentMedium.isImage()
                findItem(R.id.menu_copy_to).isVisible = !isInPCloudBin && visibleBottomActions and BOTTOM_ACTION_COPY == 0
                findItem(R.id.menu_move_to).isVisible = !isInPCloudBin && visibleBottomActions and BOTTOM_ACTION_MOVE == 0
                findItem(R.id.menu_save_as).isVisible = rotationDegrees != 0
                findItem(R.id.menu_print).isVisible = hasFile && (currentMedium.isImage() || currentMedium.isRaw())
                findItem(R.id.menu_resize).isVisible = hasFile && visibleBottomActions and BOTTOM_ACTION_RESIZE == 0 && currentMedium.isImage()
                findItem(R.id.menu_open_with).isVisible = hasFile
                // a video that is read as it plays can be had in hand first. It stays offered
                // for one already downloaded, which then says so
                findItem(R.id.menu_smb_download_video).isVisible = storage.streamsVideos && currentMedium.isVideo()
                findItem(R.id.menu_hide).isVisible =
                    storage.canHide && (!isRPlus() || isExternalStorageManager()) && !currentMedium.isHidden() && visibleBottomActions and BOTTOM_ACTION_TOGGLE_VISIBILITY == 0 && !currentMedium.getIsInRecycleBin()

                findItem(R.id.menu_unhide).isVisible =
                    storage.canHide && (!isRPlus() || isExternalStorageManager()) && currentMedium.isHidden() && visibleBottomActions and BOTTOM_ACTION_TOGGLE_VISIBILITY == 0 && !currentMedium.getIsInRecycleBin()

                findItem(R.id.menu_add_to_favorites).isVisible =
                    !currentMedium.isFavorite && visibleBottomActions and BOTTOM_ACTION_TOGGLE_FAVORITE == 0 && !currentMedium.getIsInRecycleBin()

                findItem(R.id.menu_remove_from_favorites).isVisible =
                    currentMedium.isFavorite && visibleBottomActions and BOTTOM_ACTION_TOGGLE_FAVORITE == 0 && !currentMedium.getIsInRecycleBin()

                findItem(R.id.menu_restore_file).isVisible = currentMedium.path.startsWith(recycleBinPath) || isInPCloudBin
                findItem(R.id.menu_create_shortcut).isVisible = storage.canCreateShortcut
                findItem(R.id.menu_change_orientation).isVisible = rotationDegrees == 0 && visibleBottomActions and BOTTOM_ACTION_CHANGE_ORIENTATION == 0
                findItem(R.id.menu_rotate).setShowAsAction(
                    if (rotationDegrees != 0) {
                        MenuItem.SHOW_AS_ACTION_ALWAYS
                    } else {
                        MenuItem.SHOW_AS_ACTION_IF_ROOM
                    }
                )
            }

            refreshSelectionToggle()
            if (visibleBottomActions != 0) {
                updateBottomActionIcons(currentMedium)
            }
        }
    }

    // the grid opened this view out of an active selection, so the item on screen can be taken
    // into that selection or dropped from it without going back first
    private fun toggleCurrentSelection() {
        val selectedPaths = mSelectedPaths ?: return
        val path = getCurrentPath()
        if (path.isEmpty()) {
            return
        }

        if (!selectedPaths.remove(path)) {
            selectedPaths.add(path)
        }

        publishSelection()
        refreshSelectionToggle()
    }

    // an empty white ring while the item is not selected and the same filled check a thumbnail
    // gets once it is, so both views say "selected" the same way
    private fun refreshSelectionToggle() {
        val selectedPaths = mSelectedPaths
        val path = getCurrentPath()
        binding.mediumSelectionToggle.apply {
            beVisibleIf(selectedPaths != null && path.isNotEmpty() && !mIsFullScreen)
            if (selectedPaths == null) {
                return@apply
            }

            val isSelected = selectedPaths.contains(path)
            contentDescription = getString(
                if (isSelected) R.string.remove_from_selection else R.string.add_to_selection
            )

            if (isSelected) {
                val primaryColor = getProperPrimaryColor()
                setBackgroundResource(org.fossify.commons.R.drawable.circle_background)
                background.applyColorFilter(primaryColor)
                setImageResource(org.fossify.commons.R.drawable.ic_check_vector)
                applyColorFilter(primaryColor.getContrastColor())
            } else {
                setBackgroundResource(R.drawable.circle_white_outline_background)
                setImageDrawable(null)
            }
        }
    }

    private fun dropFromSelection(path: String) {
        if (mSelectedPaths?.remove(path) == true) {
            publishSelection()
        }
    }

    // set after every change, so the grid gets the selection no matter how this view is left
    private fun publishSelection() {
        val selectedPaths = mSelectedPaths ?: return
        setResult(
            Activity.RESULT_OK,
            Intent().putStringArrayListExtra(SELECTED_PATHS, ArrayList(selectedPaths))
        )
    }

    private fun setupOptionsMenu() {
        binding.mediumViewerToolbar.apply {
            setTitleTextColor(Color.WHITE)
            overflowIcon = resources.getColoredDrawableWithColor(org.fossify.commons.R.drawable.ic_three_dots_vector, Color.WHITE)
            navigationIcon = resources.getColoredDrawableWithColor(org.fossify.commons.R.drawable.ic_arrow_left_vector, Color.WHITE)
        }

        updateMenuItemColors(binding.mediumViewerToolbar.menu, forceWhiteIcons = true)
        binding.mediumViewerToolbar.setOnMenuItemClickListener { menuItem ->
            if (getCurrentMedium() == null) {
                return@setOnMenuItemClickListener true
            }

            when (menuItem.itemId) {
                R.id.menu_set_as -> setCurrentAs()
                R.id.menu_slideshow -> initSlideshow()
                R.id.menu_copy_to -> checkMediaManagementAndCopy(true)
                R.id.menu_move_to -> moveFileTo()
                R.id.menu_open_with -> openCurrentWith()
                R.id.menu_hide -> toggleFileVisibility(true)
                R.id.menu_unhide -> toggleFileVisibility(false)
                R.id.menu_share -> shareMediumPath(getCurrentPath())
                R.id.menu_delete -> checkDeleteConfirmation()
                R.id.menu_rename -> checkMediaManagementAndRename()
                R.id.menu_print -> printFile()
                R.id.menu_edit -> editCurrentMedium()
                R.id.menu_properties -> showProperties()
                R.id.menu_show_on_map -> showCurrentOnMap()
                R.id.menu_rotate_right -> rotateImage(90)
                R.id.menu_rotate_left -> rotateImage(-90)
                R.id.menu_rotate_one_eighty -> rotateImage(180)
                R.id.menu_add_to_favorites -> toggleFavorite()
                R.id.menu_remove_from_favorites -> toggleFavorite()
                R.id.menu_restore_file -> restoreFile()
                R.id.menu_force_portrait -> toggleOrientation(SCREEN_ORIENTATION_PORTRAIT)
                R.id.menu_force_landscape -> toggleOrientation(SCREEN_ORIENTATION_LANDSCAPE)
                R.id.menu_force_landscape_reverse -> toggleOrientation(SCREEN_ORIENTATION_REVERSE_LANDSCAPE)
                R.id.menu_default_orientation -> toggleOrientation(SCREEN_ORIENTATION_UNSPECIFIED)
                R.id.menu_save_as -> saveImageAs()
                R.id.menu_create_shortcut -> createShortcut()
                R.id.menu_resize -> resizeImage()
                R.id.menu_settings -> launchSettings()
                R.id.menu_copy_to_clipboard -> copyImageToClipboard()
                R.id.menu_smb_download_video -> downloadCurrentSmbVideo()
                else -> return@setOnMenuItemClickListener false
            }
            return@setOnMenuItemClickListener true
        }

        binding.mediumViewerToolbar.setNavigationOnClickListener {
            finish()
        }

        binding.mediumSelectionToggle.setOnClickListener {
            toggleCurrentSelection()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        if (requestCode == REQUEST_EDIT_IMAGE) {
            val wasRemoteEdit = handleRemoteEditResult(resultCode) {
                mPos = -1
                mPrevHashcode = 0
                refreshViewPager()
            }

            if (!wasRemoteEdit && resultCode == Activity.RESULT_OK && resultData != null) {
                mPos = -1
                mPrevHashcode = 0
                refreshViewPager()
            }
        } else if (requestCode == REQUEST_SET_AS && resultCode == Activity.RESULT_OK) {
            toast(R.string.wallpaper_set_successfully)
        } else if (requestCode == REQUEST_VIEW_VIDEO && resultCode == Activity.RESULT_OK && resultData != null) {
            if (resultData.getBooleanExtra(GO_TO_NEXT_ITEM, false)) {
                goToNextItem()
            } else if (resultData.getBooleanExtra(GO_TO_PREV_ITEM, false)) {
                goToPrevItem()
            }
        }
        super.onActivityResult(requestCode, resultCode, resultData)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        initBottomActionsLayout()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(SAVED_PATH, getCurrentPath())
        outState.putStringArrayList(SAVED_SELECTED_PATHS, mSelectedPaths)
    }

    private fun initViewPager(savedPath: String) {
        val uri = intent.data
        if (uri != null) {
            mPath = savedPath.ifEmpty { getDataColumn(uri).orEmpty() }
        } else {
            try {
                mPath = savedPath.ifEmpty { intent.getStringExtra(PATH).orEmpty() }

                // make sure "Open Recycle Bin" works well with "Show all folders content"
                mShowAll = config.showAll && (mPath.isNotEmpty() && !mPath.startsWith(recycleBinPath))
            } catch (e: Exception) {
                showErrorToast(e)
                finish()
                return
            }
        }

        if (savedPath.isEmpty() && intent.extras?.containsKey(REAL_FILE_PATH) == true) {
            mPath = intent.extras!!.getString(REAL_FILE_PATH)!!
        }

        if (mPath.isEmpty()) {
            toast(org.fossify.commons.R.string.unknown_error_occurred)
            finish()
            return
        }

        if (mPath.isPortrait() && getPortraitPath() == "") {
            val newIntent = Intent(this, ViewPagerActivity::class.java)
            newIntent.putExtras(intent!!.extras!!)
            newIntent.putExtra(PORTRAIT_PATH, mPath)
            newIntent.putExtra(PATH, "${mPath.getParentPath().getParentPath()}/${mPath.getFilenameFromPath()}")

            startActivity(newIntent)
            finish()
            return
        }

        // a medium on a remote storage is fetched when it is shown; there is no file here to
        // find beforehand, and asking closes the viewer on every one of them
        if (!mPath.isRemotePath() && !getDoesFilePathExist(mPath) && getPortraitPath() == "") {
            finish()
            return
        }

        showSystemUI()

        if (intent.getBooleanExtra(SKIP_AUTHENTICATION, false)) {
            initContinue()
        } else {
            handleLockedFolderOpening(mPath.getParentPath()) { success ->
                if (success) {
                    initContinue()
                } else {
                    finish()
                }
            }
        }
    }

    private fun initContinue() {
        if (intent.extras?.containsKey(IS_VIEW_INTENT) == true) {
            if (isShowHiddenFlagNeeded()) {
                if (!config.isHiddenPasswordProtectionOn) {
                    config.temporarilyShowHidden = true
                }
            }

            config.isThirdPartyIntent = true
        }

        val isShowingFavorites = intent.getBooleanExtra(SHOW_FAVORITES, false)
        val isShowingRecycleBin = intent.getBooleanExtra(SHOW_RECYCLE_BIN, false)
        mDirectory = when {
            isShowingFavorites -> FAVORITES
            isShowingRecycleBin -> RECYCLE_BIN
            // a medium in the pCloud bin is listed with the bin, not with the folder its pseudo path names
            mPath.isPCloudRecycleBinPath() -> PCLOUD_RECYCLE_BIN
            else -> mPath.getParentPath()
        }
        binding.mediumViewerToolbar.title = mPath.getFilenameFromPath()

        binding.viewPager.onGlobalLayout {
            if (!isDestroyed) {
                if (mMediaFiles.isNotEmpty()) {
                    gotMedia(mMediaFiles as ArrayList<ThumbnailItem>, refetchViewPagerPosition = true)
                    checkSlideshowOnEnter()
                }
            }
        }

        // show the selected image asap, while loading the rest in the background to allow swiping between them. Might be needed at third party intents
        if (mMediaFiles.isEmpty() && mPath.isNotEmpty() && mDirectory != FAVORITES) {
            val filename = mPath.getFilenameFromPath()
            val folder = mPath.getParentPath()
            val type = getTypeFromPath(mPath)
            val medium = Medium(null, filename, mPath, folder, 0, 0, 0, type, 0, false, 0L, 0L)
            mMediaFiles.add(medium)
            gotMedia(mMediaFiles as ArrayList<ThumbnailItem>, refetchViewPagerPosition = true)
        }

        refreshViewPager(true)
        binding.viewPager.offscreenPageLimit = 2

        if (config.blackBackground) {
            binding.fragmentHolder.background = Color.BLACK.toDrawable()
            binding.viewPager.background = Color.BLACK.toDrawable()
        }

        if (config.hideSystemUI) {
            binding.viewPager.onGlobalLayout {
                Handler().postDelayed({
                    fragmentClicked()
                }, HIDE_SYSTEM_UI_DELAY)
            }
        }

        if (
            intent.action == "com.android.camera.action.REVIEW"
            || intent.action == MediaStore.ACTION_REVIEW
        ) {
            ensureBackgroundThread {
                if (mediaDB.getMediaFromPath(mPath).isEmpty()) {
                    val filename = mPath.getFilenameFromPath()
                    val parent = mPath.getParentPath()
                    val type = getTypeFromPath(mPath)
                    val isFavorite = favoritesDB.isFavorite(mPath)
                    val duration = if (type == TYPE_VIDEOS) getDuration(mPath) ?: 0 else 0
                    val ts = System.currentTimeMillis()
                    val medium = Medium(null, filename, mPath, parent, ts, ts, File(mPath).length(), type, duration, isFavorite, 0, 0L)
                    mediaDB.insert(medium)
                }
            }
        }
    }

    private fun getTypeFromPath(path: String): Int {
        return when {
            path.isVideoFast() -> TYPE_VIDEOS
            path.isGif() -> TYPE_GIFS
            path.isSvg() -> TYPE_SVGS
            path.isRawFast() -> TYPE_RAWS
            path.isPortrait() -> TYPE_PORTRAITS
            else -> TYPE_IMAGES
        }
    }

    private fun initBottomActions() {
        initBottomActionButtons()
        initBottomActionsLayout()
    }

    private fun initFavorites() {
        ensureBackgroundThread {
            mFavoritePaths = getFavoritePaths()
        }
    }

    private fun setupOrientation() {
        if (!mIsOrientationLocked) {
            if (config.screenRotation == ROTATE_BY_DEVICE_ROTATION) {
                requestedOrientation = SCREEN_ORIENTATION_SENSOR
            } else if (config.screenRotation == ROTATE_BY_SYSTEM_SETTING) {
                requestedOrientation = SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    private fun updatePagerItems(media: MutableList<Medium>) {
        val pagerAdapter = MyPagerAdapter(this, supportFragmentManager, media)
        if (!isDestroyed) {
            pagerAdapter.shouldInitFragment = mPos < 5
            binding.viewPager.apply {
                // must remove the listener before changing adapter, otherwise it might cause `mPos` to be set to 0
                removeOnPageChangeListener(this@ViewPagerActivity)
                adapter = pagerAdapter
                pagerAdapter.shouldInitFragment = true
                addOnPageChangeListener(this@ViewPagerActivity)
                currentItem = mPos
            }
        }
    }

    private fun checkSlideshowOnEnter() {
        if (intent.getBooleanExtra(SLIDESHOW_START_ON_ENTER, false)) {
            initSlideshow()
        }
    }

    private fun initSlideshow() {
        SlideshowDialog(this) {
            startSlideshow()
        }
    }

    private fun startSlideshow() {
        if (getMediaForSlideshow()) {
            binding.viewPager.onGlobalLayout {
                if (!isDestroyed) {
                    if (config.slideshowAnimation == SLIDESHOW_ANIMATION_FADE) {
                        binding.viewPager.setPageTransformer(false, FadePageTransformer())
                    }

                    hideSystemUI()
                    if (!mIsFullScreen) {
                        mIsFullScreen = true
                        fullscreenToggled()
                    }
                    mRandomSlideshowStopped = false
                    mSlideshowInterval = config.slideshowInterval
                    mSlideshowMoveBackwards = config.slideshowMoveBackwards
                    mIsSlideshowActive = true
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    scheduleSwipe()
                }
            }
        }
    }

    private fun goToNextMedium(forward: Boolean) {
        val oldPosition = binding.viewPager.currentItem
        val newPosition = if (forward) oldPosition + 1 else oldPosition - 1
        if (newPosition == -1 || newPosition > binding.viewPager.adapter!!.count - 1) {
            slideshowEnded(forward)
        } else {
            binding.viewPager.setCurrentItem(newPosition, false)
        }
    }

    private fun animatePagerTransition(forward: Boolean) {
        val oldPosition = binding.viewPager.currentItem
        val animator = ValueAnimator.ofInt(0, binding.viewPager.width)
        animator.addListener(object : Animator.AnimatorListener {
            override fun onAnimationEnd(animation: Animator) {
                if (binding.viewPager.isFakeDragging) {
                    try {
                        binding.viewPager.endFakeDrag()
                    } catch (ignored: Exception) {
                        stopSlideshow()
                    }

                    if (binding.viewPager.currentItem == oldPosition) {
                        slideshowEnded(forward)
                    }
                }
            }

            override fun onAnimationCancel(animation: Animator) {
                binding.viewPager.endFakeDrag()
            }

            override fun onAnimationStart(animation: Animator) {}

            override fun onAnimationRepeat(animation: Animator) {}
        })

        if (config.slideshowAnimation == SLIDESHOW_ANIMATION_SLIDE) {
            animator.interpolator = DecelerateInterpolator()
            animator.duration = SLIDESHOW_SLIDE_DURATION
        } else {
            animator.duration = SLIDESHOW_FADE_DURATION
        }

        animator.addUpdateListener(object : ValueAnimator.AnimatorUpdateListener {
            var oldDragPosition = 0
            override fun onAnimationUpdate(animation: ValueAnimator) {
                if (binding.viewPager.isFakeDragging == true) {
                    val dragPosition = animation.animatedValue as Int
                    val dragOffset = dragPosition - oldDragPosition
                    oldDragPosition = dragPosition
                    try {
                        binding.viewPager.fakeDragBy(dragOffset * (if (forward) -1f else 1f))
                    } catch (e: Exception) {
                        stopSlideshow()
                    }
                }
            }
        })

        binding.viewPager.beginFakeDrag()
        animator.start()
    }

    private fun slideshowEnded(forward: Boolean) {
        if (config.loopSlideshow) {
            if (forward) {
                binding.viewPager.setCurrentItem(0, false)
            } else {
                binding.viewPager.setCurrentItem(binding.viewPager.adapter!!.count - 1, false)
            }
        } else {
            stopSlideshow()
            toast(R.string.slideshow_ended)
        }
    }

    private fun stopSlideshow() {
        if (mIsSlideshowActive) {
            binding.viewPager.setPageTransformer(false, DefaultPageTransformer())
            mIsSlideshowActive = false
            showSystemUI()
            mSlideshowHandler.removeCallbacksAndMessages(null)
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            mAreSlideShowMediaVisible = false

            if (config.slideshowRandomOrder) {
                mRandomSlideshowStopped = true
            }
        }
    }

    private fun scheduleSwipe() {
        mSlideshowHandler.removeCallbacksAndMessages(null)
        if (mIsSlideshowActive) {
            if (getCurrentMedium()!!.isImage() || getCurrentMedium()!!.isGIF() || getCurrentMedium()!!.isPortrait()) {
                mSlideshowHandler.postDelayed({
                    if (mIsSlideshowActive && !isDestroyed) {
                        swipeToNextMedium()
                    }
                }, mSlideshowInterval * 1000L)
            } else {
                (getCurrentFragment() as? VideoFragment)!!.playVideo()
            }
        }
    }

    private fun swipeToNextMedium() {
        if (config.slideshowAnimation == SLIDESHOW_ANIMATION_NONE) {
            goToNextMedium(!mSlideshowMoveBackwards)
        } else {
            animatePagerTransition(!mSlideshowMoveBackwards)
        }
    }

    private fun getMediaForSlideshow(): Boolean {
        mSlideshowMedia = mMediaFiles.filter {
            it.isImage() || it.isPortrait() || (config.slideshowIncludeVideos && it.isVideo() || (config.slideshowIncludeGIFs && it.isGIF()))
        }.toMutableList()

        if (config.slideshowRandomOrder) {
            mSlideshowMedia.shuffle()
            mPos = 0
        } else {
            mPath = getCurrentPath()
            mPos = getPositionInList(mSlideshowMedia)
        }

        return if (mSlideshowMedia.isEmpty()) {
            toast(R.string.no_media_for_slideshow)
            false
        } else {
            updatePagerItems(mSlideshowMedia)
            mAreSlideShowMediaVisible = true
            true
        }
    }

    private fun moveFileTo() {
        handleDeletePasswordProtection {
            checkMediaManagementAndCopy(false)
        }
    }

    private fun checkMediaManagementAndCopy(isCopyOperation: Boolean) {
        MediaStorage.of(this, getCurrentPath()).onceAllowedToChangeMedia(this) {
            copyMoveTo(isCopyOperation)
        }
    }

    private fun copyMoveTo(isCopyOperation: Boolean) {
        val currPath = getCurrentPath()
        if (!isCopyOperation && currPath.startsWith(recycleBinPath)) {
            toast(org.fossify.commons.R.string.moving_recycle_bin_items_disabled, Toast.LENGTH_LONG)
            return
        }

        val fileDirItems = arrayListOf(FileDirItem(currPath, currPath.getFilenameFromPath()))
        tryCopyMoveFilesTo(fileDirItems, isCopyOperation) {
            val newPath = "$it/${currPath.getFilenameFromPath()}"
            rescanPaths(arrayListOf(newPath)) {
                fixDateTaken(arrayListOf(newPath), false)
            }

            config.tempFolderPath = ""
            if (!isCopyOperation) {
                refreshViewPager()
                updateFavoritePaths(fileDirItems, it)
            }
        }
    }

    private fun toggleFileVisibility(hide: Boolean, callback: (() -> Unit)? = null) {
        toggleFileVisibility(getCurrentPath(), hide) {
            val newFileName = it.getFilenameFromPath()
            binding.mediumViewerToolbar.title = newFileName

            getCurrentMedium()!!.apply {
                name = newFileName
                path = it
                getCurrentMedia()[mPos] = this
            }

            refreshMenuItems()
            callback?.invoke()
        }
    }

    private fun rotateImage(degrees: Int) {
        val currentPath = getCurrentPath()
        if (needsStupidWritePermissions(currentPath)) {
            handleSAFDialog(currentPath) {
                if (it) {
                    rotateBy(degrees)
                }
            }
        } else {
            rotateBy(degrees)
        }
    }

    private fun rotateBy(degrees: Int) {
        getCurrentPhotoFragment()?.rotateImageViewBy(degrees)
        refreshMenuItems()
    }

    private fun toggleOrientation(orientation: Int) {
        requestedOrientation = orientation
        mIsOrientationLocked = orientation != SCREEN_ORIENTATION_UNSPECIFIED
        refreshMenuItems()
    }

    private fun getChangeOrientationIcon(): Int {
        return if (mIsOrientationLocked) {
            if (requestedOrientation == SCREEN_ORIENTATION_PORTRAIT) {
                org.fossify.commons.R.drawable.ic_orientation_portrait_vector
            } else {
                org.fossify.commons.R.drawable.ic_orientation_landscape_vector
            }
        } else {
            org.fossify.commons.R.drawable.ic_orientation_auto_vector
        }
    }

    private fun editCurrentMedium() = editMedium(getCurrentPath())

    // Saving a rotated image, wherever it came from and wherever it is going.
    //
    // "Save as" asks the same question on all three storages now: where, and under what name.
    // A pCloud medium used to skip the question and write back over itself, which meant the
    // one thing it could do was the one thing it never asked about.
    //
    // The source may have no file on this device and the destination may take no file at all,
    // so the rotation always happens on a local file, and only then is the result handed to
    // whichever storage was picked
    private fun saveImageAs() {
        val currPath = getCurrentPath()
        val degrees = getCurrentPhotoFragment()?.mCurrentRotationDegrees ?: 0
        SaveAsDialog(this, currPath, false, localStorageOnly = false) { newPath ->
            if (newPath.isRemotePath()) {
                saveRotatedImageToRemote(currPath, newPath, degrees)
            } else {
                saveRotatedImageToDevice(currPath, newPath, degrees)
            }
        }
    }

    // A folder of this device, which SAF may have something to say about. The source can still
    // be remote, so it is fetched before anything reads it
    private fun saveRotatedImageToDevice(sourcePath: String, newPath: String, degrees: Int) {
        handleSAFDialog(newPath) { granted ->
            if (!granted) {
                return@handleSAFDialog
            }

            withEditableMediaFile(sourcePath) { localSource ->
                toast(org.fossify.commons.R.string.saving)
                ensureBackgroundThread {
                    saveRotatedImageToFile(localSource, newPath, degrees, true) {
                        rotationSaved()
                    }
                }
            }
        }
    }

    // A folder on pCloud or on the share. The rotated image is written into a file of this
    // device first, under the name it is to have there, because that is what both storages
    // take -- and because a write that breaks off then leaves nothing behind on them
    private fun saveRotatedImageToRemote(sourcePath: String, newPath: String, degrees: Int) {
        withEditableMediaFile(sourcePath) { localSource ->
            toast(org.fossify.commons.R.string.saving)
            ensureBackgroundThread {
                val stagingDir = File(cacheDir, REMOTE_SAVE_DIR).apply {
                    deleteRecursively()
                    mkdirs()
                }

                val staged = File(stagingDir, newPath.getFilenameFromPath())
                saveRotatedImageToFile(localSource, staged.absolutePath, degrees, true) {
                    if (newPath.isSmbPath()) {
                        sendRotatedImageToShare(staged, newPath)
                    } else {
                        sendRotatedImageToPCloud(staged, newPath)
                    }
                }
            }
        }
    }

    // The share takes both now: a name that is free is a new file, and one the user has agreed to
    // write over goes through the stash and replace that keeps the medium whole the whole way
    // (#28). Which of the two it is, is asked of the share rather than of the cache -- the same
    // question ensureWritablePath() asked a moment ago, and a share holds files this app never
    // scanned.
    //
    // A new file has the folder walked again afterwards, which is the only way the cache learns
    // of it. An overwrite carries its own rows, so nothing is walked for it
    private fun sendRotatedImageToShare(staged: File, newPath: String) {
        val taken = try {
            SmbClient.fileExists(this, newPath)
        } catch (e: Exception) {
            Log.w("SmbWrite", "Could not ask the share whether it already has $newPath", e)
            runOnUiThread { showErrorToast(e) }
            return
        }

        if (taken) {
            writeToShare({ overwriteFile(newPath, staged.absolutePath) }) { success ->
                if (success) {
                    rotationSaved()
                }
            }

            return
        }

        try {
            SmbClient.create(this, newPath) { output -> staged.inputStream().use { it.copyTo(output) } }
            SmbClient.setModified(this, newPath, staged.lastModified())
        } catch (e: Exception) {
            Log.w("SmbWrite", "Could not save $newPath onto the share", e)
            runOnUiThread { showErrorToast(e) }
            return
        }

        rescanSmbFolders(listOf(newPath.getParentPath()), reportCounts = false, priority = RemoteScanScheduler.PRIORITY_TRANSFER) {
            rotationSaved()
        }
    }

    // pCloud takes both: a name that is free is an upload, and one the user has agreed to write
    // over goes through the replace that keeps the medium whole the whole way
    private fun sendRotatedImageToPCloud(staged: File, newPath: String) {
        val folder = newPath.getParentPath()
        val isTaken = pCloudItemsDB.getItem(newPath) != null
        writeToPCloud(listOf(folder), {
            if (isTaken) {
                overwriteFile(newPath, staged.absolutePath)
            } else {
                uploadFile(staged.absolutePath, folder)
            }
        }) { success ->
            if (success) {
                rotationSaved()
            }
        }
    }

    // refreshMenuItems() posts to the main thread itself, so this is safe to call from the
    // thread a write finished on
    private fun rotationSaved() {
        toast(org.fossify.commons.R.string.file_saved)
        getCurrentPhotoFragment()?.mCurrentRotationDegrees = 0
        refreshMenuItems()
    }

    private fun createShortcut() {
        val manager = getSystemService(ShortcutManager::class.java)
        if (manager.isRequestPinShortcutSupported) {
            val medium = getCurrentMedium() ?: return
            val path = medium.path
            val drawable = resources.getDrawable(R.drawable.shortcut_image).mutate()
            getShortcutImage(path, drawable) {
                val intent = Intent(this, ViewPagerActivity::class.java).apply {
                    putExtra(PATH, path)
                    putExtra(SHOW_ALL, config.showAll)
                    putExtra(SHOW_FAVORITES, path == FAVORITES)
                    putExtra(SHOW_RECYCLE_BIN, path == RECYCLE_BIN)
                    action = Intent.ACTION_VIEW
                    flags = flags or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }

                val shortcut = ShortcutInfo.Builder(this, path)
                    .setShortLabel(medium.name)
                    .setIcon(Icon.createWithBitmap(drawable.convertToBitmap()))
                    .setIntent(intent)
                    .build()

                manager.requestPinShortcut(shortcut, null)
            }
        }
    }

    private fun getCurrentPhotoFragment() = getCurrentFragment() as? PhotoFragment

    // "download first, then play". The fragment owns the player, so it is the one that swaps the
    // stream for the downloaded file once it is there
    private fun downloadCurrentSmbVideo() {
        (getCurrentFragment() as? VideoFragment)?.downloadAndPlay()
    }

    private fun getPortraitPath() = intent.getStringExtra(PORTRAIT_PATH) ?: ""

    private fun isShowHiddenFlagNeeded(): Boolean {
        val file = File(mPath)
        if (file.isHidden) {
            return true
        }

        var parent = file.parentFile ?: return false
        while (true) {
            if (parent.isHidden || parent.list()?.any { it.startsWith(NOMEDIA) } == true) {
                return true
            }

            if (parent.absolutePath == "/") {
                break
            }
            parent = parent.parentFile ?: return false
        }

        return false
    }

    private fun getCurrentFragment() = (binding.viewPager.adapter as? MyPagerAdapter)?.getCurrentFragment(binding.viewPager.currentItem)

    private fun showProperties() {
        val medium = getCurrentMedium() ?: return
        // a medium of a remote storage has no file on the device for commons' dialog to read,
        // and what that dialog says when it cannot read one is "the source file does not exist"
        if (medium.path.isRemotePath()) {
            RemotePropertiesDialog(this, listOf(medium))
        } else {
            PropertiesDialog(this, medium.path, false)
        }
    }

    private fun initBottomActionsLayout() {
        if (config.bottomActions) {
            binding.bottomActions.root.beVisible()
        } else {
            binding.bottomActions.root.beGone()
        }
    }

    private fun initBottomActionButtons() {
        val currentMedium = getCurrentMedium()
        val visibleBottomActions = if (config.bottomActions) config.visibleBottomActions else 0
        // the same gating as refreshMenuItems(): no file, no file operations
        val storage = currentMedium?.let { MediaStorage.of(this, it.path) }
        val isInPCloudBin = currentMedium?.path?.isPCloudRecycleBinPath() == true
        val hasFile = !isInPCloudBin
        binding.bottomActions.bottomFavorite.beVisibleIf(visibleBottomActions and BOTTOM_ACTION_TOGGLE_FAVORITE != 0 && currentMedium?.getIsInRecycleBin() == false)
        binding.bottomActions.bottomFavorite.setOnLongClickListener { toast(R.string.toggle_favorite); true }
        binding.bottomActions.bottomFavorite.setOnClickListener {
            toggleFavorite()
        }

        binding.bottomActions.bottomEdit.beVisibleIf(hasFile && visibleBottomActions and BOTTOM_ACTION_EDIT != 0 && currentMedium?.isSVG() == false)
        binding.bottomActions.bottomEdit.setOnLongClickListener { toast(R.string.edit); true }
        binding.bottomActions.bottomEdit.setOnClickListener {
            editCurrentMedium()
        }

        binding.bottomActions.bottomShare.beVisibleIf(!isInPCloudBin && visibleBottomActions and BOTTOM_ACTION_SHARE != 0)
        binding.bottomActions.bottomShare.setOnLongClickListener { toast(org.fossify.commons.R.string.share); true }
        binding.bottomActions.bottomShare.setOnClickListener {
            shareMediumPath(getCurrentPath())
        }

        binding.bottomActions.bottomDelete.beVisibleIf(visibleBottomActions and BOTTOM_ACTION_DELETE != 0)
        binding.bottomActions.bottomDelete.setOnLongClickListener { toast(org.fossify.commons.R.string.delete); true }
        binding.bottomActions.bottomDelete.setOnClickListener {
            checkDeleteConfirmation()
        }

        binding.bottomActions.bottomRotate.beVisibleIf(hasFile && config.visibleBottomActions and BOTTOM_ACTION_ROTATE != 0 && getCurrentMedium()?.isImage() == true)
        binding.bottomActions.bottomRotate.setOnLongClickListener { toast(R.string.rotate); true }
        binding.bottomActions.bottomRotate.setOnClickListener {
            rotateImage(90)
        }

        binding.bottomActions.bottomProperties.applyColorFilter(Color.WHITE)
        binding.bottomActions.bottomProperties.beVisibleIf(hasFile && visibleBottomActions and BOTTOM_ACTION_PROPERTIES != 0)
        binding.bottomActions.bottomProperties.setOnLongClickListener { toast(org.fossify.commons.R.string.properties); true }
        binding.bottomActions.bottomProperties.setOnClickListener {
            showProperties()
        }

        binding.bottomActions.bottomChangeOrientation.beVisibleIf(visibleBottomActions and BOTTOM_ACTION_CHANGE_ORIENTATION != 0)
        binding.bottomActions.bottomChangeOrientation.setOnLongClickListener { toast(R.string.change_orientation); true }
        binding.bottomActions.bottomChangeOrientation.setOnClickListener {
            requestedOrientation = when (requestedOrientation) {
                SCREEN_ORIENTATION_PORTRAIT -> SCREEN_ORIENTATION_LANDSCAPE
                SCREEN_ORIENTATION_LANDSCAPE -> SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                SCREEN_ORIENTATION_REVERSE_LANDSCAPE -> SCREEN_ORIENTATION_UNSPECIFIED
                else -> SCREEN_ORIENTATION_PORTRAIT
            }
            mIsOrientationLocked = requestedOrientation != SCREEN_ORIENTATION_UNSPECIFIED
            updateBottomActionIcons(currentMedium)
        }

        binding.bottomActions.bottomSlideshow.beVisibleIf(visibleBottomActions and BOTTOM_ACTION_SLIDESHOW != 0)
        binding.bottomActions.bottomSlideshow.setOnLongClickListener { toast(R.string.slideshow); true }
        binding.bottomActions.bottomSlideshow.setOnClickListener {
            initSlideshow()
        }

        binding.bottomActions.bottomShowOnMap.beVisibleIf(hasFile && visibleBottomActions and BOTTOM_ACTION_SHOW_ON_MAP != 0)
        binding.bottomActions.bottomShowOnMap.setOnLongClickListener { toast(R.string.show_on_map); true }
        binding.bottomActions.bottomShowOnMap.setOnClickListener {
            showCurrentOnMap()
        }

        binding.bottomActions.bottomToggleFileVisibility.beVisibleIf(storage?.canHide != false && visibleBottomActions and BOTTOM_ACTION_TOGGLE_VISIBILITY != 0)
        binding.bottomActions.bottomToggleFileVisibility.setOnLongClickListener {
            toast(if (currentMedium?.isHidden() == true) org.fossify.commons.R.string.unhide else org.fossify.commons.R.string.hide); true
        }

        binding.bottomActions.bottomToggleFileVisibility.setOnClickListener {
            currentMedium?.apply {
                toggleFileVisibility(!isHidden()) {
                    updateBottomActionIcons(currentMedium)
                }
            }
        }

        binding.bottomActions.bottomRename.beVisibleIf(visibleBottomActions and BOTTOM_ACTION_RENAME != 0 && currentMedium?.getIsInRecycleBin() == false)
        binding.bottomActions.bottomRename.setOnLongClickListener { toast(org.fossify.commons.R.string.rename); true }
        binding.bottomActions.bottomRename.setOnClickListener {
            checkMediaManagementAndRename()
        }

        binding.bottomActions.bottomSetAs.beVisibleIf(hasFile && visibleBottomActions and BOTTOM_ACTION_SET_AS != 0)
        binding.bottomActions.bottomSetAs.setOnLongClickListener { toast(org.fossify.commons.R.string.set_as); true }
        binding.bottomActions.bottomSetAs.setOnClickListener {
            setCurrentAs()
        }

        binding.bottomActions.bottomCopy.beVisibleIf(!isInPCloudBin && visibleBottomActions and BOTTOM_ACTION_COPY != 0)
        binding.bottomActions.bottomCopy.setOnLongClickListener { toast(org.fossify.commons.R.string.copy); true }
        binding.bottomActions.bottomCopy.setOnClickListener {
            checkMediaManagementAndCopy(true)
        }

        binding.bottomActions.bottomMove.beVisibleIf(!isInPCloudBin && visibleBottomActions and BOTTOM_ACTION_MOVE != 0)
        binding.bottomActions.bottomMove.setOnLongClickListener { toast(org.fossify.commons.R.string.move); true }
        binding.bottomActions.bottomMove.setOnClickListener {
            moveFileTo()
        }

        binding.bottomActions.bottomResize.beVisibleIf(hasFile && visibleBottomActions and BOTTOM_ACTION_RESIZE != 0 && currentMedium?.isImage() == true)
        binding.bottomActions.bottomResize.setOnLongClickListener { toast(org.fossify.commons.R.string.resize); true }
        binding.bottomActions.bottomResize.setOnClickListener {
            resizeImage()
        }
    }

    private fun updateBottomActionIcons(medium: Medium?) {
        if (medium == null) {
            return
        }

        val favoriteIcon =
            if (medium.isFavorite) org.fossify.commons.R.drawable.ic_star_vector else org.fossify.commons.R.drawable.ic_star_outline_vector
        binding.bottomActions.bottomFavorite.setImageResource(favoriteIcon)

        val hideIcon =
            if (medium.isHidden()) org.fossify.commons.R.drawable.ic_unhide_vector else org.fossify.commons.R.drawable.ic_hide_vector
        binding.bottomActions.bottomToggleFileVisibility.setImageResource(hideIcon)

        val hasFile = !medium.path.isPCloudRecycleBinPath()
        binding.bottomActions.bottomRotate.beVisibleIf(hasFile && config.visibleBottomActions and BOTTOM_ACTION_ROTATE != 0 && getCurrentMedium()?.isImage() == true)
        binding.bottomActions.bottomChangeOrientation.setImageResource(getChangeOrientationIcon())
    }

    private fun toggleFavorite() {
        val medium = getCurrentMedium() ?: return
        medium.isFavorite = !medium.isFavorite
        ensureBackgroundThread {
            updateFavorite(medium.path, medium.isFavorite)
            if (medium.isFavorite) {
                mFavoritePaths.add(medium.path)
            } else {
                mFavoritePaths.remove(medium.path)
            }

            runOnUiThread {
                refreshMenuItems()
            }
        }
    }

    private fun printFile() {
        withLocalMediaFile(getCurrentPath()) { sendPrintIntent(it) }
    }

    private fun sendPrintIntent(path: String) {
        val printHelper = PrintHelper(this)
        printHelper.scaleMode = PrintHelper.SCALE_MODE_FIT
        printHelper.orientation = SCREEN_ORIENTATION_PORTRAIT

        try {
            val resolution = path.getImageResolution(this)
            if (resolution == null) {
                toast(org.fossify.commons.R.string.unknown_error_occurred)
                return
            }

            var requestedWidth = resolution.x
            var requestedHeight = resolution.y

            if (requestedWidth >= MAX_PRINT_SIDE_SIZE) {
                requestedHeight = (requestedHeight / (requestedWidth / MAX_PRINT_SIDE_SIZE.toFloat())).toInt()
                requestedWidth = MAX_PRINT_SIDE_SIZE
            } else if (requestedHeight >= MAX_PRINT_SIDE_SIZE) {
                requestedWidth = (requestedWidth / (requestedHeight / MAX_PRINT_SIDE_SIZE.toFloat())).toInt()
                requestedHeight = MAX_PRINT_SIDE_SIZE
            }

            val options = RequestOptions()
                .skipMemoryCache(true)
                .diskCacheStrategy(DiskCacheStrategy.NONE)

            Glide.with(this)
                .asBitmap()
                .load(path)
                .apply(options)
                .listener(object : RequestListener<Bitmap> {
                    override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Bitmap>, isFirstResource: Boolean): Boolean {
                        showErrorToast(e?.localizedMessage ?: "")
                        return false
                    }

                    override fun onResourceReady(
                        bitmap: Bitmap,
                        model: Any,
                        target: Target<Bitmap>,
                        dataSource: DataSource,
                        isFirstResource: Boolean,
                    ): Boolean {
                        printHelper.printBitmap(path.getFilenameFromPath(), bitmap)
                        return false
                    }
                }).submit(requestedWidth, requestedHeight)
        } catch (e: Exception) {
        }
    }

    private fun restoreFile() {
        val path = getCurrentPath()
        if (path.isPCloudRecycleBinPath()) {
            restorePCloudFile(path)
            return
        }

        restoreRecycleBinPath(path) {
            refreshViewPager()
        }
    }

    // the dialog names where the file goes back to, and can send it somewhere else
    private fun restorePCloudFile(path: String) {
        ensureBackgroundThread {
            val (folder, exists) = PCloudWriter(this).restoreDestinationOf(path)
            runOnUiThread {
                PCloudRestoreDialog(this, 1, folder, !exists) { destination ->
                    writeToPCloud(emptyList(), { restoreFromRecycleBin(listOf(path), destination) }) {
                        runOnUiThread { refreshViewPager(refetchPosition = true) }
                    }
                }
            }
        }
    }

    private fun resizeImage() {
        val oldPath = getCurrentPath()
        launchResizeImageDialog(oldPath)
    }

    private fun copyImageToClipboard() {
        val imagePath = getCurrentMedium()?.path ?: return
        withLocalMediaFile(imagePath) { localPath ->
            val clipboard = getSystemService(ClipboardManager::class.java) as ClipboardManager
            val clip = ClipData.newUri(contentResolver, "Image", getFinalUriFromPath(localPath, BuildConfig.APPLICATION_ID))
            clipboard.setPrimaryClip(clip)
        }
    }

    // The three that only need the file handed to another part of the system. A local medium
    // is already a file and goes through without a detour; a pCloud one is fetched first
    private fun setCurrentAs() {
        withLocalMediaFile(getCurrentPath()) { setAs(it) }
    }

    private fun openCurrentWith() {
        withLocalMediaFile(getCurrentPath()) { openPath(it, true) }
    }

    private fun showCurrentOnMap() {
        withLocalMediaFile(getCurrentPath()) { showFileOnMap(it) }
    }

    private fun checkDeleteConfirmation() {
        val currentMedium = getCurrentMedium() ?: return
        if (currentMedium.path.isPCloudPath()) {
            checkPCloudDeleteConfirmation(currentMedium)
            return
        }

        if (currentMedium.path.isSmbPath()) {
            checkSmbDeleteConfirmation(currentMedium)
            return
        }

        handleMediaManagementPrompt {
            if (config.isDeletePasswordProtectionOn) {
                handleDeletePasswordProtection {
                    deleteConfirmed(config.tempSkipRecycleBin)
                }
            } else if (config.tempSkipDeleteConfirmation || config.skipDeleteConfirmation) {
                deleteConfirmed(config.tempSkipRecycleBin)
            } else {
                askConfirmDelete()
            }
        }
    }

    private fun askConfirmDelete() {
        val fileDirItem = getCurrentMedium()?.toFileDirItem() ?: return
        val size = fileDirItem.getProperSize(this, countHidden = true).formatSize()
        val filename = "\"${getCurrentPath().getFilenameFromPath()}\""
        val filenameAndSize = "$filename ($size)"
        val isInRecycleBin = getCurrentMedium()!!.getIsInRecycleBin()

        val baseString = if (config.useRecycleBin && !config.tempSkipRecycleBin && !isInRecycleBin) {
            org.fossify.commons.R.string.move_to_recycle_bin_confirmation
        } else {
            org.fossify.commons.R.string.deletion_confirmation
        }

        val message = String.format(resources.getString(baseString), filenameAndSize)
        val showSkipRecycleBinOption = config.useRecycleBin && !isInRecycleBin

        DeleteWithRememberDialog(this, message, showSkipRecycleBinOption) { remember, skipRecycleBin ->
            config.tempSkipDeleteConfirmation = remember

            if (remember) {
                config.tempSkipRecycleBin = skipRecycleBin
            }

            deleteConfirmed(skipRecycleBin)
        }
    }

    private fun deleteConfirmed(skipRecycleBin: Boolean) {
        val currentMedium = getCurrentMedium()
        val path = currentMedium?.path ?: return
        if (getIsPathDirectory(path) || !path.isMediaFile()) {
            return
        }

        val fileDirItem = currentMedium.toFileDirItem()
        if (config.useRecycleBin && !skipRecycleBin && !getCurrentMedium()!!.getIsInRecycleBin()) {
            checkManageMediaOrHandleSAFDialogSdk30(fileDirItem.path) {
                if (!it) {
                    return@checkManageMediaOrHandleSAFDialogSdk30
                }

                mIgnoredPaths.add(fileDirItem.path)
                dropFromSelection(fileDirItem.path)
                val media = mMediaFiles.filter { !mIgnoredPaths.contains(it.path) } as ArrayList<Medium>
                if (media.isNotEmpty()) {
                    runOnUiThread {
                        refreshUI(media, false)
                    }
                }

                if (media.size == 1) {
                    onPageSelected(0)
                }

                movePathsInRecycleBin(arrayListOf(path)) {
                    if (it) {
                        tryDeleteFileDirItem(fileDirItem, false, false) {
                            mIgnoredPaths.remove(fileDirItem.path)
                            if (media.isEmpty()) {
                                deleteDirectoryIfEmpty()
                                finish()
                            }
                        }
                    } else {
                        toast(org.fossify.commons.R.string.unknown_error_occurred)
                    }
                }
            }
        } else {
            handleDeletion(fileDirItem)
        }
    }

    // A pCloud medium goes to the app's recycle bin on pCloud, with the same "skip the bin"
    // option a local file gets, or for good when the bin is off, skipped, or the medium is
    // in it already. The delete password and the "skip confirmation" setting apply like for
    // a local file. No media management prompt, there is no MediaStore entry to touch
    private fun checkPCloudDeleteConfirmation(medium: Medium) {
        val isInBin = medium.getIsInRecycleBin()
        val useBin = config.useRecycleBin && !isInBin
        when {
            config.isDeletePasswordProtectionOn -> handleDeletePasswordProtection { deletePCloudMedium(medium, config.tempSkipRecycleBin) }
            config.tempSkipDeleteConfirmation || config.skipDeleteConfirmation -> deletePCloudMedium(medium, config.tempSkipRecycleBin)
            else -> {
                val name = "\"${medium.name}\""
                val message = if (useBin && !config.tempSkipRecycleBin) {
                    getString(R.string.pcloud_move_to_recycle_bin_confirmation, name)
                } else {
                    getString(R.string.pcloud_delete_confirmation, name)
                }

                DeleteWithRememberDialog(this, message, useBin) { remember, skipRecycleBin ->
                    config.tempSkipDeleteConfirmation = remember
                    if (remember) {
                        config.tempSkipRecycleBin = skipRecycleBin
                    }

                    deletePCloudMedium(medium, skipRecycleBin)
                }
            }
        }
    }

    // the same as handleDeletion(): the page goes right away, the write follows, and the view
    // closes when it was the last one. A refused write brings the page back
    private fun deletePCloudMedium(medium: Medium, skipRecycleBin: Boolean) {
        val path = medium.path
        val isInBin = medium.getIsInRecycleBin()
        val toBin = config.useRecycleBin && !skipRecycleBin && !isInBin
        mIgnoredPaths.add(path)
        dropFromSelection(path)
        val media = mMediaFiles.filter { !mIgnoredPaths.contains(it.path) } as ArrayList<Medium>
        if (media.isNotEmpty()) {
            runOnUiThread {
                refreshUI(media, false)
            }
        }

        if (media.size == 1) {
            onPageSelected(0)
        }

        val foldersToRescan = if (isInBin) emptyList() else listOf(path.getParentPath())
        val write: PCloudWriter.() -> Unit = {
            when {
                toBin -> moveToRecycleBin(listOf(path))
                isInBin -> deleteFromRecycleBin(listOf(path))
                else -> deleteFiles(listOf(path))
            }
        }

        writeToPCloud(foldersToRescan, write) { success ->
            mIgnoredPaths.remove(path)
            runOnUiThread {
                if (!success) {
                    refreshViewPager(refetchPosition = true)
                } else if (media.isEmpty()) {
                    finish()
                }
            }
        }
    }

    // A medium of the share is deleted from the share and from nowhere else: there is no
    // recycle bin on it and none is made (#28), so there is no "skip the bin" option to show
    // and the message says the delete cannot be taken back. The delete password and the
    // "do not ask again" setting apply like they do for a local medium
    private fun checkSmbDeleteConfirmation(medium: Medium) {
        when {
            config.isDeletePasswordProtectionOn -> handleDeletePasswordProtection { deleteSmbMedium(medium) }
            config.tempSkipDeleteConfirmation || config.skipDeleteConfirmation -> deleteSmbMedium(medium)
            else -> {
                val message = getString(R.string.smb_delete_confirmation, "\"${medium.name}\"")
                DeleteWithRememberDialog(this, message, false) { remember, _ ->
                    config.tempSkipDeleteConfirmation = remember
                    deleteSmbMedium(medium)
                }
            }
        }
    }

    // the same as deletePCloudMedium(): the page goes right away, the write follows, and the
    // viewer closes when it was the last one. A refused delete brings the page back
    private fun deleteSmbMedium(medium: Medium) {
        val path = medium.path
        mIgnoredPaths.add(path)
        dropFromSelection(path)
        val media = mMediaFiles.filter { !mIgnoredPaths.contains(it.path) } as ArrayList<Medium>
        if (media.isNotEmpty()) {
            runOnUiThread {
                refreshUI(media, false)
            }
        }

        if (media.size == 1) {
            onPageSelected(0)
        }

        writeToShare({ deleteFiles(listOf(path)) }) { success ->
            mIgnoredPaths.remove(path)
            runOnUiThread {
                if (!success) {
                    refreshViewPager(refetchPosition = true)
                } else if (media.isEmpty()) {
                    finish()
                }
            }
        }
    }

    private fun handleDeletion(fileDirItem: FileDirItem) {
        checkManageMediaOrHandleSAFDialogSdk30(fileDirItem.path) {
            if (!it) {
                return@checkManageMediaOrHandleSAFDialogSdk30
            }

            mIgnoredPaths.add(fileDirItem.path)
            dropFromSelection(fileDirItem.path)
            val media = mMediaFiles.filter { !mIgnoredPaths.contains(it.path) } as ArrayList<Medium>
            if (media.isNotEmpty()) {
                runOnUiThread {
                    refreshUI(media, false)
                }
            }

            if (media.size == 1) {
                onPageSelected(0)
            }

            tryDeleteFileDirItem(fileDirItem, false, true) {
                mIgnoredPaths.remove(fileDirItem.path)
                if (media.isEmpty()) {
                    deleteDirectoryIfEmpty()
                    finish()
                }
            }
        }
    }

    private fun isDirEmpty(media: ArrayList<Medium>): Boolean {
        return if (media.isEmpty()) {
            deleteDirectoryIfEmpty()
            finish()
            true
        } else {
            false
        }
    }

    // the storage asks for the name and carries its rows along, see MediaStorage; what is on
    // screen is told the name once the storage has taken it, and stays as it was when the
    // storage refused. A medium of the share is fetched into a cached copy, which its writer
    // carries over to the new name, so the picture the viewer is holding is not fetched again
    private fun checkMediaManagementAndRename() {
        val oldPath = getCurrentPath()
        MediaStorage.of(this, oldPath).renameMedium(this, oldPath) { newPath ->
            if (newPath != null) {
                getCurrentMedia().firstOrNull { it.path == oldPath }?.apply {
                    path = newPath
                    name = newPath.getFilenameFromPath()
                }
                updateActionbarTitle()
            }
        }
    }

    private fun refreshViewPager(refetchPosition: Boolean = false) {
        // a queue is its own list in its own order. Listing the folder the first video happens
        // to sit in would throw the rest of the queue away
        val queuePaths = mQueuePaths
        if (queuePaths != null) {
            loadQueue(queuePaths, refetchPosition)
            return
        }

        val isRandomSorting = config.getFolderSorting(mDirectory) and SORT_BY_RANDOM != 0
        if (!isRandomSorting || isExternalIntent()) {
            GetMediaAsynctask(applicationContext, mDirectory, isPickImage = false, isPickVideo = false, showAll = mShowAll) {
                gotMedia(it, refetchViewPagerPosition = refetchPosition)
            }.execute()
        }
    }

    // The rows behind a queue's paths, kept in the queue's order rather than the database's. A
    // path whose row has gone is dropped, which is the same thing the folder listing would do
    private fun loadQueue(queuePaths: List<String>, refetchPosition: Boolean) {
        ensureBackgroundThread {
            val media = queuePaths.mapNotNull { mediaDB.getMediumByPath(it) }
            runOnUiThread {
                if (!isDestroyed && media.isNotEmpty()) {
                    gotMedia(ArrayList<ThumbnailItem>(media), ignorePlayingVideos = true, refetchViewPagerPosition = refetchPosition)
                }
            }
        }
    }

    private fun gotMedia(thumbnailItems: ArrayList<ThumbnailItem>, ignorePlayingVideos: Boolean = false, refetchViewPagerPosition: Boolean = false) {
        val media = thumbnailItems.asSequence().filter {
            it is Medium && !mIgnoredPaths.contains(it.path)
        }.map { it as Medium }.toMutableList() as ArrayList<Medium>

        if (isDirEmpty(media) || media.hashCode() == mPrevHashcode) {
            return
        }

        val isPlaying = (getCurrentFragment() as? VideoFragment)?.mIsPlaying == true
        if (!ignorePlayingVideos && isPlaying && !isExternalIntent()) {
            return
        }

        refreshUI(media, refetchViewPagerPosition)
    }

    private fun refreshUI(media: ArrayList<Medium>, refetchViewPagerPosition: Boolean) {
        mPrevHashcode = media.hashCode()
        mMediaFiles = media

        if (refetchViewPagerPosition || mPos == -1) {
            mPos = getPositionInList(media)
            if (mPos == -1) {
                min(mPos, media.lastIndex)
            }
        }

        updateActionbarTitle()
        updatePagerItems(mMediaFiles.toMutableList())

        refreshMenuItems()
        checkOrientation()
        initBottomActions()
    }

    private fun getPositionInList(items: MutableList<Medium>): Int {
        mPos = 0
        for ((i, medium) in items.withIndex()) {
            val portraitPath = getPortraitPath()
            if (portraitPath != "") {
                val portraitPaths = File(portraitPath).parentFile?.list()
                if (portraitPaths != null) {
                    for (path in portraitPaths) {
                        if (medium.name == path) {
                            return i
                        }
                    }
                }
            } else if (medium.path.equals(mPath, true)) {
                return i
            }
        }
        return mPos
    }

    private fun deleteDirectoryIfEmpty() {
        if (config.deleteEmptyFolders) {
            val fileDirItem = FileDirItem(mDirectory, mDirectory.getFilenameFromPath(), File(mDirectory).isDirectory)
            if (!fileDirItem.isDownloadsFolder() && fileDirItem.isDirectory) {
                ensureBackgroundThread {
                    if (fileDirItem.getProperFileCount(this, true) == 0) {
                        tryDeleteFileDirItem(fileDirItem, true, true)
                        scanPathRecursively(mDirectory)
                    }
                }
            }
        }
    }

    @SuppressLint("SourceLockedOrientationActivity")
    private fun checkOrientation() {
        // a pCloud medium has no file to read the aspect ratio from
        if (getCurrentPath().isPCloudPath()) {
            return
        }

        if (!mIsOrientationLocked && config.screenRotation == ROTATE_BY_ASPECT_RATIO) {
            var flipSides = false
            try {
                val pathToLoad = getCurrentPath()
                val exif = ExifInterface(pathToLoad)
                val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, -1)
                flipSides = orientation == ExifInterface.ORIENTATION_ROTATE_90 || orientation == ExifInterface.ORIENTATION_ROTATE_270
            } catch (e: Exception) {
            }
            val resolution = applicationContext.getResolution(getCurrentPath()) ?: return
            val width = if (flipSides) resolution.y else resolution.x
            val height = if (flipSides) resolution.x else resolution.y
            if (width > height) {
                requestedOrientation = SCREEN_ORIENTATION_LANDSCAPE
            } else if (width < height) {
                requestedOrientation = SCREEN_ORIENTATION_PORTRAIT
            }
        }
    }

    override fun fragmentClicked() {
        mIsFullScreen = !mIsFullScreen
        checkSystemUI()
        fullscreenToggled()
    }

    override fun videoEnded(): Boolean {
        if (mIsSlideshowActive) {
            swipeToNextMedium()
        }
        return mIsSlideshowActive
    }

    override fun isSlideShowActive() = mIsSlideshowActive

    override fun isFullScreen() = mIsFullScreen

    override fun goToPrevItem() {
        binding.viewPager.setCurrentItem(binding.viewPager.currentItem - 1, false)
        checkOrientation()
    }

    override fun goToNextItem() {
        binding.viewPager.setCurrentItem(binding.viewPager.currentItem + 1, false)
        checkOrientation()
    }

    override fun launchViewVideoIntent(path: String) {
        hideKeyboard()
        ensureBackgroundThread {
            val newUri = getFinalUriFromPath(path, BuildConfig.APPLICATION_ID) ?: return@ensureBackgroundThread
            val mimeType = getUriMimeType(path, newUri)
            Intent().apply {
                action = Intent.ACTION_VIEW
                setDataAndType(newUri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(IS_FROM_GALLERY, true)
                putExtra(REAL_FILE_PATH, path)
                putExtra(SHOW_PREV_ITEM, binding.viewPager.currentItem != 0)
                putExtra(SHOW_NEXT_ITEM, binding.viewPager.currentItem != mMediaFiles.lastIndex)

                try {
                    startActivityForResult(this, REQUEST_VIEW_VIDEO)
                } catch (e: ActivityNotFoundException) {
                    if (!tryGenericMimeType(this, mimeType, newUri)) {
                        toast(org.fossify.commons.R.string.no_app_found)
                    }
                } catch (e: Exception) {
                    showErrorToast(e)
                }
            }
        }
    }

    private fun checkSystemUI() {
        if (mIsFullScreen) {
            hideSystemUI()
        } else {
            stopSlideshow()
            showSystemUI()
        }
    }

    private fun fullscreenToggled() {
        binding.viewPager.adapter?.let {
            (it as MyPagerAdapter).toggleFullscreen(mIsFullScreen)
            val newAlpha = if (mIsFullScreen) 0f else 1f
            binding.topShadow.animate().alpha(newAlpha).start()
            binding.bottomActions.root.animate().alpha(newAlpha).withStartAction {
                binding.bottomActions.root.beVisible()
            }.withEndAction {
                binding.bottomActions.root.beVisibleIf(newAlpha == 1f)
            }.start()

            binding.mediumViewerAppbar.animate().alpha(newAlpha).withStartAction {
                binding.mediumViewerAppbar.beVisible()
            }.withEndAction {
                binding.mediumViewerAppbar.beVisibleIf(newAlpha == 1f)
            }.start()

            if (mSelectedPaths != null) {
                binding.mediumSelectionToggle.animate().alpha(newAlpha).withStartAction {
                    binding.mediumSelectionToggle.beVisible()
                }.withEndAction {
                    binding.mediumSelectionToggle.beVisibleIf(newAlpha == 1f)
                }.start()
            }
        }
    }

    private fun updateActionbarTitle() {
        runOnUiThread {
            val medium = getCurrentMedium()
            if (medium != null) {
                binding.mediumViewerToolbar.title = medium.path.getFilenameFromPath()
            }
        }
    }

    private fun getCurrentMedium(): Medium? {
        return if (getCurrentMedia().isEmpty() || mPos == -1) {
            null
        } else {
            getCurrentMedia()[min(mPos, getCurrentMedia().lastIndex)]
        }
    }

    private fun getCurrentMedia() = if (mAreSlideShowMediaVisible || mRandomSlideshowStopped) mSlideshowMedia else mMediaFiles

    private fun getCurrentPath() = getCurrentMedium()?.path ?: ""

    override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {}

    override fun onPageSelected(position: Int) {
        if (mPos != position) {
            mPos = position
            updateActionbarTitle()
            refreshMenuItems()
            scheduleSwipe()
        }
    }

    override fun onPageScrollStateChanged(state: Int) {
        if (state == ViewPager.SCROLL_STATE_IDLE && getCurrentMedium() != null) {
            checkOrientation()
        }
    }

    private fun isExternalIntent(): Boolean {
        return !intent.getBooleanExtra(IS_FROM_GALLERY, false)
    }
}
