package org.fossify.gallery.dialogs

import android.graphics.Color
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.dialogs.FilePickerDialog
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beInvisible
import org.fossify.commons.extensions.beVisible
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
import org.fossify.gallery.extensions.config
import org.fossify.gallery.extensions.getCachedDirectories
import org.fossify.gallery.extensions.getDirsToShow
import org.fossify.gallery.extensions.getDistinctPath
import org.fossify.gallery.extensions.getGroupedDirectories
import org.fossify.gallery.extensions.getSortedDirectories
import org.fossify.gallery.models.Directory

/**
 * Lets the user pick a folder. When [groupCallback] is given, virtual folder groups are shown too:
 * tapping a group navigates into it and the OK button confirms the currently opened group
 * (or the top level) as the destination. Groups listed in [excludedGroupIds], and their subgroups,
 * are not offered. Set [allowFolderDestination] to false to accept only groups.
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
    val groupCallback: ((groupId: Long?) -> Unit)? = null,
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
    private val showGroups = groupCallback != null

    init {
        (binding.directoriesGrid.layoutManager as MyGridLayoutManager).apply {
            orientation = if (activity.config.scrollHorizontally && isGridViewType) RecyclerView.HORIZONTAL else RecyclerView.VERTICAL
            spanCount = if (isGridViewType) activity.config.dirColumnCnt else 1
        }

        binding.directoriesFastscroller.updateColors(activity.getProperPrimaryColor())

        configureSearchView()

        val builder = activity.getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)

        // while picking groups the "Other folder" action lives in the action row above the list instead
        if (showOtherFolderButton && !showGroups) {
            builder.setNeutralButton(R.string.other_folder) { dialogInterface, i -> showOtherFolder() }
        }

        builder.apply {
            activity.setupDialogStuff(binding.root, this, org.fossify.commons.R.string.select_destination) { alertDialog ->
                dialog = alertDialog
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
                    alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        groupCallback?.invoke(currentGroupId)
                        alertDialog.dismiss()
                    }

                    val primaryColor = activity.getProperPrimaryColor()
                    binding.directoriesGroupActions.beVisible()

                    // picking a real folder outside of the list, same as the "Other folder" dialog button
                    binding.directoriesOtherFolder.apply {
                        beVisibleIf(showOtherFolderButton && allowFolderDestination)
                        setTextColor(primaryColor)
                        underlineText()
                        setOnClickListener {
                            alertDialog.dismiss()
                            showOtherFolder()
                        }
                    }

                    // like "Other folder" for real folders: create a new group at the opened level and move there right away
                    binding.directoriesCreateGroup.apply {
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

    private fun configureSearchView() = with(searchView) {
        updateHintText(context.getString(org.fossify.commons.R.string.search_folders))
        searchEditText.imeOptions = EditorInfo.IME_ACTION_DONE

        toggleHideOnScroll(!config.scrollHorizontally)
        setupMenu()
        setSearchViewListeners()
        updateSearchViewUi()
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

    // tells the user what the OK button will do while picking a group destination
    private fun updateGroupHint() {
        if (!showGroups) {
            return
        }

        val groupId = currentGroupId
        val hint = if (groupId == null) {
            activity.getString(R.string.move_to_top_level_hint)
        } else {
            val groupName = config.getFolderGroup(groupId)?.name ?: ""
            activity.getString(R.string.move_into_group_hint, groupName)
        }

        searchView.updateHintText(hint)
    }

    private fun filterFolderListBySearchQuery(query: String) {
        val adapter = binding.directoriesGrid.adapter as? DirectoryAdapter
        var dirsToShow = allDirectories
        if (query.isNotEmpty()) {
            dirsToShow = dirsToShow.filter { it.name.contains(query, true) }.toMutableList() as ArrayList
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
        activity.getCachedDirectories(forceShowHidden = forceShowHiddenAndExcluded, forceShowExcluded = forceShowHiddenAndExcluded) {
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

    private fun showOtherFolder() {
        activity.hideKeyboard(searchEditText)
        FilePickerDialog(
            activity,
            activity.getDefaultCopyDestinationPath(showHidden, sourcePath),
            !isPickingCopyMoveDestination && !isPickingFolderForWidget,
            showHidden,
            true,
            true
        ) {
            config.lastCopyPath = it
            activity.handleLockedFolderOpening(it) { success ->
                if (success) {
                    callback(it)
                }
            }
        }
    }

    private fun gotDirectories(newDirs: ArrayList<Directory>) {
        if (allDirectories.isEmpty()) {
            allDirectories = newDirs.clone() as ArrayList<Directory>
        }

        val distinctDirs = newDirs.filter { showFavoritesBin || (!it.isRecycleBin() && !it.areFavorites()) }.distinctBy { it.path.getDistinctPath() }
            .toMutableList() as ArrayList<Directory>
        val sortedDirs = activity.getSortedDirectories(distinctDirs)

        val dirs = if (showGroups) {
            val grouped = activity.getGroupedDirectories(sortedDirs, currentGroupId, excludedGroupIds)
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
