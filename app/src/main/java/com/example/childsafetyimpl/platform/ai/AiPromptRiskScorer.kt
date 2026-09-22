package com.example.childsafetyimpl.platform.ai

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Risk scoring for something typed into an AI assistant.
 *
 * This is a separate scorer from the one used on message notifications, because children ask AI
 * models things they would never send to a person. The dangerous pattern is a question, not an
 * insult: "how many paracetamol is too many" carries none of the markers a bullying scorer looks
 * for and is far more urgent than most of them.
 *
 * The scorer runs entirely in memory and returns a number and a list of categories. The text is
 * a parameter and never leaves the function, which is what lets AI monitoring exist without the
 * app becoming a transcript of a child's private questions.
 *
 * It is a phrase scorer and honest about being one. It catches direct phrasing and will miss
 * anything oblique, coded, or in another language. The alert threshold is set so that a single
 * category cannot raise an alert on its own unless that category is one where a false positive
 * costs a conversation and a false negative costs more than that.
 */
@Singleton
class AiPromptRiskScorer @Inject constructor() {

    data class Verdict(val score: Int, val tags: List<String>)

    private data class Category(
        val tag: String,
        val weight: Int,
        val phrases: List<String>,
    )

    private val categories: List<Category> = listOf(
        Category(
            tag = "self-harm",
            // Weighted above the alert threshold on its own. A child asking an AI this is the
            // case the whole protection exists for, and a parent being told about a false
            // positive here is a conversation, not a harm.
            weight = 60,
            phrases = listOf(
                "kill myself", "end my life", "want to die", "how to die",
                "hurt myself", "cut myself", "self harm", "overdose",
                "how many pills", "painless way", "no reason to live",
                "suicide", "unalive myself", "not want to be here anymore",
            ),
        ),
        Category(
            tag = "eating",
            weight = 42,
            phrases = listOf(
                "how to not eat", "stop eating", "lose weight fast", "how to purge",
                "make myself throw up", "hide from my parents that i", "thinspo",
                "how many calories to starve", "appetite suppressant",
            ),
        ),
        Category(
            tag = "sexual",
            weight = 45,
            phrases = listOf(
                "sexting", "send nudes", "explicit story", "nsfw", "erotic",
                "roleplay as my girlfriend", "roleplay as my boyfriend",
                "describe sex", "sexual roleplay",
            ),
        ),
        Category(
            tag = "violence",
            weight = 45,
            phrases = listOf(
                "how to make a bomb", "how to hurt someone", "how to fight someone",
                "how to get a gun", "how to poison", "make a weapon", "how to stab",
            ),
        ),
        Category(
            tag = "substances",
            weight = 35,
            phrases = listOf(
                "how to get weed", "how to get alcohol", "how to vape", "buy drugs",
                "how to roll a joint", "hide the smell of", "fake id",
            ),
        ),
        Category(
            tag = "secrecy",
            // Individually weak. It matters as corroboration: a question about substances asked
            // alongside "don't tell my parents" is a different question.
            weight = 22,
            phrases = listOf(
                "don't tell my parents", "dont tell my parents", "without my parents knowing",
                "how to hide from my mum", "how to hide from my dad", "delete this chat",
                "how to hide an app", "how to bypass parental",
            ),
        ),
        Category(
            tag = "contact",
            weight = 30,
            phrases = listOf(
                "meet someone i met online", "someone online asked me", "he asked for my address",
                "she asked for my address", "asked me for pictures", "someone is threatening me",
            ),
        ),
        Category(
            tag = "cheating",
            weight = 12,
            phrases = listOf(
                "write my essay", "do my homework for me", "answers to my test",
                "write this so my teacher", "avoid plagiarism detection",
            ),
        ),
    )

    fun score(text: String): Verdict {
        if (text.isBlank()) return Verdict(0, emptyList())
        val normalised = text.lowercase()

        var total = 0
        val tags = mutableListOf<String>()

        categories.forEach { category ->
            if (category.phrases.any { normalised.contains(it) }) {
                total += category.weight
                tags += category.tag
            }
        }

        return Verdict(score = total.coerceAtMost(100), tags = tags)
    }

    /** Exposed so the protection screen can show what the scorer is actually watching for. */
    val watchedCategoryCount: Int get() = categories.size
    val watchedPhraseCount: Int get() = categories.sumOf { it.phrases.size }
    val categoryNames: List<String> get() = categories.map { it.tag }

    companion object {
        /** A single serious category clears this; two weak ones together also do. */
        const val ALERT_THRESHOLD = 40

        /** Raises the event to critical and posts a notification immediately. */
        const val URGENT_THRESHOLD = 60
    }
}
