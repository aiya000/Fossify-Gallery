package org.fossify.gallery.dialogs

import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.formatDate
import org.fossify.commons.extensions.formatSize
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getFormattedDuration
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.gallery.databinding.DialogRemotePropertiesBinding
import org.fossify.gallery.databinding.ItemRemotePropertyBinding
import org.fossify.gallery.extensions.humanizeAnyPath
import org.fossify.gallery.models.Medium

// The properties of media that live on pCloud or on the network share. Commons'
// PropertiesDialog reads the file it is given off the device, and a remote medium has none: it
// would either have nothing to read -- which is what it says, "the source file does not exist"
// (#60) -- or, once fetched, report the cached copy's path in the app's own cache rather than
// where the medium actually is.
//
// So this one is built from what the gallery already knows about the medium. Nothing is
// fetched, which is the point: opening the properties of a video should not pull it down. The
// resolution is missing for the same reason -- pCloud reports it in the file's metadata and a
// share does not report it at all, and the gallery keeps neither, so there is nothing to show
// without downloading the file
class RemotePropertiesDialog(val activity: BaseSimpleActivity, val media: List<Medium>) {
    init {
        val binding = DialogRemotePropertiesBinding.inflate(activity.layoutInflater)
        val properties = if (media.size == 1) propertiesOf(media.first()) else propertiesOfSeveral(media)
        properties.forEach { (labelId, value) ->
            val row = ItemRemotePropertyBinding.inflate(activity.layoutInflater, binding.remotePropertiesHolder, true)
            row.remotePropertyLabel.text = activity.getString(labelId)
            row.remotePropertyValue.text = value
        }

        activity.getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, org.fossify.commons.R.string.properties)
            }
    }

    private fun propertiesOf(medium: Medium): List<Pair<Int, String>> {
        val properties = mutableListOf(
            org.fossify.commons.R.string.name to medium.name,
            // the pseudo path the gallery uses inside itself is "pcloud:/Photos" or
            // "smb:/Screens"; what belongs in front of the user is the storage's own name and
            // the folder under it
            org.fossify.commons.R.string.path to activity.humanizeAnyPath(medium.parentPath),
            org.fossify.commons.R.string.size to medium.size.formatSize(),
            org.fossify.commons.R.string.last_modified to medium.modified.formatDate(activity)
        )

        if (medium.taken > 0) {
            properties.add(org.fossify.commons.R.string.date_taken to medium.taken.formatDate(activity))
        }

        if (medium.isVideo() && medium.videoDuration > 0) {
            properties.add(org.fossify.commons.R.string.duration to medium.videoDuration.getFormattedDuration())
        }

        return properties
    }

    private fun propertiesOfSeveral(media: List<Medium>): List<Pair<Int, String>> {
        return listOf(
            org.fossify.commons.R.string.files_count to media.size.toString(),
            org.fossify.commons.R.string.size to media.sumOf { it.size }.formatSize()
        )
    }
}
