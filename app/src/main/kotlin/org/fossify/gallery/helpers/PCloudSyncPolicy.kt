package org.fossify.gallery.helpers

// Answers when the pCloud cache should be refreshed from the network. It only reads the
// settings, so every caller sees them as they are at that moment and nothing has to be
// invalidated when the user changes them.
//
// The list itself is always served from the cache; these only decide whether a scan is
// started on top of it
class PCloudSyncPolicy(private val config: Config) {
    val rescanOnLaunch: Boolean get() = config.pCloudRescanOnLaunch

    val rescanOnStorageSwitch: Boolean get() = config.pCloudRescanOnStorageSwitch

    val rescanOnFolderOpen: Boolean get() = config.pCloudRescanOnFolderOpen

    val rescanOnGroupOpen: Boolean get() = config.pCloudRescanOnGroupOpen

    val rescanAfterWrite: Boolean get() = config.pCloudRescanAfterWrite

    // an automatic rescan is held back until the interval has passed since the last full scan.
    // An interval of 0 disables the throttle, every event then fetches. A manual rescan never
    // asks this
    fun isFullScanDue(now: Long = System.currentTimeMillis()): Boolean {
        val intervalMinutes = config.pCloudRescanIntervalMinutes
        if (intervalMinutes <= 0) {
            return true
        }

        return now - config.pCloudLastFullScanAt >= intervalMinutes * 60_000L
    }
}
