package org.fossify.gallery.helpers

import org.junit.Assert.assertEquals
import org.junit.Test

// What the destination picker's OK does with a folder of the folder list, apart from the pair of
// storages: every pair, copy or move, at the top or inside a group, is FolderPlacementTableTest's.
// Here is what names the new folder, and the chips showing every storage
class FolderPlacementTest {
    private val deviceRoot = "/storage/emulated/0/Pictures"
    private val outbox = "$deviceRoot/Outbox"

    private fun taking(vararg taken: String): (String) -> Boolean = { taken.contains(it) }

    private fun place(
        source: String = outbox,
        isCopy: Boolean = true,
        storageFilter: Int = STORAGE_FILTER_SMB,
        groupId: Long? = null,
        isTaken: (String) -> Boolean = taking()
    ) = placeFolder(source, isCopy, storageFilter, groupId, deviceRoot, isTaken)

    // with every storage on the chips there is no storage picked, so the folder stays on its own
    @Test
    fun `with every storage shown a copy stays on the folder's own storage`() {
        assertEquals(
            FolderPlacement.Transfer("$deviceRoot/Outbox (1)", null),
            place(storageFilter = STORAGE_FILTER_ALL, isTaken = taking(outbox))
        )
    }

    @Test
    fun `with every storage shown a move only changes the folder's group`() {
        assertEquals(FolderPlacement.Regroup(null), place(isCopy = false, storageFilter = STORAGE_FILTER_ALL))
        assertEquals(FolderPlacement.Regroup(1L), place(source = "smb:/Camera", isCopy = false, storageFilter = STORAGE_FILTER_ALL, groupId = 1L))
    }

    // the share's folder copied with every storage shown is a copy within the share
    @Test
    fun `with every storage shown a copy of a share folder is refused`() {
        assertEquals(
            FolderPlacement.Refused(FolderRefusal.COPY_WITHIN_SHARE),
            place(source = "smb:/Camera", storageFilter = STORAGE_FILTER_ALL)
        )
    }

    // a copy never lands in a folder that is already there: its media would be mixed in
    @Test
    fun `a name the storage already has is numbered`() {
        assertEquals(
            FolderPlacement.Transfer("smb:/Outbox (2)", null),
            place(isTaken = taking("smb:/Outbox", "smb:/Outbox (1)"))
        )
    }

    // a folder has no extension, whatever dots its name holds
    @Test
    fun `a folder name with a dot is numbered at its end`() {
        assertEquals(
            FolderPlacement.Transfer("smb:/Trip.2026 (1)", null),
            place(source = "$deviceRoot/Trip.2026", isTaken = taking("smb:/Trip.2026"))
        )
    }

    // a folder under another on its own storage is still made in the destination's root
    @Test
    fun `a nested folder is made in the destination's root`() {
        assertEquals(
            FolderPlacement.Transfer("pcloud:/Kyoto", null),
            place(source = "smb:/Trips/Kyoto", storageFilter = STORAGE_FILTER_PCLOUD)
        )
    }
}
