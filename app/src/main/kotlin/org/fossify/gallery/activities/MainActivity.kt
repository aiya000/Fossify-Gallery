package org.fossify.gallery.activities

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.provider.MediaStore
import android.provider.MediaStore.Images
import android.provider.MediaStore.Video
import android.util.Log
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.view.Gravity
import android.view.ViewGroup
import android.widget.RelativeLayout
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.dialogs.CreateNewFolderDialog
import org.fossify.commons.dialogs.FilePickerDialog
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.appLaunched
import org.fossify.commons.extensions.appLockManager
import org.fossify.commons.extensions.areSystemAnimationsEnabled
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.checkWhatsNew
import org.fossify.commons.extensions.deleteFiles
import org.fossify.commons.extensions.getDoesFilePathExist
import org.fossify.commons.extensions.getFileCount
import org.fossify.commons.extensions.getFilePublicUri
import org.fossify.commons.extensions.getFilenameFromPath
import org.fossify.commons.extensions.getLatestMediaByDateId
import org.fossify.commons.extensions.getLatestMediaId
import org.fossify.commons.extensions.getMimeType
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperSize
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.getStorageDirectories
import org.fossify.commons.extensions.getTimeFormat
import org.fossify.commons.extensions.handleHiddenFolderPasswordProtection
import org.fossify.commons.extensions.handleLockedFolderOpening
import org.fossify.commons.extensions.hasAllPermissions
import org.fossify.commons.extensions.hasOTGConnected
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.extensions.hideKeyboard
import org.fossify.commons.extensions.internalStoragePath
import org.fossify.commons.extensions.isExternalStorageManager
import org.fossify.commons.extensions.isGif
import org.fossify.commons.extensions.isGone
import org.fossify.commons.extensions.isImageFast
import org.fossify.commons.extensions.isMediaFile
import org.fossify.commons.extensions.isPathOnOTG
import org.fossify.commons.extensions.isRawFast
import org.fossify.commons.extensions.isSvg
import org.fossify.commons.extensions.isVideoFast
import org.fossify.commons.extensions.launchMoreAppsFromUsIntent
import org.fossify.commons.extensions.recycleBinPath
import org.fossify.commons.extensions.sdCardPath
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toFileDirItem
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.underlineText
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.DAY_SECONDS
import org.fossify.commons.helpers.FAVORITES
import org.fossify.commons.helpers.PERMISSION_READ_STORAGE
import org.fossify.commons.helpers.SORT_BY_DATE_MODIFIED
import org.fossify.commons.helpers.SORT_BY_DATE_TAKEN
import org.fossify.commons.helpers.SORT_BY_SIZE
import org.fossify.commons.helpers.SORT_USE_NUMERIC_VALUE
import org.fossify.commons.helpers.VIEW_TYPE_GRID
import org.fossify.commons.helpers.VIEW_TYPE_LIST
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isRPlus
import org.fossify.commons.models.FileDirItem
import org.fossify.commons.models.RadioItem
import org.fossify.commons.models.Release
import org.fossify.commons.views.MyGridLayoutManager
import org.fossify.commons.views.MyRecyclerView
import org.fossify.gallery.BuildConfig
import org.fossify.gallery.R
import org.fossify.gallery.adapters.DirectoryAdapter
import org.fossify.gallery.databases.GalleryDatabase
import org.fossify.gallery.databinding.ActivityMainBinding
import org.fossify.gallery.dialogs.ChangeSortingDialog
import org.fossify.gallery.dialogs.ChangeViewTypeDialog
import org.fossify.gallery.dialogs.FilterMediaDialog
import org.fossify.gallery.dialogs.FolderGroupNameDialog
import org.fossify.gallery.dialogs.GrantAllFilesDialog
import org.fossify.gallery.dialogs.PCloudNameDialog
import org.fossify.gallery.extensions.addTempFolderIfNeeded
import org.fossify.gallery.extensions.config
import org.fossify.gallery.extensions.createDirectoryFromMedia
import org.fossify.gallery.extensions.directoryDB
import org.fossify.gallery.extensions.getCachedDirectories
import org.fossify.gallery.extensions.getCachedMedia
import org.fossify.gallery.extensions.getDirectorySortingValue
import org.fossify.gallery.extensions.getDirsToShow
import org.fossify.gallery.extensions.getDistinctPath
import org.fossify.gallery.extensions.getFavoritePaths
import org.fossify.gallery.extensions.getAllGroupDirectories
import org.fossify.gallery.extensions.getGroupedDirectories
import org.fossify.gallery.extensions.getNoMediaFoldersSync
import org.fossify.gallery.extensions.getOTGFolderChildrenNames
import org.fossify.gallery.extensions.getSortedDirectories
import org.fossify.gallery.extensions.handleExcludedFolderPasswordProtection
import org.fossify.gallery.extensions.handleMediaManagementPrompt
import org.fossify.gallery.extensions.isDownloadsFolder
import org.fossify.gallery.extensions.availableStorages
import org.fossify.gallery.extensions.isPCloudPath
import org.fossify.gallery.extensions.storageLabel
import org.fossify.gallery.extensions.isRemotePath
import org.fossify.gallery.extensions.isShownByStorageFilter
import org.fossify.gallery.extensions.effectiveStorageFilter
import org.fossify.gallery.extensions.getPCloudFoldersDueForRescan
import org.fossify.gallery.extensions.rescanPCloud
import org.fossify.gallery.extensions.rescanPCloudFolders
import org.fossify.gallery.extensions.rescanSmb
import org.fossify.gallery.extensions.writeToPCloud
import org.fossify.gallery.extensions.launchAbout
import org.fossify.gallery.extensions.launchCamera
import org.fossify.gallery.extensions.launchSettings
import org.fossify.gallery.extensions.mediaDB
import org.fossify.gallery.extensions.movePathsInRecycleBin
import org.fossify.gallery.extensions.movePinnedDirectoriesToFront
import org.fossify.gallery.extensions.openRecycleBin
import org.fossify.gallery.extensions.pruneFolderGroupMembers
import org.fossify.gallery.extensions.removeInvalidDBDirectories
import org.fossify.gallery.extensions.storeDirectoryItems
import org.fossify.gallery.extensions.tryDeleteFileDirItem
import org.fossify.gallery.extensions.updateDBDirectory
import org.fossify.gallery.extensions.updateWidgets
import org.fossify.gallery.helpers.DIRECTORY
import org.fossify.gallery.helpers.GET_ANY_INTENT
import org.fossify.gallery.helpers.GET_IMAGE_INTENT
import org.fossify.gallery.helpers.GET_VIDEO_INTENT
import org.fossify.gallery.helpers.GROUP_BY_DATE_TAKEN_DAILY
import org.fossify.gallery.helpers.GROUP_BY_DATE_TAKEN_MONTHLY
import org.fossify.gallery.helpers.GROUP_BY_LAST_MODIFIED_DAILY
import org.fossify.gallery.helpers.GROUP_BY_LAST_MODIFIED_MONTHLY
import org.fossify.gallery.helpers.GROUP_DESCENDING
import org.fossify.gallery.helpers.LOCATION_INTERNAL
import org.fossify.gallery.helpers.LOCATION_PCLOUD
import org.fossify.gallery.helpers.MAX_COLUMN_COUNT
import org.fossify.gallery.helpers.MONTH_MILLISECONDS
import org.fossify.gallery.helpers.MediaFetcher
import org.fossify.gallery.helpers.PCloudSyncPolicy
import org.fossify.gallery.helpers.PCloudWriter
import org.fossify.gallery.helpers.PICKED_PATHS
import org.fossify.gallery.helpers.RECYCLE_BIN
import org.fossify.gallery.helpers.RemoteScanScheduler
import org.fossify.gallery.helpers.SET_WALLPAPER_INTENT
import org.fossify.gallery.helpers.SHOW_ALL
import org.fossify.gallery.helpers.SHOW_TEMP_HIDDEN_DURATION
import org.fossify.gallery.helpers.SKIP_AUTHENTICATION
import org.fossify.gallery.helpers.PCLOUD_PATH_SCHEME
import org.fossify.gallery.helpers.PCLOUD_RECYCLE_BIN
import org.fossify.gallery.helpers.STORAGE_FILTER_ALL
import org.fossify.gallery.helpers.STORAGE_FILTER_LOCAL
import org.fossify.gallery.helpers.STORAGE_FILTER_PCLOUD
import org.fossify.gallery.helpers.STORAGE_FILTER_SMB
import org.fossify.gallery.helpers.SmbSyncPolicy
import org.fossify.gallery.helpers.SmbVideoCache
import org.fossify.gallery.helpers.TYPE_GIFS
import org.fossify.gallery.helpers.TYPE_IMAGES
import org.fossify.gallery.helpers.TYPE_RAWS
import org.fossify.gallery.helpers.TYPE_SVGS
import org.fossify.gallery.helpers.TYPE_VIDEOS
import org.fossify.gallery.helpers.getDefaultFileFilter
import org.fossify.gallery.helpers.getPermissionToRequest
import org.fossify.gallery.helpers.getPermissionsToRequest
import org.fossify.gallery.interfaces.DirectoryOperationsListener
import org.fossify.gallery.jobs.NewPhotoFetcher
import org.fossify.gallery.jobs.PCloudTransferService
import org.fossify.gallery.jobs.RemoteScanService
import org.fossify.gallery.jobs.SmbDurationService
import org.fossify.gallery.models.Directory
import org.fossify.gallery.models.Medium
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream

class MainActivity : SimpleActivity(), DirectoryOperationsListener {
    override var isSearchBarEnabled = true
    
    companion object {
        private const val PICK_MEDIA = 2
        private const val PICK_WALLPAPER = 3
        private const val LAST_MEDIA_CHECK_PERIOD = 3000L

        // what the recheck of the displayed folders reports a remote storage's losses under
        private const val TAG_REMOTE_FOLDERS = "RemoteFolders"

        // how often the recheck may build the folder list again while it runs. Long enough that
        // a thousand folders cost a handful of rebuilds rather than a thousand, short enough
        // that the list still visibly fills in
        private const val RECHECK_REDRAW_INTERVAL = 500L

        // a sideways drag of the folder list goes through past this share of the width
        private const val STORAGE_SWIPE_COMMIT_FRACTION = 5f
        private const val STORAGE_SWIPE_OUT_MILLIS = 150L
        private const val STORAGE_SWIPE_IN_MILLIS = 220L
    }

    private var mIsPickImageIntent = false
    private var mIsPickVideoIntent = false
    private var mIsGetImageContentIntent = false
    private var mIsGetVideoContentIntent = false
    private var mIsGetAnyContentIntent = false
    private var mIsSetWallpaperIntent = false
    private var mAllowPickingMultiple = false
    private var mIsThirdPartyIntent = false
    private var mIsGettingDirs = false
    private var mLoadedInitialPhotos = false
    private var mShouldStopFetching = false
    private var mWasDefaultFolderChecked = false
    private var mWasMediaManagementPromptShown = false
    private var mLatestMediaId = 0L
    private var mLatestMediaDateId = 0L

    // used at "Group direct subfolders" for navigation
    private var mCurrentPathPrefix = ""

    // used at "Group direct subfolders" for navigating Up with the back button
    private var mOpenedSubfolders = arrayListOf("")

    // the virtual folder group currently opened, null at the top level
    private var mCurrentGroupId: Long? = null

    // used for navigating Up through virtual folder groups with the back button
    private var mOpenedGroups = arrayListOf<Long?>(null)

    private var mDateFormat = ""
    private var mTimeFormat = ""
    private var mLastMediaHandler = Handler()
    private var mTempShowHiddenHandler = Handler()
    private var mZoomListener: MyRecyclerView.MyZoomListener? = null
    private var mLastMediaFetcher: MediaFetcher? = null
    private var mDirs = ArrayList<Directory>()

    // the full folder list the search filters, kept up to date by every setupAdapter() call
    // that hands the full list over, so a search sees a folder as the cache has it now
    private var mDirsIgnoringSearch = ArrayList<Directory>()

    private var mStoredAnimateGifs = true
    private var mStoredCropThumbnails = true
    private var mStoredScrollHorizontally = true
    private var mStoredTextColor = 0
    private var mStoredPrimaryColor = 0
    private var mStoredStyleString = ""
    private val binding by viewBinding(ActivityMainBinding::inflate)

    // set once per launch, a rotation must not count as one
    private var mShouldRescanPCloudOnLaunch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        appLaunched(BuildConfig.APPLICATION_ID)

        if (savedInstanceState == null) {
            config.temporarilyShowHidden = false
            config.temporarilyShowExcluded = false
            config.tempSkipDeleteConfirmation = false
            config.tempSkipRecycleBin = false
            removeTempFolder()
            checkRecycleBinItems()
            startNewPhotoFetcher()
            mShouldRescanPCloudOnLaunch = true
        }

        mIsPickImageIntent = isPickImageIntent(intent)
        mIsPickVideoIntent = isPickVideoIntent(intent)
        mIsGetImageContentIntent = isGetImageContentIntent(intent)
        mIsGetVideoContentIntent = isGetVideoContentIntent(intent)
        mIsGetAnyContentIntent = isGetAnyContentIntent(intent)
        mIsSetWallpaperIntent = isSetWallpaperIntent(intent)
        mAllowPickingMultiple = intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
        mIsThirdPartyIntent = mIsPickImageIntent
                || mIsPickVideoIntent
                || mIsGetImageContentIntent
                || mIsGetVideoContentIntent
                || mIsGetAnyContentIntent
                || mIsSetWallpaperIntent

        setupOptionsMenu()
        refreshMenuItems()

        setupEdgeToEdge(
            padBottomImeAndSystem = listOf(binding.directoriesGrid)
        )

        binding.directoriesRefreshLayout.setOnRefreshListener { refreshDirectories() }
        binding.directoriesGrid.addOnItemTouchListener(StorageSwipe())
        storeStateVariables()
        checkWhatsNewDialog()
        setupLatestMediaId()

        if (!config.wereFavoritesPinned) {
            config.addPinnedFolders(hashSetOf(FAVORITES))
            config.wereFavoritesPinned = true
        }

        if (!config.wasRecycleBinPinned) {
            config.addPinnedFolders(hashSetOf(RECYCLE_BIN))
            config.wasRecycleBinPinned = true
            config.saveFolderGrouping(SHOW_ALL, GROUP_BY_DATE_TAKEN_DAILY or GROUP_DESCENDING)
        }

        if (!config.wasSVGShowingHandled) {
            config.wasSVGShowingHandled = true
            if (config.filterMedia and TYPE_SVGS == 0) {
                config.filterMedia += TYPE_SVGS
            }
        }

        if (!config.wasSortingByNumericValueAdded) {
            config.wasSortingByNumericValueAdded = true
            config.sorting = config.sorting or SORT_USE_NUMERIC_VALUE
        }

        updateWidgets()
        registerFileUpdateListener()

        binding.directoriesSwitchSearching.setOnClickListener {
            launchSearchActivity()
        }

        // just request the permission, tryLoadGallery will then trigger in onResume
        handleMediaPermissions()
    }

    private fun handleMediaPermissions(callback: (() -> Unit)? = null) {
        requestMediaPermissions(enableRationale = true) {
            callback?.invoke()
            if (isRPlus() && !mWasMediaManagementPromptShown) {
                mWasMediaManagementPromptShown = true
                handleMediaManagementPrompt { }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        mTempShowHiddenHandler.removeCallbacksAndMessages(null)
    }

    // a copy or move to or from pCloud ends in the background; the folders are read again then
    private val pCloudTransferListener: () -> Unit = { getDirectories() }

    // and so does a scan of a remote storage, which runs in a foreground service and outlives
    // whatever screen asked for it
    private val remoteScanListener: () -> Unit = { getDirectories() }

    override fun onResume() {
        super.onResume()
        updateMenuColors()
        PCloudTransferService.addListener(pCloudTransferListener)
        RemoteScanService.addListener(remoteScanListener)
        config.isThirdPartyIntent = false
        mDateFormat = config.dateFormat
        mTimeFormat = getTimeFormat()

        refreshMenuItems()

        if (mStoredAnimateGifs != config.animateGifs) {
            getRecyclerAdapter()?.updateAnimateGifs(config.animateGifs)
        }

        if (mStoredCropThumbnails != config.cropThumbnails) {
            getRecyclerAdapter()?.updateCropThumbnails(config.cropThumbnails)
        }

        if (mStoredScrollHorizontally != config.scrollHorizontally) {
            mLoadedInitialPhotos = false
            binding.directoriesGrid.adapter = null
            getDirectories()
        }

        if (mStoredTextColor != getProperTextColor()) {
            getRecyclerAdapter()?.updateTextColor(getProperTextColor())
        }

        val primaryColor = getProperPrimaryColor()
        if (mStoredPrimaryColor != primaryColor) {
            getRecyclerAdapter()?.updatePrimaryColor()
        }

        val styleString =
            "${config.folderStyle}${config.showFolderMediaCount}${config.limitFolderTitle}"
        if (mStoredStyleString != styleString) {
            setupAdapter(mDirs, forceRecreate = true)
        }

        binding.directoriesFastscroller.updateColors(primaryColor)
        binding.directoriesRefreshLayout.isEnabled = config.enablePullToRefresh
        getRecyclerAdapter()?.apply {
            dateFormat = config.dateFormat
            timeFormat = getTimeFormat()
        }

        binding.directoriesEmptyPlaceholder.setTextColor(getProperTextColor())
        binding.directoriesEmptyPlaceholder2.setTextColor(primaryColor)
        binding.directoriesSwitchSearching.setTextColor(primaryColor)
        binding.directoriesSwitchSearching.underlineText()
        binding.directoriesEmptyPlaceholder2.bringToFront()

        if (!binding.mainMenu.isSearchOpen) {
            refreshMenuItems()
            tryLoadGallery()
        } else {
            // the folders may have changed while this screen was away (a transfer ended, a
            // folder was written to); the search keeps its query and filters the fresh list
            getDirectories()
        }

        if (config.searchAllFilesByDefault) {
            binding.mainMenu.updateHintText(getString(org.fossify.commons.R.string.search_files))
        } else {
            binding.mainMenu.updateHintText(getString(R.string.search_folders_and_groups))
        }
    }

    override fun onPause() {
        super.onPause()
        PCloudTransferService.removeListener(pCloudTransferListener)
        RemoteScanService.removeListener(remoteScanListener)
        binding.directoriesRefreshLayout.isRefreshing = false
        mIsGettingDirs = false
        storeStateVariables()
        mLastMediaHandler.removeCallbacksAndMessages(null)
    }

    override fun onStop() {
        super.onStop()

        if (config.temporarilyShowHidden || config.tempSkipDeleteConfirmation || config.temporarilyShowExcluded) {
            mTempShowHiddenHandler.postDelayed({
                config.temporarilyShowHidden = false
                config.temporarilyShowExcluded = false
                config.tempSkipDeleteConfirmation = false
                config.tempSkipRecycleBin = false
            }, SHOW_TEMP_HIDDEN_DURATION)
        } else {
            mTempShowHiddenHandler.removeCallbacksAndMessages(null)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isChangingConfigurations) {
            config.temporarilyShowHidden = false
            config.temporarilyShowExcluded = false
            config.tempSkipDeleteConfirmation = false
            config.tempSkipRecycleBin = false
            mTempShowHiddenHandler.removeCallbacksAndMessages(null)
            removeTempFolder()
            unregisterFileUpdateListener()

            if (!config.showAll) {
                mLastMediaFetcher?.shouldStop = true
                GalleryDatabase.destroyInstance()
            }
        }
    }

    override fun onBackPressedCompat(): Boolean {
        return if (binding.mainMenu.isSearchOpen) {
            binding.mainMenu.closeSearch()
            true
        } else if (config.groupDirectSubfolders && mCurrentPathPrefix.isNotEmpty()) {
            mOpenedSubfolders.removeAt(mOpenedSubfolders.lastIndex)
            mCurrentPathPrefix = mOpenedSubfolders.last()
            setupAdapter(mDirs)
            true
        } else if (mCurrentGroupId != null) {
            mOpenedGroups.removeAt(mOpenedGroups.lastIndex)
            mCurrentGroupId = mOpenedGroups.last()
            setupAdapter(mDirs)
            true
        } else {
            appLockManager.lock()
            false
        }
    }

    private fun openGroup(groupId: Long?) {
        // a group opened from the search results leaves the search behind it: what is listed below
        // is the group's own contents, which is not what the query asked for
        if (binding.mainMenu.isSearchOpen) {
            binding.mainMenu.closeSearch()
        }

        mCurrentGroupId = groupId
        mOpenedGroups.add(groupId)

        // subfolder navigation is relative to the opened group, start it over
        mCurrentPathPrefix = ""
        mOpenedSubfolders = arrayListOf("")
        setupAdapter(mDirs, "")
        rescanPCloudFoldersOfGroupIfDue(groupId)
    }

    // The pCloud folders assigned to the group, or to a group inside it, are refreshed one by
    // one when the setting asks for it, each under its own throttle. The cached folders are on
    // screen before this runs
    private fun rescanPCloudFoldersOfGroupIfDue(groupId: Long?) {
        if (groupId == null || !isPCloudShown() || !PCloudSyncPolicy(this).rescanOnGroupOpen) {
            return
        }

        ensureBackgroundThread {
            val groups = config.parseFolderGroups()
            val members = config.parseFolderGroupMembers()
                .filter { (path, memberGroupId) -> path.isPCloudPath() && config.isFolderGroupDescendantOrSelf(memberGroupId, groupId, groups) }
                .keys
                .toList()

            val due = getPCloudFoldersDueForRescan(members)
            if (due.isNotEmpty()) {
                rescanPCloudFolders(due, reportCounts = false, priority = RemoteScanScheduler.PRIORITY_AUTO) { runOnUiThread { getDirectories() } }
            }
        }
    }

    private fun createNewGroup() {
        FolderGroupNameDialog(this) { name ->
            config.addFolderGroup(name, mCurrentGroupId)
            setupAdapter(mDirs)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        if (resultCode == RESULT_OK) {
            if (requestCode == PICK_MEDIA && resultData != null) {
                val resultIntent = Intent()
                var resultUri: Uri? = null
                if (mIsThirdPartyIntent) {
                    when {
                        intent.extras?.containsKey(MediaStore.EXTRA_OUTPUT) == true
                                && intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0 -> {
                            resultUri = fillExtraOutput(resultData)
                        }

                        resultData.extras?.containsKey(PICKED_PATHS) == true -> {
                            fillPickedPaths(resultData, resultIntent)
                        }

                        else -> fillIntentPath(resultData, resultIntent)
                    }
                }

                if (resultUri != null) {
                    resultIntent.data = resultUri
                    resultIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                setResult(RESULT_OK, resultIntent)
                finish()
            } else if (requestCode == PICK_WALLPAPER) {
                setResult(RESULT_OK)
                finish()
            }
        }
        super.onActivityResult(requestCode, resultCode, resultData)
    }

    private fun refreshMenuItems() {
        if (!mIsThirdPartyIntent) {
            binding.mainMenu.requireToolbar().menu.apply {
                findItem(R.id.column_count).isVisible = config.viewTypeFolders == VIEW_TYPE_GRID
                findItem(R.id.set_as_default_folder).isVisible = !config.defaultFolder.isEmpty()
                findItem(R.id.open_recycle_bin).isVisible =
                    config.useRecycleBin && !config.showRecycleBinAtFolders
                findItem(R.id.more_apps_from_us).isVisible =
                    !resources.getBoolean(org.fossify.commons.R.bool.hide_google_relations)
                // the item doubles as the sign of which storage is on screen
                findItem(R.id.storage_filter).apply {
                    isVisible = availableStorages().size > 1
                    setIcon(storageIconRes(effectiveStorageFilter()))
                }
                findItem(R.id.rescan_pcloud).isVisible = config.isPCloudLoggedIn
                findItem(R.id.rescan_smb).isVisible = config.isSmbConfigured
                // shown for every group, not only for one that holds a folder of the share: a
                // group does not say on its face what is inside it, and a menu item that comes
                // and goes for a reason nobody can see is worse than one that reports nothing
                // to do
                findItem(R.id.read_smb_durations_group).isVisible = config.isSmbConfigured && mCurrentGroupId != null
            }

            // a freshly set icon has no tint yet
            updateMenuColors()
        }

        binding.mainMenu.requireToolbar().menu.apply {
            findItem(R.id.temporarily_show_hidden).isVisible = !config.shouldShowHidden
            findItem(R.id.stop_showing_hidden).isVisible =
                (!isRPlus() || isExternalStorageManager()) && config.temporarilyShowHidden

            findItem(R.id.temporarily_show_excluded).isVisible = !config.temporarilyShowExcluded
            findItem(R.id.stop_showing_excluded).isVisible = config.temporarilyShowExcluded
        }
    }

    private fun setupOptionsMenu() {
        val menuId = if (mIsThirdPartyIntent) {
            R.menu.menu_main_intent
        } else {
            R.menu.menu_main
        }

        binding.mainMenu.requireToolbar().inflateMenu(menuId)
        binding.mainMenu.toggleHideOnScroll(!config.scrollHorizontally)
        binding.mainMenu.setupMenu()

        binding.mainMenu.onSearchOpenListener = {
            if (config.searchAllFilesByDefault) {
                launchSearchActivity()
            }
        }

        binding.mainMenu.onSearchTextChangedListener = { text ->
            setupAdapter(mDirsIgnoringSearch, text)
            binding.directoriesRefreshLayout.isEnabled =
                text.isEmpty() && config.enablePullToRefresh
            binding.directoriesSwitchSearching.beVisibleIf(text.isNotEmpty())
        }

        binding.mainMenu.requireToolbar().setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.sort -> showSortingDialog()
                R.id.filter -> showFilterMediaDialog()
                R.id.storage_filter -> showStorageFilterDialog()
                R.id.rescan_pcloud -> rescanPCloudManually()
                R.id.rescan_smb -> rescanSmbManually()
                R.id.read_smb_durations_group -> readSmbVideoDurationsOfGroup()
                R.id.open_camera -> launchCamera()
                R.id.show_all -> showAllMedia()
                R.id.change_view_type -> changeViewType()
                R.id.temporarily_show_hidden -> tryToggleTemporarilyShowHidden()
                R.id.stop_showing_hidden -> tryToggleTemporarilyShowHidden()
                R.id.temporarily_show_excluded -> tryToggleTemporarilyShowExcluded()
                R.id.stop_showing_excluded -> tryToggleTemporarilyShowExcluded()
                R.id.create_new_folder -> createNewFolder()
                R.id.create_new_group -> createNewGroup()
                R.id.open_recycle_bin -> openRecycleBin()
                R.id.column_count -> changeColumnCount()
                R.id.set_as_default_folder -> setAsDefaultFolder()
                R.id.more_apps_from_us -> launchMoreAppsFromUsIntent()
                R.id.settings -> launchSettings()
                R.id.about -> launchAbout()
                else -> return@setOnMenuItemClickListener false
            }
            return@setOnMenuItemClickListener true
        }
    }

    private fun updateMenuColors() {
        binding.mainMenu.updateColors()
    }

    private fun getRecyclerAdapter() = binding.directoriesGrid.adapter as? DirectoryAdapter

    private fun storeStateVariables() {
        mStoredTextColor = getProperTextColor()
        mStoredPrimaryColor = getProperPrimaryColor()
        config.apply {
            mStoredAnimateGifs = animateGifs
            mStoredCropThumbnails = cropThumbnails
            mStoredScrollHorizontally = scrollHorizontally
            mStoredStyleString = "$folderStyle$showFolderMediaCount$limitFolderTitle"
        }
    }

    private fun startNewPhotoFetcher() {
        val photoFetcher = NewPhotoFetcher()
        if (!photoFetcher.isScheduled(applicationContext)) {
            photoFetcher.scheduleJob(applicationContext)
        }
    }

    // a temporary pCloud folder only comes off the list, it is never deleted: the app does
    // not delete a folder on pCloud on its own, and the one the user just made stays where
    // it was put, empty or not
    private fun removeTempFolder() {
        if (config.tempFolderPath.isPCloudPath()) {
            config.tempFolderPath = ""
            return
        }

        if (config.tempFolderPath.isNotEmpty()) {
            val newFolder = File(config.tempFolderPath)
            if (getDoesFilePathExist(newFolder.absolutePath) && newFolder.isDirectory) {
                if (
                    newFolder.getProperSize(true) == 0L
                    && newFolder.getFileCount(true) == 0
                    && newFolder.list()?.isEmpty() == true
                ) {
                    toast(
                        String.format(
                            getString(org.fossify.commons.R.string.deleting_folder),
                            config.tempFolderPath
                        ), Toast.LENGTH_LONG
                    )
                    tryDeleteFileDirItem(newFolder.toFileDirItem(applicationContext), true, true)
                }
            }
            config.tempFolderPath = ""
        }
    }

    private fun checkOTGPath() {
        ensureBackgroundThread {
            if (!config.wasOTGHandled && hasPermission(getPermissionToRequest()) && hasOTGConnected() && config.OTGPath.isEmpty()) {
                getStorageDirectories().firstOrNull {
                    it.trimEnd('/') != internalStoragePath
                            && it.trimEnd('/') != sdCardPath
                }?.apply {
                    config.wasOTGHandled = true
                    val otgPath = trimEnd('/')
                    config.OTGPath = otgPath
                    config.addIncludedFolder(otgPath)
                }
            }
        }
    }

    private fun checkDefaultSpamFolders() {
        if (!config.spamFoldersChecked) {
            val spamFolders = arrayListOf(
                "/storage/emulated/0/Android/data/com.facebook.orca/files/stickers"
            )

            val OTGPath = config.OTGPath
            spamFolders.forEach {
                if (getDoesFilePathExist(it, OTGPath)) {
                    config.addExcludedFolder(it)
                }
            }
            config.spamFoldersChecked = true
        }
    }

    private fun tryLoadGallery() {
        // avoid calling anything right after granting the permission, it will be called from onResume()
        val wasMissingPermission =
            config.appRunCount == 1 && !hasAllPermissions(getPermissionsToRequest())
        handleMediaPermissions {
            if (wasMissingPermission) {
                return@handleMediaPermissions
            }

            if (!mWasDefaultFolderChecked) {
                openDefaultFolder()
                mWasDefaultFolderChecked = true
            }

            checkOTGPath()
            checkDefaultSpamFolders()

            if (config.showAll) {
                showAllMedia()
            } else {
                getDirectories()
                rescanPCloudOnLaunchIfDue()
            }

            setupLayoutManager()
        }
    }

    private fun getDirectories() {
        if (mIsGettingDirs) {
            return
        }

        mShouldStopFetching = true
        mIsGettingDirs = true
        val getImages = mIsPickImageIntent || mIsGetImageContentIntent
        val getVideos = mIsPickVideoIntent || mIsGetVideoContentIntent

        getCachedDirectories(getVideos && !getImages, getImages && !getVideos) {
            gotDirectories(addTempFolderIfNeeded(it))
        }
    }

    private fun launchSearchActivity() {
        hideKeyboard()
        Intent(this, SearchActivity::class.java).apply {
            startActivity(this)
        }

        binding.mainMenu.postDelayed({
            binding.mainMenu.closeSearch()
        }, 500)
    }

    private fun showSortingDialog() {
        ChangeSortingDialog(this, true, false) {
            binding.directoriesGrid.adapter = null
            if (config.directorySorting and SORT_BY_DATE_MODIFIED != 0 || config.directorySorting and SORT_BY_DATE_TAKEN != 0) {
                getDirectories()
            } else {
                ensureBackgroundThread {
                    gotDirectories(getCurrentlyDisplayedDirs())
                }
            }

            getRecyclerAdapter()?.directorySorting = config.directorySorting
        }
    }

    private fun showFilterMediaDialog() {
        FilterMediaDialog(this) {
            reloadDirectories()
        }
    }

    private fun showStorageFilterDialog() {
        val items = availableStorages().map { RadioItem(it, storageLabel(it)) } as ArrayList<RadioItem>
        RadioGroupDialog(this, items, effectiveStorageFilter()) {
            switchStorage(it as Int)
        }
    }

    // The storages the folder list can be switched between, in the order the menu lists them and
    // a sideways swipe walks them. A storage that is not set up is not one of them, and with
    // only the device there is nothing to switch to, so "all storages" is left out as well
    private fun switchStorage(newFilter: Int) {
        if (newFilter == config.storageFilter) {
            return
        }

        config.storageFilter = newFilter
        refreshMenuItems()

        // A scan of a storage the list has just left is of no use to it, and the reload it would
        // end in only sends the folders on screen through their recheck once more. The scheduler
        // decides whether it actually goes: a switch outranks the settings' automatic scan, and
        // does not outrank a walk the user asked for by hand. A sideways drag is the easiest
        // gesture in the app to make by accident, and a share takes minutes to walk, so the
        // deliberate one is the one that survives (#59)
        if (!isPCloudShown()) {
            RemoteScanScheduler.leftBehind(RemoteScanScheduler.Storage.PCLOUD)
        }

        if (!isSmbShown()) {
            RemoteScanScheduler.leftBehind(RemoteScanScheduler.Storage.SMB)
        }

        // the cache is on screen right away; a rescan, when the settings ask for one,
        // refreshes the list a second time once it is through. It is queued at the switch's own
        // rank: this list is waiting on it, which the settings' scans are not
        reloadDirectories()
        val pCloudPolicy = PCloudSyncPolicy(this)
        if (pCloudPolicy.rescanOnStorageSwitch && isPCloudShown() && pCloudPolicy.isFullScanDue()) {
            rescanPCloud(reportCounts = false, priority = RemoteScanScheduler.PRIORITY_SWITCH) { runOnUiThread { getDirectories() } }
        }

        val smbPolicy = SmbSyncPolicy(this)
        if (smbPolicy.rescanOnStorageSwitch && isSmbShown() && smbPolicy.isFullScanDue()) {
            rescanSmb(reportCounts = false, priority = RemoteScanScheduler.PRIORITY_SWITCH)
        }
    }

    private fun storageIconRes(storageFilter: Int) = when (storageFilter) {
        STORAGE_FILTER_PCLOUD -> R.drawable.ic_cloud_vector
        STORAGE_FILTER_SMB -> R.drawable.ic_storage_vector
        STORAGE_FILTER_ALL -> R.drawable.ic_devices_vector
        else -> R.drawable.ic_smartphone_vector
    }

    // Turns the folder list like a page. Dragged sideways it follows the finger and uncovers
    // a strip naming the storage it is heading for: to the left pCloud, to the right this
    // device, the order of the storage menu. Let go past a fifth of the width, or with a
    // fling that way, it goes there; short of that it slides back. Every other touch stays
    // with the list: this takes over only once a drag is clearly sideways and the list is
    // not scrolling, and never while the list scrolls horizontally (a sideways drag is the
    // scroll then), while folders are selected (a drag reorder or a drag selection ends
    // with a sideways move too), without a pCloud account, or on the side where nothing lies
    // in that direction
    private inner class StorageSwipe : RecyclerView.OnItemTouchListener {
        private val touchSlop = ViewConfiguration.get(this@MainActivity).scaledTouchSlop
        private val minFlingVelocity = ViewConfiguration.get(this@MainActivity).scaledMinimumFlingVelocity * 4
        private var startX = 0f
        private var startY = 0f
        private var dragging = false
        private var animating = false
        private var target = 0

        // which way the drag that picked the target went: -1 to the left, 1 to the right. With
        // more than two storages the direction no longer follows from which one was picked
        private var direction = 0
        private var velocityTracker: VelocityTracker? = null

        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = e.x
                    startY = e.y
                    dragging = false
                    velocityTracker?.recycle()
                    velocityTracker = VelocityTracker.obtain().apply { addMovement(e) }
                }

                MotionEvent.ACTION_MOVE -> {
                    velocityTracker?.addMovement(e)
                    if (dragging || rv.scrollState != RecyclerView.SCROLL_STATE_IDLE || !canSwipeStorage()) {
                        return false
                    }

                    val dx = e.x - startX
                    val dy = e.y - startY
                    if (abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.5f) {
                        target = targetFor(dx)
                        if (target != 0) {
                            dragging = true
                            // the list starts moving from here, not a slop behind the finger
                            startX = e.x
                            rv.parent.requestDisallowInterceptTouchEvent(true)
                            showHint(target, uncoveredOnTheRight = direction < 0)
                            return true
                        }
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> recycleTracker()
            }

            return false
        }

        override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
            velocityTracker?.addMovement(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> follow(e.x - startX)

                MotionEvent.ACTION_UP -> {
                    val dx = e.x - startX
                    velocityTracker?.computeCurrentVelocity(1000)
                    val velocityX = velocityTracker?.xVelocity ?: 0f
                    recycleTracker()
                    dragging = false

                    val towardsTarget = if (direction < 0) dx < 0 else dx > 0
                    val farEnough = abs(dx) >= rv.width / STORAGE_SWIPE_COMMIT_FRACTION
                    val flungThatWay = abs(velocityX) >= minFlingVelocity && (if (direction < 0) velocityX < 0 else velocityX > 0)
                    if (towardsTarget && (farEnough || flungThatWay)) {
                        commit(target)
                    } else {
                        springBack()
                    }
                }

                MotionEvent.ACTION_CANCEL -> {
                    recycleTracker()
                    dragging = false
                    springBack()
                }
            }
        }

        override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}

        // the refresh spinner is no reason to wait: it stands for the recheck of the folders on
        // screen, which a switch stops anyway (reloadDirectories() raises mShouldStopFetching),
        // or for a pCloud scan, which runs on in the background and only fills the cache
        private fun canSwipeStorage(): Boolean {
            return availableStorages().size > 1
                && !animating
                && !config.scrollHorizontally
                && getRecyclerAdapter()?.isSelecting() != true
        }

        // The storage a drag in this direction leads to, 0 when there is none that way: the
        // storages are walked in the order the menu lists them, dragging left towards the next
        // one and right towards the one before. The ends do not wrap, so a drag off either end
        // leaves the list where it is
        private fun targetFor(dx: Float): Int {
            val storages = availableStorages()
            val current = storages.indexOf(effectiveStorageFilter())
            if (current < 0) {
                return 0
            }

            direction = if (dx < 0) -1 else 1
            val next = if (direction < 0) current + 1 else current - 1
            return storages.getOrElse(next) { 0 }
        }

        private fun recycleTracker() {
            velocityTracker?.recycle()
            velocityTracker = null
        }

        private fun showHint(storage: Int, uncoveredOnTheRight: Boolean) {
            val textColor = getProperTextColor()
            binding.directoriesStorageHintIcon.setImageResource(storageIconRes(storage))
            binding.directoriesStorageHintIcon.applyColorFilter(textColor)
            binding.directoriesStorageHintLabel.text = storageLabel(storage)
            binding.directoriesStorageHintLabel.setTextColor(textColor)
            (binding.directoriesStorageHintContent.layoutParams as FrameLayout.LayoutParams).gravity =
                (if (uncoveredOnTheRight) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL
            binding.directoriesStorageHintContent.requestLayout()
            binding.directoriesStorageHint.alpha = 0f
            binding.directoriesStorageHint.beVisible()
        }

        // the list follows the finger towards the target only; the hint fades in with the distance
        private fun follow(dx: Float) {
            val clamped = if (direction < 0) minOf(dx, 0f) else maxOf(dx, 0f)
            binding.directoriesRefreshLayout.translationX = clamped
            val commitDistance = binding.directoriesRefreshLayout.width / STORAGE_SWIPE_COMMIT_FRACTION
            binding.directoriesStorageHint.alpha = minOf(1f, abs(clamped) / commitDistance)
        }

        // the list leaves on the side it was dragged to, the other storage's list comes in from the opposite one
        private fun commit(storage: Int) {
            val list = binding.directoriesRefreshLayout
            val width = list.width.toFloat()
            val outX = if (direction < 0) -width else width
            animating = true
            list.animate()
                .translationX(outX)
                .setDuration(STORAGE_SWIPE_OUT_MILLIS)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    binding.directoriesStorageHint.beGone()
                    switchStorage(storage)
                    list.translationX = -outX
                    list.animate()
                        .translationX(0f)
                        .setDuration(STORAGE_SWIPE_IN_MILLIS)
                        .setInterpolator(DecelerateInterpolator())
                        .withEndAction { animating = false }
                        .start()
                }
                .start()
        }

        private fun springBack() {
            animating = true
            binding.directoriesStorageHint.animate().alpha(0f).setDuration(STORAGE_SWIPE_OUT_MILLIS).withEndAction { binding.directoriesStorageHint.beGone() }.start()
            binding.directoriesRefreshLayout.animate()
                .translationX(0f)
                .setDuration(STORAGE_SWIPE_OUT_MILLIS)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { animating = false }
                .start()
        }
    }

    private fun reloadDirectories() {
        mShouldStopFetching = true
        binding.directoriesRefreshLayout.isRefreshing = true
        binding.directoriesGrid.adapter = null
        getDirectories()
    }

    private fun isPCloudShown() = config.isPCloudLoggedIn && effectiveStorageFilter().let {
        it == STORAGE_FILTER_PCLOUD || it == STORAGE_FILTER_ALL
    }

    private fun isSmbShown() = config.isSmbConfigured && effectiveStorageFilter().let {
        it == STORAGE_FILTER_SMB || it == STORAGE_FILTER_ALL
    }

    // A pull refreshes the remote storages on screen too; the local folders are rechecked by
    // getDirectories() either way, and that is also what stops the spinner. The share is walked
    // by a foreground service and can take minutes, far longer than a pull should hold the
    // spinner, so it is started alongside rather than waited on: its listener builds the list
    // again when it is through
    private fun refreshDirectories() {
        // a pull walks the share only when the settings ask for it, and they do not by default:
        // the gesture is an easy one to make while scrolling, and a share of any size is minutes
        // of work to start by accident
        if (isSmbShown() && SmbSyncPolicy(this).rescanOnPullToRefresh) {
            rescanSmb(reportCounts = false, priority = RemoteScanScheduler.PRIORITY_MANUAL)
        }

        if (isPCloudShown()) {
            rescanPCloud(reportCounts = false, priority = RemoteScanScheduler.PRIORITY_MANUAL) { runOnUiThread { getDirectories() } }
        } else {
            getDirectories()
        }
    }

    // the menu item walks the whole share again, the way the pCloud one lists the whole account.
    // The spinner is not started for it: the walk outlives this screen, and its progress is in
    // the notification the service puts up
    private fun rescanSmbManually() {
        toast(R.string.smb_rescanning)
        rescanSmb(reportCounts = true, priority = RemoteScanScheduler.PRIORITY_MANUAL)
    }

    // The folders inside the group being looked at, including the ones in its subgroups. Read
    // out of the membership alone, by path, so it holds for a folder that the storage filter is
    // hiding as much as for one on screen. Which of them are on the share is the query's
    // business, not this one's
    private fun foldersOfCurrentGroup(): List<String> {
        val groupId = mCurrentGroupId ?: return emptyList()
        val groups = config.parseFolderGroups()
        return config.parseFolderGroupMembers()
            .filterValues { config.isFolderGroupDescendantOrSelf(it, groupId, groups) }
            .keys
            .toList()
    }

    // The same as the folder's own version in MediaActivity, over every folder of the share in
    // this group. A group gathers what belongs together, which is the unit worth waiting for
    private fun readSmbVideoDurationsOfGroup() {
        val folders = foldersOfCurrentGroup()
        ensureBackgroundThread {
            val paths = folders.flatMap { mediaDB.getVideoPathsWithoutDuration(it) }
            runOnUiThread {
                if (paths.isEmpty()) {
                    // nothing to ask about, so it is reported as the run that it was
                    toast(getString(R.string.smb_read_durations_done, 0))
                    return@runOnUiThread
                }

                ConfirmationDialog(this, getString(R.string.smb_read_durations_confirmation_group, paths.size)) {
                    SmbDurationService.start(this, paths)
                }
            }
        }
    }

    // the menu item lists the whole account again: it is the way out when the diff sync
    // behind every other rescan has gone astray, and its counts toast means the whole account
    private fun rescanPCloudManually() {
        toast(R.string.pcloud_rescanning)
        binding.directoriesRefreshLayout.isRefreshing = true
        rescanPCloud(reportCounts = true, priority = RemoteScanScheduler.PRIORITY_MANUAL, full = true) { runOnUiThread { getDirectories() } }
    }

    // the cached folders are on screen before this runs, so the scan never keeps the user waiting
    private fun rescanPCloudOnLaunchIfDue() {
        if (!mShouldRescanPCloudOnLaunch) {
            return
        }

        mShouldRescanPCloudOnLaunch = false
        val policy = PCloudSyncPolicy(this)
        if (policy.rescanOnLaunch && isPCloudShown() && policy.isFullScanDue()) {
            rescanPCloud(reportCounts = false, priority = RemoteScanScheduler.PRIORITY_AUTO) { runOnUiThread { getDirectories() } }
        }

        val smbPolicy = SmbSyncPolicy(this)
        if (smbPolicy.rescanOnLaunch && isSmbShown() && smbPolicy.isFullScanDue()) {
            rescanSmb(reportCounts = false, priority = RemoteScanScheduler.PRIORITY_AUTO)
        }

        sweepDownloadedSmbVideos()
    }

    // A video downloaded for watching is kept for a day after it was last watched and then
    // dropped, which nothing can schedule: the system is free to kill the app long before the
    // day is up. So the sweep runs when the app starts, which is the app's own version of
    // "a day later", and again whenever a download finishes
    private fun sweepDownloadedSmbVideos() {
        ensureBackgroundThread {
            SmbVideoCache(this).sweepExpired()
        }
    }

    private fun showAllMedia() {
        config.showAll = true
        Intent(this, MediaActivity::class.java).apply {
            putExtra(DIRECTORY, "")

            if (mIsThirdPartyIntent) {
                handleMediaIntent(this)
            } else {
                hideKeyboard()
                startActivity(this)
                finish()
            }
        }
    }

    private fun changeViewType() {
        ChangeViewTypeDialog(this, true) {
            refreshMenuItems()
            setupLayoutManager()
            binding.directoriesGrid.adapter = null
            setupAdapter(mDirs)
        }
    }

    private fun tryToggleTemporarilyShowHidden() {
        if (config.temporarilyShowHidden) {
            toggleTemporarilyShowHidden(false)
        } else {
            if (isRPlus() && !isExternalStorageManager()) {
                GrantAllFilesDialog(this)
            } else {
                handleHiddenFolderPasswordProtection {
                    toggleTemporarilyShowHidden(true)
                }
            }
        }
    }

    private fun toggleTemporarilyShowHidden(show: Boolean) {
        mLoadedInitialPhotos = false
        config.temporarilyShowHidden = show
        binding.directoriesGrid.adapter = null
        getDirectories()
        refreshMenuItems()
    }

    private fun tryToggleTemporarilyShowExcluded() {
        if (config.temporarilyShowExcluded) {
            toggleTemporarilyShowExcluded(false)
        } else {
            handleExcludedFolderPasswordProtection {
                toggleTemporarilyShowExcluded(true)
            }
        }
    }

    private fun toggleTemporarilyShowExcluded(show: Boolean) {
        mLoadedInitialPhotos = false
        config.temporarilyShowExcluded = show
        binding.directoriesGrid.adapter = null
        getDirectories()
        refreshMenuItems()
    }

    override fun deleteFolders(folders: ArrayList<File>) {
        val fileDirItems = folders
            .asSequence()
            .filter { it.isDirectory }
            .map { FileDirItem(it.absolutePath, it.name, true) }
            .toMutableList() as ArrayList<FileDirItem>

        when {
            fileDirItems.isEmpty() -> return
            fileDirItems.size == 1 -> {
                try {
                    toast(
                        String.format(
                            getString(org.fossify.commons.R.string.deleting_folder),
                            fileDirItems.first().name
                        )
                    )
                } catch (e: Exception) {
                    showErrorToast(e)
                }
            }

            else -> {
                val baseString = if (config.useRecycleBin && !config.tempSkipRecycleBin) {
                    org.fossify.commons.R.plurals.moving_items_into_bin
                } else {
                    org.fossify.commons.R.plurals.delete_items
                }

                toast(
                    msg = resources.getQuantityString(
                        baseString, fileDirItems.size, fileDirItems.size
                    )
                )
            }
        }

        val itemsToDelete = ArrayList<FileDirItem>()
        val filter = config.filterMedia
        val showHidden = config.shouldShowHidden
        fileDirItems.filter { it.isDirectory }.forEach {
            val files = File(it.path).listFiles()
            files?.filter {
                it.absolutePath.isMediaFile()
                        && (showHidden || !it.name.startsWith('.'))
                        && ((it.isImageFast() && filter and TYPE_IMAGES != 0)
                        || (it.isVideoFast() && filter and TYPE_VIDEOS != 0)
                        || (it.isGif() && filter and TYPE_GIFS != 0)
                        || (it.isRawFast() && filter and TYPE_RAWS != 0)
                        || (it.isSvg() && filter and TYPE_SVGS != 0))
            }?.mapTo(itemsToDelete) { it.toFileDirItem(applicationContext) }
        }

        if (config.useRecycleBin && !config.tempSkipRecycleBin) {
            val pathsToDelete = ArrayList<String>()
            itemsToDelete.mapTo(pathsToDelete) { it.path }

            movePathsInRecycleBin(pathsToDelete) {
                if (it) {
                    deleteFilteredFileDirItems(itemsToDelete, folders)
                } else {
                    toast(org.fossify.commons.R.string.unknown_error_occurred)
                }
            }
        } else {
            deleteFilteredFileDirItems(itemsToDelete, folders)
        }
    }

    private fun deleteFilteredFileDirItems(
        fileDirItems: ArrayList<FileDirItem>,
        folders: ArrayList<File>
    ) {
        val OTGPath = config.OTGPath
        deleteFiles(fileDirItems) {
            runOnUiThread {
                refreshItems()
            }

            ensureBackgroundThread {
                folders.filter { !getDoesFilePathExist(it.absolutePath, OTGPath) }.forEach {
                    directoryDB.deleteDirPath(it.absolutePath)
                }

                if (config.deleteEmptyFolders) {
                    folders.filter {
                        !it.absolutePath.isDownloadsFolder()
                                && it.isDirectory
                                && it.toFileDirItem(this).getProperFileCount(this, true) == 0
                    }
                        .forEach {
                            tryDeleteFileDirItem(it.toFileDirItem(this), true, true)
                        }
                }
            }
        }
    }

    private fun setupLayoutManager() {
        if (config.viewTypeFolders == VIEW_TYPE_GRID) {
            setupGridLayoutManager()
        } else {
            setupListLayoutManager()
        }

        (binding.directoriesRefreshLayout.layoutParams as RelativeLayout.LayoutParams)
            .addRule(RelativeLayout.BELOW, R.id.directories_switch_searching)
    }

    private fun setupGridLayoutManager() {
        val layoutManager = binding.directoriesGrid.layoutManager as MyGridLayoutManager
        if (config.scrollHorizontally) {
            layoutManager.orientation = RecyclerView.HORIZONTAL
            binding.directoriesRefreshLayout.layoutParams =
                RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
        } else {
            layoutManager.orientation = RecyclerView.VERTICAL
            binding.directoriesRefreshLayout.layoutParams =
                RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
        }

        layoutManager.spanCount = config.dirColumnCnt
    }

    private fun setupListLayoutManager() {
        val layoutManager = binding.directoriesGrid.layoutManager as MyGridLayoutManager
        layoutManager.spanCount = 1
        layoutManager.orientation = RecyclerView.VERTICAL
        binding.directoriesRefreshLayout.layoutParams = RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        mZoomListener = null
    }

    private fun initZoomListener() {
        if (config.viewTypeFolders == VIEW_TYPE_GRID) {
            val layoutManager = binding.directoriesGrid.layoutManager as MyGridLayoutManager
            mZoomListener = object : MyRecyclerView.MyZoomListener {
                override fun zoomIn() {
                    if (layoutManager.spanCount > 1) {
                        reduceColumnCount()
                        getRecyclerAdapter()?.finishActMode()
                    }
                }

                override fun zoomOut() {
                    if (layoutManager.spanCount < MAX_COLUMN_COUNT) {
                        increaseColumnCount()
                        getRecyclerAdapter()?.finishActMode()
                    }
                }
            }
        } else {
            mZoomListener = null
        }
    }

    // With the folder list showing pCloud alone, the new folder goes to the pCloud root: the
    // picker below only walks the device. With both storages on screen it stays a local
    // folder; a pCloud folder can be created from inside any pCloud folder
    private fun createNewFolder() {
        if (config.isPCloudLoggedIn && config.storageFilter == STORAGE_FILTER_PCLOUD) {
            PCloudNameDialog(this, "", org.fossify.commons.R.string.create_new_folder) { name ->
                // the new folder is empty and has no Directory row, so it is shown the way a
                // new local folder is: as the temporary tile at the top, until something is
                // moved into it or the app is left
                var newPath = ""
                writeToPCloud(listOf(PCLOUD_PATH_SCHEME), { newPath = createFolder(PCLOUD_PATH_SCHEME, name) }) { success ->
                    if (success) {
                        config.tempFolderPath = newPath
                        ensureBackgroundThread {
                            gotDirectories(addTempFolderIfNeeded(getCurrentlyDisplayedDirs()))
                        }
                    }
                }
            }
            return
        }

        FilePickerDialog(this, internalStoragePath, false, config.shouldShowHidden, false, true) {
            CreateNewFolderDialog(this, it) {
                config.tempFolderPath = it
                ensureBackgroundThread {
                    gotDirectories(addTempFolderIfNeeded(getCurrentlyDisplayedDirs()))
                }
            }
        }
    }

    private fun changeColumnCount() {
        val items = ArrayList<RadioItem>()
        for (i in 1..MAX_COLUMN_COUNT) {
            items.add(
                RadioItem(
                    id = i,
                    title = resources.getQuantityString(
                        org.fossify.commons.R.plurals.column_counts, i, i
                    )
                )
            )
        }

        val currentColumnCount =
            (binding.directoriesGrid.layoutManager as MyGridLayoutManager).spanCount
        RadioGroupDialog(this, items, currentColumnCount) {
            val newColumnCount = it as Int
            if (currentColumnCount != newColumnCount) {
                config.dirColumnCnt = newColumnCount
                columnCountChanged()
            }
        }
    }

    private fun increaseColumnCount() {
        config.dirColumnCnt += 1
        columnCountChanged()
    }

    private fun reduceColumnCount() {
        config.dirColumnCnt -= 1
        columnCountChanged()
    }

    private fun columnCountChanged() {
        (binding.directoriesGrid.layoutManager as MyGridLayoutManager).spanCount =
            config.dirColumnCnt
        refreshMenuItems()
        getRecyclerAdapter()?.apply {
            notifyItemRangeChanged(0, dirs.size)
        }
    }

    private fun isPickImageIntent(intent: Intent): Boolean {
        return isPickIntent(intent) && (hasImageContentData(intent) || isImageType(intent))
    }

    private fun isPickVideoIntent(intent: Intent): Boolean {
        return isPickIntent(intent) && (hasVideoContentData(intent) || isVideoType(intent))
    }

    private fun isPickIntent(intent: Intent): Boolean {
        return intent.action == Intent.ACTION_PICK
    }

    private fun isGetContentIntent(intent: Intent): Boolean {
        return intent.action == Intent.ACTION_GET_CONTENT && intent.type != null
    }

    private fun anyExtraMimeTypeStartingWith(intent: Intent, mimeTypePrefix: String): Boolean {
        return intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)
            ?.any { it.startsWith(mimeTypePrefix) } == true
    }

    private fun isGetImageContentIntent(intent: Intent): Boolean {
        return isGetContentIntent(intent)
                && (intent.type!!.startsWith("image/")
                || intent.type == Images.Media.CONTENT_TYPE
                || anyExtraMimeTypeStartingWith(intent, "image/"))
    }

    private fun isGetVideoContentIntent(intent: Intent): Boolean {
        return isGetContentIntent(intent)
                && (intent.type!!.startsWith("video/")
                || intent.type == Video.Media.CONTENT_TYPE
                || anyExtraMimeTypeStartingWith(intent, "video/"))
    }

    private fun isGetAnyContentIntent(intent: Intent): Boolean {
        return isGetContentIntent(intent) && intent.type == "*/*"
    }

    private fun isSetWallpaperIntent(intent: Intent?): Boolean {
        return intent?.action == Intent.ACTION_SET_WALLPAPER
    }

    private fun hasImageContentData(intent: Intent): Boolean {
        return intent.data == Images.Media.EXTERNAL_CONTENT_URI
                || intent.data == Images.Media.INTERNAL_CONTENT_URI
    }

    private fun hasVideoContentData(intent: Intent): Boolean {
        return intent.data == Video.Media.EXTERNAL_CONTENT_URI
                || intent.data == Video.Media.INTERNAL_CONTENT_URI
    }

    private fun isImageType(intent: Intent): Boolean {
        return (intent.type?.startsWith("image/") == true
                || intent.type == Images.Media.CONTENT_TYPE)
    }

    private fun isVideoType(intent: Intent): Boolean {
        return (intent.type?.startsWith("video/") == true
                || intent.type == Video.Media.CONTENT_TYPE)
    }

    private fun fillExtraOutput(resultData: Intent): Uri? {
        val file = File(resultData.data!!.path!!)
        var inputStream: InputStream? = null
        var outputStream: OutputStream? = null
        try {
            val output = intent.extras!!.get(MediaStore.EXTRA_OUTPUT) as Uri
            inputStream = FileInputStream(file)
            outputStream = contentResolver.openOutputStream(output)
            inputStream.copyTo(outputStream!!)
        } catch (e: SecurityException) {
            showErrorToast(e)
        } catch (ignored: FileNotFoundException) {
            return getFilePublicUri(file, BuildConfig.APPLICATION_ID)
        } finally {
            inputStream?.close()
            outputStream?.close()
        }

        return null
    }

    private fun fillPickedPaths(resultData: Intent, resultIntent: Intent) {
        val paths = resultData.extras!!.getStringArrayList(PICKED_PATHS)
        val uris = paths!!
            .map { getFilePublicUri(File(it), BuildConfig.APPLICATION_ID) } as ArrayList
        val clipData = ClipData(
            "Attachment",
            arrayOf("image/*", "video/*"),
            ClipData.Item(uris.removeAt(0))
        )

        uris.forEach {
            clipData.addItem(ClipData.Item(it))
        }

        resultIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        resultIntent.clipData = clipData
    }

    private fun fillIntentPath(resultData: Intent, resultIntent: Intent) {
        val data = resultData.data
        val path = if (data.toString().startsWith("/")) data.toString() else data!!.path
        val uri = getFilePublicUri(File(path!!), BuildConfig.APPLICATION_ID)
        val type = path.getMimeType()
        resultIntent.setDataAndTypeAndNormalize(uri, type)
        resultIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun itemClicked(path: String) {
        handleLockedFolderOpening(path) { success ->
            if (success) {
                Intent(this, MediaActivity::class.java).apply {
                    putExtra(SKIP_AUTHENTICATION, true)
                    putExtra(DIRECTORY, path)
                    handleMediaIntent(this)
                }
            }
        }
    }

    private fun handleMediaIntent(intent: Intent) {
        hideKeyboard()
        intent.apply {
            if (mIsSetWallpaperIntent) {
                putExtra(SET_WALLPAPER_INTENT, true)
                startActivityForResult(this, PICK_WALLPAPER)
            } else {
                putExtra(GET_IMAGE_INTENT, mIsPickImageIntent || mIsGetImageContentIntent)
                putExtra(GET_VIDEO_INTENT, mIsPickVideoIntent || mIsGetVideoContentIntent)
                putExtra(GET_ANY_INTENT, mIsGetAnyContentIntent)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, mAllowPickingMultiple)
                startActivityForResult(this, PICK_MEDIA)
            }
        }
    }

    private fun gotDirectories(newDirs: ArrayList<Directory>) {
        mIsGettingDirs = false
        mShouldStopFetching = false

        // if hidden item showing is disabled but all Favorite items are hidden, hide the Favorites folder
        if (!config.shouldShowHidden) {
            val favoritesFolder = newDirs.firstOrNull { it.areFavorites() }
            if (
                favoritesFolder != null
                && favoritesFolder.tmb.getFilenameFromPath().startsWith('.')
            ) {
                newDirs.remove(favoritesFolder)
            }
        }

        val dirs = getSortedDirectories(newDirs)

        // keep the full folder list available for navigating subfolders and virtual groups while the folders are being rechecked
        mDirs = dirs.clone() as ArrayList<Directory>

        var isPlaceholderVisible = dirs.isEmpty()

        runOnUiThread {
            checkPlaceholderVisibility(dirs)
            setupAdapter(dirs.clone() as ArrayList<Directory>)
        }

        // cached folders have been loaded, recheck folders one by one starting with the first displayed
        mLastMediaFetcher?.shouldStop = true
        mLastMediaFetcher = MediaFetcher(applicationContext)
        val getImages = mIsPickImageIntent || mIsGetImageContentIntent
        val getVideos = mIsPickVideoIntent || mIsGetVideoContentIntent
        val getImagesOnly = getImages && !getVideos
        val getVideosOnly = getVideos && !getImages
        val favoritePaths = getFavoritePaths()
        val hiddenString = getString(R.string.hidden)
        val albumCovers = config.parseAlbumCovers()
        val includedFolders = config.includedFolders
        val noMediaFolders = getNoMediaFoldersSync()
        val tempFolderPath = config.tempFolderPath
        val getProperFileSize = config.directorySorting and SORT_BY_SIZE != 0
        val dirPathsToRemove = ArrayList<String>()
        val lastModifieds = mLastMediaFetcher!!.getLastModifieds()
        val dateTakens = mLastMediaFetcher!!.getDateTakens()

        if (
            config.showRecycleBinAtFolders
            && !config.showRecycleBinLast
            && !dirs.map { it.path }.contains(RECYCLE_BIN)
        ) {
            try {
                if (mediaDB.getDeletedMediaCount() > 0) {
                    val recycleBin = Directory().apply {
                        path = RECYCLE_BIN
                        name = getString(org.fossify.commons.R.string.recycle_bin)
                        location = LOCATION_INTERNAL
                    }

                    dirs.add(0, recycleBin)
                }
            } catch (ignored: Exception) {
            }
        }

        // the pCloud bin the same way; "bin last" is applied when the list is sorted, so it
        // goes in first here either way
        if (config.showRecycleBinAtFolders && config.isPCloudLoggedIn && !dirs.map { it.path }.contains(PCLOUD_RECYCLE_BIN)) {
            try {
                if (mediaDB.getPCloudDeletedMediaCount() > 0) {
                    val pCloudRecycleBin = Directory().apply {
                        path = PCLOUD_RECYCLE_BIN
                        name = getString(R.string.pcloud_recycle_bin)
                        location = LOCATION_PCLOUD
                    }

                    dirs.add(0, pCloudRecycleBin)
                }
            } catch (ignored: Exception) {
            }
        }

        if (dirs.map { it.path }.contains(FAVORITES)) {
            if (mediaDB.getFavoritesCount() > 0) {
                val favorites = Directory().apply {
                    path = FAVORITES
                    name = getString(org.fossify.commons.R.string.favorites)
                    location = LOCATION_INTERNAL
                }

                dirs.add(0, favorites)
            }
        }

        // fetch files from MediaStore only, unless the app has the MANAGE_EXTERNAL_STORAGE permission on Android 11+
        val android11Files = mLastMediaFetcher?.getAndroid11FolderMedia(
            isPickImage = getImagesOnly,
            isPickVideo = getVideosOnly,
            favoritePaths = favoritePaths,
            getFavoritePathsOnly = false,
            getProperDateTaken = true,
            dateTakens = dateTakens
        )
        var lastRedrawAt = 0L
        var isRedrawPending = false
        try {
            for (directory in dirs) {
                if (mShouldStopFetching || isDestroyed || isFinishing) {
                    return
                }

                val sorting = config.getFolderSorting(directory.path)
                val grouping = config.getFolderGrouping(directory.path)
                val getProperDateTaken = config.directorySorting and SORT_BY_DATE_TAKEN != 0
                        || sorting and SORT_BY_DATE_TAKEN != 0
                        || grouping and GROUP_BY_DATE_TAKEN_DAILY != 0
                        || grouping and GROUP_BY_DATE_TAKEN_MONTHLY != 0

                val getProperLastModified =
                    config.directorySorting and SORT_BY_DATE_MODIFIED != 0
                            || sorting and SORT_BY_DATE_MODIFIED != 0
                            || grouping and GROUP_BY_LAST_MODIFIED_DAILY != 0
                            || grouping and GROUP_BY_LAST_MODIFIED_MONTHLY != 0

                val curMedia = mLastMediaFetcher!!.getFilesFrom(
                    curPath = directory.path,
                    isPickImage = getImagesOnly,
                    isPickVideo = getVideosOnly,
                    getProperDateTaken = getProperDateTaken,
                    getProperLastModified = getProperLastModified,
                    getProperFileSize = getProperFileSize,
                    favoritePaths = favoritePaths,
                    getVideoDurations = false,
                    lastModifieds = lastModifieds,
                    dateTakens = dateTakens,
                    android11Files = android11Files
                )

                val newDir = if (curMedia.isEmpty()) {
                    // A folder on a remote storage is never dropped here. This recheck is how a
                    // local folder emptied outside the app is noticed, and for that it asks the
                    // filesystem -- but a remote folder has nothing here to ask, so getFilesFrom()
                    // answers out of the same cache that holds the folder. Dropping it on that
                    // answer is the cache deleting itself over its own bad read, and a share of
                    // a thousand folders goes in one pass. What a remote storage still holds is
                    // for its scanner to say, having asked the server
                    if (directory.path.isRemotePath()) {
                        Log.w(TAG_REMOTE_FOLDERS, "The cache answered that \"${directory.path}\" holds nothing; keeping it, only a scan may drop it")
                    } else if (directory.path != tempFolderPath) {
                        dirPathsToRemove.add(directory.path)
                    }

                    directory
                } else {
                    createDirectoryFromMedia(
                        path = directory.path,
                        curMedia = curMedia,
                        albumCovers = albumCovers,
                        hiddenString = hiddenString,
                        includedFolders = includedFolders,
                        getProperFileSize = getProperFileSize,
                        noMediaFolders = noMediaFolders
                    )
                }

                // we are looping through the already displayed folders looking for changes, do not do anything if nothing changed
                if (directory.copy(subfoldersCount = 0, subfoldersMediaCount = 0) == newDir) {
                    continue
                }

                directory.apply {
                    tmb = newDir.tmb
                    name = newDir.name
                    mediaCnt = newDir.mediaCnt
                    modified = newDir.modified
                    taken = newDir.taken
                    this@apply.size = newDir.size
                    types = newDir.types
                    sortValue = getDirectorySortingValue(curMedia, path, name, size, mediaCnt)
                }

                // The list is built again as the recheck finds a folder that changed, so that the
                // covers and counts fill in while it runs -- but not once per folder. Changing
                // the sorting leaves every folder differing from the row it was stored as, since
                // sortValue is computed under whichever sorting was in force, so a share of a
                // thousand folders would rebuild the list a thousand times over. Nothing is
                // dropped by the system for it and no frame is reported late, which is why this
                // does not look like slowness: it is the list being replaced under the finger,
                // and a tap landing on a row that is gone before it registers
                val now = System.currentTimeMillis()
                if (now - lastRedrawAt >= RECHECK_REDRAW_INTERVAL) {
                    lastRedrawAt = now
                    isRedrawPending = false
                    setupAdapter(dirs)
                } else {
                    isRedrawPending = true
                }

                // update directories and media files in the local db, delete invalid items. Intentionally creating a new thread
                updateDBDirectory(directory)
                if (!directory.isRecycleBin() && !directory.isPCloudRecycleBin() && !directory.areFavorites()) {
                    Thread {
                        try {
                            mediaDB.insertAll(curMedia)
                        } catch (ignored: Exception) {
                        }
                    }.start()
                }

                if (!directory.isRecycleBin() && !directory.isPCloudRecycleBin()) {
                    getCachedMedia(directory.path, getVideosOnly, getImagesOnly) {
                        val mediaToDelete = ArrayList<Medium>()
                        it.forEach {
                            if (!curMedia.contains(it)) {
                                val medium = it as? Medium
                                val path = medium?.path
                                if (path != null) {
                                    mediaToDelete.add(medium)
                                }
                            }
                        }
                        mediaDB.deleteMedia(*mediaToDelete.toTypedArray())
                    }
                }
            }

            // whatever the throttle above held back, now that there is no next folder to fold it into
            if (isRedrawPending) {
                isRedrawPending = false
                setupAdapter(dirs)
            }

            if (dirPathsToRemove.isNotEmpty()) {
                val dirsToRemove = dirs.filter { dirPathsToRemove.contains(it.path) }
                dirsToRemove.forEach {
                    directoryDB.deleteDirPath(it.path)
                }
                dirs.removeAll(dirsToRemove)
                setupAdapter(dirs)
            }
        } catch (e: Exception) {
            // the recheck is a best effort over folders that are already on screen, so it has
            // always swallowed what went wrong. Swallowing it unseen is what makes a fault here
            // impossible to explain afterwards
            Log.w(TAG_REMOTE_FOLDERS, "The recheck of the displayed folders did not finish", e)
        }

        val foldersToScan = mLastMediaFetcher!!.getFoldersToScan()
        foldersToScan.remove(FAVORITES)
        foldersToScan.add(0, FAVORITES)
        if (config.showRecycleBinAtFolders) {
            if (foldersToScan.contains(RECYCLE_BIN)) {
                foldersToScan.remove(RECYCLE_BIN)
                foldersToScan.add(0, RECYCLE_BIN)
            } else {
                foldersToScan.add(0, RECYCLE_BIN)
            }
        } else {
            foldersToScan.remove(RECYCLE_BIN)
        }

        dirs.filterNot { it.path == RECYCLE_BIN || it.path == FAVORITES }.forEach {
            foldersToScan.remove(it.path)
        }

        // check the remaining folders which were not cached at all yet
        for (folder in foldersToScan) {
            if (mShouldStopFetching || isDestroyed || isFinishing) {
                return
            }

            val sorting = config.getFolderSorting(folder)
            val grouping = config.getFolderGrouping(folder)
            val getProperDateTaken = config.directorySorting and SORT_BY_DATE_TAKEN != 0
                    || sorting and SORT_BY_DATE_TAKEN != 0
                    || grouping and GROUP_BY_DATE_TAKEN_DAILY != 0
                    || grouping and GROUP_BY_DATE_TAKEN_MONTHLY != 0

            val getProperLastModified = config.directorySorting and SORT_BY_DATE_MODIFIED != 0
                    || sorting and SORT_BY_DATE_MODIFIED != 0
                    || grouping and GROUP_BY_LAST_MODIFIED_DAILY != 0
                    || grouping and GROUP_BY_LAST_MODIFIED_MONTHLY != 0

            val newMedia = mLastMediaFetcher!!.getFilesFrom(
                curPath = folder,
                isPickImage = getImagesOnly,
                isPickVideo = getVideosOnly,
                getProperDateTaken = getProperDateTaken,
                getProperLastModified = getProperLastModified,
                getProperFileSize = getProperFileSize,
                favoritePaths = favoritePaths,
                getVideoDurations = false,
                lastModifieds = lastModifieds,
                dateTakens = dateTakens,
                android11Files = android11Files
            )

            if (newMedia.isEmpty()) {
                continue
            }

            if (isPlaceholderVisible) {
                isPlaceholderVisible = false
                runOnUiThread {
                    binding.directoriesEmptyPlaceholder.beGone()
                    binding.directoriesEmptyPlaceholder2.beGone()
                    binding.directoriesFastscroller.beVisible()
                }
            }

            val newDir = createDirectoryFromMedia(
                path = folder,
                curMedia = newMedia,
                albumCovers = albumCovers,
                hiddenString = hiddenString,
                includedFolders = includedFolders,
                getProperFileSize = getProperFileSize,
                noMediaFolders = noMediaFolders
            )
            dirs.add(newDir)
            setupAdapter(dirs)

            // make sure to create a new thread for these operations, dont just use the common bg thread
            Thread {
                try {
                    directoryDB.insert(newDir)
                    if (folder != RECYCLE_BIN && folder != FAVORITES) {
                        mediaDB.insertAll(newMedia)
                    }
                } catch (ignored: Exception) {
                }
            }.start()
        }

        mLoadedInitialPhotos = true
        if (config.appRunCount > 1) {
            checkLastMediaChanged()
        }

        runOnUiThread {
            binding.directoriesRefreshLayout.isRefreshing = false
            checkPlaceholderVisibility(dirs)
        }

        checkInvalidDirectories(dirs)
        if (mDirs.size > 50) {
            excludeSpamFolders()
        }

        val excludedFolders = config.excludedFolders
        val everShownFolders = config.everShownFolders.toMutableSet() as HashSet<String>

        // do not add excluded folders and their subfolders at everShownFolders
        dirs.filter { dir ->
            return@filter !excludedFolders.any { dir.path.startsWith(it) }
        }.mapTo(everShownFolders) { it.path }

        try {
            // scan the internal storage from time to time for new folders
            if (config.appRunCount == 1 || config.appRunCount % 30 == 0) {
                everShownFolders.addAll(getFoldersWithMedia(config.internalStoragePath))
            }

            // catch some extreme exceptions like too many everShownFolders for storing, shouldnt really happen
            config.everShownFolders = everShownFolders
        } catch (e: Exception) {
            config.everShownFolders = HashSet()
        }

        mDirs = dirs.clone() as ArrayList<Directory>
        pruneFolderGroupMembers()
    }

    private fun setAsDefaultFolder() {
        config.defaultFolder = ""
        refreshMenuItems()
    }

    private fun openDefaultFolder() {
        if (config.defaultFolder.isEmpty()) {
            return
        }

        val defaultDir = File(config.defaultFolder)

        if ((!defaultDir.exists() || !defaultDir.isDirectory) && (config.defaultFolder != RECYCLE_BIN && config.defaultFolder != FAVORITES)) {
            config.defaultFolder = ""
            return
        }

        Intent(this, MediaActivity::class.java).apply {
            putExtra(DIRECTORY, config.defaultFolder)
            handleMediaIntent(this)
        }
    }

    private fun checkPlaceholderVisibility(dirs: ArrayList<Directory>) {
        binding.directoriesEmptyPlaceholder.beVisibleIf(dirs.isEmpty() && mLoadedInitialPhotos)
        binding.directoriesEmptyPlaceholder2.beVisibleIf(dirs.isEmpty() && mLoadedInitialPhotos)

        if (binding.mainMenu.isSearchOpen) {
            binding.directoriesEmptyPlaceholder.text =
                getString(org.fossify.commons.R.string.no_items_found)
            binding.directoriesEmptyPlaceholder2.beGone()
        } else if (mCurrentGroupId != null) {
            binding.directoriesEmptyPlaceholder.text = getString(R.string.group_is_empty)
            binding.directoriesEmptyPlaceholder2.beGone()
        } else if (dirs.isEmpty() && config.filterMedia == getDefaultFileFilter()) {
            if (isRPlus() && !isExternalStorageManager()) {
                binding.directoriesEmptyPlaceholder.text =
                    getString(org.fossify.commons.R.string.no_items_found)
                binding.directoriesEmptyPlaceholder2.beGone()
            } else {
                binding.directoriesEmptyPlaceholder.text = getString(R.string.no_media_add_included)
                binding.directoriesEmptyPlaceholder2.text = getString(R.string.add_folder)
            }

            binding.directoriesEmptyPlaceholder2.setOnClickListener {
                showAddIncludedFolderDialog {
                    refreshItems()
                }
            }
        } else {
            binding.directoriesEmptyPlaceholder.text = getString(R.string.no_media_with_filters)
            binding.directoriesEmptyPlaceholder2.text =
                getString(R.string.change_filters_underlined)

            binding.directoriesEmptyPlaceholder2.setOnClickListener {
                showFilterMediaDialog()
            }
        }

        binding.directoriesEmptyPlaceholder2.underlineText()
        binding.directoriesFastscroller.beVisibleIf(binding.directoriesEmptyPlaceholder.isGone())
    }

    private fun setupAdapter(
        dirs: ArrayList<Directory>,
        textToSearch: String = binding.mainMenu.getCurrentQuery(),
        forceRecreate: Boolean = false
    ) {
        val currAdapter = binding.directoriesGrid.adapter
        // every caller but the search itself hands the full list over; remember it for the
        // search, whether the adapter is being created or updated
        if (dirs !== mDirsIgnoringSearch) {
            mDirsIgnoringSearch = dirs
        }

        // the folders found while rechecking come from every storage, the filter decides what is displayed
        val distinctDirs = dirs
            .filter { isShownByStorageFilter(it) }
            .distinctBy { it.path.getDistinctPath() }
            .toMutableList() as ArrayList<Directory>

        val sortedDirs = getSortedDirectories(distinctDirs)
        var dirsToShow = if (textToSearch.isEmpty()) {
            getDirsToShowWithGroups(sortedDirs)
        } else {
            // searching looks through all real folders, ignoring the virtual group structure, and
            // through the groups themselves, at whatever level they sit: a group is reached by its
            // name without having to walk back to wherever it was made
            val matchedDirs = getDirsToShow(
                dirs = sortedDirs,
                allDirs = mDirs,
                currentPathPrefix = mCurrentPathPrefix
            )

            val groupDirs = getAllGroupDirectories(sortedDirs, hideGroupsWithoutVisibleFolders = true)
            getSortedDirectories(ArrayList(matchedDirs + groupDirs))
        }

        if (currAdapter == null || forceRecreate) {
            initZoomListener()
            DirectoryAdapter(
                this,
                dirsToShow,
                this,
                binding.directoriesGrid,
                isPickIntent(intent) || isGetAnyContentIntent(intent),
                binding.directoriesRefreshLayout
            ) {
                val clickedDir = it as Directory
                val path = clickedDir.path
                if (clickedDir.isGroup()) {
                    handleLockedFolderOpening(path) { success ->
                        if (success) {
                            openGroup(clickedDir.getGroupId())
                        }
                    }
                } else if (clickedDir.subfoldersCount == 1 || !config.groupDirectSubfolders) {
                    if (path != config.tempFolderPath) {
                        itemClicked(path)
                    }
                } else {
                    mCurrentPathPrefix = path
                    mOpenedSubfolders.add(path)
                    setupAdapter(mDirs, "")
                }
            }.apply {
                setupZoomListener(mZoomListener)
                runOnUiThread {
                    binding.directoriesGrid.adapter = this
                    setupScrollDirection()

                    if (config.viewTypeFolders == VIEW_TYPE_LIST && areSystemAnimationsEnabled) {
                        binding.directoriesGrid.scheduleLayoutAnimation()
                    }
                }
            }
        } else {
            runOnUiThread {
                if (textToSearch.isNotEmpty()) {
                    dirsToShow = dirsToShow
                        .filter { it.name.contains(textToSearch, true) }
                        .sortedBy { !it.name.startsWith(textToSearch, true) }
                        .toMutableList() as ArrayList
                }
                checkPlaceholderVisibility(dirsToShow)

                (binding.directoriesGrid.adapter as? DirectoryAdapter)?.updateDirs(dirsToShow)
            }
        }

        // recyclerview sometimes becomes empty at init/update, triggering an invisible refresh like this seems to work fine
        binding.directoriesGrid.postDelayed({
            binding.directoriesGrid.scrollBy(0, 0)
        }, 500)
    }

    // applies the virtual folder groups on top of the "Group direct subfolders" logic for the currently opened group level
    private fun getDirsToShowWithGroups(sortedDirs: ArrayList<Directory>): ArrayList<Directory> {
        val grouped = getGroupedDirectories(sortedDirs, mCurrentGroupId, hideGroupsWithoutVisibleFolders = true)
        val (groupDirs, realDirs) = grouped.partition { it.isGroup() }
        val realDirsToShow = getDirsToShow(
            dirs = ArrayList(realDirs),
            allDirs = mDirs,
            currentPathPrefix = mCurrentPathPrefix
        )

        return if (mCurrentPathPrefix.isEmpty()) {
            getSortedDirectories(ArrayList(realDirsToShow + groupDirs))
        } else {
            realDirsToShow.clone() as ArrayList<Directory>
        }
    }

    private fun setupScrollDirection() {
        val scrollHorizontally =
            config.scrollHorizontally && config.viewTypeFolders == VIEW_TYPE_GRID
        binding.directoriesFastscroller.setScrollVertically(!scrollHorizontally)
    }

    private fun checkInvalidDirectories(dirs: ArrayList<Directory>) {
        val invalidDirs = ArrayList<Directory>()
        val OTGPath = config.OTGPath
        // a folder on a remote storage has no file behind it, only a rescan gets to decide that
        // it is gone. getDoesFilePathExist() says no to every pseudo path, and File(path).list()
        // below would be no kinder, so a share of a thousand folders goes in a single pass
        dirs.filter { !it.areFavorites() && !it.isRecycleBin() && !it.path.isRemotePath() }.forEach {
            if (!getDoesFilePathExist(it.path, OTGPath)) {
                invalidDirs.add(it)
            } else if (it.path != config.tempFolderPath && (!isRPlus() || isExternalStorageManager())) {
                // avoid calling file.list() or listfiles() on Android 11+, it became way too slow
                val children = if (isPathOnOTG(it.path)) {
                    getOTGFolderChildrenNames(it.path)
                } else {
                    File(it.path).list()?.asList()
                }

                val hasMediaFile = children?.any {
                    it != null && (
                            it.isMediaFile()
                                    || (it.startsWith("img_", true)
                                    && File(it).isDirectory)
                            )
                } == true

                if (!hasMediaFile) {
                    invalidDirs.add(it)
                }
            }
        }

        if (getFavoritePaths().isEmpty()) {
            val favoritesFolder = dirs.firstOrNull { it.areFavorites() }
            if (favoritesFolder != null) {
                invalidDirs.add(favoritesFolder)
            }
        }

        if (config.useRecycleBin) {
            try {
                val binFolder = dirs.firstOrNull { it.path == RECYCLE_BIN }
                if (binFolder != null && mediaDB.getDeletedMedia().isEmpty()) {
                    invalidDirs.add(binFolder)
                }
            } catch (ignored: Exception) {
            }
        }

        try {
            val pCloudBinFolder = dirs.firstOrNull { it.isPCloudRecycleBin() }
            if (pCloudBinFolder != null && mediaDB.getPCloudDeletedMediaCount() == 0L) {
                invalidDirs.add(pCloudBinFolder)
            }
        } catch (ignored: Exception) {
        }

        if (invalidDirs.isNotEmpty()) {
            dirs.removeAll(invalidDirs)
            setupAdapter(dirs)
            invalidDirs.forEach {
                try {
                    directoryDB.deleteDirPath(it.path)
                } catch (ignored: Exception) {
                }
            }
        }
    }

    // the full list of real folders backing the current view. While a group or a subfolder is opened the adapter
    // holds only a subset (and virtual group items), so the last full list is used instead
    private fun getCurrentlyDisplayedDirs(): ArrayList<Directory> {
        val isShowingSubset = mCurrentGroupId != null || mCurrentPathPrefix.isNotEmpty() || config.parseFolderGroups().isNotEmpty()
        return if (isShowingSubset && mDirs.isNotEmpty()) {
            mDirs.clone() as ArrayList<Directory>
        } else {
            (getRecyclerAdapter()?.dirs ?: ArrayList()).filter { !it.isGroup() }.toMutableList() as ArrayList<Directory>
        }
    }

    private fun setupLatestMediaId() {
        ensureBackgroundThread {
            if (hasPermission(PERMISSION_READ_STORAGE)) {
                mLatestMediaId = getLatestMediaId()
                mLatestMediaDateId = getLatestMediaByDateId()
            }
        }
    }

    private fun checkLastMediaChanged() {
        if (isDestroyed) {
            return
        }

        mLastMediaHandler.postDelayed({
            ensureBackgroundThread {
                val mediaId = getLatestMediaId()
                val mediaDateId = getLatestMediaByDateId()
                if (mLatestMediaId != mediaId || mLatestMediaDateId != mediaDateId) {
                    mLatestMediaId = mediaId
                    mLatestMediaDateId = mediaDateId
                    runOnUiThread {
                        getDirectories()
                    }
                } else {
                    mLastMediaHandler.removeCallbacksAndMessages(null)
                    checkLastMediaChanged()
                }
            }
        }, LAST_MEDIA_CHECK_PERIOD)
    }

    private fun checkRecycleBinItems() {
        if (config.useRecycleBin && config.lastBinCheck < System.currentTimeMillis() - DAY_SECONDS * 1000) {
            config.lastBinCheck = System.currentTimeMillis()
            Handler().postDelayed({
                ensureBackgroundThread {
                    try {
                        val filesToDelete = mediaDB.getOldRecycleBinItems(
                            System.currentTimeMillis() - MONTH_MILLISECONDS
                        )
                        filesToDelete.forEach {
                            if (File(it.path.replaceFirst(RECYCLE_BIN, recycleBinPath)).delete()) {
                                mediaDB.deleteMediumPath(it.path)
                            }
                        }
                    } catch (e: Exception) {
                    }

                    // the month-old media in the pCloud bin go for good the same way, through
                    // pCloud; what fails today is tried again tomorrow
                    if (config.isPCloudLoggedIn) {
                        try {
                            val oldPCloudItems = mediaDB.getOldPCloudRecycleBinItems(System.currentTimeMillis() - MONTH_MILLISECONDS)
                            if (oldPCloudItems.isNotEmpty()) {
                                PCloudWriter(this).deleteFromRecycleBin(oldPCloudItems.map { it.path })
                            }
                        } catch (e: Exception) {
                        }
                    }
                }
            }, 3000L)
        }
    }

    // exclude probably unwanted folders, for example facebook stickers are split between hundreds of separate folders like
    // /storage/emulated/0/Android/data/com.facebook.orca/files/stickers/175139712676531/209575122566323
    // /storage/emulated/0/Android/data/com.facebook.orca/files/stickers/497837993632037/499671223448714
    private fun excludeSpamFolders() {
        ensureBackgroundThread {
            try {
                val internalPath = internalStoragePath
                val checkedPaths = ArrayList<String>()
                val oftenRepeatedPaths = ArrayList<String>()
                val paths = mDirs
                    .map { it.path.removePrefix(internalPath) }
                    .toMutableList() as ArrayList<String>
                paths.forEach {
                    val parts = it.split("/")
                    var currentString = ""
                    for (i in 0 until parts.size) {
                        currentString += "${parts[i]}/"

                        if (!checkedPaths.contains(currentString)) {
                            val cnt = paths.count { it.startsWith(currentString) }
                            if (cnt > 50 && currentString.startsWith("/Android/data", true)) {
                                oftenRepeatedPaths.add(currentString)
                            }
                        }

                        checkedPaths.add(currentString)
                    }
                }

                val substringToRemove = oftenRepeatedPaths.filter {
                    val path = it
                    it == "/" || oftenRepeatedPaths.any { it != path && it.startsWith(path) }
                }

                oftenRepeatedPaths.removeAll(substringToRemove)
                val OTGPath = config.OTGPath
                oftenRepeatedPaths.forEach {
                    val file = File("$internalPath/$it")
                    if (getDoesFilePathExist(file.absolutePath, OTGPath)) {
                        config.addExcludedFolder(file.absolutePath)
                    }
                }
            } catch (e: Exception) {
            }
        }
    }

    private fun getFoldersWithMedia(path: String): HashSet<String> {
        val folders = HashSet<String>()
        try {
            val files = File(path).listFiles()
            if (files != null) {
                files.sortBy { !it.isDirectory }
                for (file in files) {
                    if (file.isDirectory && !file.startsWith("${config.internalStoragePath}/Android")) {
                        folders.addAll(getFoldersWithMedia(file.absolutePath))
                    } else if (file.isFile && file.isMediaFile()) {
                        folders.add(file.parent ?: "")
                        break
                    }
                }
            }
        } catch (ignored: Exception) {
        }

        return folders
    }

    override fun refreshItems() {
        getDirectories()
    }

    override fun recheckPinnedFolders() {
        ensureBackgroundThread {
            gotDirectories(movePinnedDirectoriesToFront(getCurrentlyDisplayedDirs()))
        }
    }

    override fun updateDirectories(directories: ArrayList<Directory>) {
        ensureBackgroundThread {
            val realDirectories = directories.filter { !it.isGroup() }.toMutableList() as ArrayList<Directory>
            storeDirectoryItems(realDirectories)
            removeInvalidDBDirectories()
        }
    }

    override fun refreshGroups() {
        // groups no longer existing cannot stay opened
        val groups = config.parseFolderGroups()
        while (mCurrentGroupId != null && groups.none { it.id == mCurrentGroupId }) {
            mOpenedGroups.removeAt(mOpenedGroups.lastIndex)
            mCurrentGroupId = mOpenedGroups.last()
        }

        runOnUiThread {
            setupAdapter(mDirs)
        }
    }

    private fun checkWhatsNewDialog() {
        arrayListOf<Release>().apply {
            checkWhatsNew(this, BuildConfig.VERSION_CODE)
        }
    }
}
