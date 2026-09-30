package org.fossify.gallery.helpers

import android.content.Context
import android.util.Log
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msdtyp.FileTime
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileBasicInformation
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import org.fossify.gallery.extensions.config
import org.fossify.gallery.extensions.toSmbRemotePath
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

// The one way to the configured SMB shares. A connection is opened when it is first needed and
// kept for the next caller: a scan, the grid's thumbnails and the viewer all go through here,
// and opening a session per file would cost a handshake each time.
//
// There is one live connection per SmbConnection (#155), and which one a call goes to is read
// off the pseudo path it is given -- "smb:/a" is the first connection's, "smb:2/a" the second's
// -- so a caller that holds a path never has to know there is more than one share.
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

    // one live connection per connection id. What it was opened with is kept with it, and a
    // settings change is noticed by comparing that
    private class Live(
        val client: SMBClient,
        val connection: Connection,
        val session: Session,
        val share: DiskShare,
        val openedWith: Credentials
    )

    private val live = HashMap<Int, Live>()

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

    // the connection the pseudo path is of. Throws IllegalStateException when it is not one that
    // is set up -- a path of a connection that has been removed, or nothing configured at all
    private fun connectionOf(context: Context, path: String): SmbConnection =
        context.config.smbConnectionOf(path) ?: throw IllegalStateException("No SMB share is configured for $path")

    // Answers the share of the connection the pseudo path is of, connecting if there is no live
    // one. Throws an IOException when the share cannot be reached and IllegalStateException when
    // nothing is configured for the path
    private fun connectedShare(context: Context, path: String): DiskShare = connectedShare(connectionOf(context, path))

    private fun connectedShare(smbConnection: SmbConnection): DiskShare {
        val wanted = Credentials(
            host = smbConnection.host,
            port = smbConnection.port,
            shareName = smbConnection.share,
            user = smbConnection.user,
            password = smbConnection.password,
            domain = smbConnection.domain
        )

        synchronized(lock) {
            val current = live[smbConnection.id]
            if (current != null && current.share.isConnected && wanted == current.openedWith) {
                return current.share
            }

            disconnectLocked(smbConnection.id)
            return connectLocked(smbConnection.id, wanted)
        }
    }

    // Every step logs before it runs. A share that will not open fails with one status code out
    // of a protocol the user cannot see, and which of the three steps it came from is most of
    // the answer: reaching the host, being let in, and being given the share are three different
    // things to fix. A toast is cut short and is gone once it is read, so this goes to the log
    private fun connectLocked(id: Int, credentials: Credentials): DiskShare {
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

            live[id] = Live(client, connection, session, share, credentials)
            Log.i(TAG, "Connected to \\\\${credentials.host}\\${credentials.shareName}")
            return share
        } catch (e: Exception) {
            Log.w(TAG, "Could not $step", e)
            // a half-opened connection would hold a socket and a thread for nothing
            client.close()
            throw e
        }
    }

    private fun disconnectLocked(id: Int) {
        val current = live.remove(id) ?: return
        // closed outermost last: closing the client tears the rest down anyway, the individual
        // closes are what send the protocol's own goodbyes
        runCatching { current.share.close() }
        runCatching { current.session.close() }
        runCatching { current.connection.close() }
        runCatching { current.client.close() }
    }

    // drops every connection, so that the next call opens a fresh one. Called when a read failed
    // in a way that says a session is gone and there is no path to say which
    fun disconnect() {
        synchronized(lock) { live.keys.toList().forEach { disconnectLocked(it) } }
    }

    // drops the connection the pseudo path is of, the same way. Called when its settings change
    // and when a read of it failed in a way that says the session is gone; the other shares'
    // connections have nothing to do with either
    fun disconnect(path: String) {
        val id = smbConnectionIdOf(path) ?: return
        synchronized(lock) { disconnectLocked(id) }
    }

    // whether a live connection to the share the path is of is still standing. A listing that
    // failed means one thing while it is -- that one folder could not be read -- and quite
    // another once it is not: the share itself has gone, and nothing further can be read from it
    fun isConnected(path: String): Boolean {
        val id = smbConnectionIdOf(path) ?: return false
        return synchronized(lock) { live[id]?.share?.isConnected == true }
    }

    // Opens a connection and lists the root, for the settings screen's test button. Throws what
    // went wrong, its message is what the user is shown
    fun test(smbConnection: SmbConnection) {
        val share = connectedShare(smbConnection)
        val root = toSharePath(smbConnection, smbConnection.root)
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
        val share = connectedShare(context, path)
        return share.list(toSharePath(context, path))
            .filter { it.fileName != "." && it.fileName != ".." }
            .map { it.toEntry() }
    }

    // the file behind a pseudo path, open for reading. The caller closes it
    fun open(context: Context, path: String): OpenFile {
        val share = connectedShare(context, path)
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
        val share = connectedShare(context, path)
        return share.fileExists(toSharePath(context, path))
    }

    fun folderExists(context: Context, path: String): Boolean {
        val share = connectedShare(context, path)
        return share.folderExists(toSharePath(context, path))
    }

    // Makes the folder, and the folders above it that are not there yet. A folder that is
    // already there is left alone, so a caller can say this before every write without asking
    // first. There is no "make the parents too" request in the protocol, and a mkdir of a
    // nested path fails when a folder in the middle is missing, so it is walked segment by
    // segment
    fun createFolder(context: Context, path: String) {
        val share = connectedShare(context, path)
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
        val share = connectedShare(context, path)
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
        val share = connectedShare(context, path)
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

    // Removes the file at the pseudo path.
    //
    // A file the share no longer has counts as removed. What the caller wants is for the path to
    // be gone, and it is; failing here would turn a second ask -- a retry after a connection
    // dropped between the request and its answer, two screens deleting the same medium -- into
    // an error about something that has already happened
    fun delete(context: Context, path: String) {
        val share = connectedShare(context, path)
        try {
            share.rm(toSharePath(context, path))
        } catch (e: SMBApiException) {
            if (!e.isAlreadyGone()) {
                throw e
            }
        }
    }

    // The folder and everything under it. smbj walks it and deletes depth first, so this is many
    // requests rather than one, and a folder that is large is slow rather than atomic: what a
    // failure halfway leaves behind is a folder with the rest of its contents still in it. The
    // caller's rows are rebuilt from a walk of the share afterwards, not from the assumption
    // that this went all the way through
    fun deleteFolder(context: Context, path: String) {
        val share = connectedShare(context, path)
        try {
            share.rmdir(toSharePath(context, path), true)
        } catch (e: SMBApiException) {
            if (!e.isAlreadyGone()) {
                throw e
            }
        }
    }

    // The folder alone, and only when nothing is left in it; answers whether it went. For the
    // folders the recycle bin's layout leaves behind once what was in them is restored or gone
    // for good: a folder with something still in it is not an error here, it is the answer
    fun deleteFolderIfEmpty(context: Context, path: String): Boolean {
        val share = connectedShare(context, path)
        return try {
            share.rmdir(toSharePath(context, path), false)
            true
        } catch (e: SMBApiException) {
            if (e.status == NtStatus.STATUS_DIRECTORY_NOT_EMPTY || e.isAlreadyGone()) {
                false
            } else {
                throw e
            }
        }
    }

    // what the server answers for a name that is not there, and for one whose folder is not
    // there either -- the second is what deleting a file under a folder already removed gets
    private fun SMBApiException.isAlreadyGone() =
        status == NtStatus.STATUS_OBJECT_NAME_NOT_FOUND || status == NtStatus.STATUS_OBJECT_PATH_NOT_FOUND

    // Gives the file or folder at the pseudo path another name, in the folder it is already in.
    //
    // SMB has no rename request of its own: a handle is opened and an information class is set on
    // it, and that handle has to carry DELETE access -- taking the name away from where it is is
    // what the protocol counts as deleting. open() above asks for GENERIC_READ alone, so this
    // opens one of its own, and it opens it as neither a file nor a folder in particular: the
    // same request serves both, and which of the two it is the server already knows.
    //
    // The new name is sent as a whole path from the share's root, which is what the protocol
    // wants when no directory handle comes with it; it is built from the old path rather than
    // from the pseudo path, so the share's root folder setting is applied in one place still.
    //
    // A name that is taken is refused rather than written over -- the server answers
    // STATUS_OBJECT_NAME_COLLISION -- for the same reason create() refuses one: the caller picked
    // a name it believed to be free, and one taken since is a collision, not a request to replace
    // what is there
    fun rename(context: Context, path: String, newName: String) {
        val share = connectedShare(context, path)
        val sharePath = toSharePath(context, path)
        val newSharePath = renamedSiblingPath(sharePath, newName, SEPARATOR)
        val entry = share.open(
            sharePath,
            EnumSet.of(AccessMask.DELETE),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null
        )

        entry.use { it.rename(newSharePath) }
    }

    // Moves the file or folder at the pseudo path to another pseudo path of the same share, which
    // is how it is given another folder.
    //
    // It is the same request as rename(), and that is the point: what SMB calls a rename is
    // setting a whole new path on an open handle, and whether that path names another folder is
    // of no interest to it. So a move inside the share is one request and moves no bytes at all,
    // however large the file is -- where a move to another storage has to copy every byte and
    // then delete the original.
    //
    // A path the share already has is refused here too, the same as rename() and create(). The
    // caller picks a free name before asking, the way a copy into a folder does: the user picked
    // a folder rather than a name, so a name that is taken there is not their mistake to hear
    // about.
    //
    // The destination folder has to be there already. Nothing here makes it, because everything
    // that moves within the share moves into a folder the user picked out of the folder list.
    //
    // Both paths have to be of the one connection: a rename cannot take a file to another share,
    // and what reached here with two is a caller that did not ask TransferRefusal first
    fun moveTo(context: Context, path: String, newPath: String) {
        require(smbConnectionIdOf(path) == smbConnectionIdOf(newPath)) { "$path and $newPath are on different shares" }
        val share = connectedShare(context, path)
        val entry = share.open(
            toSharePath(context, path),
            EnumSet.of(AccessMask.DELETE),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null
        )

        entry.use { it.rename(toSharePath(context, newPath)) }
    }

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
    private fun toSharePath(context: Context, path: String) = toSharePath(connectionOf(context, path), path)

    private fun toSharePath(smbConnection: SmbConnection, path: String): String {
        val relative = path.toSmbRemotePath().trim('/')
        val root = smbConnection.rootPath
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
