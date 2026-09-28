package com.example.childsafetyimpl.data.source

import android.content.Context
import com.example.childsafetyimpl.domain.model.WebCategory
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Category host lists too large to live in Room, keyed by the category they belong to. */
fun interface HostListSource {
    fun load(): Map<WebCategory, HostSet>
}

/**
 * The bundled category lists in `assets/blocklists`, read once.
 *
 * [SeedDomainRules] proves the mechanism with a few dozen names; this is the coverage. Measured
 * against the UT1 adult category crossed with the Tranco top million, the seed list alone caught
 * 0.5% of the adult sites people actually visit and this list catches about 72%. The long tail of
 * obscure and newly registered sites stays mostly uncaught, which the category's description says.
 *
 * The lists are kept out of Room on purpose: 64k rows would be a slow first-run insert and a scan
 * the cache then has to hold as strings. Sources and licences are in `blocklists/NOTICE.txt`.
 */
@Singleton
class BundledHostLists @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : HostListSource {

    private val lists by lazy {
        ASSETS.mapValues { (_, path) ->
            runCatching {
                context.assets.open(path).use { HostSet.parse(it.readBytes(), NEVER_BLOCK) }
            }.getOrDefault(HostSet.EMPTY)
        }
    }

    override fun load(): Map<WebCategory, HostSet> = lists

    companion object {
        val ASSETS = mapOf(
            WebCategory.ADULT to "blocklists/adult-hosts.txt",
            WebCategory.VIOLENCE to "blocklists/violence-hosts.txt",
        )

        /**
         * Entries the upstream lists carry that must not be refused under these categories.
         *
         * Each was found by checking the list against the Tranco top 10k by hand. They are
         * content delivery networks and general file or image hosts, where one entry takes
         * unrelated sites down with it, or sites that are simply not adult: a search engine, a
         * film database, a newspaper, a magazine, a marketplace, torrent and streaming indexes.
         * Blocking the wrong popular site is how a filter gets switched off, so the list is
         * allowed to miss rather than to over-reach. A parent can still block any of them by name.
         */
        val NEVER_BLOCK: Set<String> = setOf(
            // CDNs and general hosting
            "b-cdn.net", "mjedge.net", "fc2.com", "imgbox.com", "fastpic.ru", "bitcheese.net",
            // Not adult
            "yandex.by", "kinopoisk.ru", "ekstrabladet.dk", "refinery29.com", "booth.pm",
            "1337x.to", "1377x.to", "qfilm.tv", "movieffm.net", "looti.net",
        )
    }
}
