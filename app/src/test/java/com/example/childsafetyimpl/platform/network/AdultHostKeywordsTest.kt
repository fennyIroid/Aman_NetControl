package com.example.childsafetyimpl.platform.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A name-based match has no list to be wrong about, only its own rules, so the rules are what is
 * tested: whole labels and hyphen-bounded parts count, anything looser does not.
 */
class AdultHostKeywordsTest {

    @Test
    fun `a whole label or a hyphen-bounded part of one matches`() {
        listOf("pornhub.com", "xxx.example", "free-porn.example", "porn-tube.example",
            "mirror.rule34.example", "tube8.xxx").forEach {
            assertNotNull("$it should match", AdultHostKeywords.matchOf(it))
        }
    }

    /** The substring version of this rule is the classic over-block: essex, sussex, middlesex. */
    @Test
    fun `a keyword inside an ordinary word does not match`() {
        listOf("essex.ac.uk", "sussex.ac.uk", "middlesex.gov.uk", "freeporntube.example",
            "unpornographic.example", "youtube.com", "webcam-reviews.example", "java.com",
            "pornography-research.example.edu", "xxxl-clothing.example").forEach {
            // freeporntube is adult, but only a list can know that without over-blocking.
            assertNull("$it should not match", AdultHostKeywords.matchOf(it))
        }
    }

    @Test
    fun `a site about stopping is never refused for its name`() {
        listOf("porn-addiction-help.example", "nofap.com", "stop-porn.example",
            "porn-recovery.example").forEach {
            assertNull("$it is a support site", AdultHostKeywords.matchOf(it))
        }
    }

    @Test
    fun `an internationalised name is judged in its decoded form`() {
        // порно.рф as it reaches the tunnel
        assertEquals("порно", AdultHostKeywords.matchOf("xn--m1abbbg.xn--p1ai"))
        assertNull(AdultHostKeywords.matchOf("xn--80ak6aa92e.com"))
    }

    @Test
    fun `an empty host matches nothing`() {
        assertNull(AdultHostKeywords.matchOf(""))
    }
}
