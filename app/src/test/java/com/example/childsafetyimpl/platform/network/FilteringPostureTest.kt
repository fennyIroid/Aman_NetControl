package com.example.childsafetyimpl.platform.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that decide whether the tunnel runs, and in which posture, across the three ways of
 * delivering filtering — and the system exemptions allow-list mode relies on.
 */
class FilteringPostureTest {

    // --- Tunnel posture -------------------------------------------------------------------------

    @Test
    fun `a pause is restored in every filtering mode`() {
        // The bug this exists for: a service restarted after its process was killed came back
        // filtering, and the parent's pause silently lifted.
        FilteringMode.entries.forEach { mode ->
            assertEquals(mode.name, TunnelMode.PAUSED, TunnelMode.restingFor(mode, internetPaused = true))
        }
    }

    @Test
    fun `only the tunnel posture keeps a filtering tunnel up`() {
        assertEquals(TunnelMode.FILTER, TunnelMode.restingFor(FilteringMode.VPN_TUNNEL, false))
        assertNull(TunnelMode.restingFor(FilteringMode.PRIVATE_DNS, false))
        assertNull(TunnelMode.restingFor(FilteringMode.HYBRID, false))
    }

    @Test
    fun `Private DNS alone cannot pause, the hybrid can`() {
        assertFalse(FilteringMode.PRIVATE_DNS.canPauseInternet)
        assertTrue(FilteringMode.HYBRID.canPauseInternet)
        assertTrue(FilteringMode.VPN_TUNNEL.canPauseInternet)
    }

    @Test
    fun `every resolver the setup screen offers is one the guard recognises as filtering`() {
        // Otherwise a parent who follows the setup steps exactly is told they have bypassed the
        // filter.
        assertTrue(PrivateDnsGuard.isFilteringResolverHost(FilteringMode.DEFAULT_PRIVATE_DNS_HOST))
        FilteringMode.demoResolvers.forEach { resolver ->
            assertTrue(resolver.hostname, PrivateDnsGuard.isFilteringResolverHost(resolver.hostname))
        }
    }

    @Test
    fun `an unfiltered resolver is not mistaken for a filtering one`() {
        listOf("dns.google", "one.one.one.one", "dns.quad9.net", "cloudflare-dns.com").forEach {
            assertFalse(it, PrivateDnsGuard.isFilteringResolverHost(it))
        }
    }

    // --- System traffic exemptions --------------------------------------------------------------

    @Test
    fun `the phone's plumbing is exempt`() {
        listOf(
            "connectivitycheck.gstatic.com",
            "clients3.google.com",
            "mtalk.google.com",
            "alt4-mtalk.google.com",
            "fcm.googleapis.com",
            "play.googleapis.com",
            "r1---sn-4g5e6nsz.gvt1.com",
            "time.android.com",
            "accounts.google.com",
        ).forEach { assertTrue(it, SystemTrafficExemptions.isExempt(it)) }
    }

    /**
     * The failure this list invites: a host added "to make an app work" that turns out to serve
     * the content the filter exists to refuse. Each of these can carry a whole website or a video.
     */
    @Test
    fun `nothing browsable is ever exempt`() {
        listOf(
            "google.com",
            "www.google.com",
            "youtube.com",
            "m.youtube.com",
            "googlevideo.com",
            "rr3---sn-abc.googlevideo.com",
            "lh3.googleusercontent.com",
            "googleusercontent.com",
            "bing.com",
            "duckduckgo.com",
            "facebook.com",
            "instagram.com",
            "tiktok.com",
            "reddit.com",
            "d1234.cloudfront.net",
            "a.akamaihd.net",
            "notgvt1.com",
            "gvt1.com.evil.example",
        ).forEach { assertFalse("$it must not be exempt", SystemTrafficExemptions.isExempt(it)) }
    }
}
