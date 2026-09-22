package com.example.childsafetyimpl.domain

import com.example.childsafetyimpl.domain.model.RestrictedSurface
import com.example.childsafetyimpl.domain.model.SurfaceUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The allowance arithmetic decides whether a child is let into a feed or turned away, so the edge
 * that matters most is the one where no allowance was set: that has to mean "refuse", never
 * "unlimited".
 */
class SurfaceUsageTest {

    private fun usage(dwellMillis: Long, allowanceMinutes: Int?) = SurfaceUsage(
        surface = RestrictedSurface.SHORT_FORM_FEED,
        encounters = 1,
        dwellMillis = dwellMillis,
        allowanceMinutes = allowanceMinutes,
    )

    @Test
    fun `no allowance means exhausted, not unlimited`() {
        assertTrue(usage(dwellMillis = 0, allowanceMinutes = null).exhausted)
    }

    @Test
    fun `an unused allowance is not exhausted`() {
        assertFalse(usage(dwellMillis = 0, allowanceMinutes = 15).exhausted)
    }

    @Test
    fun `an allowance is exhausted exactly at the boundary`() {
        val fifteenMinutes = 15 * 60_000L
        assertTrue(usage(fifteenMinutes, 15).exhausted)
        assertFalse(usage(fifteenMinutes - 1, 15).exhausted)
    }

    @Test
    fun `an allowance of zero is exhausted immediately`() {
        assertTrue(usage(dwellMillis = 0, allowanceMinutes = 0).exhausted)
    }

    @Test
    fun `the fraction tracks progress through the allowance`() {
        assertEquals(0.5f, usage(5 * 60_000L, 10).fraction, 0.001f)
    }

    @Test
    fun `the fraction never exceeds one, so a meter cannot overflow`() {
        assertEquals(1f, usage(60 * 60_000L, 10).fraction, 0.001f)
    }

    @Test
    fun `with no allowance the fraction reads as full rather than empty`() {
        // An empty meter beside "not allowed" would read as though time remained.
        assertEquals(1f, usage(0, null).fraction, 0.001f)
    }
}
