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
import org.fossify.gallery.extensions.mediaDB
import org.fossify.gallery.helpers.SmbClient
import org.fossify.gallery.helpers.SmbVideoDuration
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

// Fills in how long the videos of one folder, or of one group of folders, are.
//
// The scan cannot do it: it walks listings and never opens a file, and asking the share for the
// length of all fourteen thousand videos on it would turn a walk of minutes into one of hours.
// The viewer does it for the one video it is playing. This is the deliberate middle: the user
// names a folder and waits for it, having been told how many files that means opening.
//
// A foreground service for the same reason RemoteScanService is one -- the work is minutes long
// and the system takes the network away from a process nobody is looking at, so a plain
// background thread dies as soon as the app is left. The notification is also where the
// progress can be read once it is, and carries the stop action
class SmbDurationService : Service() {
    companion object {
        private const val TAG = "SmbVideo"
        private const val CHANNEL_ID = "smb_durations"
        private const val PROGRESS_NOTIFICATION_ID = 7005
        private const val RESULT_NOTIFICATION_ID = 7006
        private const val EXTRA_PATHS = "paths"

        // the notification's stop action comes back in as this
        private const val ACTION_ABORT = "org.fossify.gallery.ABORT_SMB_DURATIONS"

        private const val PROGRESS_INTERVAL_MILLIS = 500L

        private val isWorking = AtomicBoolean(false)
        private val isAborted = AtomicBoolean(false)
        private val listeners = CopyOnWriteArraySet<() -> Unit>()

        // the videos to read, by full path. A folder holds tens of them and a group a few
        // hundred, which is well inside what an intent carries; the alternative, handing over
        // the folders and querying here, would race with whatever the screen has just shown
        fun start(context: Context, paths: List<String>) {
            val intent = Intent(context, SmbDurationService::class.java).putStringArrayListExtra(EXTRA_PATHS, ArrayList(paths))
            ContextCompat.startForegroundService(context, intent)
        }

        // called on the main thread once the run is through and the rows have been written, so
        // that a screen still showing those videos can draw the lengths
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
            isAborted.set(true)
            return START_NOT_STICKY
        }

        // a service started with startForegroundService() has to show its notification before
        // this returns, whatever it then decides to do
        showProgress(buildNotification(getString(R.string.smb_read_durations_progress, 0, 0), null))

        if (!isWorking.compareAndSet(false, true)) {
            // a second run would be a second set of file opens over the same share, competing
            // with the one already going. That run owns the notification and stops the service
            // when it is through, so this one only steps aside
            Log.w(TAG, "Lengths are already being read, not starting a second run")
            return START_NOT_STICKY
        }

        val paths = intent?.getStringArrayListExtra(EXTRA_PATHS).orEmpty()
        if (paths.isEmpty()) {
            isWorking.set(false)
            finish(notifyListeners = false)
            return START_NOT_STICKY
        }

        isAborted.set(false)
        ensureBackgroundThread {
            work(paths)
        }

        return START_NOT_STICKY
    }

    private fun work(paths: List<String>) {
        var read = 0
        var failed = 0
        try {
            for ((index, path) in paths.withIndex()) {
                if (isAborted.get()) {
                    Log.i(TAG, "Reading the lengths was called off after $index of ${paths.size}")
                    break
                }

                showProgress(index, paths.size, path)
                // written one at a time on purpose: a run that is called off, or that loses the
                // share halfway, keeps everything it has read so far
                val seconds = SmbVideoDuration.readSeconds(this, path)
                if (seconds > 0) {
                    mediaDB.updateVideoDuration(path, seconds)
                    read++
                } else {
                    // a file that cannot be read is not worth stopping for -- a share holds
                    // videos in containers the device has no parser for, and one of them says
                    // nothing about the next
                    failed++
                }
            }

            Log.i(TAG, "Read the length of $read videos, $failed could not be read")
            when {
                read == 0 && failed > 0 -> showResult(getString(R.string.smb_read_durations_failed))
                failed > 0 -> showResult(getString(R.string.smb_read_durations_done_with_failed, read, failed))
                else -> showResult(getString(R.string.smb_read_durations_done, read))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the lengths of the videos on the share", e)
            // the usual failure is a session the server has given up on; dropping the
            // connection means the next run opens a fresh one instead of failing the same way
            SmbClient.disconnect()
            showResult(getString(R.string.smb_read_durations_failed))
        } finally {
            isWorking.set(false)
            // whatever was read is worth redrawing for, even after a run that was called off
            finish(notifyListeners = true)
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

    private fun showProgress(done: Int, total: Int, path: String) {
        val now = System.currentTimeMillis()
        if (now - lastProgressAt < PROGRESS_INTERVAL_MILLIS) {
            return
        }

        lastProgressAt = now
        showProgress(buildNotification(getString(R.string.smb_read_durations_progress, done, total), path.substringAfterLast('/'), done, total))
    }

    private fun showProgress(notification: Notification) {
        if (isQPlus()) {
            startForeground(PROGRESS_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(PROGRESS_NOTIFICATION_ID, notification)
        }
    }

    // the one notification that stays behind, for a run that ended while the app was off screen
    private fun showResult(text: String) {
        toast(text)
        val notification = NotificationCompat.Builder(this, ensureChannel())
            .setSmallIcon(R.drawable.ic_storage_vector)
            .setContentTitle(text)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(RESULT_NOTIFICATION_ID, notification)
    }

    private fun buildNotification(text: String, filename: String?, done: Int = 0, total: Int = 0): Notification {
        val abort = PendingIntent.getService(
            this,
            0,
            Intent(this, SmbDurationService::class.java).setAction(ACTION_ABORT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, ensureChannel())
            .setSmallIcon(R.drawable.ic_storage_vector)
            .setContentTitle(text)
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(0, getString(R.string.smb_scan_stop), abort)
            .apply {
                if (filename != null) {
                    setContentText(filename)
                }
            }
            .build()
    }

    private fun ensureChannel(): String {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(CHANNEL_ID, getString(R.string.smb_read_durations_channel), NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }

        return CHANNEL_ID
    }
}
