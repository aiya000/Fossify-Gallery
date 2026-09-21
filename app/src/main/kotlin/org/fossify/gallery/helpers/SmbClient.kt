package org.fossify.gallery.helpers

import android.content.Context
import android.util.Log
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msdtyp.FileTime
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileBasicInformation
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import org.fossify.gallery.extensions.config
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

// The one way to the configured SMB share. A connection is opened when it is first needed and
// kept for the next caller: a scan, the grid's thumbnails and the viewer all go through here,
// and opening a session per file would cost a handshake each time.
//
// Everything in here blocks and talks to the network, so none of it may be called on the main
// thread. A share that is not reachable has to fail fast rather than hold the folder list, so
// the timeouts are short by the standards of a file protocol; a share lives on the local
// network, where a second is already a long time
object SmbClient {
    // what a path inside the share is separated by. The pseudo paths use "/" like every other
    // path in the app, so they are translated on the way in
    private const val SEPARATOR = '\\'

    private val smbConfig = SmbConfig.builder()
        .withTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .withSoTimeout(SOCKET_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val lock = Any()

    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private var share: DiskShare? = null

    // what the live connection was opened with; a settings change is noticed by comparing it
    private var openedWith: Credentials? = null

    private data class Credentials(
        val host: String, val port: Int, val shareName: String, val user: String, val password: String, val domain: String
    )

    class Entry(val name: String, val isFolder: Boolean, val size: Long, val modified: Long)

    // One file open for reading, closed by close(). The share hands out an InputStream and
    // random access over the same handle, so both are kept together
    class OpenFile(private val file: File) : AutoCloseable {
        val size: Long
            get() = file.fileInformation.standardInformation.endOfFile

        fun inputStream(): InputStream = file.inputStream

        // Reads up to len bytes from offset, the shape MediaDataSource wants. -1 at the end of
        // the file, which is what smbj answers for a read past it
        fun readAt(offset: Long, buffer: ByteArray, bufferOffset: Int, len: Int) = file.read(buffer, offset, bufferOffset, len)

        override fun close() = file.close()
    }

    // Answers the share, connecting if there is no live one. Throws an IOException when the
    // share cannot be reached and IllegalStateException when nothing is configured
    private fun connectedShare(context: Context): DiskShare {
        val config = context.config
        if (!config.isSmbConfigured) {
            throw IllegalStateException("No SMB share is configured")
        }

        val wanted = Credentials(
            host = config.smbHost,
            port = config.smbPort,
            shareName = config.smbShare,
            user = config.smbUser,
            password = config.smbPassword,
            domain = config.smbDomain
        )

        synchronized(lock) {
            val live = share
            if (live != null && live.isConnected && wanted == openedWith) {
                return live
            }

            disconnectLocked()
            return connectLocked(wanted)
        }
    }

    // Every step logs before it runs. A share that will not open fails with one status code out
    // of a protocol the user cannot see, and which of the three steps it came from is most of
    // the answer: reaching the host, being let in, and being given the share are three different
    // things to fix. A toast is cut short and is gone once it is read, so this goes to the log
    private fun connectLocked(credentials: Credentials): DiskShare {
        val client = SMBClient(smbConfig)
        var step = "connect to ${credentials.host}:${credentials.port}"
        try {
            val connection = client.connect(credentials.host, credentials.port)

            step = if (credentials.user.isEmpty()) {
                "authenticate as a guest"
            } else {
                "authenticate as ${credentials.user}${if (credentials.domain.isEmpty()) "" else "@${credentials.domain}"}"
            }

            val authentication = if (credentials.user.isEmpty()) {
                // a share that lets anyone in still wants a session; smbj calls that one guest
                AuthenticationContext.guest()
            } else {
                AuthenticationContext(credentials.user, credentials.password.toCharArray(), credentials.domain.ifEmpty { null })
            }

            val session = connection.authenticate(authentication)

            step = "open the share ${credentials.shareName}"
            val share = session.connectShare(credentials.shareName) as? DiskShare
                ?: throw IOException("${credentials.shareName} is not a disk share")

            this.client = client
            this.connection = connection
            this.session = session
            this.share = share
            openedWith = credentials
            Log.i(TAG, "Connected to \\\\${credentials.host}\\${credentials.shareName}")
            return share
        } catch (e: Exception) {
            Log.w(TAG, "Could not $step", e)
            // a half-opened connection would hold a socket and a thread for nothing
            client.close()
            throw e
        }
    }

    private fun disconnectLocked() {
        // closed outermost last: closing the client tears the rest down anyway, the individual
        // closes are what send the protocol's own goodbyes
        runCatching { share?.close() }
        runCatching { session?.close() }
        runCatching { connection?.close() }
        runCatching { client?.close() }
        share = null
        session = null
        connection = null
        client = null
        openedWith = null
    }

    // drops the connection, so that the next call opens a fresh one. Called when the settings
    // change and when a read failed in a way that says the session is gone
    fun disconnect() {
        synchronized(lock) { disconnectLocked() }
    }

    // whether a live connection to the share is still standing. A listing that failed means one
    // thing while it is -- that one folder could not be read -- and quite another once it is
    // not: the share itself has gone, and nothing further can be read from it either
    fun isConnected(): Boolean = synchronized(lock) { share?.isConnected == true }

    // Opens a connection and lists the root, for the settings screen's test button. Throws what
    // went wrong, its message is what the user is shown
    fun test(context: Context) {
        val share = connectedShare(context)
        val root = toSharePath(context, SMB_PATH_SCHEME)
        try {
            share.list(root)
        } catch (e: Exception) {
            Log.w(TAG, "Could not list the folder \"$root\" in the share", e)
            throw e
        }

        Log.i(TAG, "Listed the folder \"$root\" in the share")
    }

    // the entries of one folder, "." and ".." and hidden system entries left out. The path is a
    // pseudo path, "smb:" for the root
    fun list(context: Context, path: String): List<Entry> {
        val share = connectedShare(context)
        return share.list(toSharePath(context, path))
            .filter { it.fileName != "." && it.fileName != ".." }
            .map { it.toEntry() }
    }

    // the file behind a pseudo path, open for reading. The caller closes it
    fun open(context: Context, path: String): OpenFile {
        val share = connectedShare(context)
        val file = share.openFile(
            toSharePath(context, path),
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null
        )

        return OpenFile(file)
    }

    // whether the share has a file at the pseudo path. A folder there answers false, the way
    // File.isFile does, and so does a path whose parent folder is not there either
    fun fileExists(context: Context, path: String): Boolean {
        val share = connectedShare(context)
        return share.fileExists(toSharePath(context, path))
    }

    fun folderExists(context: Context, path: String): Boolean {
        val share = connectedShare(context)
        return share.folderExists(toSharePath(context, path))
    }

    // Makes the folder, and the folders above it that are not there yet. A folder that is
    // already there is left alone, so a caller can say this before every write without asking
    // first. There is no "make the parents too" request in the protocol, and a mkdir of a
    // nested path fails when a folder in the middle is missing, so it is walked segment by
    // segment
    fun createFolder(context: Context, path: String) {
        val share = connectedShare(context)
        var walked = ""
        for (segment in toSharePath(context, path).split(SEPARATOR).filter { it.isNotEmpty() }) {
            walked = if (walked.isEmpty()) segment else "$walked$SEPARATOR$segment"
            if (!share.folderExists(walked)) {
                share.mkdir(walked)
            }
        }
    }

    // Writes a new file at the pseudo path, its bytes coming from [write]. A name that is
    // already taken is refused rather than written over: the caller picked a name it believed
    // to be free, and one taken since is a collision, not a request to replace what is there.
    //
    // What [write] threw comes back out, with the half-written file already removed. The share
    // keeps a file a write broke off in the middle, and nothing afterwards would tell it from
    // a whole one -- the same reason the copy off the share discards its half-written files
    fun create(context: Context, path: String, write: (OutputStream) -> Unit) {
        val share = connectedShare(context)
        val sharePath = toSharePath(context, path)
        val file = share.openFile(
            sharePath,
            EnumSet.of(AccessMask.GENERIC_WRITE),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_CREATE,
            null
        )

        try {
            // both are closed: closing the stream flushes what is left but leaves the handle
            // open on the server, the same way the read side does
            file.use { open -> open.outputStream.use(write) }
        } catch (e: Exception) {
            runCatching { share.rm(sharePath) }
            throw e
        }
    }

    // Puts a modification time on a file of the share. The gallery sorts by that time, so a
    // copy that landed here and now would sort to the top of the folder instead of where the
    // original belongs
    fun setModified(context: Context, path: String, millis: Long) {
        val share = connectedShare(context)
        // only the write time is being set; the other three say so with DONT_SET, and 0
        // attributes leaves the file's own alone
        val information = FileBasicInformation(
            FileBasicInformation.DONT_SET,
            FileBasicInformation.DONT_SET,
            FileTime.ofEpochMillis(millis),
            FileBasicInformation.DONT_SET,
            0L
        )

        share.setFileInformation(toSharePath(context, path), information)
    }

    // Deleting, renaming and moving on the share are not here yet, and are deliberately not
    // written ahead of the screens that would call them (#28). smbj has `rm`, `rmdir` and
    // `DiskEntry.rename` waiting; what is missing is everything around them -- which rows follow
    // the file, what a half-done batch leaves behind, and what the menus should offer

    private fun FileIdBothDirectoryInformation.toEntry(): Entry {
        val isFolder = fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
        return Entry(
            name = fileName,
            isFolder = isFolder,
            size = endOfFile,
            modified = lastWriteTime.toEpochMillis()
        )
    }

    // "smb:/2026/IMG_0001.jpg" -> "<root>\2026\IMG_0001.jpg", the path inside the share. The
    // configured root folder is prepended here and nowhere else, so that the pseudo paths stay
    // the same if it is ever changed to another folder of the same share
    private fun toSharePath(context: Context, path: String): String {
        val relative = path.removePrefix(SMB_PATH_SCHEME).trim('/')
        val root = context.config.smbRootPath
        val joined = when {
            root.isEmpty() -> relative
            relative.isEmpty() -> root
            else -> "$root/$relative"
        }

        return joined.replace('/', SEPARATOR)
    }

    private const val TAG = "SmbClient"
    private const val TIMEOUT_SECONDS = 15L
    private const val SOCKET_TIMEOUT_SECONDS = 30L
}
