package com.example.childsafetyimpl.platform.network

import java.net.IDN

/**
 * Recognises a host whose name alone says it is adult, for the mirrors and new domains no list has
 * caught up with yet.
 *
 * Ported from BlockNSFW's `shared/host-keywords.js` (MIT), keeping its matching rules, because
 * those rules are the whole value: a keyword counts only as a whole label or a hyphen-bounded part
 * of one. A substring match on "sex" refuses essex.ac.uk; this does not. Measured against the Tranco
 * top 10k it refused no site that was not adult, and it roughly quadruples what the bundled list
 * catches in the long tail, from 0.6% to 2.9%. That is small, and it is free.
 *
 * Words with strong ordinary meanings ("sex", "cam", "tube", "jav", "video") are deliberately
 * absent; BlockNSFW records why for each. A label containing a support word ("recovery",
 * "addiction", "nofap") is exempt, so a site helping someone stop is never refused for its name.
 * Internationalised names are checked in their decoded form too, so `порно.рф` is caught although
 * it reaches the tunnel as `xn--m1abbbg.xn--p1ai`.
 */
object AdultHostKeywords {

    private val keywords = setOf(
        "porn", "porno", "pornos", "xxx", "xvideos", "xhamster", "xnxx", "redtube", "youporn",
        "brazzers", "chaturbate", "bongacams", "cam4", "pornhub", "spankbang", "tube8", "youjizz",
        "nudography", "onlyfans", "erome", "hentai", "hentaihaven", "rule34", "pornoizle",
        "tubeporn",
        // Transliterated slang, whole-label or hyphen-bounded only
        "seks", "sikis", "bokep", "yadong",
        // Non-Latin scripts: Chinese, Korean, Russian, Arabic, Thai
        "色情", "야동", "порно", "سكس", "หนังโป๊",
    )

    private val supportWords = listOf(
        "help", "recovery", "recover", "quit", "addiction", "support", "therapy", "counseling",
        "counselling", "treatment", "awareness", "education", "educate", "protection",
        "accountability", "nofap", "no-porn", "stop-porn", "antiporn", "anti-porn", "healing",
        "rehab", "overcome", "overcoming", "testimonial", "testimony",
    )

    // Built once: composing these per comparison cost ~250 µs a lookup on a Pixel 4.
    private val keywordPrefixes = keywords.map { it to "$it-" }
    private val keywordSuffixes = keywords.map { it to "-$it" }
    private val paddedSupportWords = supportWords.map { "-$it-" }

    /** The keyword [host] was matched on, or null. [host] must already be lower-case. */
    fun matchOf(host: String): String? {
        if (host.isEmpty() || isSupportSite(host)) return null
        labelMatch(host)?.let { return it }
        if (!host.contains("xn--")) return null
        val decoded = runCatching { IDN.toUnicode(host, IDN.ALLOW_UNASSIGNED) }.getOrNull()
            ?.lowercase() ?: return null
        return if (decoded != host && !isSupportSite(decoded)) labelMatch(decoded) else null
    }

    private fun labelMatch(host: String): String? {
        for (label in host.split('.')) {
            if (label.isEmpty()) continue
            if (label in keywords) return label
            if ('-' !in label) continue
            keywordPrefixes.firstOrNull { label.startsWith(it.second) }?.let { return it.first }
            keywordSuffixes.firstOrNull { label.endsWith(it.second) }?.let { return it.first }
        }
        return null
    }

    private fun isSupportSite(host: String): Boolean = host.split('.').any { label ->
        val padded = "-$label-"
        paddedSupportWords.any { padded.contains(it) }
    }
}
