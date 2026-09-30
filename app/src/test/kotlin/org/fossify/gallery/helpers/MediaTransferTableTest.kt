package org.fossify.gallery.helpers

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

// Every cell of a medium copied or moved into a folder with the destination picker (#140): the
// storage it is on, the storage of the folder tapped, and copy or move. Each cell says whether
// it is carried out or turned away, so that a pair nobody decided on cannot hide.
//
// The same two refusals stand for a folder of the folder list, see FolderPlacementTableTest
@RunWith(Parameterized::class)
class MediaTransferTableTest(
    private val from: Storage,
    private val to: Storage,
    private val op: Op,
    private val expected: TransferRefusal?
) {
    // two shares, each a storage of its own (#155): the first connection's paths and another's
    enum class Storage(val path: String) {
        DEVICE("/storage/emulated/0/Pictures/Outbox"),
        PCLOUD("pcloud:/Outbox"),
        SHARE("smb:/Outbox"),
        OTHER_SHARE("smb:2/Outbox")
    }

    enum class Op { COPY, MOVE }

    companion object {
        private val table: List<Array<Any?>> = buildList {
            fun cell(from: Storage, to: Storage, op: Op, expected: TransferRefusal?) {
                add(arrayOf(from, to, op, expected))
            }

            val carried: TransferRefusal? = null
            cell(Storage.DEVICE, Storage.DEVICE, Op.COPY, carried)
            cell(Storage.DEVICE, Storage.DEVICE, Op.MOVE, carried)
            cell(Storage.DEVICE, Storage.PCLOUD, Op.COPY, carried)
            cell(Storage.DEVICE, Storage.PCLOUD, Op.MOVE, carried)
            cell(Storage.DEVICE, Storage.SHARE, Op.COPY, carried)
            cell(Storage.DEVICE, Storage.SHARE, Op.MOVE, carried)
            cell(Storage.DEVICE, Storage.OTHER_SHARE, Op.COPY, carried)
            cell(Storage.DEVICE, Storage.OTHER_SHARE, Op.MOVE, carried)

            cell(Storage.PCLOUD, Storage.DEVICE, Op.COPY, carried)
            cell(Storage.PCLOUD, Storage.DEVICE, Op.MOVE, carried)
            cell(Storage.PCLOUD, Storage.PCLOUD, Op.COPY, carried)
            cell(Storage.PCLOUD, Storage.PCLOUD, Op.MOVE, carried)
            cell(Storage.PCLOUD, Storage.SHARE, Op.COPY, TransferRefusal.PCLOUD_ONTO_SHARE)
            cell(Storage.PCLOUD, Storage.SHARE, Op.MOVE, TransferRefusal.PCLOUD_ONTO_SHARE)
            cell(Storage.PCLOUD, Storage.OTHER_SHARE, Op.COPY, TransferRefusal.PCLOUD_ONTO_SHARE)
            cell(Storage.PCLOUD, Storage.OTHER_SHARE, Op.MOVE, TransferRefusal.PCLOUD_ONTO_SHARE)

            cell(Storage.SHARE, Storage.DEVICE, Op.COPY, carried)
            cell(Storage.SHARE, Storage.DEVICE, Op.MOVE, carried)
            cell(Storage.SHARE, Storage.PCLOUD, Op.COPY, carried)
            cell(Storage.SHARE, Storage.PCLOUD, Op.MOVE, carried)
            // #150: it used to be carried out as a move, the original gone from where it was
            cell(Storage.SHARE, Storage.SHARE, Op.COPY, TransferRefusal.COPY_WITHIN_SHARE)
            cell(Storage.SHARE, Storage.SHARE, Op.MOVE, carried)
            // #155: the rename a move within a share is cannot reach another share
            cell(Storage.SHARE, Storage.OTHER_SHARE, Op.COPY, TransferRefusal.BETWEEN_SHARES)
            cell(Storage.SHARE, Storage.OTHER_SHARE, Op.MOVE, TransferRefusal.BETWEEN_SHARES)

            cell(Storage.OTHER_SHARE, Storage.DEVICE, Op.COPY, carried)
            cell(Storage.OTHER_SHARE, Storage.DEVICE, Op.MOVE, carried)
            cell(Storage.OTHER_SHARE, Storage.PCLOUD, Op.COPY, carried)
            cell(Storage.OTHER_SHARE, Storage.PCLOUD, Op.MOVE, carried)
            cell(Storage.OTHER_SHARE, Storage.SHARE, Op.COPY, TransferRefusal.BETWEEN_SHARES)
            cell(Storage.OTHER_SHARE, Storage.SHARE, Op.MOVE, TransferRefusal.BETWEEN_SHARES)
            cell(Storage.OTHER_SHARE, Storage.OTHER_SHARE, Op.COPY, TransferRefusal.COPY_WITHIN_SHARE)
            cell(Storage.OTHER_SHARE, Storage.OTHER_SHARE, Op.MOVE, carried)
        }

        @JvmStatic
        @Parameterized.Parameters(name = "{0} to {1}, {2}: {3}")
        fun cells(): List<Array<Any?>> = table
    }

    // the table itself has every cell, once
    @Test
    fun `the table has every combination`() {
        assertEquals(Storage.entries.size * Storage.entries.size * Op.entries.size, table.size)
        assertEquals(table.size, table.map { it.take(3) }.toSet().size)
    }

    @Test
    fun `the pair is carried out or turned away as the table says`() {
        assertEquals(expected, transferRefusal(storageFilterOf(from.path), storageFilterOf(to.path), op == Op.COPY))
    }
}
