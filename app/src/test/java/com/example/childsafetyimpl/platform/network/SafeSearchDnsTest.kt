package com.example.childsafetyimpl.platform.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rewrite table decides what a child's browser resolves, so a wrong entry here is either a
 * search engine that quietly stops filtering or a site that stops loading at all. Both are worth
 * a test that runs without a device.
 */
class SafeSearchDnsTest {

    @Test
    fun `google is pointed at the forced safe search address`() {
        assertEquals(SafeSearchDns.GOOGLE_SAFE, SafeSearchDns.aliasFor("www.google.com"))
        assertEquals(SafeSearchDns.GOOGLE_SAFE, SafeSearchDns.aliasFor("google.com"))
    }

    @Test
    fun `a country google is pointed there too`() {
        // Switching to a country domain is one tap in a browser, and the obvious way around a
        // table that only knows google.com.
        listOf("google.co.uk", "www.google.co.in", "google.de", "google.com.au").forEach { host ->
            assertEquals(host, SafeSearchDns.GOOGLE_SAFE, SafeSearchDns.aliasFor(host))
        }
    }

    @Test
    fun `bing and duckduckgo have their own filtered addresses`() {
        assertEquals(SafeSearchDns.BING_SAFE, SafeSearchDns.aliasFor("www.bing.com"))
        assertEquals(SafeSearchDns.DUCKDUCKGO_SAFE, SafeSearchDns.aliasFor("duckduckgo.com"))
    }

    @Test
    fun `youtube is pointed at restricted mode including its api host`() {
        // Rewriting only the website leaves the YouTube app on its own unrestricted data path.
        assertEquals(SafeSearchDns.YOUTUBE_RESTRICTED, SafeSearchDns.aliasFor("www.youtube.com"))
        assertEquals(SafeSearchDns.YOUTUBE_RESTRICTED, SafeSearchDns.aliasFor("m.youtube.com"))
        assertEquals(
            SafeSearchDns.YOUTUBE_RESTRICTED,
            SafeSearchDns.aliasFor("youtubei.googleapis.com"),
        )
    }

    @Test
    fun `a name is matched however it is written`() {
        assertEquals(SafeSearchDns.GOOGLE_SAFE, SafeSearchDns.aliasFor("WWW.Google.COM"))
        assertEquals(SafeSearchDns.GOOGLE_SAFE, SafeSearchDns.aliasFor("www.google.com."))
    }

    @Test
    fun `ordinary hosts are left alone`() {
        // Rewriting one of these would break the site rather than filter it.
        listOf(
            "example.com",
            "mail.google.com",
            "drive.google.com",
            "googleusercontent.com",
            "notgoogle.com",
            "khanacademy.org",
        ).forEach { host ->
            assertNull(host, SafeSearchDns.aliasFor(host))
        }
    }

    @Test
    fun `the filtered address is available before any lookup has completed`() {
        // The first search of a session must not go out unfiltered while a resolution is pending.
        assertNotNull(SafeSearchDns.addressFor(SafeSearchDns.GOOGLE_SAFE))
        assertEquals(4, SafeSearchDns.addressFor(SafeSearchDns.GOOGLE_SAFE)?.size)
    }

    @Test
    fun `encrypted dns endpoints are refused`() {
        assertTrue(SafeSearchDns.blocksEncryptedDns("dns.google"))
        assertTrue(SafeSearchDns.blocksEncryptedDns("chrome.cloudflare-dns.com"))
        assertFalse(SafeSearchDns.blocksEncryptedDns("www.google.com"))
    }
}
