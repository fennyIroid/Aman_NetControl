package com.example.childsafetyimpl.platform.network

/**
 * Encrypted DNS, which is the single most effective way to walk around a DNS filter.
 *
 * Everything this app blocks at the network layer depends on one assumption: that when an app
 * wants to reach a host, it asks the resolver we are holding. DNS-over-HTTPS and DNS-over-TLS
 * break that assumption completely. Chrome with DoH enabled resolves names inside an HTTPS
 * session to a resolver of its own choosing, sees the answer we never got to filter, and connects
 * to an address we never got to refuse. The filter does not fail loudly in that case. It simply
 * stops applying, which is worse.
 *
 * There are two defences here and they work at different layers, because blocking by name alone
 * is not enough.
 *
 * **By name.** A browser that has been told to use `cloudflare-dns.com` has to resolve that name
 * first, and that lookup does come to us. Refusing it sends the browser back to the system
 * resolver, which is the one we are filtering. This is the cheap defence and it catches the
 * common configurations.
 *
 * **By address.** The gap in the defence above is bootstrapping: a resolver configured by IP, or
 * a browser shipping a hardcoded bootstrap address, never performs that lookup at all. So the
 * tunnel also takes a route for each well-known resolver address and drops what is sent there.
 * Those are host routes, one address each, which is what keeps this surgical: the tunnel still
 * carries almost nothing, and a bug here cannot take the device offline. See
 * [ChildSafetyVpnService] for why that restraint matters.
 *
 * The limit worth stating plainly, and the protection screen states it: this is a list of the
 * resolvers people actually use, not a proof. A resolver that is not on this list, reached by
 * address, is not blocked by either defence. That is a real bypass, it is recorded as a platform
 * limitation in the Sprint 0 validation results, and closing it properly needs either
 * Device Owner (which can force all traffic through the tunnel) or a private-DNS lockdown that
 * only a managed device allows.
 */
object EncryptedDnsPolicy {

    /**
     * Hostnames that serve DNS-over-HTTPS or DNS-over-TLS.
     *
     * Refused at the name layer. The browser asking falls back to the system resolver, which is
     * the outcome we want: not an error, just the filtered path.
     */
    val resolverHosts: Set<String> = setOf(
        // Google
        "dns.google",
        "dns64.dns.google",
        // Cloudflare, including the per-browser endpoints Chrome and Firefox ship with
        "cloudflare-dns.com",
        "chrome.cloudflare-dns.com",
        "mozilla.cloudflare-dns.com",
        "security.cloudflare-dns.com",
        "family.cloudflare-dns.com",
        "one.one.one.one",
        // Quad9
        "dns.quad9.net",
        "dns9.quad9.net",
        "dns10.quad9.net",
        "dns11.quad9.net",
        // NextDNS and friends
        "dns.nextdns.io",
        "anycast.dns.nextdns.io",
        // OpenDNS
        "doh.opendns.com",
        "doh.familyshield.opendns.com",
        // CleanBrowsing
        "doh.cleanbrowsing.org",
        "family-filter-dns.cleanbrowsing.org",
        // AdGuard
        "dns.adguard.com",
        "dns-family.adguard.com",
        "dns-unfiltered.adguard.com",
        // Smaller public resolvers that turn up in bypass guides
        "doh.dns.sb",
        "doh.libredns.gr",
        "dns.brahma.world",
        "doh.mullvad.net",
        "dns.controld.com",
        "freedns.controld.com",
        "doh.applied-privacy.net",
        "dns.digitale-gesellschaft.ch",
        "doh-de.blahdns.com",
        "doh.ffmuc.net",
    )

    /**
     * Addresses of the same resolvers, for the case where no name lookup happens at all.
     *
     * Each of these becomes a host route on the tunnel, and packets that arrive there are
     * dropped rather than forwarded. Dropping rather than answering is deliberate: a resolver
     * that times out makes a browser fall back to the system resolver, whereas a refusal it
     * understands may make it try the next one on its list.
     *
     * Only IPv4 literals belong here. The IPv6 forms are handled by not offering an IPv6 route at
     * all, which keeps the tunnel's address family story simple.
     */
    val resolverAddresses: Set<String> = setOf(
        // Cloudflare
        "1.1.1.1", "1.0.0.1", "1.1.1.2", "1.0.0.2", "1.1.1.3", "1.0.0.3",
        // Google
        "8.8.8.8", "8.8.4.4",
        // Quad9
        "9.9.9.9", "149.112.112.112", "9.9.9.10", "9.9.9.11",
        // OpenDNS
        "208.67.222.222", "208.67.220.220", "208.67.222.123", "208.67.220.123",
        // AdGuard
        "94.140.14.14", "94.140.15.15", "94.140.14.15", "94.140.15.16",
        // CleanBrowsing
        "185.228.168.9", "185.228.169.9", "185.228.168.10", "185.228.169.11",
        // Comodo, Neustar, Verisign, Level3 — the rest of the "change your DNS" lists
        "8.26.56.26", "8.20.247.20",
        "64.6.64.6", "64.6.65.6",
        "64.6.64.7",
        "4.2.2.1", "4.2.2.2", "4.2.2.3", "4.2.2.4",
        // ControlD
        "76.76.2.0", "76.76.10.0",
        // Mullvad
        "194.242.2.2", "194.242.2.4",
    )

    /**
     * The resolver this app forwards permitted lookups to.
     *
     * It is deliberately one of the addresses above, and deliberately excluded from the drop
     * routes: our own forwarding socket is protected and leaves outside the tunnel, so it never
     * meets the route, and hard-coding the exclusion here documents the asymmetry rather than
     * leaving it as a coincidence that happens to work.
     */
    const val UPSTREAM_RESOLVER = "1.1.1.1"

    /** Addresses to drop, with the upstream this app itself needs left out. */
    val blockedAddresses: List<String> =
        resolverAddresses.filterNot { it == UPSTREAM_RESOLVER }

    fun isResolverHost(host: String): Boolean =
        host.lowercase().trimEnd('.') in resolverHosts

    /**
     * Whether a Private DNS setting points somewhere this app cannot filter.
     *
     * Android's Private DNS is DNS-over-TLS configured at the OS level. "Automatic" is
     * opportunistic and falls back to our resolver, so it is not a bypass. A named hostname is:
     * every lookup on the device goes there in an encrypted session and never reaches the tunnel.
     *
     * An app cannot change this setting. It can only see it and say so, which is exactly why the
     * result of this function ends up in a parent alert rather than in an enforcement branch.
     */
    fun isPrivateDnsBypassing(mode: String?, host: String?): Boolean = when (mode) {
        null, "off", "opportunistic" -> false
        "hostname" -> !host.isNullOrBlank()
        else -> !host.isNullOrBlank()
    }

    /** What to tell a parent when Private DNS is doing the bypassing. */
    fun privateDnsExplanation(host: String?): String =
        "Android's Private DNS is set to ${host ?: "a named provider"}, so every name lookup on " +
            "this device is encrypted and sent there instead of to the filter. Website blocking " +
            "and Safe Search cannot apply while that setting is in place. It is changed in " +
            "Settings, Network and internet, Private DNS, and no app is allowed to change it " +
            "for you."
}
