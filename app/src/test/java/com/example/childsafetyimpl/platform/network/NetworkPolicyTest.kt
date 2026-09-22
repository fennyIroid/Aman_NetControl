package com.example.childsafetyimpl.platform.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The network policy decisions that are easy to get subtly wrong and impossible to notice.
 *
 * Every test here guards a case where the code would keep working and the protection would
 * silently stop applying, which is this product's defining failure mode.
 */
class NetworkPolicyTest {

    // --- Encrypted DNS ----------------------------------------------------------------------

    @Test
    fun `the upstream resolver is never among the addresses the tunnel drops`() {
        // If this ever fails, the tunnel routes its own forwarding target into a black hole and
        // every permitted lookup on the device times out. The device does not lose filtering, it
        // loses the internet, which is far worse and would be blamed on anything but this list.
        assertFalse(
            EncryptedDnsPolicy.UPSTREAM_RESOLVER in EncryptedDnsPolicy.blockedAddresses,
        )
    }

    @Test
    fun `the major browser DoH endpoints are refused by name`() {
        // These are the exact hostnames Chrome and Firefox ship in their secure-DNS pickers.
        listOf(
            "chrome.cloudflare-dns.com",
            "mozilla.cloudflare-dns.com",
            "dns.google",
            "dns.quad9.net",
            "dns.adguard.com",
        ).forEach {
            assertTrue("$it must be refused or the browser leaves the filter", EncryptedDnsPolicy.isResolverHost(it))
        }
    }

    @Test
    fun `resolver matching ignores case and a trailing dot`() {
        // A DNS name arrives fully qualified and in whatever case the client used. Matching the
        // literal string is the bug: "DNS.Google." would sail straight past.
        assertTrue(EncryptedDnsPolicy.isResolverHost("DNS.Google."))
        assertTrue(EncryptedDnsPolicy.isResolverHost("Cloudflare-DNS.com"))
    }

    @Test
    fun `bootstrap addresses for the major resolvers are dropped`() {
        // The name defence is useless against a resolver configured by IP, which is the whole
        // reason the address list exists.
        listOf("8.8.8.8", "9.9.9.9", "208.67.222.222", "94.140.14.14").forEach {
            assertTrue("$it is a documented DoH bootstrap address", it in EncryptedDnsPolicy.blockedAddresses)
        }
    }

    @Test
    fun `private DNS set to automatic is not treated as a bypass`() {
        // Opportunistic mode falls back to whatever resolver is in use, which is ours. Alerting
        // on it would train parents to ignore the alert that matters.
        assertFalse(EncryptedDnsPolicy.isPrivateDnsBypassing("off", null))
        assertFalse(EncryptedDnsPolicy.isPrivateDnsBypassing("opportunistic", null))
    }

    @Test
    fun `private DNS pointed at a named host is a bypass`() {
        assertTrue(EncryptedDnsPolicy.isPrivateDnsBypassing("hostname", "dns.google"))
    }

    // --- Tunnel exclusions ------------------------------------------------------------------

    @Test
    fun `banking packages are excluded including regional variants`() {
        assertTrue(TunnelExclusions.isExcluded("com.emiratesnbd"))
        assertTrue(TunnelExclusions.isExcluded("com.emiratesnbd.uae"))
        assertTrue(TunnelExclusions.isExcluded("com.paypal.android.p2pmobile"))
    }

    @Test
    fun `a prefix never swallows an unrelated package`() {
        // "com.ncb" must not match "com.ncbsomethingelse". A sloppy startsWith would quietly pull
        // arbitrary apps out of the filter, which is a hole rather than a compatibility fix.
        assertFalse(TunnelExclusions.isExcluded("com.ncbsomethingelse"))
        assertFalse(TunnelExclusions.isExcluded("com.qnbank.fake"))
    }

    @Test
    fun `no browser or social app is ever excluded from filtering`() {
        // The exclusion list exists to keep banking apps working. The moment anything a child
        // browses with appears on it, it has become a bypass with a friendly name.
        listOf(
            "com.android.chrome",
            "org.mozilla.firefox",
            "com.instagram.android",
            "com.google.android.youtube",
            "com.snapchat.android",
            "com.zhiliaoapp.musically",
        ).forEach {
            assertFalse("$it must never bypass the filter", TunnelExclusions.isExcluded(it))
        }
    }

    @Test
    fun `exclusions resolve only against what is installed`() {
        // addDisallowedApplication throws for a missing package and one throw abandons the whole
        // tunnel build, so filtering against the installed set is load-bearing, not tidiness.
        val installed = listOf("com.paypal.android.p2pmobile", "com.android.chrome")
        assertEquals(listOf("com.paypal.android.p2pmobile"), TunnelExclusions.installedExclusions(installed))
    }

    // --- Filtering mode ---------------------------------------------------------------------

    @Test
    fun `private DNS alone cannot pause the internet`() {
        // The capability that decides the architecture. A resolver cannot stop an open
        // connection, so any proposal to drop the tunnel entirely drops Internet Pause with it.
        assertFalse(FilteringMode.PRIVATE_DNS.supports(NetworkCapability.INTERNET_PAUSE))
        assertTrue(FilteringMode.VPN_TUNNEL.supports(NetworkCapability.INTERNET_PAUSE))
    }

    @Test
    fun `only private DNS is invisible to banking app VPN detection`() {
        // The client's actual observation, encoded so a later refactor cannot quietly claim the
        // tunnel is invisible when it is not.
        assertTrue(FilteringMode.PRIVATE_DNS.supports(NetworkCapability.INVISIBLE_TO_VPN_DETECTION))
        assertFalse(FilteringMode.VPN_TUNNEL.supports(NetworkCapability.INVISIBLE_TO_VPN_DETECTION))
    }

    @Test
    fun `the hybrid posture is the only one that covers everything`() {
        NetworkCapability.entries.forEach { capability ->
            assertTrue(
                "${capability.label} is unsupported even in the hybrid posture",
                FilteringMode.HYBRID.supports(capability),
            )
        }
    }
}
