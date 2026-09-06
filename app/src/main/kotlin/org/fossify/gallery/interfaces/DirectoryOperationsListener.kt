package org.fossify.gallery.interfaces

import org.fossify.gallery.models.Directory
import java.io.File

interface DirectoryOperationsListener {
    fun refreshItems()

    fun deleteFolders(folders: ArrayList<File>)

    fun recheckPinnedFolders()

    fun updateDirectories(directories: ArrayList<Directory>)

    // called after virtual folder groups changed, no filesystem rescan is needed
    fun refreshGroups()
}
