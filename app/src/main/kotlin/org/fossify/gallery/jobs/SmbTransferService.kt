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
import org.fossify.commons.extensions.getFileInputStreamSync
import org.fossify.commons.extensions.getFileOutputStreamSync
import org.fossify.commons.extensions.getFilenameFromPath
import org.fossify.commons.extensions.getMimeType
import org.fossify.commons.extensions.getParentPath
import org.fossify.commons.extensions.getSomeDocumentFile
import org.fossify.commons.extensions.rescanPaths
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.tryFastDocumentDelete
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isQPlus
import org.fossify.gallery.R
import org.fossify.gallery.extensions.addPathToDB
import org.fossify.gallery.extensions.config
import org.fossify.gallery.extensions.deleteDBPath
import org.fossify.gallery.extensions.mediaDB
import org.fossify.gallery.extensions.rescanPCloudFolders
import org.fossify.gallery.extensions.rescanSmbFolders
import org.fossify.gallery.extensions.updateDirectoryPath
import org.fossify.gallery.helpers.PCloudException
import org.fossify.gallery.helpers.PCloudWriter
import org.fossify.gallery.helpers.RemoteScanScheduler
import org.fossify.gallery.helpers.SmbClient
import org.fossify.gallery.helpers.SmbFileCache
import org.fossify.gallery.helpers.SmbVideoCache
import org.fossify.gallery.helpers.SmbWriter
import org.fossify.gallery.helpers.availableName
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// Copies media off the network share and onto it, as a foreground service with a progress
// notification: a folder of videos is minutes over the network and should not die with the
// screen that asked for it. Built the same way as PCloudTransferService, for the same reasons
// -- jobs handed over through enqueue() rather than in the Intent, one job at a time in the
// order they came, the service stopping once the queue is empty.
//
// Copies and moves. A move to another storage is the copy with the source dropped once the copy
// has landed, one file at a time, so a run that stops halfway leaves the files it never reached
// where they were rather than half gone.
//
// A move within the share is none of that. WITHIN_SHARE is one request per file and no bytes at
// all, because the share can put a file in another folder itself -- see SmbClient.moveTo(). It
// goes through this service all the same, for the progress and for the one-job-at-a-time queue.
//
// A copy or a move onto the share starts from a file on the device: pCloud straight onto the
// share would have to stage the file the way a copy to pCloud does, which is not built (#28).
//
// Every file is one unit of progress. What went wrong with one file does not stop the others:
// a share drops a connection mid-folder often enough that giving up on the rest would be the
// wrong answer. Once a job is through the destination is brought up to date -- a local folder
// through the media scanner and the cache, a pCloud or share folder through a rescan -- and the
// screens learn of it through the listeners, because the callback of
// copyMoveFilesToPickedDestination() never fires for a transfer
class SmbTransferService : Service() {
    enum class Kind { TO_DEVICE, TO_PCLOUD, FROM_DEVICE, WITHIN_SHARE }

    // For TO_DEVICE and TO_PCLOUD the sourcePaths are pseudo paths on the share and the
    // destination is a folder on the device or on pCloud. FROM_DEVICE is the other way round:
    // the sources are files on the device and the destination is a folder on the share.
    // WITHIN_SHARE has the share on both sides.
    //
    // [isCopy] false leaves nothing behind on the side the files came from. WITHIN_SHARE is
    // always a move -- a copy within the share would have to read every byte back out and write
    // it again, and nobody has asked for that
    class Job(val kind: Kind, val sourcePaths: List<String>, val destination: String, val isCopy: Boolean = true)

    companion object {
        private const val TAG = "SmbTransfer"
        private const val CHANNEL_ID = "smb_transfer"
        private const val PROGRESS_NOTIFICATION_ID = 7003
        private const val RESULT_NOTIFICATION_ID = 7004

        // where a file bound for pCloud is staged; see stagedCopy()
        private const val STAGING_DIR = "smb-transfer"

        // how long a job waits for its refresh to come round in the scan queue before it gives
        // up on it and lets the next scan pick the folder up instead
        private const val SCAN_WAIT_MILLIS = 60_000L

        private val queue = ConcurrentLinkedQueue<Job>()
        private val isWorking = AtomicBoolean(false)
        private val listeners = CopyOnWriteArraySet<() -> Unit>()

        fun enqueue(context: Context, job: Job) {
            synchronized(queue) {
                queue.add(job)
            }

            ContextCompat.startForegroundService(context, Intent(context, SmbTransferService::class.java))
        }

        // called on the main thread once a run of jobs is through, the folders already brought
        // up to date. A screen adds itself in onResume and leaves in onPause
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
        showProgress(buildNotification(getString(R.string.smb_transfer_channel), 0, 0))

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
        var copied = 0
        var failed = 0
        // what the result is called. A run that only moved says so; one that copied anything at
        // all keeps the copy wording, since there is one line for the whole run
        var movesOnly = true
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

                movesOnly = movesOnly && !job.isCopy
                val result = run(job)
                copied += result.first
                failed += result.second
            }
        } finally {
            isWorking.set(false)
            if (copied + failed > 0) {
                showResult(copied, failed, movesOnly)
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
        val writer = SmbWriter(this)
        val newLocalPaths = ArrayList<String>()

        for ((index, path) in job.sourcePaths.withIndex()) {
            showProgress(buildNotification(progressText(job, index, total), index, total))
            if (!config.isSmbConfigured) {
                failed += total - index
                break
            }

            try {
                when (job.kind) {
                    // the share's own copy goes only once the new one is whole: what the writer
                    // drops here is the file this loop has just read to the end
                    Kind.TO_DEVICE -> {
                        newLocalPaths.add(copyToDevice(path, job.destination))
                        if (!job.isCopy) {
                            writer.deleteFiles(listOf(path))
                        }
                    }

                    Kind.TO_PCLOUD -> {
                        copyToPCloud(path, job.destination)
                        if (!job.isCopy) {
                            writer.deleteFiles(listOf(path))
                        }
                    }

                    Kind.FROM_DEVICE -> {
                        copyFromDevice(path, job.destination)
                        if (!job.isCopy) {
                            deleteLocalFile(path)
                        }
                    }

                    Kind.WITHIN_SHARE -> writer.moveFileTo(path, job.destination)
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
                failed++
                lastFailure = e.toString()
                Log.w(TAG, "$path failed", e)
            }
        }

        settle(job, newLocalPaths)
        // said once per job, after the destination has been brought up to date, so that it means
        // "the copies are there and the lists would show them". Everything else this service says
        // is a failure, and a log with nothing but failures in it cannot tell a copy that never
        // ran from one that went through
        val direction = when (job.kind) {
            Kind.FROM_DEVICE -> "onto the share"
            Kind.WITHIN_SHARE -> "within the share"
            else -> "off the share"
        }

        Log.i(TAG, "${if (job.isCopy) "Copied" else "Moved"} $done of $total $direction to ${job.destination}, $failed failed")
        return Pair(done, failed)
    }

    // brings the destination up to date, and waits for it, so that the listeners fire once the
    // lists would show the copies
    private fun settle(job: Job, newLocalPaths: List<String>) {
        when (job.kind) {
            Kind.TO_DEVICE -> {
                if (newLocalPaths.isNotEmpty()) {
                    val latch = CountDownLatch(1)
                    rescanPaths(newLocalPaths) { latch.countDown() }
                    latch.await()
                    newLocalPaths.forEach { addPathToDB(it) }
                    updateDirectoryPath(job.destination)
                }
            }

            // Nothing is walked for a move within the share. SmbWriter carried every row with
            // the file it moved, both folders included, which is what writeToShare() does for
            // every other write -- a walk would only confirm what the cache already says, and a
            // walk of a share is minutes
            Kind.WITHIN_SHARE -> Unit

            Kind.TO_PCLOUD -> {
                if (!config.isPCloudLoggedIn) {
                    return
                }

                // the same rank the pCloud transfer gives its own refresh: this is short, what
                // was copied stays out of sight until it has run, and the scan it cuts short is
                // minutes nobody is waiting on (#59). The wait is bounded all the same, so that
                // a turn that never comes cannot hold up the report
                val refreshed = CountDownLatch(1)
                rescanPCloudFolders(listOf(job.destination), reportCounts = false, priority = RemoteScanScheduler.PRIORITY_TRANSFER) {
                    refreshed.countDown()
                }

                if (!refreshed.await(SCAN_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                    Log.w(TAG, "the refresh of ${job.destination} did not finish in time; the next scan picks it up")
                }
            }

            Kind.FROM_DEVICE -> {
                // The share has no diff stream, so the only way the cache learns of what was
                // just written is to walk the folder again. Same rank and same bounded wait as
                // the pCloud side: what landed stays out of sight until it has run, and a turn
                // that never comes must not hold up the report
                val refreshed = CountDownLatch(1)
                rescanSmbFolders(listOf(job.destination), reportCounts = false, priority = RemoteScanScheduler.PRIORITY_TRANSFER) {
                    refreshed.countDown()
                }

                if (!refreshed.await(SCAN_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                    Log.w(TAG, "the refresh of ${job.destination} did not finish in time; the next scan picks it up")
                }

                // the folders on the device the files left; their rows count what is in them
                if (!job.isCopy) {
                    job.sourcePaths.map { it.getParentPath() }.distinct().forEach { updateDirectoryPath(it) }
                }
            }
        }
    }

    // Streams the file into a folder on the device. A name that is taken there gets a number.
    // A read that broke off part way takes its half-written file with it rather than leaving it
    // in the gallery, where nothing afterwards would tell it from a whole one. Answers the new
    // local path
    private fun copyToDevice(path: String, destinationFolder: String): String {
        val target = availableName(destinationFolder, path.getFilenameFromPath()) { File(it).exists() }
        val out = getFileOutputStreamSync(target, path.getMimeType()) ?: throw IOException("Could not open $target for writing")
        try {
            out.use { output ->
                readShareFile(path) { it.copyTo(output) }
            }
        } catch (e: Exception) {
            discardHalfWritten(target)
            throw e
        }

        keepShareModified(File(target), path)
        return target
    }

    // Puts the share's own modification time back on a copy of one of its files.
    //
    // The gallery sorts by that time, and a file written here and now carries the time it was
    // written: a copy that kept it would sort to the top of the destination instead of where the
    // original belongs. What the share said is what the scan recorded, so the row is where it
    // comes from rather than another round trip.
    //
    // It is not only the copy the user ends up with that needs it. A file bound for pCloud is
    // staged on the device first, and PCloudWriter names the mtime of the file it is handed --
    // so a staged copy that carries the time of the staging tells pCloud the wrong one, and
    // nothing afterwards can tell that it was ever different
    private fun keepShareModified(file: File, path: String) {
        val modified = mediaDB.getMediumByPath(path)?.modified ?: return
        if (modified > 0) {
            file.setLastModified(modified)
        }
    }

    // Best effort: the file was only just created here, so a plain delete gets it on internal
    // storage; the two SAF ways are what the rest of the app falls back to. Nothing has told
    // the media scanner about it yet, so there is no MediaStore entry to clear. A file that
    // will not go says so in the log rather than taking the copy's own error with it
    private fun discardHalfWritten(path: String) {
        val file = File(path)
        val deleted = file.delete() || tryFastDocumentDelete(path, false) || getSomeDocumentFile(path)?.delete() == true
        if (!deleted && file.exists()) {
            Log.w(TAG, "the half-written $path could not be removed")
        }
    }

    // Sends the file on to pCloud. It goes through a file on the device first: pCloud is told
    // the length up front, and the share does not promise one for a stream it is still reading
    private fun copyToPCloud(path: String, destinationFolder: String) {
        if (!config.isPCloudLoggedIn) {
            throw IOException(getString(R.string.pcloud_log_in_required))
        }

        val dir = File(File(cacheDir, STAGING_DIR), UUID.randomUUID().toString())
        try {
            val staged = stagedCopy(path, dir)
            PCloudWriter(this).uploadFile(staged.absolutePath, destinationFolder)
        } finally {
            // the cache survives the app being killed, so a staging directory left behind here
            // would stay until the system is short of space
            dir.deleteRecursively()
        }
    }

    // The file in a directory of its own, under its own name, because the name is what pCloud
    // stores it as. The caller drops the directory
    private fun stagedCopy(path: String, dir: File): File {
        dir.mkdirs()
        val staged = File(dir, path.getFilenameFromPath())
        staged.outputStream().use { output ->
            readShareFile(path) { it.copyTo(output) }
        }

        keepShareModified(staged, path)
        return staged
    }

    // Writes a file of the device into a folder on the share. The folder is made first: it is a
    // folder the user picked out of the cache, and the share need not still have it. A name that
    // is taken there gets a number, the same way a copy to the device does it.
    //
    // The share stamps a file it has just been handed with its own clock, so the device file's
    // modification time is put back on afterwards. The gallery sorts by that time, and a copy
    // that kept the share's would sort to the top of the folder instead of where it belongs --
    // the same reason keepShareModified() exists for the other direction
    private fun copyFromDevice(localPath: String, destinationFolder: String) {
        SmbClient.createFolder(this, destinationFolder)
        val target = availableName(destinationFolder, localPath.getFilenameFromPath()) { SmbClient.fileExists(this, it) }
        SmbClient.create(this, target) { output ->
            val input = getFileInputStreamSync(localPath) ?: throw IOException("Could not open $localPath for reading")
            input.use { it.copyTo(output) }
        }

        // 0 is what a file the device will not stat answers, an OTG or SAF path among them;
        // the share's own stamp is then the best there is
        val modified = File(localPath).lastModified()
        if (modified > 0) {
            SmbClient.setModified(this, target, modified)
        }
    }

    // The device half of a move onto the share, once the copy is through: the file, its MediaStore
    // entry and its cache row. The screen that started the move already asked for whatever storage
    // permission the file's location needs. The same as PCloudTransferService does it
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

    // Hands the file's bytes to [write], from a copy already on the device when there is one --
    // the fullscreen view and the video download both leave one behind -- and from the share
    // otherwise. A short read is refused rather than written out as a whole file: the share
    // ends the stream the same way whether it is done or the connection dropped
    private fun readShareFile(path: String, write: (InputStream) -> Unit) {
        val cached = SmbFileCache(this).peek(path) ?: SmbVideoCache(this).peek(path)
        if (cached != null) {
            cached.inputStream().use(write)
            return
        }

        SmbClient.open(this, path).use { open ->
            val expected = open.size
            val counting = CountingInputStream(open.inputStream())
            counting.use(write)
            if (counting.count != expected) {
                throw IOException("The share sent ${counting.count} bytes of $expected for $path")
            }
        }
    }

    private class CountingInputStream(private val wrapped: InputStream) : InputStream() {
        var count = 0L
            private set

        override fun read(): Int = wrapped.read().also { if (it >= 0) count++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int = wrapped.read(b, off, len).also { if (it > 0) count += it }

        override fun close() = wrapped.close()
    }

    private fun progressText(job: Job, done: Int, total: Int): String {
        val id = when (job.kind) {
            Kind.TO_DEVICE -> if (job.isCopy) R.string.smb_copying_to_device else R.string.smb_moving_to_device
            Kind.TO_PCLOUD -> if (job.isCopy) R.string.smb_copying_to_pcloud else R.string.smb_moving_to_pcloud
            Kind.FROM_DEVICE -> if (job.isCopy) R.string.smb_copying_to_share else R.string.smb_moving_to_share
            Kind.WITHIN_SHARE -> R.string.smb_moving_within_share
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

    private fun showResult(copied: Int, failed: Int, movesOnly: Boolean) {
        val text = when {
            failed == 0 && movesOnly -> getString(R.string.smb_transfer_moved, copied)
            failed == 0 -> getString(R.string.smb_transfer_done, copied)
            movesOnly -> getString(R.string.smb_transfer_moved_with_failures, copied, failed)
            else -> getString(R.string.smb_transfer_done_with_failures, copied, failed)
        }

        toast(text)
        val notification = NotificationCompat.Builder(this, ensureChannel())
            .setSmallIcon(R.drawable.ic_storage_vector)
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
            .setSmallIcon(R.drawable.ic_storage_vector)
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
            val channel = NotificationChannel(CHANNEL_ID, getString(R.string.smb_transfer_channel), NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }

        return CHANNEL_ID
    }
}
