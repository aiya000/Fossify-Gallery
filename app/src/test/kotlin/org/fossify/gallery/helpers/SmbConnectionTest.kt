package org.fossify.gallery.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// What ties a path, a row and a storage filter to the share it belongs to (#155). The first
// connection keeps the paths and the filter the single share had, so what was stored before
// there could be a second one still means the same thing
class SmbConnectionTest {
    private val first = SmbConnection(id = 0, host = "nas", share = "photos")
    private val second = SmbConnection(id = 2, host = "pc", share = "pictures", rootPath = "Camera/2026")

    @Test
    fun `the first connection has the paths the single share had`() {
        assertEquals("smb:", first.root)
        assertEquals("smb:/", first.rowPrefix)
        assertEquals("smb:/.gallery-recycle-bin", first.recycleBin)
        assertEquals(SMB_RECYCLE_BIN, first.recycleBin)
        assertEquals(STORAGE_FILTER_SMB, first.storageFilter)
    }

    @Test
    fun `another connection carries its id after the scheme`() {
        assertEquals("smb:2", second.root)
        assertEquals("smb:2/", second.rowPrefix)
        assertEquals("smb:2/.gallery-recycle-bin", second.recycleBin)
    }

    @Test
    fun `a path names the connection it is of`() {
        assertEquals(0, smbConnectionIdOf("smb:"))
        assertEquals(0, smbConnectionIdOf("smb:/Trips/a.jpg"))
        assertEquals(2, smbConnectionIdOf("smb:2"))
        assertEquals(2, smbConnectionIdOf("smb:2/Trips/a.jpg"))
        assertEquals(12, smbConnectionIdOf("smb:12/a.jpg"))
    }

    // an id is written one way only, so that one connection is never named by two paths
    @Test
    fun `a path that is no connection's names none`() {
        assertNull(smbConnectionIdOf("/storage/emulated/0/DCIM/a.jpg"))
        assertNull(smbConnectionIdOf("pcloud:/a.jpg"))
        assertNull(smbConnectionIdOf("smb:0/a.jpg"))
        assertNull(smbConnectionIdOf("smb:02/a.jpg"))
        assertNull(smbConnectionIdOf("smb:2a/b.jpg"))
    }

    // the prefix of one connection is not a prefix of another's: "smb:/" is not what "smb:2/"
    // starts with, which the bare scheme would be
    @Test
    fun `a connection holds its own paths and not another's`() {
        assertTrue(first.holds("smb:/Trips/a.jpg"))
        assertTrue(first.holds("smb:"))
        assertFalse(first.holds("smb:2/Trips/a.jpg"))
        assertTrue(second.holds("smb:2/Trips/a.jpg"))
        assertFalse(second.holds("smb:/Trips/a.jpg"))
        assertFalse(second.holds("smb:22/Trips/a.jpg"))
        assertFalse("smb:2/Trips/a.jpg".startsWith(first.rowPrefix))
    }

    @Test
    fun `a storage filter and a connection are one another`() {
        assertEquals(STORAGE_FILTER_SMB, smbStorageFilterOf(0))
        assertEquals(0, smbConnectionIdOfFilter(STORAGE_FILTER_SMB))
        assertEquals(2, smbConnectionIdOfFilter(smbStorageFilterOf(2)))
        assertNull(smbConnectionIdOfFilter(STORAGE_FILTER_LOCAL))
        assertNull(smbConnectionIdOfFilter(STORAGE_FILTER_PCLOUD))
        assertNull(smbConnectionIdOfFilter(STORAGE_FILTER_ALL))
    }

    @Test
    fun `a filter tells the two shares apart`() {
        assertEquals(STORAGE_FILTER_SMB, storageFilterOf("smb:/Trips/a.jpg"))
        assertEquals(smbStorageFilterOf(2), storageFilterOf("smb:2/Trips/a.jpg"))
    }

    // the first connection's settings are the single share's keys, so a share set up before
    // there could be a second one is the first connection without anything moved
    @Test
    fun `the first connection's keys are the single share's`() {
        assertEquals(SMB_HOST, smbKey(SMB_HOST, 0))
        assertEquals("${SMB_HOST}_2", smbKey(SMB_HOST, 2))
    }

    @Test
    fun `a connection is shown by its address when it has no name`() {
        assertEquals("\\\\nas\\photos", first.address)
        assertEquals("\\\\pc\\pictures\\Camera\\2026", second.address)
    }
}
