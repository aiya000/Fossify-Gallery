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
import org.fossify.commons.extensions.formatSize
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isQPlus
import org.fossify.gallery.R
import org.fossify.gallery.activities.ViewPagerActivity
import org.fossify.gallery.helpers.PATH
import org.fossify.gallery.helpers.QUEUE_PATHS
import org.fossify.gallery.helpers.SmbClient
import org.fossify.gallery.helpers.SmbVideoCache
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

// Fetches one video off the share in full, so that it can be watched from the device instead of
// being read as it plays. Streaming is still what a video does by default -- it starts at once
// and costs only what is watched -- but a share that cannot keep up stalls part way through, and
// then having the whole file in hand first is the only thing that helps.
//
// A foreground service for the same reason RemoteScanService and SmbDurationService are: the system
// takes the network away from a process nobody is looking at, and a gigabyte over a slow share
// is minutes of work that the user will not sit and watch. The notification carries the progress
// and the stop action, so the download survives leaving the app
class SmbDownloadService : Service() {
    companion object {
        private const val TAG = "SmbVideo"
        private const val CHANNEL_ID = "smb_downloads"
        private const val PROGRESS_NOTIFICATION_ID = 7007
        private const val RESULT_NOTIFICATION_ID = 7008
        private const val EXTRA_PATHS = "paths"

        // the videos to watch afterwards, which is not the same list: one already on the device
        // is not fetched again, but it is still part of what was asked for
        private const val EXTRA_PLAYLIST = "playlist"

        // the notification's stop action comes back in as this
        private const val ACTION_ABORT = "org.fossify.gallery.ABORT_SMB_DOWNLOAD"

        private const val PROGRESS_INTERVAL_MILLIS = 500L
        private const val BUFFER_BYTES = 256 * 1024

        private val isWorking = AtomicBoolean(false)
        private val isAborted = AtomicBoolean(false)
        private val listeners = CopyOnWriteArraySet<Listener>()

        @Volatile
        private var workingPath: String? = null

        // where the video being fetched sits in the run, so the notification can say "3 of 12"
        @Volatile
        private var queuePosition = 0

        @Volatile
        private var queueSize = 0

        // One run at a time, and one video at a time inside it: a second read over the same
        // share would compete with the one already going, which is the very thing the user is
        // here to avoid. Returns false when a run is already going, so the caller can say so.
        //
        // The order of the list is the order they are fetched in, and the caller decides it --
        // from the grid that is the order the user tapped them in
        fun start(context: Context, paths: List<String>, playlist: List<String> = emptyList()): Boolean {
            if (isWorking.get()) {
                return false
            }

            val intent = Intent(context, SmbDownloadService::class.java)
                .putStringArrayListExtra(EXTRA_PATHS, ArrayList(paths))
                .putStringArrayListExtra(EXTRA_PLAYLIST, ArrayList(playlist))
            ContextCompat.startForegroundService(context, intent)
            return true
        }

        // the video being fetched right now, so a viewer that comes back to it can pick the
        // progress up again instead of offering to start a second download
        fun downloadingPath(): String? = workingPath

        fun addListener(listener: Listener) {
            listeners.add(listener)
        }

        fun removeListener(listener: Listener) {
            listeners.remove(listener)
        }
    }

    // what the viewer watches to draw the progress over the video it is showing. Both are called
    // on the main thread
    interface Listener {
        fun onSmbDownloadProgress(path: String, done: Long, total: Long)

        // file is null when the download failed or was called off
        fun onSmbDownloadFinished(path: String, file: File?)
    }

    private var lastProgressAt = 0L
    private var playlist: List<String> = emptyList()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_ABORT) {
            isAborted.set(true)
            return START_NOT_STICKY
        }

        // a service started with startForegroundService() has to show its notification before
        // this returns, whatever it then decides to do
        showProgress(buildNotification(getString(R.string.smb_download_preparing), null))

        val paths = intent?.getStringArrayListExtra(EXTRA_PATHS).orEmpty()
        if (paths.isEmpty()) {
            stop()
            return START_NOT_STICKY
        }

        if (!isWorking.compareAndSet(false, true)) {
            Log.w(TAG, "A video is already being downloaded, not starting a second run")
            return START_NOT_STICKY
        }

        playlist = intent?.getStringArrayListExtra(EXTRA_PLAYLIST).orEmpty()
        isAborted.set(false)
        ensureBackgroundThread {
            work(paths)
        }

        return START_NOT_STICKY
    }

    private fun work(paths: List<String>) {
        val cache = SmbVideoCache(this)
        var done = 0
        var failed = 0
        try {
            for ((index, path) in paths.withIndex()) {
                if (isAborted.get()) {
                    Log.i(TAG, "The downloads were called off after $index of ${paths.size}")
                    break
                }

                workingPath = path
                queuePosition = index
                queueSize = paths.size
                // each video is finished and reported before the next one starts, so a run that
                // is called off, or that loses the share halfway, keeps everything it has got
                val file = downloadOne(cache, path)
                finished(path, file)
                when {
                    file != null -> done++
                    isAborted.get() -> Unit
                    else -> failed++
                }
            }

            showResult(resultText(done, failed, paths))
        } finally {
            // a copy that has just been written is the newest thing here, so the sweep can only
            // take what has really gone a day unwatched
            cache.sweepExpired()
            workingPath = null
            isWorking.set(false)
            stop()
        }
    }

    // null when the video could not be fetched, or when the run was called off part way
    private fun downloadOne(cache: SmbVideoCache, path: String): File? {
        return try {
            val target = cache.targetOf(path)
            when {
                // no scanned row, so there is no name to give the copy. A rescan is what fixes
                // it, and there is nothing the user can do about it from here
                target == null -> {
                    Log.w(TAG, "No scanned row for $path, so it cannot be downloaded")
                    null
                }

                target.isFile && target.length() > 0 -> target
                else -> download(path, target)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not download $path from the share", e)
            // the usual failure is a session the server has given up on; dropping the connection
            // means the next video opens a fresh one instead of failing the same way
            SmbClient.disconnect()
            null
        }
    }

    private fun resultText(done: Int, failed: Int, paths: List<String>): String = when {
        done == 0 -> getString(R.string.smb_download_failed)
        // a run started from a folder or a group has somewhere to go afterwards, and the
        // notification is what takes the user there
        playlist.isNotEmpty() && failed > 0 -> getString(R.string.smb_download_done_with_failed_play, done, failed)
        playlist.isNotEmpty() -> getString(R.string.smb_download_done_play, done)
        // the single video the viewer's menu asks for is worth naming; a selection is not
        done == 1 && paths.size == 1 -> getString(R.string.smb_download_done, paths.first().substringAfterLast('/'))
        failed > 0 -> getString(R.string.smb_download_done_with_failed, done, failed)
        else -> getString(R.string.smb_download_done_many, done)
    }

    // returns the finished copy, or null when the download was called off part way
    private fun download(path: String, target: File): File? {
        val cache = SmbVideoCache(this)
        val partial = File(cache.ensureDir(), "${target.name}${SmbVideoCache.PARTIAL_SUFFIX}")
        var completed = false
        try {
            SmbClient.open(this, path).use { open ->
                val total = open.size
                var done = 0L
                partial.outputStream().use { out ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    val input = open.inputStream()
                    while (true) {
                        if (isAborted.get()) {
                            Log.i(TAG, "The download of $path was called off after $done of $total bytes")
                            return null
                        }

                        val read = input.read(buffer)
                        if (read < 0) {
                            break
                        }

                        out.write(buffer, 0, read)
                        done += read
                        reportProgress(path, done, total)
                    }
                }

                // a connection that drops part way through just ends the stream. Renaming a
                // short read into place would leave a torn video under a name that only changes
                // when the file on the share does, so it would be played from then on
                if (done != total) {
                    throw IOException("The share sent $done bytes of $total for $path")
                }
            }

            if (!partial.renameTo(target)) {
                throw IOException("Could not move the downloaded video into place")
            }

            completed = true
            Log.i(TAG, "Downloaded $path to ${target.name}")
            showResult(getString(R.string.smb_download_done, path.substringAfterLast('/')))
            return target
        } finally {
            if (!completed) {
                partial.delete()
            }
        }
    }

    private fun finished(path: String, file: File?) {
        Handler(Looper.getMainLooper()).post {
            listeners.forEach { it.onSmbDownloadFinished(path, file) }
        }
    }

    private fun stop() {
        Handler(Looper.getMainLooper()).post {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // a buffer of a quarter megabyte means thousands of these over one video, so both the
    // notification and the viewer are told twice a second rather than on every block
    private fun reportProgress(path: String, done: Long, total: Long) {
        val now = System.currentTimeMillis()
        if (now - lastProgressAt < PROGRESS_INTERVAL_MILLIS) {
            return
        }

        lastProgressAt = now
        val bytes = getString(R.string.smb_download_progress, done.formatSize(), total.formatSize())
        // one video says how far through the file it is; a selection says which video as well,
        // since that is what the user is waiting on
        val text = if (queueSize > 1) {
            getString(R.string.smb_download_progress_queue, queuePosition + 1, queueSize, done.formatSize(), total.formatSize())
        } else {
            bytes
        }

        showProgress(buildNotification(text, path.substringAfterLast('/'), done, total))
        Handler(Looper.getMainLooper()).post {
            listeners.forEach { it.onSmbDownloadProgress(path, done, total) }
        }
    }

    private fun showProgress(notification: Notification) {
        if (isQPlus()) {
            startForeground(PROGRESS_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(PROGRESS_NOTIFICATION_ID, notification)
        }
    }

    // the one notification that stays behind, for a run that ended while the app was off screen.
    // The toast is for when it did not, which is the usual case for a single video
    private fun showResult(text: String) {
        toast(text)
        val notification = NotificationCompat.Builder(this, ensureChannel())
            .setSmallIcon(R.drawable.ic_storage_vector)
            .setContentTitle(text)
            .setAutoCancel(true)
            .setContentIntent(playlistIntent())
            .build()
        getSystemService(NotificationManager::class.java).notify(RESULT_NOTIFICATION_ID, notification)
    }

    // What "then play them" hangs off. The screen is never taken over on its own -- a run of
    // minutes ends whenever it ends, and by then the user may be somewhere else entirely -- so
    // the finished notification is what opens the viewer, on the whole list and in its order
    private fun playlistIntent(): PendingIntent? {
        if (playlist.isEmpty()) {
            return null
        }

        val intent = Intent(this, ViewPagerActivity::class.java).apply {
            putExtra(PATH, playlist.first())
            putStringArrayListExtra(QUEUE_PATHS, ArrayList(playlist))
            action = Intent.ACTION_VIEW
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        return PendingIntent.getActivity(
            this,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildNotification(text: String, filename: String?, done: Long = 0, total: Long = 0): Notification {
        val abort = PendingIntent.getService(
            this,
            0,
            Intent(this, SmbDownloadService::class.java).setAction(ACTION_ABORT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // a video runs to gigabytes, which does not fit the Int the progress bar takes, so it is
        // drawn as a percentage instead
        val percent = if (total > 0) ((done * 100) / total).toInt() else 0
        return NotificationCompat.Builder(this, ensureChannel())
            .setSmallIcon(R.drawable.ic_storage_vector)
            .setContentTitle(text)
            .setProgress(100, percent, total == 0L)
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
            val channel = NotificationChannel(CHANNEL_ID, getString(R.string.smb_download_channel), NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }

        return CHANNEL_ID
    }
}
