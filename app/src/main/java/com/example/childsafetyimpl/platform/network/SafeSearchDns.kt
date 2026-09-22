package com.example.childsafetyimpl.platform.network

import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Forcing SafeSearch on every app, not only on ours.
 *
 * A child typing "bikini" into Chrome is the case a filter built only from host blocking cannot
 * answer. The host is google.com, the host is allowed, and nothing about the lookup says what was
 * searched for. Blocking the search engine instead is not a filter, it is an outage.
 *
 * Google, Microsoft, DuckDuckGo and YouTube each publish an address that serves the same site with
 * filtering switched on and no control inside the browser to switch it off. Answering the name
 * lookup with that address is the documented way to enforce it, it is what schools and network
 * filters use, and because it happens at the DNS layer it applies to Chrome, Samsung Internet and
 * every other app on the device rather than only to a browser this app wrote.
 *
 * Two limits are worth stating plainly, and the screen that claims this says both. SafeSearch is
 * the search engine's own judgement, so it removes explicit imagery but does not pretend to be a
 * topic filter: a search for a drug name still returns encyclopaedia entries. And an app that
 * resolves names over DNS-over-HTTPS never asks us at all, which is why the endpoints those
 * lookups use are refused here.
 */
object SafeSearchDns {

    const val GOOGLE_SAFE = "forcesafesearch.google.com"
    const val BING_SAFE = "strict.bing.com"
    const val DUCKDUCKGO_SAFE = "safe.duckduckgo.com"
    const val YOUTUBE_RESTRICTED = "restrict.youtube.com"

    private val exactAliases: Map<String, String> = buildMap {
        listOf(
            "www.bing.com", "bing.com", "cn.bing.com",
        ).forEach { put(it, BING_SAFE) }

        listOf(
            "duckduckgo.com", "www.duckduckgo.com", "start.duckduckgo.com",
        ).forEach { put(it, DUCKDUCKGO_SAFE) }

        // The set YouTube documents for restricted mode. The API host is included on purpose:
        // without it the app keeps its own unrestricted data path and only the website changes.
        listOf(
            "www.youtube.com", "m.youtube.com", "youtube.com", "youtubei.googleapis.com",
            "youtube.googleapis.com", "www.youtube-nocookie.com",
        ).forEach { put(it, YOUTUBE_RESTRICTED) }
    }

    /**
     * Google runs a country domain for almost every country on earth, and a child switching to
     * google.co.in is one tap in the browser. Matching the shape rather than listing them is the
     * only version of this that holds.
     */
    private val googleDomain = Regex("^(www\\.)?google\\.(com|[a-z]{2,3}(\\.[a-z]{2})?)$")

    /**
     * Endpoints a browser uses to resolve names over HTTPS, which is how it leaves the tunnel.
     *
     * The catalog itself lives in [EncryptedDnsPolicy], which also carries the address-level
     * defence for resolvers configured by IP. Kept as an alias here so existing callers and tests
     * do not have to care which class owns the list.
     */
    val encryptedDnsHosts: Set<String> get() = EncryptedDnsPolicy.resolverHosts

    /**
     * Where a lookup should be answered from instead, or null to leave it alone.
     *
     * YouTube is included because restricted mode cannot be switched on from inside the YouTube
     * app by any API, and this is the one route that reaches it.
     */
    fun aliasFor(host: String): String? {
        val name = host.lowercase().trimEnd('.')
        exactAliases[name]?.let { return it }
        return if (googleDomain.matches(name)) GOOGLE_SAFE else null
    }

    fun blocksEncryptedDns(host: String): Boolean = EncryptedDnsPolicy.isResolverHost(host)

    /** Every alias, for the pre-warm that happens when the tunnel starts. */
    val aliases: Set<String> =
        exactAliases.values.toSet() + GOOGLE_SAFE

    // --- address resolution ----------------------------------------------------------------

    /**
     * Published addresses, used only when a live lookup has not landed yet.
     *
     * Hardcoding an address normally rots. These are the exception: they are documented endpoints
     * that filtering products have pointed at for years, and the alternative to having them is a
     * tunnel that forwards the first search of every session unfiltered while it resolves.
     */
    private val fallbackAddresses: Map<String, String> = mapOf(
        GOOGLE_SAFE to "216.239.38.120",
        YOUTUBE_RESTRICTED to "216.239.38.120",
        BING_SAFE to "204.79.197.220",
    )

    private val resolved = ConcurrentHashMap<String, ByteArray>()

    /**
     * The address to answer with, or null when neither a lookup nor a fallback has one.
     *
     * Deliberately never blocks. This runs inside the packet pump, where a name lookup of our own
     * would stall every other app's DNS behind it.
     */
    fun addressFor(alias: String): ByteArray? =
        resolved[alias] ?: fallbackAddresses[alias]?.let { parseIpv4(it) }

    /** Resolves every alias once, off the pump, so the answers above are the real current ones. */
    fun warm() {
        aliases.forEach { alias ->
            runCatching {
                InetAddress.getAllByName(alias)
                    .firstOrNull { it.address.size == 4 }
                    ?.address
                    ?.let { resolved[alias] = it }
            }
        }
    }

    private fun parseIpv4(value: String): ByteArray? {
        val parts = value.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        parts.forEachIndexed { index, part ->
            val number = part.toIntOrNull() ?: return null
            if (number !in 0..255) return null
            bytes[index] = number.toByte()
        }
        return bytes
    }
}
