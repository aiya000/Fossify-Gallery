package org.fossify.gallery.extensions

import android.content.Context
import org.fossify.commons.extensions.getDoesFilePathExist
import org.fossify.commons.helpers.SORT_BY_COUNT
import org.fossify.commons.helpers.SORT_BY_DATE_MODIFIED
import org.fossify.commons.helpers.SORT_BY_DATE_TAKEN
import org.fossify.commons.helpers.SORT_BY_NAME
import org.fossify.commons.helpers.SORT_BY_PATH
import org.fossify.commons.helpers.SORT_BY_SIZE
import org.fossify.gallery.helpers.LOCATION_INTERNAL
import org.fossify.gallery.models.Directory
import org.fossify.gallery.models.FolderGroup
import java.util.Locale

// number of thumbnails shown in the 2x2 collage of a group
const val GROUP_COLLAGE_SIZE = 4

// Virtual folder groups: real folders are assigned to groups by path, groups can be nested via parentId.
// Groups are shown as Directory items with a pseudo path (see GROUP_PATH_PREFIX), they never reach the DB or the media scanner.

// Returns the items to display at the given group level: the real folders assigned to that level plus its direct subgroups.
// Any pseudo group items in the input are dropped and regenerated, so it is safe to pass an already grouped list in.
fun Context.getGroupedDirectories(
    dirs: ArrayList<Directory>,
    currentGroupId: Long?,
    excludedGroupIds: Collection<Long> = emptyList()
): ArrayList<Directory> {
    val realDirs = dirs.filter { !it.isGroup() }
    val groups = config.parseFolderGroups()
    if (groups.isEmpty()) {
        return ArrayList(realDirs)
    }

    val validGroupIds = groups.map { it.id }.toHashSet()
    val members = config.parseFolderGroupMembers().filterValues { validGroupIds.contains(it) }

    val result = ArrayList<Directory>()
    realDirs.forEach { dir ->
        if (members[dir.path] == currentGroupId) {
            result.add(dir)
        }
    }

    val hiddenGroupIds = groups
        .filter { group -> excludedGroupIds.any { config.isFolderGroupDescendantOrSelf(group.id, it, groups) } }
        .map { it.id }
        .toHashSet()

    groups
        .filter { !hiddenGroupIds.contains(it.id) }
        .filter { (it.parentId?.takeIf { id -> validGroupIds.contains(id) }) == currentGroupId }
        .forEach { group ->
            result.add(createGroupDirectory(group, groups, members, realDirs))
        }

    return result
}

// Collects every real folder inside the group, including the ones in nested subgroups
fun collectFolderGroupContents(
    groupId: Long,
    groups: List<FolderGroup>,
    members: Map<String, Long>,
    dirs: List<Directory>,
    visited: HashSet<Long> = HashSet()
): ArrayList<Directory> {
    val result = ArrayList<Directory>()
    if (!visited.add(groupId)) {
        return result
    }

    dirs.filterTo(result) { members[it.path] == groupId }
    groups.filter { it.parentId == groupId }.forEach {
        result.addAll(collectFolderGroupContents(it.id, groups, members, dirs, visited))
    }

    return result
}

// The direct children of a group (real folders and subgroups) in the order they are displayed in
private fun Context.getSortedGroupChildren(
    groupId: Long,
    groups: List<FolderGroup>,
    members: Map<String, Long>,
    dirs: List<Directory>,
    visited: HashSet<Long>
): ArrayList<Directory> {
    val children = ArrayList<Directory>()
    dirs.filterTo(children) { members[it.path] == groupId }
    groups.filter { it.parentId == groupId }.forEach {
        if (!visited.contains(it.id)) {
            children.add(createGroupDirectory(it, groups, members, dirs, visited))
        }
    }

    return getSortedDirectories(children)
}

private fun Context.createGroupDirectory(
    group: FolderGroup,
    groups: List<FolderGroup>,
    members: Map<String, Long>,
    dirs: List<Directory>,
    visited: HashSet<Long> = HashSet()
): Directory {
    visited.add(group.id)
    val contents = collectFolderGroupContents(group.id, groups, members, dirs)

    // the collage shows the first image of the first 4 items. A subgroup's "first image" is the thumbnail of the
    // first folder found depth first inside it, which is exactly what its own tmb holds. Items without an image are skipped
    val children = getSortedGroupChildren(group.id, groups, members, dirs, visited)
    val thumbnails = children.map { it.tmb }.filter { it.isNotEmpty() }.take(GROUP_COLLAGE_SIZE)
    val mediaCount = contents.sumOf { it.mediaCnt }
    val size = contents.sumOf { it.size }
    val modified = contents.maxOfOrNull { it.modified } ?: 0L
    val taken = contents.maxOfOrNull { it.taken } ?: 0L
    var types = 0
    contents.forEach { types = types or it.types }

    val pseudoPath = group.getPseudoPath()
    val sorting = config.directorySorting
    val sortValue = when {
        sorting and SORT_BY_NAME != 0 -> group.name.lowercase(Locale.getDefault())
        sorting and SORT_BY_PATH != 0 -> group.name.lowercase(Locale.getDefault())
        sorting and SORT_BY_SIZE != 0 -> size.toString()
        sorting and SORT_BY_COUNT != 0 -> mediaCount.toString()
        sorting and SORT_BY_DATE_MODIFIED != 0 -> modified.toString()
        sorting and SORT_BY_DATE_TAKEN != 0 -> taken.toString()
        else -> ""
    }

    return Directory(
        id = null,
        path = pseudoPath,
        tmb = thumbnails.firstOrNull() ?: "",
        name = group.name,
        mediaCnt = mediaCount,
        modified = modified,
        taken = taken,
        size = size,
        location = LOCATION_INTERNAL,
        types = types,
        sortValue = sortValue
    ).apply {
        subfoldersCount = contents.size
        subfoldersMediaCount = mediaCount
        containsMediaFilesDirectly = false
        groupThumbnails = ArrayList(thumbnails)
    }
}

// Drops memberships of folders which no longer exist on the filesystem, keeps the stored JSON small.
// Hidden or excluded folders are not shown in the folder list but still exist, so they keep their group.
fun Context.pruneFolderGroupMembers() {
    val members = config.parseFolderGroupMembers()
    val OTGPath = config.OTGPath
    val stalePaths = members.keys.filter { !getDoesFilePathExist(it, OTGPath) }
    if (stalePaths.isNotEmpty()) {
        stalePaths.forEach { members.remove(it) }
        config.storeFolderGroupMembers(members)
    }
}
