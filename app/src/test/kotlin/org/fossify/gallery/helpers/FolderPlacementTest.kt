package org.fossify.gallery.helpers

import org.junit.Assert.assertEquals
import org.junit.Test

// What the destination picker's OK does with a folder of the folder list, at the top or inside a
// group: the folder itself goes there, on the storage the chips were showing. It used to be told
// that a group holds folders, not files -- about a folder
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

    @Test
    fun `a copy at the top of the share makes the folder in the share's root`() {
        assertEquals(FolderPlacement.Transfer("smb:/Outbox", null), place())
    }

    // a group belongs to no storage, so the one the chips showed before it was opened is the one
    // the folder is made on, and the new folder goes into the group
    @Test
    fun `a copy inside a group makes the folder on the chips' storage and puts it in the group`() {
        assertEquals(FolderPlacement.Transfer("smb:/Outbox", 1L), place(groupId = 1L))
    }

    @Test
    fun `a copy onto pCloud makes the folder in pCloud's root`() {
        assertEquals(FolderPlacement.Transfer("pcloud:/Outbox", null), place(storageFilter = STORAGE_FILTER_PCLOUD))
    }

    @Test
    fun `a copy onto the device makes the folder under Pictures`() {
        assertEquals(
            FolderPlacement.Transfer("$deviceRoot/Camera", null),
            place(source = "smb:/Camera", storageFilter = STORAGE_FILTER_LOCAL)
        )
    }

    // with every storage on the chips there is no storage picked, so the folder stays on its own
    @Test
    fun `with every storage shown a copy stays on the folder's own storage`() {
        assertEquals(
            FolderPlacement.Transfer("$deviceRoot/Outbox (1)", null),
            place(storageFilter = STORAGE_FILTER_ALL, isTaken = taking(outbox))
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

    @Test
    fun `a move onto another storage makes the folder there too`() {
        assertEquals(FolderPlacement.Transfer("smb:/Outbox", 1L), place(isCopy = false, groupId = 1L))
    }

    // what "Move to" always did: the folder changes group and nothing on any storage moves
    @Test
    fun `a move that stays on the folder's storage only changes its group`() {
        assertEquals(FolderPlacement.Regroup(1L), place(isCopy = false, storageFilter = STORAGE_FILTER_LOCAL, groupId = 1L))
        assertEquals(FolderPlacement.Regroup(null), place(isCopy = false, storageFilter = STORAGE_FILTER_ALL))
        assertEquals(FolderPlacement.Regroup(1L), place(source = "smb:/Camera", isCopy = false, groupId = 1L))
    }
}
