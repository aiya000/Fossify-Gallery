package org.fossify.gallery.helpers

import android.content.Context
import org.fossify.gallery.extensions.config
import org.fossify.gallery.extensions.directoryDB
import org.fossify.gallery.extensions.getSortedGroupChildren
import org.fossify.gallery.extensions.isSmbPath
import org.fossify.gallery.extensions.mediaDB
import org.fossify.gallery.models.Directory
import org.fossify.gallery.models.FolderGroup
import org.fossify.gallery.models.Medium
import org.fossify.gallery.models.toFolderGroupId

// The order a selection of folders and groups is fetched in, and watched in afterwards.
//
// One rule, and it is the one the eye already follows: every level is taken in the order that
// level is shown in, the walk goes depth first from there, and what comes out is a single flat
// list. The top level is the order the rows were tapped in, which the selection keeps on its own
// -- selectedKeys is a LinkedHashSet.
//
// A group is walked into, because a group is how folders are gathered here and its members lie
// all over the share. A plain folder is not: everywhere else in the gallery a folder means the
// media directly in it, and "the videos in this folder" means the same thing here.
//
// Blocks on the database, so call it off the main thread
object SmbDownloadQueue {
    fun videosOf(context: Context, selected: List<Directory>): List<Medium> {
        if (selected.isEmpty()) {
            return emptyList()
        }

        // the displayed list holds a group as one row in place of its members, so the folders
        // inside it have to come from the database rather than from what is on screen
        val dirs = context.directoryDB.getAll()
        val groups = context.config.parseFolderGroups()
        val members = context.config.parseFolderGroupMembers()
        val walked = HashSet<Long>()
        return selected.flatMap { videosOf(context, it, groups, members, dirs, walked) }
    }

    private fun videosOf(
        context: Context,
        directory: Directory,
        groups: List<FolderGroup>,
        members: Map<String, Long>,
        dirs: List<Directory>,
        walked: HashSet<Long>,
    ): List<Medium> {
        val groupId = directory.path.toFolderGroupId() ?: return videosOfFolder(context, directory.path)

        // a group picked twice, or one that somehow holds itself, is walked once
        if (!walked.add(groupId)) {
            return emptyList()
        }

        // folders and subgroups come back as one list, in the order the folder list draws them,
        // which is what makes a subgroup fall where it sits rather than after all the folders.
        // The visited set is this call's own: it is how that helper avoids drawing a group
        // inside itself, and reusing ours would hide the children we are here for
        return context.getSortedGroupChildren(groupId, groups, members, dirs, HashSet())
            .flatMap { videosOf(context, it, groups, members, dirs, walked) }
    }

    // the folder's own videos on the share, in the order that folder is sorted by. A folder on
    // the device or on pCloud answers with nothing: the menu item is offered everywhere, so that
    // nobody has to work out why it is missing from this one
    private fun videosOfFolder(context: Context, path: String): List<Medium> {
        val videos = context.mediaDB.getMediaFromPath(path).filter { it.path.isSmbPath() && it.isVideo() }
        if (videos.isEmpty()) {
            return emptyList()
        }

        val sorted = ArrayList(videos)
        MediaFetcher(context).sortMedia(sorted, context.config.getFolderSorting(path), path)
        return sorted
    }
}
