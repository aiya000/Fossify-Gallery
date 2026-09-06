package org.fossify.gallery.models

import org.fossify.gallery.helpers.GROUP_PATH_PREFIX

// a virtual group of folders (and other groups), it does not exist on the filesystem
data class FolderGroup(
    val id: Long,
    var name: String,
    var parentId: Long? = null
) {
    fun getPseudoPath() = "$GROUP_PATH_PREFIX$id"
}

fun String.isFolderGroupPath() = startsWith(GROUP_PATH_PREFIX)

fun String.toFolderGroupId(): Long? = if (isFolderGroupPath()) removePrefix(GROUP_PATH_PREFIX).toLongOrNull() else null
