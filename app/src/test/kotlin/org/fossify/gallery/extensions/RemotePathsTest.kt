package org.fossify.gallery.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Media on pCloud and on the share carry a pseudo path, because there is no file behind them
// until one is fetched. These predicates are what the menus, the viewer and the storage chips ask
// before offering anything that needs a real file
class RemotePathsTest {
    @Test
    fun `a pCloud path is told apart from a share's and from a real one`() {
        assertTrue("pcloud:/Camera/IMG_0001.jpg".isPCloudPath())
        assertFalse("smb:/pictures/IMG_0001.jpg".isPCloudPath())
        assertFalse("/storage/emulated/0/DCIM/IMG_0001.jpg".isPCloudPath())
    }

    @Test
    fun `a share path is told apart from pCloud's and from a real one`() {
        assertTrue("smb:/pictures/IMG_0001.jpg".isSmbPath())
        assertFalse("pcloud:/Camera/IMG_0001.jpg".isSmbPath())
        assertFalse("/storage/emulated/0/DCIM/IMG_0001.jpg".isSmbPath())
    }

    // what the viewer and the menu gating ask, rather than naming one storage
    @Test
    fun `both storages are remote, the device is not`() {
        assertTrue("pcloud:/Camera".isRemotePath())
        assertTrue("smb:/pictures".isRemotePath())
        assertFalse("/storage/emulated/0/DCIM".isRemotePath())
    }

    @Test
    fun `a pCloud pseudo path and the path the API wants are the same path`() {
        assertEquals("/Camera/IMG_0001.jpg", "pcloud:/Camera/IMG_0001.jpg".toPCloudRemotePath())
        assertEquals("pcloud:/Camera/IMG_0001.jpg", "/Camera/IMG_0001.jpg".toPCloudPseudoPath())
    }

    // the root of the account: "pcloud:" one way, "/" the other
    @Test
    fun `the pCloud root survives the round trip`() {
        assertEquals("/", "pcloud:".toPCloudRemotePath())
        assertEquals("pcloud:", "/".toPCloudPseudoPath())
    }

    // smbj wants the path inside the share, with no separator in front of it
    @Test
    fun `a share pseudo path and the path inside the share are the same path`() {
        assertEquals("pictures/IMG_0001.jpg", "smb:/pictures/IMG_0001.jpg".toSmbRemotePath())
        assertEquals("smb:/pictures/IMG_0001.jpg", "pictures/IMG_0001.jpg".toSmbPseudoPath())
    }

    @Test
    fun `the share's own root survives the round trip`() {
        assertEquals("", "smb:".toSmbRemotePath())
        assertEquals("smb:", "".toSmbPseudoPath())
    }

    // a path that is already a real one is handed back untouched, so a caller does not have to
    // ask twice
    @Test
    fun `a path of the other kind is left alone`() {
        assertEquals("/storage/emulated/0/DCIM", "/storage/emulated/0/DCIM".toPCloudRemotePath())
        assertEquals("/storage/emulated/0/DCIM", "/storage/emulated/0/DCIM".toSmbRemotePath())
    }
}
