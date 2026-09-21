package org.fossify.gallery.jobs

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isQPlus
import org.fossify.gallery.R
import org.fossify.gallery.helpers.SMB_PATH_PREFIX
import org.fossify.gallery.helpers.SmbClient
import org.fossify.gallery.helpers.SmbScanAbortedException
import org.fossify.gallery.helpers.SmbScanner
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

// Walks the whole share as a foreground service with a progress notification.
//
// A share of any size takes minutes, and a plain background thread does not survive the user
// leaving the app: the system takes the network away from a process nobody is looking at, and
// the scan then fails on whatever folder it had got to. Switching to another app for a moment
// was enough to kill one. A foreground service is how the system is told that this is work the
// user asked for and is waiting on.
//
// The notification is also the only place the progress can be read once the app is off screen,
// which is where a scan of a large share spends most of its time. A share has no total to count
// towards, so it shows what has been found so far and the folder it is in -- enough to tell a
// slow scan from a stuck one -- and carries the same stop action the folder list uses when it
// leaves the share behind.
//
// Only the whole-share walk runs here. A single folder is one request and is over long before
// the app could be left, so Context.rescanSmbFolders() still does its own
class SmbScanService : Service() {
    companion object {
        private const val TAG = "SmbScan"
        private const val CHANNEL_ID = "smb_scan"
        private const val PROGRESS_NOTIFICATION_ID = 7003
        private const val RESULT_NOTIFICATION_ID = 7004
        private const val EXTRA_REPORT_COUNTS = "report_counts"

        // the notification's stop action comes back in as this, and calls the scan off
        private const val ACTION_ABORT = "org.fossify.gallery.ABORT_SMB_SCAN"

        // a notification redrawn on every folder would be redrawn hundreds of times a minute,
        // and the system throws away updates that come faster than it cares to draw anyway
        private const val PROGRESS_INTERVAL_MILLIS = 500L

        private val isWorking = AtomicBoolean(false)
        private val listeners = CopyOnWriteArraySet<() -> Unit>()

        fun start(context: Context, reportCounts: Boolean) {
            val intent = Intent(context, SmbScanService::class.java).putExtra(EXTRA_REPORT_COUNTS, reportCounts)
            ContextCompat.startForegroundService(context, intent)
        }

        // called on the main thread once a scan is through and the cache has been written. A
        // screen adds itself in onResume and leaves in onPause, the way it does for a transfer
        fun addListener(listener: () -> Unit) {
            listeners.add(listener)
        }

        fun removeListener(listener: () -> Unit) {
            listeners.remove(listener)
        }
    }

    private var lastProgressAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_ABORT) {
            SmbScanner.abortCurrent()
            return START_NOT_STICKY
        }

        // a service started with startForegroundService() has to show its notification before
        // this returns, whatever it then decides to do
        showProgress(buildNotification(getString(R.string.smb_rescanning), null))

        if (!isWorking.compareAndSet(false, true)) {
            // the scan already running here is the one being asked for; it owns the
            // notification and stops the service when it is through
            return START_NOT_STICKY
        }

        val scanner = SmbScanner(this)
        if (!SmbScanner.start(scanner)) {
            // a folder scan holds the scanner. Waiting for it would be a walk of the whole
            // share started at the wrong moment; the next rescan is soon enough
            Log.w(TAG, "Another scan of the share is running, not starting a second one")
            isWorking.set(false)
            finish(notifyListeners = false)
            return START_NOT_STICKY
        }

        val reportCounts = intent?.getBooleanExtra(EXTRA_REPORT_COUNTS, false) == true
        ensureBackgroundThread {
            work(scanner, reportCounts)
        }

        return START_NOT_STICKY
    }

    private fun work(scanner: SmbScanner, reportCounts: Boolean) {
        var aborted = false
        try {
            val result = scanner.scanAll { folders, media, path -> showProgress(folders, media, path) }
            // a scan that went through says so even when the counts were not asked for. How one
            // ended is otherwise only visible in what it wrote, which is no help when the
            // question is whether it wrote at all
            Log.i(
                TAG,
                "Walked the share: ${result.folderCount} folders, ${result.mediaCount} files, ${result.skippedFolderCount} folders skipped"
            )

            when {
                // folders the walk could not get into are what failed about this scan, so they
                // are said even when the counts were not asked for
                result.skippedFolderCount > 0 -> showResult(
                    getString(R.string.smb_rescan_done_with_skipped, result.folderCount, result.mediaCount, result.skippedFolderCount)
                )

                reportCounts -> showResult(getString(R.string.smb_rescan_done, result.folderCount, result.mediaCount))
            }
        } catch (e: SmbScanAbortedException) {
            // the screen that called it off is reloading on its own, and nothing was written.
            // Nothing is said to the user about it, so the log is the only record that the walk
            // happened at all and threw its result away
            Log.i(TAG, "The walk of the share was called off; nothing was written")
            aborted = true
        } catch (e: Exception) {
            Log.w(TAG, "A scan of the network share failed", e)
            // the most common failure is a session the server has given up on; dropping the
            // connection means the next scan opens a fresh one instead of failing the same way
            SmbClient.disconnect()
            showResult(getString(R.string.smb_scan_failed))
        } finally {
            SmbScanner.finish()
            isWorking.set(false)
            finish(notifyListeners = !aborted)
        }
    }

    private fun finish(notifyListeners: Boolean) {
        Handler(Looper.getMainLooper()).post {
            if (notifyListeners) {
                listeners.forEach { it() }
            }

            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun showProgress(folderCount: Int, mediaCount: Int, path: String) {
        val now = System.currentTimeMillis()
        if (now - lastProgressAt < PROGRESS_INTERVAL_MILLIS) {
            return
        }

        lastProgressAt = now
        showProgress(buildNotification(getString(R.string.smb_scan_progress, folderCount, mediaCount), path))
    }

    private fun showProgress(notification: Notification) {
        if (isQPlus()) {
            startForeground(PROGRESS_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(PROGRESS_NOTIFICATION_ID, notification)
        }
    }

    // the one notification that stays behind, for a scan that ended while the app was off
    // screen. A toast would be the app talking to nobody by then, and Android drops one from
    // the background anyway; it is still shown for whoever is looking
    private fun showResult(text: String) {
        toast(text)
        val notification = NotificationCompat.Builder(this, ensureChannel())
            .setSmallIcon(R.drawable.ic_storage_vector)
            .setContentTitle(text)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(RESULT_NOTIFICATION_ID, notification)
    }

    private fun buildNotification(text: String, folder: String?): Notification {
        val abort = PendingIntent.getService(
            this,
            0,
            Intent(this, SmbScanService::class.java).setAction(ACTION_ABORT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, ensureChannel())
            .setSmallIcon(R.drawable.ic_storage_vector)
            .setContentTitle(text)
            // a share is only counted by walking it, so there is no total to fill a bar with
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(0, getString(R.string.smb_scan_stop), abort)
            .apply {
                // which folder it is in is what says it is moving; the counts hold still for a
                // while when it is walking through folders that hold no media
                if (folder != null) {
                    setContentText(folder.removePrefix(SMB_PATH_PREFIX))
                }
            }
            .build()
    }

    private fun ensureChannel(): String {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(CHANNEL_ID, getString(R.string.smb_scan_channel), NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }

        return CHANNEL_ID
    }
}
