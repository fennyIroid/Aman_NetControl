package com.example.childsafetyimpl.platform.network

/**
 * How network filtering is delivered, as a decision the product makes rather than an accident of
 * what was built first.
 *
 * This type exists because of a question the client raised directly: a DNS-based product like
 * Kahf Guard felt simpler in daily use than a VpnService-based one like BlockerX, and their own
 * VpnService prototype ran into permission complexity and banking apps objecting to an active
 * tunnel. Both observations are correct, and the honest answer is that the two products being
 * compared are not doing the same job.
 *
 * Kahf Guard sets Android's **Private DNS**, a system setting that sends every lookup on the
 * device to a chosen resolver over TLS. No tunnel, no VPN key in the status bar, and no banking
 * app deciding the device is compromised. What it cannot do is anything that is not a name
 * lookup: it cannot pause the internet, cannot enforce a per-family block-list without a resolver
 * that knows which family is asking, cannot strip ads, and cannot see or stop an app opening.
 * It also cannot be set by an app — Android reserves that for a Device Owner — so a parent types
 * a hostname into Settings, and a child can untype it in the same three taps. Kahf Guard closes
 * that gap the same way this app would have to: an Accessibility service that watches the setting.
 *
 * So the real trade is not "DNS versus VPN". It is **how much of the product is network
 * filtering**. Of Aman's committed scope, app restriction, screen time, routines, Shorts and
 * Status control and clone control never touch the network at all. Of what remains, only Internet
 * Pause and device-wide ad filtering genuinely require the tunnel.
 *
 * That is what [HYBRID] is for, and why it is the recommended production direction: run the
 * quiet mechanism permanently and raise the intrusive one only when a feature actually needs it.
 */
enum class FilteringMode(
    val label: String,
    val summary: String,
) {

    /**
     * Android's Private DNS, pointed at a filtering resolver.
     *
     * The quietest posture available. Covers Wi-Fi and mobile data at the system level, costs
     * effectively no battery, and is invisible to VPN detection, so the banking-app problem
     * disappears entirely.
     *
     * Two things make it insufficient on its own. A parent has to enter the hostname by hand
     * because no API grants it, and a child can remove it just as easily, so it needs the
     * Accessibility guard to hold. And per-family rules require the resolver itself to know which
     * family is asking, which means a per-family hostname and a resolver we operate — backend
     * work that is explicitly outside Sprint 0 but squarely inside Phase 1.
     */
    PRIVATE_DNS(
        label = "Private DNS",
        summary = "Filtering at the system resolver. No tunnel, no VPN icon, nothing for a " +
            "banking app to object to. Cannot pause the internet or block an app.",
    ),

    /**
     * This app's own VpnService, carrying name lookups and nothing else.
     *
     * Strictly more capable than [PRIVATE_DNS]: rules are evaluated on the device so they work
     * with no backend, encrypted-DNS resolvers can be dropped by address as well as by name, and
     * Internet Pause becomes possible because there is something in the path to stop.
     *
     * The costs are the ones the client observed. A consent dialog at setup, a permanent key icon
     * in the status bar, and `TRANSPORT_VPN` visible to every app that looks for it — which is
     * why [TunnelExclusions] exists and why it is not optional.
     */
    VPN_TUNNEL(
        label = "Filtering tunnel",
        summary = "This app holds the device's VPN slot and filters lookups inside it. Enables " +
            "Internet Pause, device-wide ad filtering and on-device rules.",
    ),

    /**
     * Private DNS permanently, the tunnel only while a feature needs it.
     *
     * The recommendation. Day to day the device is in the quiet posture: filtering applies, no
     * VPN icon, banking apps untroubled. The tunnel comes up for the minutes a parent has the
     * internet paused, and goes away again.
     *
     * The honest cost, which belongs on the setup screen rather than in a footnote: the posture
     * changes, so a child watching the status bar can tell when a pause begins. That is arguably
     * correct behaviour — a pause is not meant to be a secret — but it is a real difference from
     * a tunnel that is simply always there, and the Sprint 0 results record it as one.
     */
    HYBRID(
        label = "Private DNS with a tunnel on demand",
        summary = "Quiet by default, tunnel raised only for Internet Pause and ad filtering. " +
            "Recommended production direction.",
    );

    /**
     * What each posture can actually deliver.
     *
     * Kept as data rather than prose so the Sprint 0 scope summary and the capability matrix are
     * generated from the same source the enforcement code reads, and cannot drift apart from it.
     */
    val capabilities: Set<NetworkCapability>
        get() = when (this) {
            PRIVATE_DNS -> setOf(
                NetworkCapability.CATEGORY_BLOCKING,
                NetworkCapability.SAFE_SEARCH,
                NetworkCapability.COVERS_MOBILE_DATA,
                NetworkCapability.SURVIVES_REBOOT,
                NetworkCapability.INVISIBLE_TO_VPN_DETECTION,
            )

            VPN_TUNNEL -> setOf(
                NetworkCapability.CATEGORY_BLOCKING,
                NetworkCapability.SAFE_SEARCH,
                NetworkCapability.COVERS_MOBILE_DATA,
                NetworkCapability.CUSTOM_RULES_WITHOUT_BACKEND,
                NetworkCapability.AD_FILTERING,
                NetworkCapability.INTERNET_PAUSE,
                NetworkCapability.ENCRYPTED_DNS_BLOCKING,
                NetworkCapability.SURVIVES_REBOOT,
            )

            HYBRID -> PRIVATE_DNS.capabilities + VPN_TUNNEL.capabilities
        }

    fun supports(capability: NetworkCapability): Boolean = capability in capabilities

    /** Whether the filtering tunnel is held all the time. Only the tunnel posture does. */
    val keepsTunnelUp: Boolean get() = this == VPN_TUNNEL

    /** Whether Android's Private DNS setting is expected to point at a filtering resolver. */
    val usesPrivateDns: Boolean get() = this != VPN_TUNNEL

    /** Whether a pause can be applied. Private DNS alone has nothing in the traffic path. */
    val canPauseInternet: Boolean get() = supports(NetworkCapability.INTERNET_PAUSE)

    companion object {
        /**
         * The resolver a parent is told to enter until the per-family one exists.
         *
         * CleanBrowsing's family filter is used because it does both halves of what the Private
         * DNS posture claims — adult categories refused and SafeSearch forced at the resolver —
         * and it is already on [PrivateDnsGuard]'s list of resolvers that filter. It is a
         * demonstration stand-in: it cannot hold this family's own block and allow lists, which
         * is exactly the Phase 1 backend work the architecture note describes.
         */
        const val DEFAULT_PRIVATE_DNS_HOST = "family-filter-dns.cleanbrowsing.org"

        /** Public filtering resolvers a parent can pick from for the demonstration. */
        val demoResolvers: List<PrivateDnsResolver> = listOf(
            PrivateDnsResolver(
                hostname = DEFAULT_PRIVATE_DNS_HOST,
                label = "CleanBrowsing Family",
                covers = "Adult sites refused, SafeSearch forced on Google, Bing and YouTube.",
            ),
            PrivateDnsResolver(
                hostname = "family.cloudflare-dns.com",
                label = "Cloudflare for Families",
                covers = "Adult and malware sites refused. Does not force SafeSearch.",
            ),
        )
    }
}

/** A public resolver that filters, offered while there is no per-family resolver of our own. */
data class PrivateDnsResolver(
    val hostname: String,
    val label: String,
    /** What this resolver refuses, in a parent's words. */
    val covers: String,
)

/**
 * The things a network-filtering posture either can or cannot do.
 *
 * Named at the level a parent would recognise, because these rows become the comparison table in
 * the Sprint 0 results and the limitation text on the protection screens.
 */
enum class NetworkCapability(val label: String, val note: String) {
    CATEGORY_BLOCKING(
        "Block harmful categories",
        "Both postures refuse the lookup. Private DNS needs the resolver to hold the category list.",
    ),
    SAFE_SEARCH(
        "Force Safe Search",
        "Answering search-engine lookups with the filtered address each engine publishes.",
    ),
    CUSTOM_RULES_WITHOUT_BACKEND(
        "Per-family block and allow lists with no server",
        "Evaluated on the device. Private DNS can only do this with a per-family resolver.",
    ),
    AD_FILTERING(
        "Remove ads across apps",
        "Requires refusing ad hosts for apps this product does not own.",
    ),
    INTERNET_PAUSE(
        "Pause the internet",
        "Requires something in the traffic path. A resolver cannot stop an open connection.",
    ),
    ENCRYPTED_DNS_BLOCKING(
        "Close DNS-over-HTTPS bypasses",
        "Needs address-level drops, not only name refusals. See EncryptedDnsPolicy.",
    ),
    COVERS_MOBILE_DATA(
        "Works on mobile data as well as Wi-Fi",
        "Both postures sit below the radios, so both do.",
    ),
    SURVIVES_REBOOT(
        "Still in force after a restart",
        "Private DNS is a system setting and simply persists. The tunnel has to be re-established.",
    ),
    INVISIBLE_TO_VPN_DETECTION(
        "Banking and identity apps unaffected",
        "The decisive practical difference. A tunnel sets TRANSPORT_VPN; Private DNS does not.",
    ),
}
