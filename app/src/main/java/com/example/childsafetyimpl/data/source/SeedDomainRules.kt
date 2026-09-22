package com.example.childsafetyimpl.data.source

import com.example.childsafetyimpl.data.local.DomainRuleEntity
import com.example.childsafetyimpl.data.local.WebCategorySettingEntity
import com.example.childsafetyimpl.domain.model.RuleAction
import com.example.childsafetyimpl.domain.model.RuleSource
import com.example.childsafetyimpl.domain.model.WebCategory

/**
 * A compact seeded blocklist.
 *
 * This is a demonstration list, not a safety product's list. Real category coverage means a
 * licensed, continuously maintained feed running to millions of entries; the catalog records that
 * explicitly against the category-blocking capability. What this list proves is the mechanism:
 * that a name reaching the tunnel or the browser can be matched, categorised and refused.
 */
object SeedDomainRules {

    private val adult = listOf(
        "pornhub.com", "xvideos.com", "xnxx.com", "redtube.com", "youporn.com",
        "xhamster.com", "onlyfans.com", "chaturbate.com", "stripchat.com", "spankbang.com",
    )

    private val gambling = listOf(
        "bet365.com", "888casino.com", "pokerstars.com", "draftkings.com", "fanduel.com",
        "betway.com", "stake.com", "roobet.com", "1xbet.com",
    )

    private val violence = listOf(
        "bestgore.com", "liveleak.com", "theync.com", "kaotic.com",
    )

    private val malware = listOf(
        "malware-traffic-analysis.net", "phishing-example.com", "testsafebrowsing.appspot.com",
    )

    private val dating = listOf(
        "tinder.com", "bumble.com", "badoo.com", "grindr.com", "okcupid.com", "hinge.co",
    )

    private val social = listOf(
        "instagram.com", "facebook.com", "tiktok.com", "snapchat.com", "x.com",
        "twitter.com", "reddit.com", "discord.com",
    )

    private val streaming = listOf(
        "youtube.com", "twitch.tv", "dailymotion.com", "rumble.com",
    )

    /**
     * Category switches. Adult, gambling, violence and malware default to blocked because a
     * child-safety product that ships them off by default is not a child-safety product. Social
     * and streaming default to allowed: they are a parenting decision, not a safety absolute.
     */
    val categoryDefaults: List<WebCategorySettingEntity> = listOf(
        WebCategorySettingEntity(WebCategory.ADULT, blocked = true),
        WebCategorySettingEntity(WebCategory.GAMBLING, blocked = true),
        WebCategorySettingEntity(WebCategory.VIOLENCE, blocked = true),
        WebCategorySettingEntity(WebCategory.MALWARE, blocked = true),
        WebCategorySettingEntity(WebCategory.DATING, blocked = true),
        WebCategorySettingEntity(WebCategory.SOCIAL, blocked = false),
        WebCategorySettingEntity(WebCategory.STREAMING, blocked = false),
        WebCategorySettingEntity(WebCategory.UNCATEGORISED, blocked = false),
    )

    val rules: List<DomainRuleEntity> = buildList {
        fun addAll(hosts: List<String>, category: WebCategory) {
            hosts.forEach {
                add(
                    DomainRuleEntity(
                        pattern = it,
                        category = category,
                        action = RuleAction.BLOCK,
                        source = RuleSource.SEEDED_LIST,
                        enabled = true,
                    )
                )
            }
        }
        addAll(adult, WebCategory.ADULT)
        addAll(gambling, WebCategory.GAMBLING)
        addAll(violence, WebCategory.VIOLENCE)
        addAll(malware, WebCategory.MALWARE)
        addAll(dating, WebCategory.DATING)
        addAll(social, WebCategory.SOCIAL)
        addAll(streaming, WebCategory.STREAMING)
    }

    /**
     * Search engines and the parameter each one uses for strict filtering.
     * Kept as data rather than branching code because these change without notice.
     */
    val safeSearchParameters: Map<String, Pair<String, String>> = mapOf(
        "google.com" to ("safe" to "active"),
        "www.google.com" to ("safe" to "active"),
        "bing.com" to ("adlt" to "strict"),
        "www.bing.com" to ("adlt" to "strict"),
        "duckduckgo.com" to ("kp" to "1"),
        "search.yahoo.com" to ("vm" to "r"),
        "yandex.com" to ("fyandex" to "1"),
    )
}
