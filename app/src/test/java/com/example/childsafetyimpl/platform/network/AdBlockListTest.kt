package com.example.childsafetyimpl.platform.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ad blocklist decides what every app on the device can resolve, so the two failure modes are
 * not symmetrical. Missing an ad shows a child an ad. Over-matching breaks an app on a child's
 * phone in a way a parent cannot diagnose, and these tests exist mainly to pin down the second.
 */
class AdBlockListTest {

    @Test
    fun `refuses the video ad host the YouTube app uses`() {
        assertTrue(AdBlockList.blocksHost("googleads.g.doubleclick.net"))
        assertTrue(AdBlockList.blocksHost("pagead2.googlesyndication.com"))
    }

    @Test
    fun `refuses subdomains beneath a listed host`() {
        assertTrue(AdBlockList.blocksHost("static.criteo.com"))
        assertTrue(AdBlockList.blocksHost("eu.ib.adnxs.com"))
    }

    @Test
    fun `ignores a leading www and a trailing dot`() {
        assertTrue(AdBlockList.blocksHost("www.taboola.com"))
        assertTrue(AdBlockList.blocksHost("outbrain.com."))
    }

    @Test
    fun `is case insensitive`() {
        assertTrue(AdBlockList.blocksHost("PageAd2.GoogleSyndication.COM"))
    }

    /**
     * The important one. Video delivery, thumbnails and the API share hosts with the app itself,
     * and refusing any of them would leave a child staring at a player that never starts.
     */
    @Test
    fun `does not refuse the hosts YouTube needs to play a video`() {
        listOf(
            "www.youtube.com",
            "m.youtube.com",
            "youtubei.googleapis.com",
            "rr3---sn-abc.googlevideo.com",
            "i.ytimg.com",
            "yt3.ggpht.com",
        ).forEach { host ->
            assertFalse("$host must stay reachable", AdBlockList.blocksHost(host))
        }
    }

    @Test
    fun `does not refuse ordinary sites that merely contain a listed word`() {
        listOf(
            "criteon.example.com",
            "notdoubleclick.net",
            "adnxs.example.org",
        ).forEach { host ->
            assertFalse("$host must stay reachable", AdBlockList.blocksHost(host))
        }
    }

    @Test
    fun `matches ad requests by path where the host alone cannot decide`() {
        assertTrue(AdBlockList.blocksRequest("https://www.youtube.com/api/stats/ads?ver=2"))
        assertTrue(AdBlockList.blocksRequest("https://www.youtube.com/pagead/interaction/"))
    }

    @Test
    fun `leaves ordinary YouTube requests alone`() {
        assertFalse(AdBlockList.blocksRequest("https://www.youtube.com/watch?v=abc123"))
        assertFalse(AdBlockList.blocksRequest("https://www.youtube.com/api/stats/watchtime"))
        assertFalse(AdBlockList.blocksRequest("https://i.ytimg.com/vi/abc123/hqdefault.jpg"))
    }

    @Test
    fun `blocks a request whose host is listed regardless of path`() {
        assertTrue(AdBlockList.blocksRequest("https://googleads.g.doubleclick.net/anything"))
    }

    @Test
    fun `survives a url it cannot parse`() {
        // WebView hands over data: and blob: URLs, and a throw here would break the page load.
        assertFalse(AdBlockList.blocksRequest("not a url at all"))
        assertFalse(AdBlockList.blocksRequest("data:image/png;base64,iVBORw0KGgo="))
    }
}
