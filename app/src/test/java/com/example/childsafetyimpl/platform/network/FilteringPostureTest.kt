package com.example.childsafetyimpl.platform.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules behind filtering through Private DNS, and the system exemptions allow-list mode
 * relies on.
 */
class FilteringPostureTest {

    // --- Resolvers ------------------------------------------------------------------------------

    @Test
    fun `every resolver the setup screen offers is one the guard recognises as filtering`() {
        // Otherwise a parent who follows the setup steps exactly is told they have bypassed the
        // filter.
        assertTrue(PrivateDnsGuard.isFilteringResolverHost(PrivateDnsGuard.DEFAULT_HOST))
        PrivateDnsGuard.resolvers.forEach { resolver ->
            assertTrue(resolver.hostname, PrivateDnsGuard.isFilteringResolverHost(resolver.hostname))
        }
    }

    @Test
    fun `an unfiltered resolver is not mistaken for a filtering one`() {
        listOf("dns.google", "one.one.one.one", "dns.quad9.net", "cloudflare-dns.com").forEach {
            assertFalse(it, PrivateDnsGuard.isFilteringResolverHost(it))
        }
    }

    @Test
    fun `resolver matching ignores case and a trailing dot`() {
        // The setting is typed by a person, in whatever case they used.
        assertTrue(PrivateDnsGuard.isFilteringResolverHost("Family-Filter-DNS.CleanBrowsing.org."))
        assertEquals(
            PrivateDnsGuard.DEFAULT_HOST,
            PrivateDnsGuard.resolverFor("FAMILY-FILTER-DNS.cleanbrowsing.org.")?.hostname,
        )
    }

    @Test
    fun `the default resolver forces SafeSearch`() {
        // Device-wide SafeSearch rests on the resolver alone now that nothing rewrites lookups on
        // the device. A default that did not force it would quietly drop the protection.
        assertTrue(PrivateDnsGuard.resolverFor(PrivateDnsGuard.DEFAULT_HOST)!!.forcesSafeSearch)
    }

    @Test
    fun `a resolver this app does not offer is not described as one it does`() {
        // A filtering resolver the parent set up themselves is recognised, but its coverage is
        // unknown, so nothing may claim SafeSearch on its behalf.
        assertTrue(PrivateDnsGuard.isFilteringResolverHost("dns-family.adguard.com"))
        assertNull(PrivateDnsGuard.resolverFor("dns-family.adguard.com"))
    }

    @Test
    fun `a resolver that only refuses ads is not mistaken for one that filters`() {
        // Measured: pornhub.com resolves through both. Recognising either as filtering would
        // show a parent a green card over a device with no adult filtering at all.
        listOf("dns.adguard.com", "dns.adguard-dns.com", "unfiltered.adguard-dns.com")
            .forEach { assertFalse(it, PrivateDnsGuard.isFilteringResolverHost(it)) }
        assertTrue(PrivateDnsGuard.isFilteringResolverHost("family.adguard-dns.com"))
        assertTrue(PrivateDnsGuard.isFilteringResolverHost("high.kahfguard.com"))
    }

    @Test
    fun `every offered resolver is one the posture check recognises`() {
        PrivateDnsGuard.resolvers.forEach {
            assertTrue(it.hostname, PrivateDnsGuard.isFilteringResolverHost(it.hostname))
        }
    }

    @Test
    fun `YouTube restriction is never claimed without SafeSearch`() {
        PrivateDnsGuard.resolvers.filter { it.restrictsYouTube }
            .forEach { assertTrue(it.label, it.forcesSafeSearch) }
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
