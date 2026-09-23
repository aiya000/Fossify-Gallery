package org.fossify.gallery.interfaces

import org.fossify.gallery.models.Directory

interface DirectoryOperationsListener {
    fun refreshItems()

    // the folders at [paths], all on one storage, with their media into the bin when
    // [toRecycleBin]; the storage does the deleting, see MediaStorage.deleteFolders()
    fun deleteFolders(paths: ArrayList<String>, toRecycleBin: Boolean)

    fun recheckPinnedFolders()

    fun updateDirectories(directories: ArrayList<Directory>)

    // called after virtual folder groups changed, no filesystem rescan is needed
    fun refreshGroups()
}
