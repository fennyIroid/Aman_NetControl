package com.example.childsafetyimpl.platform.network

import android.content.Context
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android's Private DNS setting: read it, judge it, and say what it means.
 *
 * This is the other half of the architecture comparison in [FilteringMode]. Private DNS is the
 * quiet posture — filtering with no tunnel, no VPN icon and nothing for a banking app to object
 * to — and this class is what makes it usable by a product rather than by a person who already
 * knows what DNS-over-TLS is.
 *
 * Three facts shape everything here, and all three are platform constraints rather than choices:
 *
 * 1. **An app cannot set this.** `Settings.Global.private_dns_mode` is not writable without
 *    Device Owner. A parent types the hostname into Settings themselves, and the product's job is
 *    to make that unambiguous and then to notice if it changes.
 * 2. **An app can read it.** Which is what makes the guard possible: the setting can be checked
 *    on every monitoring tick and a change reported immediately.
 * 3. **Reading it is not guaranteed forever.** It lives in `Settings.Global`, which OEMs have
 *    been known to restrict, so every read here is defensive and an unreadable setting is
 *    reported as unknown rather than as "fine".
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
     * Deliberately not a boolean. "Off", "pointed at us", "pointed at someone else" and "we could
     * not tell" need four different sentences on a parent's screen and produce three different
     * severities in the event log.
     */
    sealed interface Posture {

        /** Off, or opportunistic, so lookups fall through to whatever resolver is in use. */
        data object NotConfigured : Posture

        /** Pointed at a resolver this product recognises as filtered. */
        data class Filtering(val host: String) : Posture

        /**
         * Pointed at a named resolver that is not ours.
         *
         * The bypass. Every lookup is encrypted to a third party and neither the tunnel nor any
         * rule in this app can see or refuse it.
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
     */
    fun explain(
        posture: Posture,
        filtering: FilteringMode = FilteringMode.VPN_TUNNEL,
    ): Advice {
        // In the Private DNS postures an unset setting is not a neutral fact: it is the whole
        // filter missing, and saying "nothing is wrong" there would be the calm green screen the
        // health model exists to prevent.
        if (posture == Posture.NotConfigured && filtering.usesPrivateDns) {
            return Advice(
                headline = "Private DNS is not set",
                whatItMeans = "${filtering.label} filters through Android's Private DNS, and it " +
                    "is not pointed at a filtering resolver, so websites are not being filtered " +
                    "on this device.",
                whatToDo = "Open Settings, Network and internet, Private DNS, choose \"Private " +
                    "DNS provider hostname\" and enter the hostname shown here.",
            )
        }
        val advice = explainPosture(posture)
        // Pointing it back at Automatic would be the right fix for the tunnel, and the wrong one
        // here: in these postures Automatic is no filter at all.
        return if (posture is Posture.ForeignResolver && filtering.usesPrivateDns) {
            advice.copy(
                whatToDo = "Open Settings, Network and internet, Private DNS, and put the " +
                    "hostname shown here back. Android does not allow any app to change this " +
                    "for you.",
            )
        } else {
            advice
        }
    }

    private fun explainPosture(posture: Posture): Advice = when (posture) {
        is Posture.Filtering -> Advice(
            headline = "Private DNS is filtering",
            whatItMeans = "Every name lookup on this device goes to ${posture.host} over an " +
                "encrypted connection, on Wi-Fi and on mobile data.",
            whatToDo = null,
        )

        Posture.NotConfigured -> Advice(
            headline = "Private DNS is not set",
            whatItMeans = "Nothing is wrong. Filtering is running through this app instead, " +
                "which does the same job and adds the controls a resolver cannot do on its own.",
            whatToDo = null,
        )

        is Posture.ForeignResolver -> Advice(
            headline = "Private DNS points somewhere this app cannot filter",
            whatItMeans = "Name lookups are being sent to ${posture.host} in an encrypted " +
                "session. Website blocking and Safe Search cannot apply to them, because this " +
                "app never sees them. This is the most effective way around the filter on a " +
                "phone that is not managed, and it takes three taps in Settings.",
            whatToDo = "Open Settings, Network and internet, Private DNS, and set it back to " +
                "Automatic. Android does not allow any app to change this for you.",
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
         * Resolvers this product treats as already filtering.
         *
         * The family-safe endpoints the major providers publish, plus the per-family hostname this
         * product would issue once it operates its own resolver. Recognising the third-party ones
         * matters: a parent who already set up CleanBrowsing should be told their filtering is
         * working, not nagged to replace it with ours.
         */
        private val filteringResolverSuffixes = listOf(
            "families.cloudflare-dns.com",
            "family.cloudflare-dns.com",
            "dns.adguard.com",
            "dns-family.adguard.com",
            "family-filter-dns.cleanbrowsing.org",
            "adult-filter-dns.cleanbrowsing.org",
            "doh.familyshield.opendns.com",
            "familyshield.opendns.com",
            "dns.kahfguard.com",
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
