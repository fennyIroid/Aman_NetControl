package com.example.childsafetyimpl.core

import com.example.childsafetyimpl.core.common.TimeFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeFormatTest {

    @Test
    fun `formats durations compactly`() {
        assertEquals("0m", TimeFormat.duration(0))
        assertEquals("under 1m", TimeFormat.duration(30_000))
        assertEquals("45m", TimeFormat.duration(45 * 60_000L))
        assertEquals("1h", TimeFormat.duration(60 * 60_000L))
        assertEquals("2h 15m", TimeFormat.duration((2 * 60 + 15) * 60_000L))
    }

    @Test
    fun `formats a minute of day as a twelve hour clock`() {
        assertEquals("12:00 AM", TimeFormat.formatMinuteOfDay(0))
        assertEquals("7:30 AM", TimeFormat.formatMinuteOfDay(7 * 60 + 30))
        assertEquals("12:00 PM", TimeFormat.formatMinuteOfDay(12 * 60))
        assertEquals("9:05 PM", TimeFormat.formatMinuteOfDay(21 * 60 + 5))
    }

    /**
     * Bedtime windows cross midnight, and getting the wrap wrong would either block a child all
     * day or never block them at all. Both failures are silent.
     */
    @Test
    fun `a window that wraps past midnight includes the small hours`() {
        val from = 21 * 60
        val to = 7 * 60

        assertTrue(TimeFormat.isWithinWindow(22 * 60, from, to))
        assertTrue(TimeFormat.isWithinWindow(2 * 60, from, to))
        assertTrue(TimeFormat.isWithinWindow(from, from, to))
        assertFalse(TimeFormat.isWithinWindow(to, from, to))
        assertFalse(TimeFormat.isWithinWindow(12 * 60, from, to))
    }

    @Test
    fun `a window inside one day behaves normally`() {
        val from = 9 * 60
        val to = 15 * 60

        assertTrue(TimeFormat.isWithinWindow(10 * 60, from, to))
        assertFalse(TimeFormat.isWithinWindow(16 * 60, from, to))
        assertFalse(TimeFormat.isWithinWindow(3 * 60, from, to))
    }

    @Test
    fun `relative time reads naturally`() {
        val now = 1_700_000_000_000L
        assertEquals("just now", TimeFormat.relative(now - 20_000, now))
        assertEquals("5m ago", TimeFormat.relative(now - 5 * 60_000, now))
        assertEquals("3h ago", TimeFormat.relative(now - 3 * 3_600_000, now))
        assertEquals("2d ago", TimeFormat.relative(now - 2 * 86_400_000L, now))
    }
}
