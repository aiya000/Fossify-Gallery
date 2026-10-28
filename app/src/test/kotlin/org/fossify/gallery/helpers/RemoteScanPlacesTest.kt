package org.fossify.gallery.helpers

import org.fossify.gallery.helpers.RemoteScanPlaces.Kind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

// When a held place is handed to the next scan, and when it is not (#80). Each "not" is a scan
// that would otherwise store half of one tree with half of another.
class RemoteScanPlacesTest {
    private var clock = 0L
    private val realNow = RemoteScanPlaces.now

    @Before
    fun setUp() {
        RemoteScanPlaces.dropAll()
        RemoteScanPlaces.now = { clock }
    }

    @After
    fun tearDown() {
        RemoteScanPlaces.dropAll()
        RemoteScanPlaces.now = realNow
    }

    @Test
    fun `a held place is handed to the next scan of the same storage`() {
        RemoteScanPlaces.hold(Kind.SMB_WALK, 2, "share", "place")

        assertEquals("place", RemoteScanPlaces.take<String>(Kind.SMB_WALK, 2, "share"))
    }

    // a scan that is called off again holds a fresh place, and one that fails leaves none
    @Test
    fun `a place is handed out once`() {
        RemoteScanPlaces.hold(Kind.SMB_WALK, 0, "share", "place")
        RemoteScanPlaces.take<String>(Kind.SMB_WALK, 0, "share")

        assertNull(RemoteScanPlaces.take<String>(Kind.SMB_WALK, 0, "share"))
    }

    // the connection's settings changed under the same id: another tree, or the same as another user
    @Test
    fun `a place held under other settings is not handed out`() {
        RemoteScanPlaces.hold(Kind.SMB_WALK, 0, "the old share", "place")

        assertNull(RemoteScanPlaces.take<String>(Kind.SMB_WALK, 0, "the new share"))
    }

    @Test
    fun `a place of one share is not handed to another`() {
        RemoteScanPlaces.hold(Kind.SMB_WALK, 2, "share", "place")

        assertNull(RemoteScanPlaces.take<String>(Kind.SMB_WALK, 3, "share"))
    }

    // a full walk and a search for new folders are different work, each with its own place
    @Test
    fun `a place of one kind of scan is not handed to another`() {
        RemoteScanPlaces.hold(Kind.SMB_WALK, 0, "share", "place")

        assertNull(RemoteScanPlaces.take<String>(Kind.SMB_NEW_FOLDERS, 0, "share"))
    }

    @Test
    fun `a place older than an hour is not handed out`() {
        RemoteScanPlaces.hold(Kind.PCLOUD_LISTING, 0, "account", "place")
        clock += RemoteScanPlaces.MAX_AGE_MILLIS + 1

        assertNull(RemoteScanPlaces.take<String>(Kind.PCLOUD_LISTING, 0, "account"))
    }

    @Test
    fun `a place just under an hour old is still handed out`() {
        RemoteScanPlaces.hold(Kind.PCLOUD_LISTING, 0, "account", "place")
        clock += RemoteScanPlaces.MAX_AGE_MILLIS

        assertEquals("place", RemoteScanPlaces.take<String>(Kind.PCLOUD_LISTING, 0, "account"))
    }

    // a share that was removed, or pointed at another tree
    @Test
    fun `dropping a connection drops its places and only its places`() {
        RemoteScanPlaces.hold(Kind.SMB_WALK, 2, "share", "walk")
        RemoteScanPlaces.hold(Kind.SMB_NEW_FOLDERS, 2, "share", "search")
        RemoteScanPlaces.hold(Kind.SMB_WALK, 3, "share", "other walk")
        RemoteScanPlaces.hold(Kind.PCLOUD_LISTING, 0, "account", "listing")

        RemoteScanPlaces.dropConnection(2)

        assertNull(RemoteScanPlaces.take<String>(Kind.SMB_WALK, 2, "share"))
        assertNull(RemoteScanPlaces.take<String>(Kind.SMB_NEW_FOLDERS, 2, "share"))
        assertEquals("other walk", RemoteScanPlaces.take<String>(Kind.SMB_WALK, 3, "share"))
        assertEquals("listing", RemoteScanPlaces.take<String>(Kind.PCLOUD_LISTING, 0, "account"))
    }
}
