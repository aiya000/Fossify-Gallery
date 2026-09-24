package org.fossify.gallery.helpers

import org.fossify.gallery.helpers.RemoteScanScheduler.Storage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// What a yes to "rescan on mobile data?" covers afterwards (#124). The question itself needs a
// screen and is driven in test-device/drive/92-ask-before-a-rescan-on-mobile-data.sh; what is
// pinned here is the memory of the answer, which decides whether the next gesture asks again
class RescanOnMobileDataTest {
    @Before
    fun startAfresh() {
        RescanOnMobileData.forget()
    }

    @Test
    fun `nothing is covered until something was said yes to`() {
        assertFalse(RescanOnMobileData.isConsented(Storage.SMB, RescanScope.FOLDER))
        assertFalse(RescanOnMobileData.isConsented(Storage.PCLOUD, RescanScope.WHOLE))
    }

    // the folder opened next on the same trip is a few listings, like the one just said yes to
    @Test
    fun `a yes to one folder covers another folder, and no more`() {
        RescanOnMobileData.consent(Storage.SMB, RescanScope.FOLDER)

        assertTrue(RescanOnMobileData.isConsented(Storage.SMB, RescanScope.FOLDER))
        assertFalse(RescanOnMobileData.isConsented(Storage.SMB, RescanScope.GROUP))
        assertFalse(RescanOnMobileData.isConsented(Storage.SMB, RescanScope.WHOLE))
    }

    // a walk of the whole share is more than any part of it
    @Test
    fun `a yes to the whole storage covers everything on it`() {
        RescanOnMobileData.consent(Storage.PCLOUD, RescanScope.WHOLE)

        assertTrue(RescanOnMobileData.isConsented(Storage.PCLOUD, RescanScope.FOLDER))
        assertTrue(RescanOnMobileData.isConsented(Storage.PCLOUD, RescanScope.GROUP))
        assertTrue(RescanOnMobileData.isConsented(Storage.PCLOUD, RescanScope.WHOLE))
    }

    // the two settings are separate, and so is the size of what each walks
    @Test
    fun `a yes for one storage says nothing about the other`() {
        RescanOnMobileData.consent(Storage.SMB, RescanScope.WHOLE)

        assertFalse(RescanOnMobileData.isConsented(Storage.PCLOUD, RescanScope.FOLDER))
    }

    // a later, smaller yes must not shrink what was already covered
    @Test
    fun `a yes to less does not take back a yes to more`() {
        RescanOnMobileData.consent(Storage.SMB, RescanScope.WHOLE)
        RescanOnMobileData.consent(Storage.SMB, RescanScope.FOLDER)

        assertTrue(RescanOnMobileData.isConsented(Storage.SMB, RescanScope.WHOLE))
    }

    // the next launch is a new trip
    @Test
    fun `forgetting starts the questions over`() {
        RescanOnMobileData.consent(Storage.SMB, RescanScope.WHOLE)
        RescanOnMobileData.forget()

        assertFalse(RescanOnMobileData.isConsented(Storage.SMB, RescanScope.FOLDER))
    }
}
