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
import org.fossify.gallery.extensions.rescanSmbFolders
import org.fossify.gallery.extensions.updateDirectoryPath
import org.fossify.gallery.helpers.PCloudApi
import org.fossify.gallery.helpers.PCloudException
import org.fossify.gallery.helpers.PCloudFileCache
import org.fossify.gallery.helpers.PCloudWriter
import org.fossify.gallery.helpers.RemoteScanScheduler
import org.fossify.gallery.helpers.SmbClient
import org.fossify.gallery.helpers.availableName
import java.io.File
import java.io.IOException
import java.io.OutputStream
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
// fires for a pCloud transfer.
//
// ONTO_SHARE takes pCloud's files straight into a folder of a share (#161). Each file passes
// through the device on its way, streamed from pCloud into the share with nothing kept here, so it
// takes as long as a download and an upload together
class PCloudTransferService : Service() {
    enum class Kind { UPLOAD, DOWNLOAD, WITHIN_PCLOUD, ONTO_SHARE }

    // sourcePaths are local paths for an upload and pCloud pseudo paths otherwise; the
    // destination is a folder of the other kind, a pCloud folder for WITHIN_PCLOUD, and a folder
    // of a share for ONTO_SHARE
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
            showProgress(buildNotification(progressText(job, index, total), index, total, progressDetail(job)))
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

                    // pCloud's own file goes only once the share has the whole of the copy
                    Kind.ONTO_SHARE -> {
                        copyOntoShare(path, job.destination)
                        if (!job.isCopy) {
                            writer.deleteFiles(listOf(path))
                        }
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
        // said once per job, after the destination has been brought up to date, so that it means
        // "the files are there and the lists would show them"
        Log.i(TAG, "${if (job.isCopy) "Copied" else "Moved"} $done of $total ${job.kind.name.lowercase()} to ${job.destination}, $failed failed")
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

            // the pCloud folders a move left were brought up to date by the writer as it deleted,
            // the same as a download that moves
            Kind.ONTO_SHARE -> rescanShareFolderAndWait(job.destination)
        }
    }

    // The share has no diff stream, so the only way the cache learns of what was just written is
    // to walk the folder again, at the same rank and with the same bounded wait as pCloud's
    private fun rescanShareFolderAndWait(folder: String) {
        val refreshed = CountDownLatch(1)
        rescanSmbFolders(listOf(folder), reportCounts = false, priority = RemoteScanScheduler.PRIORITY_TRANSFER) { refreshed.countDown() }
        if (!refreshed.await(SCAN_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "the refresh of $folder did not finish in time; the next scan picks it up")
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
        val target = availableName(destinationFolder, path.getFilenameFromPath()) { File(it).exists() }
        val out = getFileOutputStreamSync(target, path.getMimeType()) ?: throw IOException("Could not open $target for writing")
        out.use { writePCloudFile(path, it) }

        modifiedOf(path)?.let { File(target).setLastModified(it) }
        return target
    }

    // Writes a file of pCloud into a folder of a share, the way a copy onto the share from the
    // device writes: the folder made first, a free name, nothing written over, and a write that
    // broke off taken back off the share by create(). Streamed straight through, nothing kept on
    // the device; the medium's modification time is put on the copy afterwards
    private fun copyOntoShare(path: String, destinationFolder: String) {
        SmbClient.createFolder(this, destinationFolder)
        val target = availableName(destinationFolder, path.getFilenameFromPath()) { SmbClient.fileExists(this, it) }
        SmbClient.create(this, target) { writePCloudFile(path, it) }
        modifiedOf(path)?.let { SmbClient.setModified(this, target, it) }
    }

    // The file's bytes, from the local copy when the fullscreen view already fetched one, from
    // pCloud otherwise. A body that ends short of the length pCloud announced is refused rather
    // than written out as a whole file
    private fun writePCloudFile(path: String, output: OutputStream) {
        val cached = PCloudFileCache(this).peek(path)
        if (cached != null) {
            cached.inputStream().use { it.copyTo(output) }
            return
        }

        val item = pCloudItemsDB.getItem(path) ?: throw IOException("$path is not in the pCloud cache")
        val url = PCloudApi.getFileLink(config.pCloudApiHost, config.pCloudAccessToken, item.itemId)
        PCloudApi.download(url).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("pCloud answered HTTP ${response.code}")
            }

            val expected = response.body.contentLength()
            val copied = response.body.byteStream().copyTo(output)
            if (expected >= 0 && copied != expected) {
                throw IOException("pCloud sent $copied bytes of $expected for $path")
            }
        }
    }

    // the modification time is what the gallery sorts by; pCloud's is the upload time, so the
    // one the row has is put on a copy instead
    private fun modifiedOf(path: String): Long? =
        mediaDB.getMediaFromPath(path.getParentPath()).firstOrNull { it.path == path }?.modified?.takeIf { it > 0 }

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
            Kind.ONTO_SHARE -> if (job.isCopy) R.string.smb_copying_to_share else R.string.smb_moving_to_share
        }

        return getString(id, done + 1, total)
    }

    // A file carried onto a share takes as long as a download and an upload together, which a
    // big video makes plain; the notification says why
    private fun progressDetail(job: Job): String? =
        if (job.kind == Kind.ONTO_SHARE) getString(R.string.transfer_through_device_detail) else null

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

    private fun buildNotification(text: String, done: Int, total: Int, detail: String? = null): Notification {
        return NotificationCompat.Builder(this, ensureChannel())
            .setSmallIcon(R.drawable.ic_cloud_vector)
            .setContentTitle(text)
            .setContentText(detail)
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
