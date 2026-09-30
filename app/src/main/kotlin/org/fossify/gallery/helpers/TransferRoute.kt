package org.fossify.gallery.helpers

import org.fossify.gallery.jobs.PCloudTransferService
import org.fossify.gallery.jobs.SmbTransferService

// What carries a copy or a move of media from one folder into another, for a medium and for a
// folder of the folder list alike. Every pair of storages is carried: the pairs once turned away
// -- a copy within a share (#150), anything between two shares (#155) and pCloud straight onto a
// share (#28) -- are carried since #154 and #161. MediaTransferTableTest has the whole table
sealed interface TransferRoute {
    // between two folders of the device, the commons copy
    data object OnDevice : TransferRoute

    // pCloud on either side, and the share not the source
    data class ByPCloud(val kind: PCloudTransferService.Kind) : TransferRoute

    // the share the source, or the destination with the device the source
    data class ByShare(val kind: SmbTransferService.Kind) : TransferRoute
}

fun transferRouteOf(source: String, destination: String, isCopy: Boolean): TransferRoute {
    val sourceStorage = storageFilterOf(source)
    val destinationStorage = storageFilterOf(destination)
    val sourceShare = smbConnectionIdOfFilter(sourceStorage)
    val destinationShare = smbConnectionIdOfFilter(destinationStorage)
    return when {
        sourceShare != null -> TransferRoute.ByShare(
            when {
                destinationStorage == STORAGE_FILTER_PCLOUD -> SmbTransferService.Kind.TO_PCLOUD
                destinationShare == null -> SmbTransferService.Kind.TO_DEVICE
                // the rename, one request and no bytes; everything else between shares reads the
                // file off and writes it back
                !isCopy && sourceShare == destinationShare -> SmbTransferService.Kind.WITHIN_SHARE
                else -> SmbTransferService.Kind.SHARE_TO_SHARE
            }
        )

        sourceStorage == STORAGE_FILTER_PCLOUD -> TransferRoute.ByPCloud(
            when {
                destinationStorage == STORAGE_FILTER_PCLOUD -> PCloudTransferService.Kind.WITHIN_PCLOUD
                destinationShare != null -> PCloudTransferService.Kind.ONTO_SHARE
                else -> PCloudTransferService.Kind.DOWNLOAD
            }
        )

        destinationStorage == STORAGE_FILTER_PCLOUD -> TransferRoute.ByPCloud(PCloudTransferService.Kind.UPLOAD)
        destinationShare != null -> TransferRoute.ByShare(SmbTransferService.Kind.FROM_DEVICE)
        else -> TransferRoute.OnDevice
    }
}

fun storageFilterOf(path: String): Int {
    val smbConnectionId = smbConnectionIdOf(path)
    return when {
        path.startsWith(PCLOUD_PATH_SCHEME) -> STORAGE_FILTER_PCLOUD
        smbConnectionId != null -> smbStorageFilterOf(smbConnectionId)
        // an SMB path whose id is not written the way smbRootOf() writes it is no connection's;
        // it is still not the device's, and the first share is the one that would own it
        path.startsWith(SMB_PATH_SCHEME) -> STORAGE_FILTER_SMB
        else -> STORAGE_FILTER_LOCAL
    }
}
