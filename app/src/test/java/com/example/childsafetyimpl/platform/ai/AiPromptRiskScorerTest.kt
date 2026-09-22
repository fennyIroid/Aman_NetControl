package com.example.childsafetyimpl.platform.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scorer decides whether a parent is told their child asked an AI something worrying, so both
 * error directions have a cost: a missed self-harm question is the worse one, and a stream of
 * false alarms is what makes a parent stop reading the alerts that matter.
 */
class AiPromptRiskScorerTest {

    private val scorer = AiPromptRiskScorer()

    @Test
    fun `a self-harm question alerts on its own`() {
        val verdict = scorer.score("how many pills would it take")
        assertTrue(verdict.score >= AiPromptRiskScorer.ALERT_THRESHOLD)
        assertTrue("self-harm" in verdict.tags)
    }

    @Test
    fun `a self-harm question is urgent, not merely concerning`() {
        val verdict = scorer.score("I want to die and I do not know what to do")
        assertTrue(verdict.score >= AiPromptRiskScorer.URGENT_THRESHOLD)
    }

    @Test
    fun `secrecy alone does not alert`() {
        // Weighted below the threshold on purpose: "don't tell my parents" is what a child says
        // about a surprise birthday present as often as about anything else.
        val verdict = scorer.score("how do I do this without my parents knowing")
        assertTrue(verdict.score > 0)
        assertFalse(verdict.score >= AiPromptRiskScorer.ALERT_THRESHOLD)
    }

    @Test
    fun `secrecy combined with substances does alert`() {
        val verdict = scorer.score("how to get weed without my parents knowing")
        assertTrue(verdict.score >= AiPromptRiskScorer.ALERT_THRESHOLD)
        assertTrue("substances" in verdict.tags)
        assertTrue("secrecy" in verdict.tags)
    }

    @Test
    fun `ordinary homework help scores nothing`() {
        val verdict = scorer.score("can you explain how photosynthesis works for my science test")
        assertEquals(0, verdict.score)
        assertTrue(verdict.tags.isEmpty())
    }

    @Test
    fun `a request to write an essay is noted but does not alert`() {
        val verdict = scorer.score("write my essay about the Romans")
        assertTrue("cheating" in verdict.tags)
        assertFalse(verdict.score >= AiPromptRiskScorer.ALERT_THRESHOLD)
    }

    @Test
    fun `matching is case insensitive`() {
        assertTrue(scorer.score("HOW TO MAKE A BOMB").score >= AiPromptRiskScorer.ALERT_THRESHOLD)
    }

    @Test
    fun `blank text scores nothing`() {
        assertEquals(0, scorer.score("").score)
        assertEquals(0, scorer.score("    ").score)
    }

    @Test
    fun `the score is capped so several matches cannot exceed the scale`() {
        val verdict = scorer.score(
            "how to hurt myself and how to make a bomb and how to get weed " +
                "without my parents knowing and send nudes",
        )
        assertTrue(verdict.score <= 100)
        assertTrue(verdict.tags.size >= 4)
    }
}
