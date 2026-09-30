package org.fossify.gallery.jobs

import org.fossify.gallery.jobs.SmbTransferService.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

// What a copy or a move from a share into a share is carried out as (#154). Only a move within
// one share is the rename, where no bytes travel; everything else reads the file off and writes
// it back. MediaTransferTableTest has which pairs are carried out at all
class SmbTransferKindTest {
    private fun kind(source: String, destination: String, isCopy: Boolean) =
        SmbTransferService.kindBetweenShares(source, destination, isCopy)

    @Test
    fun `a move within one share is the rename`() {
        assertEquals(Kind.WITHIN_SHARE, kind("smb:/Outbox/a.jpg", "smb:/Inbox", isCopy = false))
        assertEquals(Kind.WITHIN_SHARE, kind("smb:2/Outbox/a.jpg", "smb:2/Inbox", isCopy = false))
    }

    // #150: carried out as the rename, the original was gone from where it was
    @Test
    fun `a copy within one share carries the bytes`() {
        assertEquals(Kind.SHARE_TO_SHARE, kind("smb:/Outbox/a.jpg", "smb:/Inbox", isCopy = true))
        assertEquals(Kind.SHARE_TO_SHARE, kind("smb:2/Outbox/a.jpg", "smb:2/Inbox", isCopy = true))
    }

    // #155: the rename cannot reach another share
    @Test
    fun `anything onto another share carries the bytes`() {
        assertEquals(Kind.SHARE_TO_SHARE, kind("smb:/Outbox/a.jpg", "smb:2/Inbox", isCopy = true))
        assertEquals(Kind.SHARE_TO_SHARE, kind("smb:/Outbox/a.jpg", "smb:2/Inbox", isCopy = false))
        assertEquals(Kind.SHARE_TO_SHARE, kind("smb:2/Outbox/a.jpg", "smb:/Inbox", isCopy = false))
    }
}
