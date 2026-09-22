package com.example.childsafetyimpl.data

import com.example.childsafetyimpl.data.source.BlockedSearchTerms
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The cost of a wrong answer here is asymmetric, so both directions are tested.
 *
 * A miss means a child searches for something restricted and nothing happens. A false positive
 * means a browser closes on a piece of homework, which is how a parent decides the protection is
 * broken and switches it off, at which point every miss follows from it.
 */
class BlockedSearchTermsTest {

    @Test
    fun `catches the searches this exists for`() {
        listOf(
            "bikini",
            "bikini photos",
            "how to buy weed online",
            "cocaine",
            "porn",
            "best online casino",
        ).forEach { search ->
            assertNotNull(search, BlockedSearchTerms.match(search))
        }
    }

    @Test
    fun `matches whatever separator a search url used`() {
        // "buy+weed+online" out of a URL is the same search as the one typed with spaces.
        assertNotNull(BlockedSearchTerms.match("buy+weed+online"))
        assertNotNull(BlockedSearchTerms.match("BIKINI"))
        assertNotNull(BlockedSearchTerms.match("  bikini  "))
    }

    @Test
    fun `a term inside a longer word does not match`() {
        // This is the rule that keeps the protection switched on.
        listOf(
            "essex county council",
            "sextant navigation",
            "assessment paper",
            "grapes of wrath",
            "weeding the garden",
            "scunthorpe united",
        ).forEach { search ->
            assertNull(search, BlockedSearchTerms.match(search))
        }
    }

    @Test
    fun `ordinary homework searches are left alone`() {
        listOf(
            "photosynthesis explained",
            "world war 2 timeline",
            "how to solve quadratic equations",
            "premier league table",
            "minecraft redstone tutorial",
        ).forEach { search ->
            assertNull(search, BlockedSearchTerms.match(search))
        }
    }

    @Test
    fun `hyphenated terms still match once written with a space`() {
        // The list is normalised as it is declared, so "self-harm" cannot silently match nothing.
        assertNotNull(BlockedSearchTerms.match("self harm"))
        assertNotNull(BlockedSearchTerms.match("self-harm"))
    }

    @Test
    fun `self harm searches are categorised as severe`() {
        val term = BlockedSearchTerms.match("i want to kill myself")
        assertNotNull(term)
        assertEquals(BlockedSearchTerms.SearchCategory.SELF_HARM, term?.category)
        assertEquals(true, term?.category?.severe)
    }

    @Test
    fun `every category carries terms`() {
        BlockedSearchTerms.SearchCategory.entries.forEach { category ->
            assertEquals(
                "${category.name} has no terms",
                true,
                BlockedSearchTerms.countIn(category) > 0,
            )
        }
    }

    @Test
    fun `blank input matches nothing`() {
        assertNull(BlockedSearchTerms.match(""))
        assertNull(BlockedSearchTerms.match("   "))
        assertNull(BlockedSearchTerms.match("!!! ???"))
    }
}
