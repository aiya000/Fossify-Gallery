package org.fossify.gallery.jobs

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
import org.fossify.commons.extensions.deleteFromMediaStore
import org.fossify.commons.extensions.getFileOutputStreamSync
import org.fossify.commons.extensions.getFilenameExtension
import org.fossify.commons.extensions.getFilenameFromPath
import org.fossify.commons.extensions.getMimeType
import org.fossify.commons.extensions.getParentPath
import org.fossify.commons.extensions.getSomeDocumentFile
import org.fossify.commons.extensions.rescanPaths
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.tryFastDocumentDelete
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isQPlus
import org.fossify.gallery.R
import org.fossify.gallery.extensions.addPathToDB
import org.fossify.gallery.extensions.config
import org.fossify.gallery.extensions.deleteDBPath
import org.fossify.gallery.extensions.mediaDB
import org.fossify.gallery.extensions.pCloudItemsDB
import org.fossify.gallery.extensions.rescanPCloudFolders
import org.fossify.gallery.extensions.updateDirectoryPath
import org.fossify.gallery.helpers.PCloudApi
import org.fossify.gallery.helpers.PCloudException
import org.fossify.gallery.helpers.PCloudFileCache
import org.fossify.gallery.helpers.PCloudWriter
import org.fossify.gallery.helpers.RemoteScanScheduler
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// Copies and moves between the device and pCloud, and within pCloud, as a foreground service
// with a progress notification: a folder of videos takes a while and should not die with the
// screen that asked for it. Jobs are handed over through enqueue() rather than in the Intent,
// a list of paths can be larger than an Intent may carry. One job runs at a time, in the
// order they came, and the service stops once the queue is empty.
//
// Every file is one unit of progress; there is no per-file byte count. What went wrong with
// one file does not stop the others, only a token pCloud no longer accepts does. Once a job
// is through, the folders it touched are brought up to date: a pCloud destination is
// rescanned, a local one is handed to the media scanner and the cache. The screens learn of
// the end through the listeners, the callback of copyMoveFilesToPickedDestination() never
// fires for a pCloud transfer
class PCloudTransferService : Service() {
    enum class Kind { UPLOAD, DOWNLOAD, WITHIN_PCLOUD }

    // sourcePaths are local paths for an upload and pCloud pseudo paths otherwise; the
    // destination is a folder of the other kind, or a pCloud folder for WITHIN_PCLOUD
    class Job(val kind: Kind, val sourcePaths: List<String>, val destination: String, val isCopy: Boolean)

    companion object {
        private const val TAG = "PCloudTransfer"
        private const val CHANNEL_ID = "pcloud_transfer"
        private const val PROGRESS_NOTIFICATION_ID = 7001
        private const val RESULT_NOTIFICATION_ID = 7002

        // how long a job waits for its refresh to come round in the scan queue before it gives
        // up on it and lets the next scan pick the folders up instead
        private const val SCAN_WAIT_MILLIS = 60_000L

        private val queue = ConcurrentLinkedQueue<Job>()
        private val isWorking = AtomicBoolean(false)
        private val listeners = CopyOnWriteArraySet<() -> Unit>()

        fun enqueue(context: Context, job: Job) {
            synchronized(queue) {
                queue.add(job)
            }

            ContextCompat.startForegroundService(context, Intent(context, PCloudTransferService::class.java))
        }

        // called on the main thread once a run of jobs is through, the folders already
        // brought up to date. A screen adds itself in onResume and leaves in onPause
        fun addListener(listener: () -> Unit) {
            listeners.add(listener)
        }

        fun removeListener(listener: () -> Unit) {
            listeners.remove(listener)
        }
    }

    // why the last file of a run failed, for the result notification
    private var lastFailure: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        showProgress(buildNotification(getString(R.string.pcloud_transfer_channel), 0, 0))

        val shouldStart = synchronized(queue) {
            isWorking.compareAndSet(false, true)
        }

        if (shouldStart) {
            ensureBackgroundThread {
                work()
            }
        }

        return START_NOT_STICKY
    }

    private fun work() {
        var transferred = 0
        var failed = 0
        lastFailure = null
        try {
            while (true) {
                val job = synchronized(queue) {
                    queue.poll().also {
                        if (it == null) {
                            isWorking.set(false)
                        }
                    }
                } ?: break

                val result = run(job)
                transferred += result.first
                failed += result.second
            }
        } finally {
            isWorking.set(false)
            if (transferred + failed > 0) {
                showResult(transferred, failed)
            }

            Handler(Looper.getMainLooper()).post {
                listeners.forEach { it() }
                // another run may have started on a job that came in meanwhile; it owns the
                // notification then and stops the service itself
                synchronized(queue) {
                    if (queue.isEmpty() && !isWorking.get()) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
    }

    // answers how many files went through and how many did not
    private fun run(job: Job): Pair<Int, Int> {
        val total = job.sourcePaths.size
        var done = 0
        var failed = 0
        val writer = PCloudWriter(this)
        val newLocalPaths = ArrayList<String>()

        for ((index, path) in job.sourcePaths.withIndex()) {
            showProgress(buildNotification(progressText(job, index, total), index, total))
            if (!config.isPCloudLoggedIn) {
                failed += total - index
                break
            }

            try {
                when (job.kind) {
                    Kind.UPLOAD -> {
                        writer.uploadFile(path, job.destination)
                        if (!job.isCopy) {
                            deleteLocalFile(path)
                        }
                    }

                    Kind.DOWNLOAD -> {
                        newLocalPaths.add(download(path, job.destination))
                        if (!job.isCopy) {
                            writer.deleteFiles(listOf(path))
                        }
                    }

                    Kind.WITHIN_PCLOUD -> if (job.isCopy) {
                        writer.copyFileTo(path, job.destination)
                    } else {
                        writer.moveFileTo(path, job.destination)
                    }
                }
                done++
            } catch (e: PCloudException) {
                failed++
                lastFailure = e.message
                Log.w(TAG, "$path: $e")
                if (e.requiresLogIn) {
                    config.clearPCloudAccount()
                    toast(R.string.pcloud_log_in_required)
                    failed += total - index - 1
                    break
                }
            } catch (e: Exception) {
                // the file may well have arrived all the same, an upload answers only after
                // the whole body went out; the log says what happened, the notification shows it
                failed++
                lastFailure = e.toString()
                Log.w(TAG, "$path failed", e)
            }
        }

        settle(job, newLocalPaths)
        return Pair(done, failed)
    }

    // brings the folders a job touched up to date, and waits for it, so that the listeners
    // fire once the lists would show the result
    private fun settle(job: Job, newLocalPaths: List<String>) {
        when (job.kind) {
            Kind.UPLOAD -> {
                rescanPCloudFoldersAndWait(listOf(job.destination))
                if (!job.isCopy) {
                    job.sourcePaths.map { it.getParentPath() }.distinct().forEach { updateDirectoryPath(it) }
                }
            }

            Kind.DOWNLOAD -> {
                if (newLocalPaths.isNotEmpty()) {
                    val latch = CountDownLatch(1)
                    rescanPaths(newLocalPaths) { latch.countDown() }
                    latch.await()
                    newLocalPaths.forEach { addPathToDB(it) }
                    updateDirectoryPath(job.destination)
                }
            }

            Kind.WITHIN_PCLOUD -> {
                val folders = if (job.isCopy) listOf(job.destination) else listOf(job.destination) + job.sourcePaths.map { it.getParentPath() }.distinct()
                rescanPCloudFoldersAndWait(folders)
            }
        }
    }

    // Refreshes the folders a transfer wrote to, and waits for it, so that the listeners fire
    // once the lists would show the result. It is queued at PRIORITY_TRANSFER, which goes to the
    // front of the scan queue and cuts short whatever is running: this is short, and what was
    // transferred stays out of sight until it has run, while the scan it interrupts is minutes
    // nobody is waiting on (#59). It used to spin on the scanner's lock instead, which is the
    // "hope they do not collide" the queue was built to replace.
    //
    // The wait is bounded all the same: a turn that somehow never comes must not hold up the
    // transfer's own report, and the next scan picks the folder up either way
    private fun rescanPCloudFoldersAndWait(paths: List<String>) {
        if (!config.isPCloudLoggedIn) {
            return
        }

        val refreshed = CountDownLatch(1)
        rescanPCloudFolders(paths, reportCounts = false, priority = RemoteScanScheduler.PRIORITY_TRANSFER) { refreshed.countDown() }
        if (!refreshed.await(SCAN_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "the refresh of ${paths.joinToString()} did not finish in time; the next scan picks it up")
        }
    }

    // Streams the file into the destination folder, from the local copy when the fullscreen
    // view already fetched one, from pCloud otherwise. A name that is taken there gets a
    // number appended. Answers the new local path
    private fun download(path: String, destinationFolder: String): String {
        val target = availableLocalName(destinationFolder, path.getFilenameFromPath())
        val out = getFileOutputStreamSync(target, path.getMimeType()) ?: throw IOException("Could not open $target for writing")
        out.use { output ->
            val cached = PCloudFileCache(this).peek(path)
            if (cached != null) {
                cached.inputStream().use { it.copyTo(output) }
            } else {
                val item = pCloudItemsDB.getItem(path) ?: throw IOException("$path is not in the pCloud cache")
                val url = PCloudApi.getFileLink(config.pCloudApiHost, config.pCloudAccessToken, item.itemId)
                PCloudApi.download(url).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("pCloud answered HTTP ${response.code}")
                    }

                    response.body.byteStream().copyTo(output)
                }
            }
        }

        // the modification time is what the gallery sorts by; pCloud's is the upload time
        mediaDB.getMediaFromPath(path.getParentPath()).firstOrNull { it.path == path }?.let { medium ->
            if (medium.modified > 0) {
                File(target).setLastModified(medium.modified)
            }
        }

        return target
    }

    private fun availableLocalName(folder: String, name: String): String {
        val first = "$folder/$name"
        if (!File(first).exists()) {
            return first
        }

        val extension = name.getFilenameExtension()
        val base = if (extension.isEmpty() || extension == name) name else name.removeSuffix(".$extension")
        var index = 1
        while (true) {
            val candidate = if (extension.isEmpty() || extension == name) "$folder/$base ($index)" else "$folder/$base ($index).$extension"
            if (!File(candidate).exists()) {
                return candidate
            }

            index++
        }
    }

    // the local half of a move to pCloud, once the upload is through: the file, its
    // MediaStore entry and its cache row. The screen that started the move already asked
    // for whatever storage permission the file's location needs
    private fun deleteLocalFile(path: String) {
        val file = File(path)
        var deleted = file.delete()
        if (!deleted) {
            deleted = tryFastDocumentDelete(path, false)
        }

        if (!deleted) {
            deleted = getSomeDocumentFile(path)?.delete() == true
        }

        if (!deleted && file.exists()) {
            throw IOException("Could not delete $path")
        }

        deleteFromMediaStore(path)
        deleteDBPath(path)
    }

    private fun progressText(job: Job, done: Int, total: Int): String {
        val id = when (job.kind) {
            Kind.UPLOAD -> R.string.pcloud_uploading
            Kind.DOWNLOAD -> R.string.pcloud_downloading
            Kind.WITHIN_PCLOUD -> if (job.isCopy) R.string.pcloud_copying else R.string.pcloud_moving
        }

        return getString(id, done + 1, total)
    }

    private fun showProgress(notification: Notification) {
        if (isQPlus()) {
            startForeground(PROGRESS_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(PROGRESS_NOTIFICATION_ID, notification)
        }
    }

    private fun showResult(transferred: Int, failed: Int) {
        val text = if (failed == 0) {
            getString(R.string.pcloud_transfer_done, transferred)
        } else {
            getString(R.string.pcloud_transfer_done_with_failures, transferred, failed)
        }

        toast(text)
        val notification = NotificationCompat.Builder(this, ensureChannel())
            .setSmallIcon(R.drawable.ic_cloud_vector)
            .setContentTitle(text)
            .setAutoCancel(true)
            .apply {
                // the reason of the last failure, where the toast has no room for it
                val failure = lastFailure
                if (failed > 0 && failure != null) {
                    setContentText(failure)
                    setStyle(NotificationCompat.BigTextStyle().bigText(failure))
                }
            }
            .build()
        getSystemService(NotificationManager::class.java).notify(RESULT_NOTIFICATION_ID, notification)
    }

    private fun buildNotification(text: String, done: Int, total: Int): Notification {
        return NotificationCompat.Builder(this, ensureChannel())
            .setSmallIcon(R.drawable.ic_cloud_vector)
            .setContentTitle(text)
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    private fun ensureChannel(): String {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(CHANNEL_ID, getString(R.string.pcloud_transfer_channel), NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }

        return CHANNEL_ID
    }
}
