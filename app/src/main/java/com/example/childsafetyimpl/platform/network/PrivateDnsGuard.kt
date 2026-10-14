package com.example.childsafetyimpl.platform.network

import android.content.Context
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android's Private DNS setting: read it, judge it, and say what it means.
 *
 * Private DNS is how this product filters the web. Every name lookup on the device goes to a
 * filtering resolver over TLS, on Wi-Fi and on mobile data, with no tunnel, no VPN key in the
 * status bar and nothing for a banking app to object to. This class is what makes that usable by
 * a product rather than by a person who already knows what DNS-over-TLS is.
 *
 * Three facts shape everything here, and all three are platform constraints rather than choices:
 *
 * 1. **An ordinary app cannot set this.** `Settings.Global.private_dns_mode` is writable only by
 *    a Device Owner, which [com.example.childsafetyimpl.platform.admin.NetworkLockdown] does when
 *    the app is one. Everywhere else a parent types the hostname into Settings, and the product's
 *    job is to make that unambiguous and then to notice if it changes.
 * 2. **An app can read it.** Which is what makes the guard possible: the setting is checked on
 *    every monitoring tick and a change reported immediately.
 * 3. **Reading it is not guaranteed forever.** It lives in `Settings.Global`, which OEMs have
 *    been known to restrict, so every read here is defensive and an unreadable setting is
 *    reported as unknown rather than as "fine".
 *
 * What a resolver cannot do is anything that is not a name lookup. It cannot pause the internet
 * (the pause raises a tunnel for exactly as long as it lasts; see [PauseTunnelService]), it cannot
 * strip ads in apps this product does not own, and a public resolver cannot hold one family's own
 * block and allow lists. Those lists are applied on the device instead, by reading the address
 * bar of every browser (`SearchTermGuard`) and inside the built-in browser.
 *
 * The distinction this class refuses to blur: a resolver we do not recognise is not the same as
 * no resolver. A child who points Private DNS at `dns.google` has not disabled filtering in a way
 * that looks like an error — they have replaced it with an unfiltered resolver, and every lookup
 * on the device now leaves encrypted and unfilterable. That is the single most effective bypass
 * available on an unmanaged Android device, and [Posture.ForeignResolver] is the state that says
 * so out loud.
 */
@Singleton
class PrivateDnsGuard @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    /**
     * Where Private DNS currently stands.
     *
     * Deliberately not a boolean. "Off", "pointed at a filter", "pointed at someone else" and "we
     * could not tell" need four different sentences on a parent's screen and produce three
     * different severities in the event log.
     */
    sealed interface Posture {

        /** Off, or opportunistic, so lookups fall through to the network's own resolver. */
        data object NotConfigured : Posture

        /** Pointed at a resolver this product recognises as filtered. */
        data class Filtering(val host: String) : Posture

        /**
         * Pointed at a named resolver that does not filter.
         *
         * The bypass. Every lookup is encrypted to a third party and nothing in this app can see
         * or refuse it.
         */
        data class ForeignResolver(val host: String) : Posture

        /** The setting could not be read on this build. */
        data class Unknown(val reason: String) : Posture
    }

    fun currentPosture(): Posture {
        // A read that throws is a build refusing to answer. A read that returns nothing is a
        // setting nobody has touched, which on every build means Automatic. Treating the second as
        // the first reported "could not be checked" on phones that had simply never set it.
        val mode = runCatching { readSetting(MODE_KEY) }.getOrElse {
            return Posture.Unknown(
                "This Android build does not let an app read the Private DNS setting, so it " +
                    "cannot be checked from here.",
            )
        }

        val host = runCatching { readSetting(HOST_KEY) }.getOrNull()?.takeIf { it.isNotBlank() }

        return when {
            mode == null || mode == "off" || mode == "opportunistic" -> Posture.NotConfigured
            host == null -> Posture.NotConfigured
            isFilteringResolver(host) -> Posture.Filtering(host)
            else -> Posture.ForeignResolver(host)
        }
    }

    fun isFilteringResolver(host: String): Boolean = isFilteringResolverHost(host)

    /**
     * What to tell the parent, in the shape the brief asks issues to take: what is wrong, why it
     * matters, and what to do about it.
     *
     * Private DNS is the filter, so an unset setting is never a neutral fact: it is the whole
     * filter missing, and saying "nothing is wrong" there would be the calm green screen the
     * health model exists to prevent. For the same reason the fix is never "set it to Automatic",
     * which is no filter at all.
     */
    fun explain(posture: Posture): Advice = when (posture) {
        is Posture.Filtering -> Advice(
            headline = "Private DNS is filtering",
            whatItMeans = "Every name lookup on this device goes to ${posture.host} over an " +
                "encrypted connection, on Wi-Fi and on mobile data." +
                (resolverFor(posture.host)?.let { " ${it.covers}" } ?: ""),
            whatToDo = null,
        )

        Posture.NotConfigured -> Advice(
            headline = "Private DNS is not set",
            whatItMeans = "Web filtering on this device runs through Android's Private DNS, and " +
                "it is not pointed at a filtering resolver, so websites are not being filtered.",
            whatToDo = "Open Settings, Network and internet, Private DNS, choose \"Private DNS " +
                "provider hostname\" and enter the hostname shown here.",
        )

        is Posture.ForeignResolver -> Advice(
            headline = "Private DNS points somewhere that does not filter",
            whatItMeans = "Name lookups are being sent to ${posture.host} in an encrypted " +
                "session. Website blocking and Safe Search cannot apply to them. This is the " +
                "most effective way around the filter on a phone that is not managed, and it " +
                "takes three taps in Settings.",
            whatToDo = "Open Settings, Network and internet, Private DNS, and put the hostname " +
                "shown here back. Android does not allow an app to change this for you unless " +
                "the device is managed.",
        )

        is Posture.Unknown -> Advice(
            headline = "Private DNS could not be checked",
            whatItMeans = posture.reason,
            whatToDo = "Check Settings, Network and internet, Private DNS by hand.",
        )
    }

    data class Advice(
        val headline: String,
        val whatItMeans: String,
        /** Null when nothing is wrong, which is what keeps the UI from inventing a call to action. */
        val whatToDo: String?,
    )

    /** Throws when the build refuses the read; see [currentPosture] for why that matters. */
    private fun readSetting(key: String): String? =
        Settings.Global.getString(context.contentResolver, key)

    companion object {
        /**
         * The resolver a parent is told to enter until the per-family one exists.
         *
         * CleanBrowsing's family filter is the default because it is the only public one offered
         * here that also refuses the ways round a resolver by name — web proxies, VPN providers'
         * sites, and other DNS services a browser could be pointed at — and forces SafeSearch on
         * YouTube too. Measured, it is also the weakest on adult coverage and on Arabic sites,
         * and it refuses some Moroccan government sites. It is a stand-in: it cannot hold this
         * family's own block and allow lists, which is the Phase 1 backend work.
         */
        const val DEFAULT_HOST = "family-filter-dns.cleanbrowsing.org"

        /**
         * Public filtering resolvers a parent can pick from.
         *
         * What each one covers was measured over DNS-over-TLS on 2026-10-09, not taken from the
         * providers' pages (docs/sprint0/Website-Blocking-Coverage.md). No one of them does
         * everything: the one that refuses proxies, VPN sites and other resolvers by name is the
         * weakest on adult sites and on Arabic ones, and the strongest on those refuses none of
         * the ways round it. That gap is the case for a resolver of our own.
         */
        val resolvers: List<PrivateDnsResolver> = listOf(
            PrivateDnsResolver(
                hostname = DEFAULT_HOST,
                label = "CleanBrowsing Family",
                covers = "Refuses proxies, VPN sites and other DNS services, and forces " +
                    "SafeSearch and YouTube Restricted Mode. Misses about a third of popular " +
                    "adult sites and most Arabic ones.",
                forcesSafeSearch = true,
                restrictsYouTube = true,
            ),
            PrivateDnsResolver(
                hostname = "family.cloudflare-dns.com",
                label = "Cloudflare for Families",
                covers = "Widest adult coverage, Arabic sites included, and almost never " +
                    "refuses an ordinary site. Forces SafeSearch on Google and Bing but not " +
                    "YouTube, and leaves proxies, VPN sites and other DNS services open.",
                forcesSafeSearch = true,
                restrictsYouTube = false,
            ),
            PrivateDnsResolver(
                hostname = "family.adguard-dns.com",
                label = "AdGuard Family",
                covers = "Wide adult coverage, and refuses ad and tracker hosts in every app. " +
                    "Forces SafeSearch and YouTube Restricted Mode. Leaves proxies, VPN sites " +
                    "and other DNS services open.",
                forcesSafeSearch = true,
                restrictsYouTube = true,
            ),
        )

        fun resolverFor(host: String): PrivateDnsResolver? {
            val name = host.lowercase().trimEnd('.')
            return resolvers.firstOrNull { it.hostname == name }
        }

        /**
         * Resolvers this product treats as already filtering.
         *
         * The family-safe endpoints the major providers publish, plus the per-family hostname this
         * product would issue once it operates its own resolver. Recognising the third-party ones
         * matters: a parent who already set up CleanBrowsing should be told their filtering is
         * working, not nagged to replace it with ours.
         */
        //
        // Only family endpoints belong here. AdGuard's dns.adguard.com and dns.adguard-dns.com
        // refuse ads and nothing else — pornhub.com resolves through them — so listing either
        // would tell a parent adult sites are refused when they are not. KAHF's dns.kahfguard.com
        // does not answer on port 853 and so cannot be a Private DNS host at all.
        private val filteringResolverSuffixes = listOf(
            "families.cloudflare-dns.com",
            "family.cloudflare-dns.com",
            "family.adguard-dns.com",
            "dns-family.adguard.com",
            "family-filter-dns.cleanbrowsing.org",
            "adult-filter-dns.cleanbrowsing.org",
            "doh.familyshield.opendns.com",
            "familyshield.opendns.com",
            "high.kahfguard.com",
            // Reserved for the per-family resolver this product issues in Phase 1.
            "dns.aman.app",
        )

        private const val MODE_KEY = "private_dns_mode"
        private const val HOST_KEY = "private_dns_specifier"

        /** Pure, so the setup screen's resolvers can be checked against it without a device. */
        fun isFilteringResolverHost(host: String): Boolean {
            val name = host.lowercase().trimEnd('.')
            return filteringResolverSuffixes.any { name == it || name.endsWith(".$it") }
        }
    }
}

/** A public resolver that filters, offered while there is no per-family resolver of our own. */
data class PrivateDnsResolver(
    val hostname: String,
    val label: String,
    /** What this resolver refuses, in a parent's words. */
    val covers: String,
    /**
     * Whether it answers search-engine lookups with their SafeSearch address. Device-wide
     * SafeSearch depends on this and nothing else now that no tunnel rewrites those lookups.
     */
    val forcesSafeSearch: Boolean,
    /** Whether YouTube's lookups get its Restricted Mode address. Independent of SafeSearch. */
    val restrictsYouTube: Boolean,
)
