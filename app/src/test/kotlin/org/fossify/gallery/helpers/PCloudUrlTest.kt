package org.fossify.gallery.helpers

import org.junit.Assert.assertEquals
import org.junit.Test

// The one rule that decides whether a request reaches pCloud or the stub that stands in for it.
// Worth pinning in the small, because getting it wrong the other way round -- an https host
// quietly downgraded -- would send a token over plain http
class PCloudUrlTest {
    @Test
    fun `a bare host is reached over https`() {
        assertEquals("https://api.pcloud.com/userinfo", pCloudUrl("api.pcloud.com", "userinfo"))
    }

    @Test
    fun `a host that says https keeps it`() {
        assertEquals("https://eapi.pcloud.com/diff", pCloudUrl("https://eapi.pcloud.com", "diff"))
    }

    // what a stub is pointed at: 10.0.2.2 is the host as seen from inside the emulator, and the
    // port is part of the host
    @Test
    fun `a host that says http keeps it, port and all`() {
        assertEquals("http://10.0.2.2:8089/uploadfile", pCloudUrl("http://10.0.2.2:8089", "uploadfile"))
    }

    // link answers name a host and a path that already starts with one
    @Test
    fun `a path of its own is not given a second slash`() {
        assertEquals("https://c123.pcloud.com/dl/file.jpg", pCloudUrl("c123.pcloud.com", "/dl/file.jpg"))
    }

    @Test
    fun `a host written with a trailing slash does not double it`() {
        assertEquals("http://10.0.2.2:8089/getthumb", pCloudUrl("http://10.0.2.2:8089/", "getthumb"))
    }
}
