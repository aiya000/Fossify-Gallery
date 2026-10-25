package org.fossify.gallery.helpers

import org.fossify.gallery.extensions.isPCloudPath
import org.fossify.gallery.extensions.isSmbPath

// Whether a list can be pulled to refresh at all, on the storage it is showing.
//
// The general switch (enablePullToRefresh) turns the gesture off everywhere. On top of it, each
// remote storage's "rescan on a pull" switch turns it off for that storage: a pull on the share
// that walks nothing would only re-read the cache, which is nothing the user asked for, and the
// gesture sitting there with nothing behind it is what made the setting read as if it had been
// ignored. So the switch is the gesture, on that storage -- in the folder list, and inside a
// folder of it, where a pull rescans that folder.
//
// This device has no switch of its own: a pull there rechecks its folders, which is quick, and
// the general switch is the one for it. "All storages" shows this device too, so the gesture
// stays; the remote storages on that list then follow their own switches when the pull runs
object PullToRefreshPolicy {
    fun canPullFolderList(enabled: Boolean, storageFilter: Int, pCloudPull: Boolean, smbPull: Boolean): Boolean = enabled && when {
        storageFilter == STORAGE_FILTER_PCLOUD -> pCloudPull
        smbConnectionIdOfFilter(storageFilter) != null -> smbPull
        else -> true
    }

    fun canPullFolder(enabled: Boolean, path: String, pCloudPull: Boolean, smbPull: Boolean): Boolean = enabled && when {
        path.isPCloudPath() -> pCloudPull
        path.isSmbPath() -> smbPull
        else -> true
    }
}
