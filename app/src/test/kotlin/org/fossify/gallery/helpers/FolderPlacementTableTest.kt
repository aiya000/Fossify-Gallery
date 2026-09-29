package org.fossify.gallery.helpers

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

// Every cell of a folder of the folder list copied or moved with the destination picker's OK
// (#139, #140): the storage it is on, the storage the chips show, copy or move, and whether the
// OK was at the top of the list or inside a group. Each cell says what happens, so that a cell
// nobody decided on cannot hide: a new storage or a new rule shows up here as rows to fill in.
//
// test-device/drive/72-a-folder-onto-every-storage.sh drives the same table on the emulator.
// What is not about the pair of storages -- the numbering of a taken name, a name with a dot,
// every storage on the chips -- is FolderPlacementTest's
@RunWith(Parameterized::class)
class FolderPlacementTableTest(
    private val from: Storage,
    private val to: Storage,
    private val op: Op,
    private val at: At,
    private val expected: Outcome
) {
    enum class Storage(val filter: Int, val folder: String, val root: String) {
        DEVICE(STORAGE_FILTER_LOCAL, "$DEVICE_ROOT/Outbox", DEVICE_ROOT),
        PCLOUD(STORAGE_FILTER_PCLOUD, "pcloud:/Outbox", "pcloud:"),
        SHARE(STORAGE_FILTER_SMB, "smb:/Outbox", "smb:")
    }

    enum class Op { COPY, MOVE }

    enum class At(val groupId: Long?) { TOP(null), GROUP(GROUP_ID) }

    sealed interface Outcome {
        // the folder is made in the destination's root, as Outbox or, where Outbox is the source
        // itself, as Outbox (1)
        data object NewFolder : Outcome
        data object Regroup : Outcome
        data class Refused(val reason: FolderRefusal) : Outcome
    }

    companion object {
        const val DEVICE_ROOT = "/storage/emulated/0/Pictures"
        const val GROUP_ID = 1L

        private val table: List<Array<Any>> = buildList {
            fun cell(from: Storage, to: Storage, op: Op, outcome: Outcome) {
                At.entries.forEach { at -> add(arrayOf(from, to, op, at, outcome)) }
            }

            val pcloudOntoShare = Outcome.Refused(FolderRefusal.PCLOUD_ONTO_SHARE)
            val copyWithinShare = Outcome.Refused(FolderRefusal.COPY_WITHIN_SHARE)

            cell(Storage.DEVICE, Storage.DEVICE, Op.COPY, Outcome.NewFolder)
            cell(Storage.DEVICE, Storage.DEVICE, Op.MOVE, Outcome.Regroup)
            cell(Storage.DEVICE, Storage.PCLOUD, Op.COPY, Outcome.NewFolder)
            cell(Storage.DEVICE, Storage.PCLOUD, Op.MOVE, Outcome.NewFolder)
            cell(Storage.DEVICE, Storage.SHARE, Op.COPY, Outcome.NewFolder)
            cell(Storage.DEVICE, Storage.SHARE, Op.MOVE, Outcome.NewFolder)

            cell(Storage.PCLOUD, Storage.DEVICE, Op.COPY, Outcome.NewFolder)
            cell(Storage.PCLOUD, Storage.DEVICE, Op.MOVE, Outcome.NewFolder)
            cell(Storage.PCLOUD, Storage.PCLOUD, Op.COPY, Outcome.NewFolder)
            cell(Storage.PCLOUD, Storage.PCLOUD, Op.MOVE, Outcome.Regroup)
            cell(Storage.PCLOUD, Storage.SHARE, Op.COPY, pcloudOntoShare)
            cell(Storage.PCLOUD, Storage.SHARE, Op.MOVE, pcloudOntoShare)

            cell(Storage.SHARE, Storage.DEVICE, Op.COPY, Outcome.NewFolder)
            cell(Storage.SHARE, Storage.DEVICE, Op.MOVE, Outcome.NewFolder)
            cell(Storage.SHARE, Storage.PCLOUD, Op.COPY, Outcome.NewFolder)
            cell(Storage.SHARE, Storage.PCLOUD, Op.MOVE, Outcome.NewFolder)
            cell(Storage.SHARE, Storage.SHARE, Op.COPY, copyWithinShare)
            cell(Storage.SHARE, Storage.SHARE, Op.MOVE, Outcome.Regroup)
        }

        @JvmStatic
        @Parameterized.Parameters(name = "{0} to {1}, {2} at {3}: {4}")
        fun cells(): List<Array<Any>> = table
    }

    // the table itself has every cell, once
    @Test
    fun `the table has every combination`() {
        assertEquals(Storage.entries.size * Storage.entries.size * Op.entries.size * At.entries.size, table.size)
        assertEquals(table.size, table.map { it.take(4) }.toSet().size)
    }

    @Test
    fun `the OK does what the table says`() {
        val placement = placeFolder(
            sourceFolder = from.folder,
            isCopy = op == Op.COPY,
            storageFilter = to.filter,
            groupId = at.groupId,
            deviceRoot = DEVICE_ROOT,
            isTaken = { it == from.folder }
        )

        val want = when (expected) {
            Outcome.NewFolder -> {
                val name = if (from == to) "Outbox (1)" else "Outbox"
                FolderPlacement.Transfer("${to.root}/$name", at.groupId)
            }

            Outcome.Regroup -> FolderPlacement.Regroup(at.groupId)
            is Outcome.Refused -> FolderPlacement.Refused(expected.reason)
        }

        assertEquals(want, placement)
    }
}
