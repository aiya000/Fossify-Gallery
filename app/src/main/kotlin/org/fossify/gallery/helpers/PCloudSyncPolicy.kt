package org.fossify.gallery.helpers

import android.content.Context
import android.net.ConnectivityManager
import org.fossify.gallery.extensions.config

// Answers when the pCloud cache should be refreshed from the network. It only reads the
// settings and the network state, so every caller sees them as they are at that moment and
// nothing has to be invalidated when the user changes them.
//
// The list itself is always served from the cache; these only decide whether a scan is
// started on top of it. Each event answers false on a metered network (mobile data) while
// the "unmetered only" setting is on; the manual rescans from the menu never ask this
class PCloudSyncPolicy(private val context: Context) {
    private val config = context.config

    val rescanOnLaunch: Boolean get() = config.pCloudRescanOnLaunch && isNetworkAllowed

    val rescanOnStorageSwitch: Boolean get() = config.pCloudRescanOnStorageSwitch && isNetworkAllowed

    val rescanOnFolderOpen: Boolean get() = config.pCloudRescanOnFolderOpen && isNetworkAllowed

    val rescanOnPullToRefresh: Boolean get() = config.pCloudRescanOnPullToRefresh && isNetworkAllowed

    val rescanOnGroupOpen: Boolean get() = config.pCloudRescanOnGroupOpen && isNetworkAllowed

    val rescanAfterWrite: Boolean get() = config.pCloudRescanAfterWrite && isNetworkAllowed

    // no active network counts as unmetered: the fetch then fails on its own, quietly
    private val isNetworkAllowed: Boolean
        get() {
            if (!config.pCloudRescanOnUnmeteredOnly) {
                return true
            }

            val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return true
            return !connectivity.isActiveNetworkMetered
        }

    // an automatic rescan is held back until the interval has passed since the account was last
    // brought up to date, by a full scan or a diff sync. An interval of 0 disables the throttle,
    // every event then fetches. A manual rescan never asks this
    fun isFullScanDue(now: Long = System.currentTimeMillis()) = isDue(config.pCloudLastFullScanAt, now)

    // the same throttle for one folder, measured from when that folder was last scanned, by a
    // full scan or on its own. lastScannedAt is the pcloud_items row's value, 0 when unknown
    fun isFolderScanDue(lastScannedAt: Long, now: Long = System.currentTimeMillis()) = isDue(lastScannedAt, now)

    private fun isDue(lastScannedAt: Long, now: Long): Boolean {
        val intervalMinutes = config.pCloudRescanIntervalMinutes
        if (intervalMinutes <= 0) {
            return true
        }

        return now - lastScannedAt >= intervalMinutes * 60_000L
    }
}
