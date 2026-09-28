package org.fossify.gallery.dialogs

import android.content.DialogInterface
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.beGoneIf
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.isVisible
import org.fossify.commons.extensions.setupDialogStuff
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
import org.fossify.gallery.R
import org.fossify.gallery.databinding.DialogChangeSortingBinding
import org.fossify.gallery.extensions.config
import org.fossify.gallery.helpers.SHOW_ALL
import org.fossify.gallery.helpers.SORT_GROUP_BY_FILENAME

class ChangeSortingDialog(
    val activity: BaseSimpleActivity,
    val isDirectorySorting: Boolean,
    val showFolderCheckbox: Boolean,
    val path: String = "",
    // the folder group the folder list has open, null at its top
    val groupId: Long? = null,
    val callback: () -> Unit
) :
    DialogInterface.OnClickListener {
    companion object {
        private const val DISABLED_ALPHA = 0.4f
    }

    private var currSorting = 0
    private var wasUseForThisFolderChecked = false
    private var config = activity.config
    private var pathToUse = if (!isDirectorySorting && path.isEmpty()) SHOW_ALL else path
    private val binding: DialogChangeSortingBinding

    // the folder list offers one sorting per storage where the media of a folder offer one per
    // folder, once there is a second storage to tell apart; the checkbox is the same view
    //
    // Inside a group it is about the group instead (#137): a group belongs to no storage, so a
    // storage's own sorting means nothing there, and what is sorted is plainly the group
    private val showGroupCheckbox = isDirectorySorting && groupId != null
    private val showStorageCheckbox = isDirectorySorting && !showGroupCheckbox && config.isPCloudLoggedIn
    private val storageFilter = config.storageFilter

    init {
        currSorting = if (isDirectorySorting) {
            config.directorySorting
        } else {
            config.getFolderSorting(pathToUse)
        }

        binding = DialogChangeSortingBinding.inflate(activity.layoutInflater).apply {
            sortingDialogRadioNumberOfItems.beVisibleIf(isDirectorySorting)

            // a folder list has no file names to group by
            sortingDialogRadioFileNameGroupDateTaken.beVisibleIf(!isDirectorySorting)
            sortingDialogRadioFileNameGroupLastModified.beVisibleIf(!isDirectorySorting)
            sortingDialogNumericSorting.beVisibleIf(
                beVisible = showFolderCheckbox
                        && (currSorting and SORT_BY_NAME != 0 || currSorting and SORT_BY_PATH != 0)
            )

            // the divider only has to separate the order from the numeric switch now, the folder
            // switch moved above the sortings and brought its own divider
            sortingDialogOrderDivider.beVisibleIf(sortingDialogNumericSorting.isVisible())
            sortingDialogFolderDivider.beVisibleIf(showFolderCheckbox || showStorageCheckbox || showGroupCheckbox)

            sortingDialogNumericSorting.isChecked = currSorting and SORT_USE_NUMERIC_VALUE != 0

            sortingDialogUseForThisFolder.beVisibleIf(showFolderCheckbox || showStorageCheckbox || showGroupCheckbox)
            if (showGroupCheckbox) {
                // checked whenever the dialog opens, since changing every group at once is rarely
                // what is meant; see confirmApplyingToEveryGroup()
                sortingDialogUseForThisFolder.setText(R.string.use_for_this_group_only)
                sortingDialogUseForThisFolder.isChecked = true
                sortingDialogUseForThisFolder.setOnClickListener { confirmApplyingToEveryGroup() }
            } else if (showStorageCheckbox) {
                sortingDialogUseForThisFolder.setText(R.string.use_for_this_storage_only)
                sortingDialogUseForThisFolder.isChecked = config.hasStorageDirectorySorting(storageFilter)
            } else {
                sortingDialogUseForThisFolder.isChecked = config.hasCustomSorting(pathToUse)
            }
            sortingDialogBottomNote.beVisibleIf(!isDirectorySorting)

            // the folder list calls its drag and drop order "custom", for the media of a single
            // folder the shorter name would not say what it reorders
            if (!isDirectorySorting) {
                sortingDialogRadioCustom.setText(R.string.reorder_media_by_dragging)
            }
        }

        activity.getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok, this)
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, org.fossify.commons.R.string.sort_by)
            }

        setupSortRadio()
        setupOrderRadio()
    }

    private fun setupSortRadio() {
        val sortingRadio = binding.sortingDialogRadioSorting
        sortingRadio.setOnCheckedChangeListener { _, checkedId ->
            val isSortingByNameOrPath =
                checkedId == binding.sortingDialogRadioName.id
                        || checkedId == binding.sortingDialogRadioPath.id

            binding.sortingDialogNumericSorting.beVisibleIf(isSortingByNameOrPath)
            binding.sortingDialogOrderDivider.beVisibleIf(
                binding.sortingDialogNumericSorting.isVisible()
            )

            val hideSortOrder =
                checkedId == binding.sortingDialogRadioCustom.id
                        || checkedId == binding.sortingDialogRadioRandom.id

            binding.sortingDialogRadioOrder.beGoneIf(hideSortOrder)
            binding.sortingDialogSortingDivider.beGoneIf(hideSortOrder)

            updateUseForThisFolder(isCustomSorting = checkedId == binding.sortingDialogRadioCustom.id)
        }

        val sortBtn = when {
            // the grouping flag is combined with a date flag, so it has to be looked at first
            currSorting and SORT_GROUP_BY_FILENAME != 0 -> {
                if (currSorting and SORT_BY_DATE_MODIFIED != 0) {
                    binding.sortingDialogRadioFileNameGroupLastModified
                } else {
                    binding.sortingDialogRadioFileNameGroupDateTaken
                }
            }

            currSorting and SORT_BY_PATH != 0 -> binding.sortingDialogRadioPath
            currSorting and SORT_BY_SIZE != 0 -> binding.sortingDialogRadioSize
            currSorting and SORT_BY_COUNT != 0 -> binding.sortingDialogRadioNumberOfItems
            currSorting and SORT_BY_DATE_MODIFIED != 0 -> binding.sortingDialogRadioLastModified
            currSorting and SORT_BY_DATE_TAKEN != 0 -> binding.sortingDialogRadioDateTaken
            currSorting and SORT_BY_RANDOM != 0 -> binding.sortingDialogRadioRandom
            currSorting and SORT_BY_CUSTOM != 0 -> binding.sortingDialogRadioCustom
            else -> binding.sortingDialogRadioName
        }
        sortBtn.isChecked = true
    }

    // a custom order is kept per folder, so it only ever makes sense together with
    // "use for this folder" - the checkbox gets turned on and locked instead of being left
    // for the user to get wrong
    private fun updateUseForThisFolder(isCustomSorting: Boolean) {
        if (isDirectorySorting || !showFolderCheckbox) {
            return
        }

        binding.sortingDialogUseForThisFolder.apply {
            if (isCustomSorting) {
                if (isEnabled) {
                    wasUseForThisFolderChecked = isChecked
                }

                isChecked = true
                isEnabled = false
                alpha = DISABLED_ALPHA
            } else {
                if (!isEnabled) {
                    isChecked = wasUseForThisFolderChecked
                }

                isEnabled = true
                alpha = 1f
            }
        }

        binding.sortingDialogUseForThisFolderNote.beVisibleIf(isCustomSorting)
    }

    // Unchecking "apply to this group only" changes the order of every group without a sorting of
    // its own, and of the folder list's top, so it is asked about first. The box is put back at
    // once and only unchecked on a yes, so a No, a back press or a tap outside all leave it checked
    private fun confirmApplyingToEveryGroup() {
        val checkbox = binding.sortingDialogUseForThisFolder
        if (checkbox.isChecked) {
            return
        }

        checkbox.isChecked = true
        ConfirmationDialog(activity, activity.getString(R.string.use_for_every_group_confirmation)) {
            checkbox.isChecked = false
        }
    }

    private fun setupOrderRadio() {
        var orderBtn = binding.sortingDialogRadioAscending

        if (currSorting and SORT_DESCENDING != 0) {
            orderBtn = binding.sortingDialogRadioDescending
        }
        orderBtn.isChecked = true
    }

    override fun onClick(dialog: DialogInterface, which: Int) {
        val sortingRadio = binding.sortingDialogRadioSorting
        var sorting = when (sortingRadio.checkedRadioButtonId) {
            R.id.sorting_dialog_radio_name -> SORT_BY_NAME
            R.id.sorting_dialog_radio_path -> SORT_BY_PATH
            R.id.sorting_dialog_radio_size -> SORT_BY_SIZE
            R.id.sorting_dialog_radio_number_of_items -> SORT_BY_COUNT
            R.id.sorting_dialog_radio_last_modified -> SORT_BY_DATE_MODIFIED
            R.id.sorting_dialog_radio_file_name_group_date_taken ->
                SORT_GROUP_BY_FILENAME or SORT_BY_DATE_TAKEN

            R.id.sorting_dialog_radio_file_name_group_last_modified ->
                SORT_GROUP_BY_FILENAME or SORT_BY_DATE_MODIFIED

            R.id.sorting_dialog_radio_random -> SORT_BY_RANDOM
            R.id.sorting_dialog_radio_custom -> SORT_BY_CUSTOM
            else -> SORT_BY_DATE_TAKEN
        }

        if (binding.sortingDialogRadioOrder.checkedRadioButtonId == R.id.sorting_dialog_radio_descending) {
            sorting = sorting or SORT_DESCENDING
        }

        if (binding.sortingDialogNumericSorting.isChecked) {
            sorting = sorting or SORT_USE_NUMERIC_VALUE
        }

        if (showGroupCheckbox) {
            if (binding.sortingDialogUseForThisFolder.isChecked) {
                config.saveGroupDirectorySorting(groupId!!, sorting)
            } else {
                // written to what the group falls back to without its own: the storage's own
                // sorting when it has one, the shared one otherwise
                config.removeGroupDirectorySorting(groupId!!)
                config.directorySorting = sorting
            }
        } else if (isDirectorySorting) {
            if (showStorageCheckbox && binding.sortingDialogUseForThisFolder.isChecked) {
                config.saveStorageDirectorySorting(storageFilter, sorting)
            } else {
                config.removeStorageDirectorySorting(storageFilter)
                config.globalDirectorySorting = sorting
            }
        } else {
            if (binding.sortingDialogUseForThisFolder.isChecked) {
                config.saveCustomSorting(pathToUse, sorting)
            } else {
                config.removeCustomSorting(pathToUse)
                config.sorting = sorting
            }
        }

        if (currSorting != sorting) {
            callback()
        }
    }
}
