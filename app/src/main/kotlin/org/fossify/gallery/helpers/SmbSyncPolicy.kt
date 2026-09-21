package org.fossify.gallery.helpers

import android.content.Context
import android.net.ConnectivityManager
import org.fossify.gallery.extensions.config

// Answers when the SMB cache should be refreshed from the share, the same shape as
// PCloudSyncPolicy. A share lives on the local network, so the metered check is doing more work
// here than it does for pCloud: on mobile data the host is not reachable at all, and every scan
// would be a wait for a timeout
class SmbSyncPolicy(private val context: Context) {
    private val config = context.config

    val rescanOnLaunch: Boolean get() = config.smbRescanOnLaunch && isNetworkAllowed

    val rescanOnStorageSwitch: Boolean get() = config.smbRescanOnStorageSwitch && isNetworkAllowed

    val rescanOnFolderOpen: Boolean get() = config.smbRescanOnFolderOpen && isNetworkAllowed

    val rescanOnPullToRefresh: Boolean get() = config.smbRescanOnPullToRefresh && isNetworkAllowed

    // no active network counts as unmetered: the scan then fails on its own, quietly
    private val isNetworkAllowed: Boolean
        get() {
            if (!config.smbRescanOnUnmeteredOnly) {
                return true
            }

            val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return true
            return !connectivity.isActiveNetworkMetered
        }

    // an automatic rescan is held back until the interval has passed since the share was last
    // walked. An interval of 0 disables the throttle. A manual rescan never asks this
    fun isFullScanDue(now: Long = System.currentTimeMillis()): Boolean {
        val intervalMinutes = config.smbRescanIntervalMinutes
        if (intervalMinutes <= 0) {
            return true
        }

        return now - config.smbLastFullScanAt >= intervalMinutes * 60_000L
    }
}
