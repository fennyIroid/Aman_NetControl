package com.example.childsafetyimpl.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [HostSet] replaces `host == entry || host.endsWith(".$entry")` with hash lookups. These hold it
 * to exactly that rule, since a difference in either direction is an over-block or a leak.
 */
class HostSetTest {

    private val set = HostSet.of(listOf("badsite.com", "deep.cdn.example.org", "tube8.xxx"))

    @Test
    fun `matches an entry and every subdomain of it`() {
        assertEquals("badsite.com", set.matchOf("badsite.com"))
        assertEquals("badsite.com", set.matchOf("img.cdn.badsite.com"))
        assertEquals("deep.cdn.example.org", set.matchOf("a.deep.cdn.example.org"))
    }

    @Test
    fun `never matches on anything but a label boundary`() {
        assertNull(set.matchOf("notbadsite.com"))
        assertNull(set.matchOf("badsite.com.example.net"))
        assertNull(set.matchOf("cdn.example.org"))
        assertNull(set.matchOf("com"))
    }

    @Test
    fun `agrees with the plain suffix rule on awkward hosts`() {
        val entries = listOf("b.com", ".b.com", "com", "x.y")
        val hosts = listOf("a..b.com", "b.com", "ab.com", ".b.com", "x.y.", "y", "a.x.y", "")
        val hostSet = HostSet.of(entries)
        hosts.forEach { host ->
            val expected = entries.any { host == it || host.endsWith(".$it") }
            assertEquals("disagreement on '$host'", expected, hostSet.matchOf(host) != null)
        }
    }

    @Test
    fun `parses the bundled format and leaves out what it is told to`() {
        val parsed = HostSet.parse(
            sequenceOf("# a comment", "", "  WWW.Example.COM.  ", "keep.me", "drop.me"),
            leaveOut = setOf("drop.me"),
        )
        assertEquals(2, parsed.size)
        assertEquals("example.com", parsed.matchOf("example.com"))
        assertEquals("keep.me", parsed.matchOf("keep.me"))
        assertNull(parsed.matchOf("drop.me"))
    }

    /** The app loads from bytes and the tests mostly from lines; the two must never disagree. */
    @Test
    fun `parsing bytes gives the same set as parsing lines`() {
        val text = "# comment\r\n\r\n  WWW.Example.COM.  \r\nkeep.me\ndrop.me\n\twww.x.org\nwww.\n" +
            "..\nno-newline.at.end"
        val leaveOut = setOf("drop.me", "WWW.X.org")
        val fromLines = HostSet.parse(text.lineSequence(), leaveOut.map { it.lowercase().removePrefix("www.") }.toSet())
        val fromBytes = HostSet.parse(text.toByteArray(), leaveOut)
        assertEquals(fromLines.size, fromBytes.size)
        listOf("example.com", "keep.me", "drop.me", "x.org", "no-newline.at.end", "a.keep.me").forEach {
            assertEquals(it, fromLines.matchOf(it), fromBytes.matchOf(it))
        }
    }

    @Test
    fun `an empty set matches nothing`() {
        assertNull(HostSet.EMPTY.matchOf("anything.com"))
        assertNull(HostSet.of(emptyList()).matchOf("anything.com"))
    }
}
