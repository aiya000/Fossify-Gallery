package org.fossify.gallery.helpers

import org.fossify.gallery.models.Medium

// One walk of a share, folder by folder, kept apart from SmbScanner so that it can be put down
// part-way and picked up again (#80). The walk used to be a recursion, whose place lived on the
// call stack and was gone the moment the scan was called off; here it is a list of the folders
// still to be listed, and what the listed ones held.
//
// [R] is what one folder came to -- its rows for a full walk, a count for a search for new folders
// -- and [countOf] how many media that is, for the progress. A folder that came to nothing (no
// media, or a folder the search already knew) is not kept.
//
// The order is the recursion's: a folder, then each of its subfolders in the order the share
// listed them, all the way down, before the next one.
class SmbWalk<R>(val root: String, private val maxDepth: Int, private val countOf: (R) -> Int) {
    // [descend] is false for a folder reopened by reopen(): only its own media are read again,
    // the folders under it are either listed already or still to come
    private class Folder(val path: String, val depth: Int, val descend: Boolean)

    // the end of the list is the next folder, so that taking one and pushing its subfolders are
    // both at the cheap end
    private val pending = ArrayList<Folder>().apply { add(Folder(root, 0, true)) }
    private val found = LinkedHashMap<String, R>()

    // the folders the walk could not get into. Nothing it found says anything about them, so a
    // store must not read their absence as the share having dropped them
    val skippedPaths = HashSet<String>()

    // what the cache held when the walk began, folder by folder, see changedFolders(). Set by the
    // scanner, which is what can read the cache
    var cacheSignatures: Map<String, Long> = emptyMap()

    var listedCount = 0
        private set

    var mediaCount = 0
        private set

    val foundCount get() = found.size
    val results: Collection<R> get() = found.values

    // Lists folders until none are left. [list] answers a folder's entries, or null for one to
    // skip, and may throw: the folder it threw on stays at the end of the list, so the walk picks
    // up with that same folder when it is walked again. [read] turns a listed folder into what is
    // kept of it; [isWalkable] says which of its subfolders are walked into at all.
    //
    // Calling this a second time, after a throw, carries on where the first call stopped
    fun walk(
        list: (path: String, depth: Int) -> List<SmbClient.Entry>?,
        read: (path: String, entries: List<SmbClient.Entry>) -> R?,
        isWalkable: (parentPath: String, entry: SmbClient.Entry) -> Boolean,
        onFolder: (path: String) -> Unit = {},
    ) {
        while (pending.isNotEmpty()) {
            val next = pending.last()
            val entries = list(next.path, next.depth)
            pending.removeAt(pending.lastIndex)
            listedCount++

            if (entries == null) {
                skippedPaths.add(next.path)
                continue
            }

            found.remove(next.path)?.let { mediaCount -= countOf(it) }
            read(next.path, entries)?.let {
                found[next.path] = it
                mediaCount += countOf(it)
            }

            onFolder(next.path)
            if (!next.descend || next.depth >= maxDepth) {
                continue
            }

            entries.filter { it.isFolder && isWalkable(next.path, it) }.asReversed().forEach {
                pending.add(Folder("${next.path}/${it.name}", next.depth + 1, true))
            }
        }
    }

    // Puts folders whose rows in the cache changed while the walk was put down back on the list,
    // to be read again. What the walk holds for one of them is older than what a transfer or a
    // delete has written there since, and storing it would undo that: a file just copied in would
    // be dropped, one just deleted would come back.
    //
    // Only the folder's own media are read again. A folder still to be listed, or under one, is
    // left to the walk; one under a folder that was skipped is not this walk's to touch; one
    // deeper than the walk goes is not read by it at all
    fun reopen(changedFolders: Collection<String>) {
        changedFolders.forEach { path ->
            if (path != root && !path.startsWith("$root/")) {
                return@forEach
            }

            if (skippedPaths.any { path == it || path.startsWith("$it/") }) {
                return@forEach
            }

            if (pending.any { path == it.path || (it.descend && path.startsWith("${it.path}/")) }) {
                return@forEach
            }

            val depth = path.removePrefix(root).count { it == '/' }
            if (depth > maxDepth) {
                return@forEach
            }

            found.remove(path)?.let { mediaCount -= countOf(it) }
            pending.add(Folder(path, depth, false))
        }
    }

    companion object {
        // What the cache holds in each folder, folded into one number per folder: which files,
        // and their sizes and times. Two of these taken at different moments differ exactly for
        // the folders something wrote to in between. The fold is a sum so that the order the
        // rows come back in does not matter
        fun signaturesOf(rows: List<Medium>): Map<String, Long> {
            val signatures = HashMap<String, Long>()
            rows.forEach { medium ->
                val row = (medium.path.hashCode().toLong() shl 32) xor (medium.size * 31 + medium.modified)
                signatures[medium.parentPath] = (signatures[medium.parentPath] ?: 0L) + row + 1
            }

            return signatures
        }

        // the folders whose rows differ between two of those, a folder that gained its first row
        // or lost its last one included
        fun changedFolders(before: Map<String, Long>, after: Map<String, Long>): Set<String> =
            (before.keys + after.keys).filterTo(HashSet()) { before[it] != after[it] }
    }
}
