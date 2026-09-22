package org.fossify.gallery.dialogs

import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.InsetDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.dialogs.FilePickerDialog
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beInvisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getDefaultCopyDestinationPath
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.handleHiddenFolderPasswordProtection
import org.fossify.commons.extensions.handleLockedFolderOpening
import org.fossify.commons.extensions.hideKeyboard
import org.fossify.commons.extensions.isGone
import org.fossify.commons.extensions.isInDownloadDir
import org.fossify.commons.extensions.isRestrictedWithSAFSdk30
import org.fossify.commons.extensions.onGlobalLayout
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.underlineText
import org.fossify.commons.helpers.VIEW_TYPE_GRID
import org.fossify.commons.views.MyGridLayoutManager
import org.fossify.commons.views.MySearchMenu
import org.fossify.gallery.R
import org.fossify.gallery.adapters.DirectoryAdapter
import org.fossify.gallery.databinding.DialogDirectoryPickerBinding
import org.fossify.gallery.extensions.addTempFolderIfNeeded
import org.fossify.gallery.extensions.availableStorages
import org.fossify.gallery.extensions.config
import org.fossify.gallery.extensions.effectiveStorageFilter
import org.fossify.gallery.extensions.getCachedDirectories
import org.fossify.gallery.extensions.getDirsToShow
import org.fossify.gallery.extensions.getDistinctPath
import org.fossify.gallery.extensions.getAllGroupDirectories
import org.fossify.gallery.extensions.getGroupedDirectories
import org.fossify.gallery.extensions.getSortedDirectories
import org.fossify.gallery.extensions.isPCloudPath
import org.fossify.gallery.extensions.isRemotePath
import org.fossify.gallery.extensions.isSmbPath
import org.fossify.gallery.extensions.storageLabel
import org.fossify.gallery.helpers.PCLOUD_PATH_SCHEME
import org.fossify.gallery.helpers.STORAGE_FILTER_ALL
import org.fossify.gallery.helpers.STORAGE_FILTER_LOCAL
import org.fossify.gallery.helpers.STORAGE_FILTER_PCLOUD
import org.fossify.gallery.helpers.STORAGE_FILTER_SMB
import org.fossify.gallery.models.Directory
import org.fossify.gallery.views.StorageChips

// a sentence of guidance wants more than the two lines a snackbar gives it by default
private const val SNACKBAR_MAX_LINES = 5

/**
 * Lets the user pick a folder. Virtual folder groups are shown as soon as [navigateGroups] is set or
 * [groupCallback] is given: tapping a group navigates into it, so grouped folders are found where they
 * live instead of being listed flat. With [groupCallback] a group itself is a valid destination too and
 * the OK button confirms the currently opened group (or the top level). Groups listed in
 * [excludedGroupIds], and their subgroups, are not offered. Set [allowFolderDestination] to false to
 * accept only groups.
 *
 * [localDestinationOnly] leaves the remote storages out of the list and out of the "Other folder"
 * picker, for a caller that can only write to the device. [onCancelled] tells a caller that nothing was picked,
 * so that a screen standing on this dialog alone can close itself.
 *
 * [isCopyOperation] says which of the two a copy/move destination is being picked for. It is what
 * tells a folder that can be copied into from one that can be moved into, which is not the same
 * thing on the network share (#28); it means nothing when [isPickingCopyMoveDestination] is off.
 */
class PickDirectoryDialog(
    val activity: BaseSimpleActivity,
    val sourcePath: String,
    showOtherFolderButton: Boolean,
    val showFavoritesBin: Boolean,
    val isPickingCopyMoveDestination: Boolean,
    val isPickingFolderForWidget: Boolean,
    val excludedGroupIds: Collection<Long> = emptyList(),
    val allowFolderDestination: Boolean = true,
    navigateGroups: Boolean = false,
    val localDestinationOnly: Boolean = false,
    val isCopyOperation: Boolean = true,
    val groupCallback: ((groupId: Long?) -> Unit)? = null,
    val onCancelled: (() -> Unit)? = null,
    val callback: (path: String) -> Unit
) {
    private var dialog: AlertDialog? = null
    private var shownDirectories = ArrayList<Directory>()
    private var allDirectories = ArrayList<Directory>()
    private var openedSubfolders = arrayListOf("")
    private var openedGroups = arrayListOf<Long?>(null)
    private var binding = DialogDirectoryPickerBinding.inflate(activity.layoutInflater)
    private var isGridViewType = activity.config.viewTypeFolders == VIEW_TYPE_GRID
    private var showHidden = activity.config.shouldShowHidden
    private var currentPathPrefix = ""
    private var currentGroupId: Long? = null
    private val config = activity.config
    private val searchView = binding.folderSearchView
    private val searchEditText = searchView.binding.topToolbarSearch
    private val searchBarContainer = searchView.binding.searchBarContainer
    private val isPickingGroup = groupCallback != null
    private val showGroups = isPickingGroup || navigateGroups

    // A copy or move destination can be on any storage that is set up, so the list starts out
    // showing the one the folder list is on and can be switched from a row of chips; the
    // choice lives for this dialog only. Without the chips every folder passes, unless the
    // caller takes the device only: then the list is narrowed with no chips to switch it
    private val storages = activity.availableStorages()
    private val hasStorageChips = isPickingCopyMoveDestination && !localDestinationOnly && storages.size > 1

    // inside a group the chips are put away, and the narrowing goes with them: a group belongs to no
    // storage, so it is opened whole, the way its count and its collage are counted whole
    private val showStorageChips get() = hasStorageChips && currentGroupId == null
    private val narrowsStorage get() = showStorageChips || localDestinationOnly
    private var storageFilter = when {
        localDestinationOnly -> STORAGE_FILTER_LOCAL
        // the folder list's own filter, read the way the list reads it: a filter pointing at a
        // storage that is no longer set up would leave no chip selected and narrow to nothing
        hasStorageChips -> activity.effectiveStorageFilter()
        else -> STORAGE_FILTER_ALL
    }

    init {
        (binding.directoriesGrid.layoutManager as MyGridLayoutManager).apply {
            orientation = if (activity.config.scrollHorizontally && isGridViewType) RecyclerView.HORIZONTAL else RecyclerView.VERTICAL
            spanCount = if (isGridViewType) activity.config.dirColumnCnt else 1
        }

        binding.directoriesFastscroller.updateColors(activity.getProperPrimaryColor())

        configureSearchView()
        configureStorageChips()

        val builder = activity.getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel) { _, _ -> onCancelled?.invoke() }

        // while picking groups the "Other folder" action lives in the action row above the list instead
        if (showOtherFolderButton && !showGroups) {
            builder.setNeutralButton(R.string.other_folder) { dialogInterface, i -> showOtherFolder() }
        }

        builder.apply {
            activity.setupDialogStuff(binding.root, this, org.fossify.commons.R.string.select_destination) { alertDialog ->
                dialog = alertDialog
                // only a cancel, never a dismiss(): picking a folder and opening the "Other
                // folder" picker both dismiss this dialog, and neither is "nothing was picked"
                alertDialog.setOnCancelListener { onCancelled?.invoke() }
                compactDialogChrome(alertDialog)
                binding.directoriesShowHidden.beVisibleIf(!context.config.shouldShowHidden)
                binding.directoriesShowHidden.setOnClickListener {
                    activity.handleHiddenFolderPasswordProtection {
                        binding.directoriesShowHidden.beGone()
                        showHidden = true
                        fetchDirectories(true)
                    }
                }

                if (showGroups) {
                    updateGroupHint()
                    if (isPickingGroup) {
                        alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                            groupCallback?.invoke(currentGroupId)
                            alertDialog.dismiss()
                        }
                    } else {
                        // OK picks nothing in this mode: a destination is a folder tapped in the
                        // list, and a group is only walked into -- it holds folders, not files.
                        // It used to close the dialog without picking anything, which looked like
                        // a copy that quietly did nothing, so it says what to do instead and the
                        // dialog stays where it is. Cancel is still how it is left
                        alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                            showPickerMessage(
                                if (currentGroupId == null) {
                                    R.string.pick_a_destination_by_tapping
                                } else {
                                    R.string.group_is_not_a_destination
                                }
                            )
                        }
                    }

                    val primaryColor = activity.getProperPrimaryColor()
                    val showOtherFolderAction = showOtherFolderButton && allowFolderDestination
                    binding.directoriesGroupActions.beVisibleIf(showOtherFolderAction || isPickingGroup)
                    liftShowHiddenAboveActions()

                    // picking a real folder outside of the list, same as the "Other folder" dialog button
                    binding.directoriesOtherFolder.apply {
                        beVisibleIf(showOtherFolderAction)
                        setTextColor(primaryColor)
                        underlineText()
                        setOnClickListener {
                            alertDialog.dismiss()
                            showOtherFolder()
                        }
                    }

                    // like "Other folder" for real folders: create a new group at the opened level and move there right away.
                    // Only a group destination can take a new group, files always end up in a real folder
                    binding.directoriesCreateGroup.apply {
                        beVisibleIf(isPickingGroup)
                        setTextColor(primaryColor)
                        underlineText()
                        setOnClickListener {
                            activity.hideKeyboard(searchEditText)
                            FolderGroupNameDialog(activity) { name ->
                                val newGroup = config.addFolderGroup(name, currentGroupId)
                                groupCallback?.invoke(newGroup.id)
                                alertDialog.dismiss()
                            }
                        }
                    }
                }

                alertDialog.onBackPressedDispatcher.addCallback(alertDialog) {
                    if (searchView.isSearchOpen) {
                        searchView.closeSearch()
                    } else if (activity.config.groupDirectSubfolders && currentPathPrefix.isNotEmpty()) {
                        openedSubfolders.removeAt(openedSubfolders.lastIndex)
                        currentPathPrefix = openedSubfolders.last()
                        gotDirectories(allDirectories)
                    } else if (currentGroupId != null) {
                        openedGroups.removeAt(openedGroups.lastIndex)
                        currentGroupId = openedGroups.last()
                        updateGroupHint()
                        gotDirectories(allDirectories)
                    } else {
                        isEnabled = false
                        alertDialog.onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        }

        fetchDirectories(false)
    }

    /**
     * The dialog chrome (window insets, title, buttons) takes up so much room that only a couple of folders fit.
     * Trim it down so the folder list itself gets the space.
     */
    private fun compactDialogChrome(alertDialog: AlertDialog) {
        shrinkDialogVerticalInsets(alertDialog)
        shrinkDialogTitle(alertDialog)
        shrinkDialogButtons(alertDialog)
    }

    /**
     * Material dialogs keep 80dp of empty space above and below the window, which leaves little room for the
     * folder list. Re-inset the very same background with a smaller vertical inset so the list gets that space.
     * Non-material (non dynamic theme) dialogs are not inset like that, so there is nothing to shrink there.
     */
    private fun shrinkDialogVerticalInsets(alertDialog: AlertDialog) {
        val window = alertDialog.window ?: return
        val background = window.decorView.background as? InsetDrawable ?: return
        val inner = background.drawable ?: return
        val insets = Rect().apply { background.getPadding(this) }
        val verticalInset = activity.resources.getDimensionPixelSize(R.dimen.directory_picker_dialog_vertical_inset)
        window.setBackgroundDrawable(InsetDrawable(inner, insets.left, verticalInset, insets.right, verticalInset))
    }

    private fun shrinkDialogTitle(alertDialog: AlertDialog) {
        val title = alertDialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle) ?: return
        title.setTextSizeRes(org.fossify.commons.R.dimen.big_text_size)

        // the title panel adds a good 40dp of padding around it by default
        val titlePanel = title.parent as? View ?: return
        val padding = activity.resources.getDimensionPixelSize(org.fossify.commons.R.dimen.medium_margin)
        titlePanel.setPadding(titlePanel.paddingLeft, padding, titlePanel.paddingRight, padding)
    }

    private fun shrinkDialogButtons(alertDialog: AlertDialog) {
        val minHeight = activity.resources.getDimensionPixelSize(R.dimen.directory_picker_button_min_height)
        arrayOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL).forEach { which ->
            val button = alertDialog.getButton(which) ?: return@forEach
            button.setTextSizeRes(org.fossify.commons.R.dimen.smaller_text_size)
            button.minHeight = minHeight
            button.minimumHeight = minHeight
            (button as? MaterialButton)?.apply {
                insetTop = 0
                insetBottom = 0
            }
        }
    }

    // the search bar is sized for a toolbar, which is taller than this dialog can spare
    private fun shrinkSearchBar() {
        searchBarContainer.layoutParams.height = activity.resources.getDimensionPixelSize(R.dimen.directory_picker_search_bar_height)
        searchBarContainer.setPadding(searchBarContainer.paddingLeft, 0, searchBarContainer.paddingRight, 0)
        searchEditText.setTextSizeRes(org.fossify.commons.R.dimen.medium_text_size)
    }

    // the action row sits at the very bottom of the dialog, right where the hidden folders button floats
    private fun liftShowHiddenAboveActions() = with(binding.directoriesShowHidden) {
        val baseMargin = activity.resources.getDimensionPixelSize(org.fossify.commons.R.dimen.activity_margin)
        binding.directoriesGroupActions.onGlobalLayout {
            val actionsHeight = if (binding.directoriesGroupActions.isGone()) 0 else binding.directoriesGroupActions.height
            (layoutParams as? ViewGroup.MarginLayoutParams)?.apply {
                bottomMargin = baseMargin + actionsHeight
                layoutParams = this
            }
        }
    }

    private fun TextView.setTextSizeRes(dimenResId: Int) {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(dimenResId))
    }

    private fun configureStorageChips() = with(binding.directoriesStorages) {
        beVisibleIf(showStorageChips)
        if (!hasStorageChips) {
            return@with
        }

        val chips = storages.map { StorageChips.Chip(it, activity.storageLabel(it)) }
        setChips(chips, storageFilter)
        onChipClicked = { tag ->
            storageFilter = tag as Int
            select(tag)
            if (searchView.isSearchOpen) {
                filterFolderListBySearchQuery(searchEditText.text.toString())
            } else {
                gotDirectories(allDirectories)
            }
        }
    }

    // favorites, the recycle bin and groups belong to no storage and always pass here, like they
    // do for the folder list's own storage filter. A group is narrowed later, in gotDirectories,
    // by whether anything it holds is still in the list this leaves behind
    private fun isShownByStorageChips(directory: Directory): Boolean {
        val path = directory.path
        return when {
            !narrowsStorage || directory.areFavorites() || directory.isRecycleBin() || directory.isGroup() -> true
            storageFilter == STORAGE_FILTER_PCLOUD -> path.isPCloudPath()
            storageFilter == STORAGE_FILTER_SMB -> path.isSmbPath()
            storageFilter == STORAGE_FILTER_LOCAL -> !path.isPCloudPath() && !path.isSmbPath()
            else -> true
        }
    }

    private fun configureSearchView() = with(searchView) {
        val hint = if (showGroups) R.string.search_folders_and_groups else org.fossify.commons.R.string.search_folders
        updateHintText(context.getString(hint))
        searchEditText.imeOptions = EditorInfo.IME_ACTION_DONE

        toggleHideOnScroll(!config.scrollHorizontally)
        setupMenu()
        setSearchViewListeners()
        updateSearchViewUi()
        shrinkSearchBar()
    }

    private fun MySearchMenu.updateSearchViewUi() {
        requireToolbar().beInvisible()
        updateColors()
        setBackgroundColor(Color.TRANSPARENT)
        searchBarContainer.setBackgroundColor(Color.TRANSPARENT)
    }

    private fun MySearchMenu.setSearchViewListeners() {
        onSearchOpenListener = {
            updateSearchViewLeftIcon(org.fossify.commons.R.drawable.ic_cross_vector)
        }

        onSearchClosedListener = {
            searchEditText.clearFocus()
            activity.hideKeyboard(searchEditText)
            updateSearchViewLeftIcon(org.fossify.commons.R.drawable.ic_search_vector)
        }

        onSearchTextChangedListener = { text ->
            filterFolderListBySearchQuery(text)
        }
    }

    private fun updateSearchViewLeftIcon(iconResId: Int) = with(searchView.binding.topToolbarSearchIcon) {
        post {
            setImageResource(iconResId)
        }
    }

    // tells the user what the OK button will do while picking a group destination,
    // or which group is opened while groups are only walked through
    private fun updateGroupHint() {
        if (!showGroups) {
            return
        }

        val groupId = currentGroupId
        val hint = when {
            isPickingGroup && groupId == null -> activity.getString(R.string.move_to_top_level_hint)
            isPickingGroup -> activity.getString(R.string.move_into_group_hint, groupName(groupId!!))
            groupId == null -> activity.getString(R.string.search_folders_and_groups)
            else -> activity.getString(R.string.inside_group, groupName(groupId))
        }

        searchView.updateHintText(hint)
    }

    private fun groupName(groupId: Long) = config.getFolderGroup(groupId)?.name ?: ""

    // The dialog's own message, in place of a toast: this dialog fills the screen and a toast
    // behind it was cut off after its first line. A snackbar lands in this dialog's own
    // CoordinatorLayout, and is given the lines a sentence of guidance needs
    private fun showPickerMessage(messageId: Int) {
        Snackbar.make(binding.root, activity.getString(messageId), Snackbar.LENGTH_LONG).apply {
            view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)?.maxLines = SNACKBAR_MAX_LINES
            show()
        }
    }

    private fun filterFolderListBySearchQuery(query: String) {
        val adapter = binding.directoriesGrid.adapter as? DirectoryAdapter
        var dirsToShow = ArrayList(allDirectories.filter { isShownByStorageChips(it) })
        if (showGroups) {
            // the groups are searched through as well, at whatever level they sit: the group a
            // folder is headed for is often not the one that happens to be open
            dirsToShow.addAll(
                activity.getAllGroupDirectories(
                    dirs = dirsToShow,
                    hideGroupsWithoutVisibleFolders = narrowsStorage,
                    groupContentDirs = allDirectories
                )
            )
        }

        if (query.isNotEmpty()) {
            dirsToShow = ArrayList(dirsToShow.filter { it.name.contains(query, true) })
        }

        dirsToShow = activity.getSortedDirectories(dirsToShow)
        checkPlaceholderVisibility(dirsToShow)

        val filteredFolderListUpdated = adapter?.dirs != dirsToShow
        if (filteredFolderListUpdated) {
            adapter?.updateDirs(dirsToShow)

            binding.directoriesGrid.apply {
                post {
                    scrollToPosition(0)
                }
            }
        }
    }

    private fun checkPlaceholderVisibility(dirs: ArrayList<Directory>) = with(binding) {
        directoriesEmptyPlaceholder.beVisibleIf(dirs.isEmpty())

        if (folderSearchView.isSearchOpen) {
            directoriesEmptyPlaceholder.text = root.context.getString(org.fossify.commons.R.string.no_items_found)
        } else if (currentGroupId != null) {
            directoriesEmptyPlaceholder.text = root.context.getString(R.string.group_is_empty)
        }

        directoriesFastscroller.beVisibleIf(directoriesEmptyPlaceholder.isGone())
    }

    private fun fetchDirectories(forceShowHiddenAndExcluded: Boolean) {
        // a copy or move can go to either storage whichever one the folder list is showing;
        // the chips narrow the list here, so the storage filter must not narrow it first
        activity.getCachedDirectories(
            forceShowHidden = forceShowHiddenAndExcluded,
            forceShowExcluded = forceShowHiddenAndExcluded,
            forceShowAllStorages = isPickingCopyMoveDestination
        ) {
            if (it.isNotEmpty()) {
                it.forEach {
                    it.subfoldersMediaCount = it.mediaCnt
                }

                activity.runOnUiThread {
                    allDirectories.clear()
                    gotDirectories(activity.addTempFolderIfNeeded(it))
                }
            } else if (showGroups) {
                // there may be groups to pick even without any cached folder
                activity.runOnUiThread {
                    allDirectories.clear()
                    gotDirectories(ArrayList())
                }
            }
        }
    }

    // A copy or move destination is picked with the gallery's own folder picker, which knows
    // pCloud; everything else (a file to pick, a folder for a widget) keeps commons' picker.
    // The last destination is remembered whichever storage it was on, the commons default
    // only knows about folders on the device
    private fun showOtherFolder() {
        activity.hideKeyboard(searchEditText)
        val onPicked = { path: String ->
            config.lastCopyPath = path
            activity.handleLockedFolderOpening(path) { success ->
                if (success) {
                    callback(path)
                }
            }
        }

        if (isPickingCopyMoveDestination) {
            val lastCopyPath = config.lastCopyPath
            val lastCopyPathOnPCloud = lastCopyPath.isPCloudPath() && config.isPCloudLoggedIn && !localDestinationOnly
            // the picker opens on the storage the chips are showing, at the last destination there
            val startPath = when {
                showStorageChips && storageFilter == STORAGE_FILTER_PCLOUD -> if (lastCopyPathOnPCloud) lastCopyPath else PCLOUD_PATH_SCHEME
                showStorageChips && storageFilter == STORAGE_FILTER_LOCAL -> activity.getDefaultCopyDestinationPath(showHidden, sourcePath)
                lastCopyPathOnPCloud -> lastCopyPath
                else -> activity.getDefaultCopyDestinationPath(showHidden, sourcePath)
            }

            FolderPickerDialog(
                activity = activity,
                currPath = startPath,
                showHidden = showHidden,
                showFAB = true,
                canAddShowHiddenButton = true,
                localStorageOnly = localDestinationOnly,
                onCancelled = onCancelled,
                callback = onPicked
            )
            return
        }

        FilePickerDialog(
            activity,
            activity.getDefaultCopyDestinationPath(showHidden, sourcePath),
            !isPickingFolderForWidget,
            showHidden,
            true,
            true,
            callback = onPicked
        )
    }

    private fun gotDirectories(newDirs: ArrayList<Directory>) {
        if (allDirectories.isEmpty()) {
            allDirectories = newDirs.clone() as ArrayList<Directory>
        }

        // walking into a group takes the chips away and walking back out brings them back
        binding.directoriesStorages.beVisibleIf(showStorageChips)

        val distinctDirs = newDirs
            .filter { (showFavoritesBin || (!it.isRecycleBin() && !it.areFavorites())) && !it.isPCloudRecycleBin() && isShownByStorageChips(it) }
            .distinctBy { it.path.getDistinctPath() }
            .toMutableList() as ArrayList<Directory>
        val sortedDirs = activity.getSortedDirectories(distinctDirs)

        val dirs = if (showGroups) {
            // a group follows the chips by what it holds: the one whose folders are all on another
            // storage is put away with them, and a group with no folder in it yet stays, so that it
            // can be filled. A folder headed for a group that is out of view is taken there by
            // switching the chips to the storage that group's folders live on, or to all of them.
            // What a group is drawn as does not change with the chips, though: its count and its
            // collage are summed from every folder inside it, wherever that folder lives
            val grouped = activity.getGroupedDirectories(
                dirs = sortedDirs,
                currentGroupId = currentGroupId,
                excludedGroupIds = excludedGroupIds,
                hideGroupsWithoutVisibleFolders = narrowsStorage,
                groupContentDirs = allDirectories
            )
            val (groupDirs, realDirs) = grouped.partition { it.isGroup() }
            val realDirsToShow = activity.getDirsToShow(ArrayList(realDirs), allDirectories, currentPathPrefix)
            if (currentPathPrefix.isEmpty()) {
                activity.getSortedDirectories(ArrayList(realDirsToShow + groupDirs))
            } else {
                realDirsToShow.clone() as ArrayList<Directory>
            }
        } else {
            activity.getDirsToShow(sortedDirs, allDirectories, currentPathPrefix).clone() as ArrayList<Directory>
        }

        if (dirs.hashCode() == shownDirectories.hashCode()) {
            return
        }

        shownDirectories = dirs
        checkPlaceholderVisibility(dirs)
        val adapter = DirectoryAdapter(activity, dirs.clone() as ArrayList<Directory>, null, binding.directoriesGrid, true) {
            val clickedDir = it as Directory
            val path = clickedDir.path
            if (clickedDir.isGroup()) {
                val groupId = clickedDir.getGroupId()
                activity.handleLockedFolderOpening(path) { success ->
                    if (success) {
                        // a group opened from the search results leaves the search behind it, the
                        // same way the folder list does
                        if (searchView.isSearchOpen) {
                            searchView.closeSearch()
                        }

                        currentGroupId = groupId
                        openedGroups.add(groupId)
                        currentPathPrefix = ""
                        openedSubfolders = arrayListOf("")
                        updateGroupHint()
                        gotDirectories(allDirectories)
                    }
                }
            } else if (clickedDir.subfoldersCount == 1 || !activity.config.groupDirectSubfolders) {
                if (!allowFolderDestination) {
                    activity.toast(R.string.pick_a_group, Toast.LENGTH_LONG)
                    return@DirectoryAdapter
                } else if (isPickingCopyMoveDestination && path.trimEnd('/') == sourcePath) {
                    activity.toast(org.fossify.commons.R.string.source_and_destination_same)
                    return@DirectoryAdapter
                } else if (isPickingCopyMoveDestination && path.isSmbPath() && sourcePath.isRemotePath() && !sourcePath.isSmbPath()) {
                    // What goes onto the share from outside it is read off a file of the device;
                    // anything already remote would have to be staged on the way, see #28. The
                    // share to itself is another thing entirely -- no bytes travel at all
                    activity.toast(R.string.smb_no_remote_copy_to_share, Toast.LENGTH_LONG)
                    return@DirectoryAdapter
                } else if (isPickingCopyMoveDestination && activity.isRestrictedWithSAFSdk30(path) && !activity.isInDownloadDir(path)) {
                    activity.toast(org.fossify.commons.R.string.system_folder_copy_restriction, Toast.LENGTH_LONG)
                    return@DirectoryAdapter
                } else {
                    activity.handleLockedFolderOpening(path) { success ->
                        if (success) {
                            callback(path)
                        }
                    }
                    dialog?.dismiss()
                }
            } else {
                currentPathPrefix = path
                openedSubfolders.add(path)
                gotDirectories(allDirectories)
            }
        }

        val scrollHorizontally = activity.config.scrollHorizontally && isGridViewType
        binding.apply {
            directoriesGrid.adapter = adapter
            directoriesFastscroller.setScrollVertically(!scrollHorizontally)
        }
    }
}
