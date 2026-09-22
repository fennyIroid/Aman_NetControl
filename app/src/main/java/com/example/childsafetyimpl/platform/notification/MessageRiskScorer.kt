package com.example.childsafetyimpl.platform.notification

import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device risk scoring for a message preview.
 *
 * Runs entirely in memory and returns only a score and the categories that matched. The text
 * itself never leaves this function, which is what allows the communication capability to exist
 * without the app becoming a message archive.
 *
 * This is a keyword scorer, and it is honest about being one. It catches blunt cases and will miss
 * anything indirect, coded or in another language. A production version needs a small on-device
 * classifier; the catalog records that gap against the notification-monitoring capability.
 */
@Singleton
class MessageRiskScorer @Inject constructor() {

    data class Verdict(val score: Int, val tags: List<String>)

    private val categories: Map<String, Pair<Int, List<String>>> = mapOf(
        "grooming" to (40 to listOf(
            "don't tell your parents", "dont tell your parents", "our secret", "keep this between us",
            "how old are you", "send a pic", "send pics", "are you alone", "delete this chat",
            "meet me alone", "don't tell anyone", "dont tell anyone",
        )),
        "bullying" to (28 to listOf(
            "kill yourself", "kys", "nobody likes you", "you're worthless", "youre worthless",
            "loser", "everyone hates you", "you should die",
        )),
        "self-harm" to (45 to listOf(
            "want to die", "end it all", "hurt myself", "cut myself", "suicide", "no reason to live",
        )),
        "sexual" to (35 to listOf(
            "nudes", "sexting", "send nudes", "dick pic", "horny",
        )),
        "threat" to (30 to listOf(
            "i'll find you", "ill find you", "i know where you live", "you're dead", "youre dead",
            "watch your back",
        )),
        "money" to (18 to listOf(
            "send money", "gift card", "crypto", "bank details", "don't tell your mum",
        )),
    )

    fun score(text: String): Verdict {
        if (text.isBlank()) return Verdict(0, emptyList())
        val normalised = text.lowercase()
        var total = 0
        val tags = mutableListOf<String>()

        categories.forEach { (tag, definition) ->
            val (weight, phrases) = definition
            if (phrases.any { normalised.contains(it) }) {
                total += weight
                tags += tag
            }
        }
        return Verdict(score = total.coerceAtMost(100), tags = tags)
    }

    /** Exposed so the lab can show what the scorer is actually looking for. */
    val watchedCategoryCount: Int get() = categories.size
    val watchedPhraseCount: Int get() = categories.values.sumOf { it.second.size }

    companion object {
        const val ALERT_THRESHOLD = 30
    }
}
