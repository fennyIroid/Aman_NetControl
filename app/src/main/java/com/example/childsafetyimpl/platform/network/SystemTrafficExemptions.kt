package com.example.childsafetyimpl.platform.network

/**
 * The hosts a phone needs in order to keep working, kept reachable in allow-list-only mode.
 *
 * Allow-list-only refuses every name the parent has not listed, and the parent lists websites. A
 * phone is not only websites: with nothing else allowed, app updates stop, push notifications
 * stop arriving, the clock stops syncing and Android decides the Wi-Fi has no internet. A parent
 * sees a broken phone, not a strict filter, and a strict filter that reads as a broken phone is
 * switched off by the end of the day. The POC 2 results recorded exactly that and held the mode
 * back until this list existed.
 *
 * The rule for membership is narrow on purpose: **a host belongs here only if it is plumbing a
 * child cannot browse.** Connectivity probes, the Play Store's download and update endpoints,
 * push delivery, account sign-in and time. No search engine, no video site, no social network, no
 * general-purpose CDN a whole website can be served from. `SystemTrafficExemptionsTest` holds the
 * line on the ones that would matter most, because the failure this list invites is someone
 * adding a host "to make an app work" that turns out to be a way around the filter.
 *
 * Exempting a host here does not outrank the parent: an explicit block or a blocked category is
 * decided before allow-list-only is consulted, so this only ever changes a refusal that happened
 * because a host was *unlisted*.
 */
object SystemTrafficExemptions {

    /** Matched on the label boundary, like every other host rule in this app. */
    val hostSuffixes: List<String> = listOf(
        // Connectivity checks. Without these Android marks the network as having no internet
        // and some builds stop using it.
        "connectivitycheck.gstatic.com",
        "connectivitycheck.android.com",
        "clients3.google.com",
        "clients1.google.com",

        // Network time.
        "time.android.com",
        "time.google.com",

        // Push delivery (Firebase Cloud Messaging), including the numbered fallback hosts a
        // device switches to when the main one is unreachable.
        "mtalk.google.com",
        "alt1-mtalk.google.com",
        "alt2-mtalk.google.com",
        "alt3-mtalk.google.com",
        "alt4-mtalk.google.com",
        "alt5-mtalk.google.com",
        "alt6-mtalk.google.com",
        "alt7-mtalk.google.com",
        "alt8-mtalk.google.com",
        "fcm.googleapis.com",
        "fcmtoken.googleapis.com",
        "firebaseinstallations.googleapis.com",
        "android.apis.google.com",

        // Google Play: store front end, app downloads and updates, Play services.
        "play.googleapis.com",
        "play-fe.googleapis.com",
        "android.clients.google.com",
        "android.googleapis.com",
        "play-lh.googleusercontent.com",
        "gvt1.com",
        "gvt2.com",
        "gvt3.com",
        "dl.google.com",

        // Account sign-in, which Play and push both depend on.
        "accounts.google.com",
    )

    fun isExempt(host: String): Boolean {
        val name = host.lowercase().trimEnd('.')
        return hostSuffixes.any { name == it || name.endsWith(".$it") }
    }
}
