package org.fossify.gallery.helpers

import org.fossify.gallery.jobs.PCloudTransferService
import org.fossify.gallery.jobs.SmbTransferService
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

// Every cell of a medium copied or moved into a folder with the destination picker (#140): the
// storage it is on, the storage of the folder tapped, and copy or move. Each cell says what
// carries it, so that a pair nobody decided on cannot hide. Every pair is carried now: the last
// ones turned away were a copy within a share (#150), anything between two shares (#155) and
// pCloud onto a share (#28), carried since #154 and #161.
//
// A folder of the folder list goes the same way once FolderPlacementTableTest has placed it
@RunWith(Parameterized::class)
class MediaTransferTableTest(
    private val from: Storage,
    private val to: Storage,
    private val op: Op,
    private val expected: TransferRoute
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
        private val table: List<Array<Any>> = buildList {
            fun cell(from: Storage, to: Storage, op: Op, expected: TransferRoute) {
                add(arrayOf(from, to, op, expected))
            }

            val onDevice = TransferRoute.OnDevice
            fun pCloud(kind: PCloudTransferService.Kind) = TransferRoute.ByPCloud(kind)
            fun share(kind: SmbTransferService.Kind) = TransferRoute.ByShare(kind)
            val upload = pCloud(PCloudTransferService.Kind.UPLOAD)
            val download = pCloud(PCloudTransferService.Kind.DOWNLOAD)
            val withinPCloud = pCloud(PCloudTransferService.Kind.WITHIN_PCLOUD)
            val pCloudOntoShare = pCloud(PCloudTransferService.Kind.ONTO_SHARE)
            val fromDevice = share(SmbTransferService.Kind.FROM_DEVICE)
            val toDevice = share(SmbTransferService.Kind.TO_DEVICE)
            val toPCloud = share(SmbTransferService.Kind.TO_PCLOUD)
            // the rename, one request and no bytes
            val withinShare = share(SmbTransferService.Kind.WITHIN_SHARE)
            // every byte read off a share and written back
            val shareToShare = share(SmbTransferService.Kind.SHARE_TO_SHARE)

            cell(Storage.DEVICE, Storage.DEVICE, Op.COPY, onDevice)
            cell(Storage.DEVICE, Storage.DEVICE, Op.MOVE, onDevice)
            cell(Storage.DEVICE, Storage.PCLOUD, Op.COPY, upload)
            cell(Storage.DEVICE, Storage.PCLOUD, Op.MOVE, upload)
            cell(Storage.DEVICE, Storage.SHARE, Op.COPY, fromDevice)
            cell(Storage.DEVICE, Storage.SHARE, Op.MOVE, fromDevice)
            cell(Storage.DEVICE, Storage.OTHER_SHARE, Op.COPY, fromDevice)
            cell(Storage.DEVICE, Storage.OTHER_SHARE, Op.MOVE, fromDevice)

            cell(Storage.PCLOUD, Storage.DEVICE, Op.COPY, download)
            cell(Storage.PCLOUD, Storage.DEVICE, Op.MOVE, download)
            cell(Storage.PCLOUD, Storage.PCLOUD, Op.COPY, withinPCloud)
            cell(Storage.PCLOUD, Storage.PCLOUD, Op.MOVE, withinPCloud)
            // #161: refused before, the user had to go through the device
            cell(Storage.PCLOUD, Storage.SHARE, Op.COPY, pCloudOntoShare)
            cell(Storage.PCLOUD, Storage.SHARE, Op.MOVE, pCloudOntoShare)
            cell(Storage.PCLOUD, Storage.OTHER_SHARE, Op.COPY, pCloudOntoShare)
            cell(Storage.PCLOUD, Storage.OTHER_SHARE, Op.MOVE, pCloudOntoShare)

            cell(Storage.SHARE, Storage.DEVICE, Op.COPY, toDevice)
            cell(Storage.SHARE, Storage.DEVICE, Op.MOVE, toDevice)
            cell(Storage.SHARE, Storage.PCLOUD, Op.COPY, toPCloud)
            cell(Storage.SHARE, Storage.PCLOUD, Op.MOVE, toPCloud)
            // #150: a copy carried out as the rename took the original away
            cell(Storage.SHARE, Storage.SHARE, Op.COPY, shareToShare)
            cell(Storage.SHARE, Storage.SHARE, Op.MOVE, withinShare)
            // #155: the rename cannot reach another share
            cell(Storage.SHARE, Storage.OTHER_SHARE, Op.COPY, shareToShare)
            cell(Storage.SHARE, Storage.OTHER_SHARE, Op.MOVE, shareToShare)

            cell(Storage.OTHER_SHARE, Storage.DEVICE, Op.COPY, toDevice)
            cell(Storage.OTHER_SHARE, Storage.DEVICE, Op.MOVE, toDevice)
            cell(Storage.OTHER_SHARE, Storage.PCLOUD, Op.COPY, toPCloud)
            cell(Storage.OTHER_SHARE, Storage.PCLOUD, Op.MOVE, toPCloud)
            cell(Storage.OTHER_SHARE, Storage.SHARE, Op.COPY, shareToShare)
            cell(Storage.OTHER_SHARE, Storage.SHARE, Op.MOVE, shareToShare)
            cell(Storage.OTHER_SHARE, Storage.OTHER_SHARE, Op.COPY, shareToShare)
            cell(Storage.OTHER_SHARE, Storage.OTHER_SHARE, Op.MOVE, withinShare)
        }

        @JvmStatic
        @Parameterized.Parameters(name = "{0} to {1}, {2}: {3}")
        fun cells(): List<Array<Any>> = table
    }

    // the table itself has every cell, once
    @Test
    fun `the table has every combination`() {
        assertEquals(Storage.entries.size * Storage.entries.size * Op.entries.size, table.size)
        assertEquals(table.size, table.map { it.take(3) }.toSet().size)
    }

    @Test
    fun `the pair is carried as the table says`() {
        assertEquals(expected, transferRouteOf(from.path, to.path, op == Op.COPY))
    }
}
