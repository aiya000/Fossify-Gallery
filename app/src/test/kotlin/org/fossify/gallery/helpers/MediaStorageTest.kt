package org.fossify.gallery.helpers

import android.content.ContextWrapper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// What each storage says it can do is what the menus offer, so the table here is the table the
// driving scripts see on screen. A row that says yes where the code says no is #71 again
class MediaStorageTest {
    // nothing here reads the context; the operations that will are not on the type yet
    private val context = ContextWrapper(null)

    private val device = MediaStorage.of(context, "/storage/emulated/0/DCIM/IMG_0001.jpg")
    private val pCloud = MediaStorage.of(context, "pcloud:/Camera/IMG_0001.jpg")
    private val smb = MediaStorage.of(context, "smb:/Screens/a.jpg")

    @Test
    fun `a path names its storage`() {
        assertTrue(device is MediaStorage.Device)
        assertTrue(pCloud is MediaStorage.PCloud)
        assertTrue(smb is MediaStorage.Smb)
    }

    @Test
    fun `a storage holds its own paths and nobody else's`() {
        assertTrue(device.holds("/storage/emulated/0/Pictures/b.jpg"))
        assertFalse(device.holds("pcloud:/Camera/b.jpg"))
        assertFalse(device.holds("smb:/Screens/b.jpg"))

        assertTrue(pCloud.holds("pcloud:/Camera/b.jpg"))
        assertFalse(pCloud.holds("smb:/Screens/b.jpg"))

        assertTrue(smb.holds("smb:/Screens/b.jpg"))
        assertFalse(smb.holds("/storage/emulated/0/Pictures/b.jpg"))
    }

    // the menus ask about a whole selection: it is offered what its one storage can do, and
    // nothing when it has more than one
    @Test
    fun `a selection on one storage is that storage`() {
        assertTrue(MediaStorage.ofAll(context, listOf("smb:/Screens/a.jpg", "smb:/Trips/b.jpg")) is MediaStorage.Smb)
        assertTrue(MediaStorage.ofAll(context, listOf("/storage/emulated/0/DCIM/a.jpg")) is MediaStorage.Device)
    }

    @Test
    fun `a selection mixing storages is no storage, and so is an empty one`() {
        assertNull(MediaStorage.ofAll(context, listOf("smb:/Screens/a.jpg", "/storage/emulated/0/DCIM/a.jpg")))
        assertNull(MediaStorage.ofAll(context, listOf("pcloud:/Camera/a.jpg", "smb:/Screens/a.jpg")))
        assertNull(MediaStorage.ofAll(context, emptyList()))
    }

    @Test
    fun `only the device is not remote`() {
        assertFalse(device.isRemote)
        assertTrue(pCloud.isRemote)
        assertTrue(smb.isRemote)
    }

    // what needs the device's own file system: several renames at once, the EXIF date, a
    // shortcut, hiding by a dot, excluding, and a folder's properties
    @Test
    fun `what the device alone can do`() {
        for (storage in listOf(pCloud, smb)) {
            assertFalse(storage.canRenameSeveral)
            assertFalse(storage.canFixDateTaken)
            assertFalse(storage.canResizeSeveral)
            assertFalse(storage.canCreateShortcut)
            assertFalse(storage.canHide)
            assertFalse(storage.canRenameSeveralFolders)
            assertFalse(storage.canShowFolderProperties)
            assertFalse(storage.canExcludeFolders)
        }

        assertTrue(device.canRenameSeveral)
        assertTrue(device.canFixDateTaken)
        assertTrue(device.canResizeSeveral)
        assertTrue(device.canCreateShortcut)
        assertTrue(device.canHide)
        assertTrue(device.canRenameSeveralFolders)
        assertTrue(device.canShowFolderProperties)
        assertTrue(device.canExcludeFolders)
    }

    // what a pCloud medium is fetched into a file for, and a medium of the share is not yet (#71)
    @Test
    fun `what the device and pCloud can do, and the share cannot yet`() {
        for (storage in listOf(device, pCloud)) {
            assertTrue(storage.canOpenWith)
            assertTrue(storage.canSetAs)
            assertTrue(storage.canShare)
            assertTrue(storage.canRotate)
            assertTrue(storage.canResize)
        }

        assertFalse(smb.canOpenWith)
        assertFalse(smb.canSetAs)
        assertFalse(smb.canShare)
        assertFalse(smb.canRotate)
        assertFalse(smb.canResize)
    }

    // where media can be copied or moved to: anywhere, but for pCloud straight onto the share,
    // which would have to be staged on the way (#28). The destination picker turns that pair
    // away, and copyMoveTo() refuses it if asked anyway
    @Test
    fun `every pair of storages can be transferred between, but pCloud onto the share`() {
        for (source in listOf(device, pCloud, smb)) {
            assertTrue(source.canTransferTo(device))
            assertTrue(source.canTransferTo(pCloud))
        }

        assertTrue(device.canTransferTo(smb))
        assertTrue(smb.canTransferTo(smb))
        assertFalse(pCloud.canTransferTo(smb))
    }

    @Test
    fun `only the share streams its videos`() {
        assertTrue(smb.streamsVideos)
        assertFalse(device.streamsVideos)
        assertFalse(pCloud.streamsVideos)
    }
}
