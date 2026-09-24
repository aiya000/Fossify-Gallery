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
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isQPlus
import org.fossify.gallery.R
import org.fossify.gallery.extensions.config
import org.fossify.gallery.helpers.PCloudException
import org.fossify.gallery.helpers.PCloudScanAbortedException
import org.fossify.gallery.helpers.PCloudScanner
import org.fossify.gallery.helpers.RemoteScanScheduler
import org.fossify.gallery.helpers.SMB_PATH_PREFIX
import org.fossify.gallery.helpers.SmbClient
import org.fossify.gallery.helpers.SmbScanAbortedException
import org.fossify.gallery.helpers.SmbScanner
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

// Works through RemoteScanScheduler's queue, one scan at a time, as a foreground service.
//
// It took over from SmbScanService, which was the same thing for the share alone. A scan of any
// remote storage takes minutes and a plain background thread does not survive the user leaving the
// app: the system takes the network away from a process nobody is looking at, and the scan then
// fails on whatever it had got to. Switching to another app for a moment was enough to kill one.
// The share learned this first; pCloud's scans ran on a plain thread until now and had the same
// bug, which is half of what #59 was about.
//
// The notification is also the only place the progress can be read once the app is off screen,
// which is where a long scan spends most of its time. A share has no total to count towards, so it
// shows what has been found so far and the folder it is in -- enough to tell a slow scan from a
// stuck one -- and its stop action calls off the running scan and empties the queue with it.
//
// A single folder is one request and is over long before the app could be left, but it goes
// through the queue too: it used to be dropped silently whenever a whole-storage scan held the
// lock, which is exactly the "hope they do not collide" this replaced.
class RemoteScanService : Service() {
    companion object {
        private const val TAG = "RemoteScan"
        private const val CHANNEL_ID = "remote_scan"
        private const val PROGRESS_NOTIFICATION_ID = 7003
        private const val RESULT_NOTIFICATION_ID = 7004

        // the notification's stop action comes back in as this
        private const val ACTION_ABORT = "org.fossify.gallery.ABORT_REMOTE_SCAN"

        // a notification redrawn on every folder would be redrawn hundreds of times a minute,
        // and the system throws away updates that come faster than it cares to draw anyway
        private const val PROGRESS_INTERVAL_MILLIS = 500L

        private val isWorking = AtomicBoolean(false)
        private val listeners = CopyOnWriteArraySet<() -> Unit>()

        // Starts draining the queue. Called after every submit: when a drain is already running
        // it picks the new request up on its own, so a second start costs nothing
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RemoteScanService::class.java))
        }

        // called on the main thread once the queue has run dry and the caches have been written.
        // A screen adds itself in onResume and leaves in onPause, the way it does for a transfer
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
            // the user stopping a scan wants the network left alone, not the next request
            // started on its heels, so the queue goes with it
            RemoteScanScheduler.abortAll()
            return START_NOT_STICKY
        }

        // a service started with startForegroundService() has to show its notification before
        // this returns, whatever it then decides to do
        showProgress(buildNotification(getString(R.string.remote_scan_starting), null))

        if (!isWorking.compareAndSet(false, true)) {
            // a drain is already running here; it owns the notification, will take the request
            // that was just queued, and stops the service once the queue is empty
            return START_NOT_STICKY
        }

        ensureBackgroundThread {
            drain()
        }

        return START_NOT_STICKY
    }

    // Takes requests until there are none left. Letting go of the claim and finding the queue
    // refilled is not a mistake: a request submitted between the last empty read and the release
    // would otherwise sit there with nobody to run it
    private fun drain() {
        var wroteAnything = false
        while (true) {
            while (true) {
                val request = RemoteScanScheduler.takeNext() ?: break
                try {
                    wroteAnything = run(request) || wroteAnything
                } finally {
                    RemoteScanScheduler.finished()
                }
            }

            isWorking.set(false)
            if (!RemoteScanScheduler.hasWork() || !isWorking.compareAndSet(false, true)) {
                break
            }
        }

        finish(notifyListeners = wroteAnything)
    }

    // Runs the one request, and answers whether it wrote anything worth rebuilding a screen for.
    // A request that was called off wrote nothing and says so, which is also why its onDone does
    // not run: the screen that outranked it is reloading on its own
    private fun run(request: RemoteScanScheduler.Request) = when (request.storage) {
        RemoteScanScheduler.Storage.PCLOUD -> runPCloud(request)
        RemoteScanScheduler.Storage.SMB -> runSmb(request)
    }

    private fun runPCloud(request: RemoteScanScheduler.Request): Boolean {
        if (!config.isPCloudLoggedIn) {
            request.onDone?.invoke()
            return false
        }

        val scanner = PCloudScanner(this)
        if (!PCloudScanner.start(scanner)) {
            // nothing outside this queue claims the scanner any more, so this should not happen;
            // the claim is kept because it is also what registers the scan for abortCurrent(),
            // and answering false is cheaper than finding out the hard way that something does
            Log.w(TAG, "the pCloud scanner is held elsewhere, not starting a second scan")
            request.onDone?.invoke()
            return false
        }

        showProgress(buildNotification(getString(if (request.newFoldersOnly) R.string.pcloud_finding_new_folders else R.string.pcloud_rescanning), null))
        var wrote = false
        try {
            if (request.newFoldersOnly) {
                val result = scanner.scanNewFolders()
                // said even when the counts were not asked for, like the share's walk: the log
                // is what a script reads, and the question was "what is new"
                Log.i(TAG, "Found ${result.folderCount} new folders on pCloud, ${result.mediaCount} files")
                if (request.reportCounts) {
                    showResult(getString(R.string.pcloud_new_folders_done, result.folderCount, result.mediaCount))
                }
            } else if (request.isWholeStorage) {
                val result = if (request.full) scanner.scanAll() else scanner.sync()
                if (request.reportCounts) {
                    showResult(getString(R.string.pcloud_rescan_done, result.folderCount, result.mediaCount))
                }
            } else {
                var folderCount = 0
                var mediaCount = 0
                request.folders.forEach { path ->
                    scanner.scanFolder(path)?.let {
                        folderCount += it.folderCount
                        mediaCount += it.mediaCount
                    }
                }

                if (request.reportCounts) {
                    showResult(getString(R.string.pcloud_rescan_done, folderCount, mediaCount))
                }
            }

            wrote = true
        } catch (e: PCloudScanAbortedException) {
            Log.i(TAG, "the pCloud scan was called off; nothing was written")
        } catch (e: PCloudException) {
            if (e.requiresLogIn) {
                config.clearPCloudAccount()
                toast(R.string.pcloud_log_in_required)
            } else {
                showErrorToast(e)
            }
        } catch (e: Exception) {
            showErrorToast(e)
        } finally {
            PCloudScanner.finish()
        }

        if (wrote) {
            request.onDone?.invoke()
        }

        return wrote
    }

    private fun runSmb(request: RemoteScanScheduler.Request): Boolean {
        if (!config.isSmbConfigured) {
            request.onDone?.invoke()
            return false
        }

        val scanner = SmbScanner(this)
        if (!SmbScanner.start(scanner)) {
            Log.w(TAG, "the share scanner is held elsewhere, not starting a second scan")
            request.onDone?.invoke()
            return false
        }

        showProgress(buildNotification(getString(if (request.newFoldersOnly) R.string.smb_finding_new_folders else R.string.smb_rescanning), null))
        var wrote = false
        try {
            if (request.newFoldersOnly) {
                val result = scanner.scanNewFolders { newFolders, _, path -> showNewFoldersProgress(newFolders, path) }
                Log.i(
                    TAG,
                    "Found ${result.folderCount} new folders on the share, ${result.mediaCount} files, ${result.skippedFolderCount} folders skipped"
                )

                when {
                    result.skippedFolderCount > 0 -> showResult(
                        getString(R.string.smb_new_folders_done_with_skipped, result.folderCount, result.mediaCount, result.skippedFolderCount)
                    )

                    request.reportCounts -> showResult(getString(R.string.smb_new_folders_done, result.folderCount, result.mediaCount))
                }
            } else if (request.isWholeStorage) {
                val result = scanner.scanAll { folders, media, path -> showProgress(folders, media, path) }
                // a scan that went through says so even when the counts were not asked for. How
                // one ended is otherwise only visible in what it wrote, which is no help when the
                // question is whether it wrote at all
                Log.i(
                    TAG,
                    "Walked the share: ${result.folderCount} folders, ${result.mediaCount} files, ${result.skippedFolderCount} folders skipped"
                )

                when {
                    // folders the walk could not get into are what failed about this scan, so
                    // they are said even when the counts were not asked for
                    result.skippedFolderCount > 0 -> showResult(
                        getString(R.string.smb_rescan_done_with_skipped, result.folderCount, result.mediaCount, result.skippedFolderCount)
                    )

                    request.reportCounts -> showResult(getString(R.string.smb_rescan_done, result.folderCount, result.mediaCount))
                }
            } else {
                var folderCount = 0
                var mediaCount = 0
                request.folders.forEach { path ->
                    val result = scanner.scanFolder(path)
                    folderCount += result.folderCount
                    mediaCount += result.mediaCount
                }

                Log.i(TAG, "Rescanned ${request.folders.size} folders of the share: $folderCount folders, $mediaCount files")
                if (request.reportCounts) {
                    showResult(getString(R.string.smb_rescan_done, folderCount, mediaCount))
                }
            }

            wrote = true
        } catch (e: SmbScanAbortedException) {
            // the screen that outranked it is reloading on its own, and nothing was written.
            // Nothing is said to the user about it, so the log is the only record that the walk
            // happened at all and threw its result away
            Log.i(TAG, "The walk of the share was called off; nothing was written")
        } catch (e: Exception) {
            Log.w(TAG, "A scan of the network share failed", e)
            // the most common failure is a session the server has given up on; dropping the
            // connection means the next scan opens a fresh one instead of failing the same way
            SmbClient.disconnect()
            showResult(getString(R.string.smb_scan_failed))
        } finally {
            SmbScanner.finish()
        }

        if (wrote) {
            request.onDone?.invoke()
        }

        return wrote
    }

    private fun finish(notifyListeners: Boolean) {
        Handler(Looper.getMainLooper()).post {
            if (notifyListeners) {
                listeners.forEach { it() }
            }

            // A request that arrived in the moment this drain let go of the claim starts a drain
            // of its own, and stopping the service now would take the notification -- and the
            // foreground state the network depends on -- away from work that is still running.
            // Whichever drain ends with nothing behind it is the one that stops the service
            if (isWorking.get() || RemoteScanScheduler.hasWork()) {
                return@post
            }

            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun showProgress(folderCount: Int, mediaCount: Int, path: String) =
        showProgressNowAndThen(getString(R.string.smb_scan_progress, folderCount, mediaCount), path)

    private fun showNewFoldersProgress(newFolderCount: Int, path: String) =
        showProgressNowAndThen(getString(R.string.smb_new_folders_progress, newFolderCount), path)

    private fun showProgressNowAndThen(text: String, path: String) {
        val now = System.currentTimeMillis()
        if (now - lastProgressAt < PROGRESS_INTERVAL_MILLIS) {
            return
        }

        lastProgressAt = now
        showProgress(buildNotification(text, path))
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
            Intent(this, RemoteScanService::class.java).setAction(ACTION_ABORT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, ensureChannel())
            .setSmallIcon(R.drawable.ic_storage_vector)
            .setContentTitle(text)
            // a remote storage is only counted by walking it, so there is no total to fill a
            // bar with
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
            val channel = NotificationChannel(CHANNEL_ID, getString(R.string.remote_scan_channel), NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }

        return CHANNEL_ID
    }
}
