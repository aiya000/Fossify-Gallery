package org.fossify.gallery.helpers

// Where a scan of a remote storage had got to when it was called off to make way for another, so
// that the next scan of the same storage picks up from there instead of walking it again (#80).
// A walk of a large share takes minutes, and before this a transfer in the middle of one threw
// all of it away.
//
// A place is never published: what it holds is the scanner's own half-done work, kept out of the
// cache until the scan that picks it up has run through. Taking a place removes it, so a scan that
// is called off again holds a fresh one and a scan that fails leaves none behind.
//
// Held in memory only. A process the system took away takes its places with it, and the next scan
// starts over -- which is what every scan did before, so losing one costs nothing new.
object RemoteScanPlaces {
    enum class Kind {
        // SmbScanner.scanAll(): the folders still to list, and what the listed ones held
        SMB_WALK,

        // SmbScanner.scanNewFolders(): the folders still to list; the new ones are stored already
        SMB_NEW_FOLDERS,

        // PCloudScanner.scanAll(): the diff id it started from, and the trees fetched so far
        PCLOUD_LISTING,

        // PCloudScanner.sync(): the folders the diff touched that are still to be listed
        PCLOUD_SYNC,

        // PCloudScanner.scanNewFolders(): the folders the cache knew before the search began
        PCLOUD_NEW_FOLDERS,
    }

    // a place older than this belongs to a walk of a storage that has had time to change under
    // it; the part already walked would be stale by the time the rest was, so it starts over
    const val MAX_AGE_MILLIS = 60 * 60 * 1000L

    private data class Key(val kind: Kind, val connectionId: Int)

    // [owner] is what the storage was when the place was held: the connection's settings, or the
    // pCloud login. A place taken under another owner is of a different storage that happens to
    // have the same id
    private class Held(val owner: Any, val heldAt: Long, val place: Any)

    private val held = HashMap<Key, Held>()

    // the clock, swapped by the tests
    internal var now: () -> Long = System::currentTimeMillis

    @Synchronized
    fun hold(kind: Kind, connectionId: Int, owner: Any, place: Any) {
        held[Key(kind, connectionId)] = Held(owner, now(), place)
    }

    // The place held for this storage, removed as it is handed out; null when there is none, or
    // when the one there is of another owner or too old to trust
    @Synchronized
    fun <T : Any> take(kind: Kind, connectionId: Int, owner: Any, type: Class<T>): T? {
        val place = held.remove(Key(kind, connectionId)) ?: return null
        if (place.owner != owner || now() - place.heldAt > MAX_AGE_MILLIS) {
            return null
        }

        return type.cast(place.place)
    }

    inline fun <reified T : Any> take(kind: Kind, connectionId: Int, owner: Any): T? = take(kind, connectionId, owner, T::class.java)

    @Synchronized
    fun drop(kind: Kind, connectionId: Int) {
        held.remove(Key(kind, connectionId))
    }

    // every place of one share, for a connection that was removed or pointed at another tree
    @Synchronized
    fun dropConnection(connectionId: Int) {
        held.keys.removeAll { it.connectionId == connectionId && (it.kind == Kind.SMB_WALK || it.kind == Kind.SMB_NEW_FOLDERS) }
    }

    @Synchronized
    fun dropAll() {
        held.clear()
    }
}
