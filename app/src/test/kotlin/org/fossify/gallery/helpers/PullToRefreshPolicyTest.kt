package org.fossify.gallery.helpers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Which lists can be pulled to refresh: the general switch for all of them, and each remote
// storage's "rescan on a pull" switch for its own. The gesture itself is driven in
// test-device/drive/96-ask-before-a-rescan-on-a-pull.sh
class PullToRefreshPolicyTest {
    private fun folderList(enabled: Boolean = true, storageFilter: Int, pCloudPull: Boolean = true, smbPull: Boolean = true) =
        PullToRefreshPolicy.canPullFolderList(enabled, storageFilter, pCloudPull, smbPull)

    private fun folder(enabled: Boolean = true, path: String, pCloudPull: Boolean = true, smbPull: Boolean = true) =
        PullToRefreshPolicy.canPullFolder(enabled, path, pCloudPull, smbPull)

    @Test
    fun `the general switch turns the gesture off everywhere`() {
        assertFalse(folderList(enabled = false, storageFilter = STORAGE_FILTER_LOCAL))
        assertFalse(folder(enabled = false, path = "/storage/emulated/0/DCIM"))
    }

    @Test
    fun `this device has no switch of its own`() {
        assertTrue(folderList(storageFilter = STORAGE_FILTER_LOCAL, pCloudPull = false, smbPull = false))
        assertTrue(folder(path = "/storage/emulated/0/DCIM", pCloudPull = false, smbPull = false))
    }

    // the share's switch is the gesture on the share, in the list and inside a folder of it
    @Test
    fun `the share follows its own switch`() {
        assertTrue(folderList(storageFilter = STORAGE_FILTER_SMB, smbPull = true))
        assertFalse(folderList(storageFilter = STORAGE_FILTER_SMB, smbPull = false))
        assertFalse(folderList(storageFilter = smbStorageFilterOf(2), smbPull = false))
        assertFalse(folder(path = "smb:/Trips/Osaka", smbPull = false))
    }

    @Test
    fun `pCloud follows its own switch`() {
        assertTrue(folderList(storageFilter = STORAGE_FILTER_PCLOUD, pCloudPull = true))
        assertFalse(folderList(storageFilter = STORAGE_FILTER_PCLOUD, pCloudPull = false))
        assertFalse(folder(path = "pcloud:/InGroup", pCloudPull = false))
    }

    // the device is on that list too, and the remote ones follow their switches when the pull runs
    @Test
    fun `all storages keeps the gesture, whatever the remote switches say`() {
        assertTrue(folderList(storageFilter = STORAGE_FILTER_ALL, pCloudPull = false, smbPull = false))
    }

    // one storage's switch says nothing about another
    @Test
    fun `the share's switch does not reach pCloud`() {
        assertTrue(folderList(storageFilter = STORAGE_FILTER_PCLOUD, smbPull = false))
        assertTrue(folder(path = "pcloud:/InGroup", smbPull = false))
    }
}
