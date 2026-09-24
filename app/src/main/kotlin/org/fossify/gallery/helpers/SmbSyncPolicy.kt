package org.fossify.gallery.helpers

import android.content.Context
import android.net.ConnectivityManager
import org.fossify.gallery.extensions.config

// Answers when the SMB cache should be refreshed from the share, the same shape as
// PCloudSyncPolicy. A share lives on the local network, so the metered check is doing more work
// here than it does for pCloud: on mobile data the host is not reachable at all, and every scan
// would be a wait for a timeout -- unless the share is reachable from outside, which is the
// case #124 was thought of on
class SmbSyncPolicy(private val context: Context) {
    private val config = context.config

    // An event nobody sees answers yes or no: on a metered network with "unmetered only" on, it
    // is no, without a word
    val rescanOnLaunch: Boolean get() = config.smbRescanOnLaunch && isNetworkAllowed

    // An event the user made with a gesture can answer ASK as well: the setting wants the rescan
    // and the network alone is holding it back, so the screen asks before going ahead (#124)
    val rescanOnStorageSwitch: RescanVerdict get() = verdict(config.smbRescanOnStorageSwitch)

    val rescanOnFolderOpen: RescanVerdict get() = verdict(config.smbRescanOnFolderOpen)

    val rescanOnPullToRefresh: RescanVerdict get() = verdict(config.smbRescanOnPullToRefresh)

    private fun verdict(wanted: Boolean) = when {
        !wanted -> RescanVerdict.SKIP
        isNetworkAllowed -> RescanVerdict.RUN
        else -> RescanVerdict.ASK
    }

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
