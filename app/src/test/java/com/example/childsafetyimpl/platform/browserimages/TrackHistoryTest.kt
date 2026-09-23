package com.example.childsafetyimpl.platform.browserimages

import android.graphics.Point
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TrackHistoryTest {

    private val page = 1L
    private val out = Point()

    private fun track(shiftY: Int, at: Long, velocityY: Float = 0f) =
        Track(shiftX = 0, shiftY = shiftY, atUptime = at, velocityX = 0f, velocityY = velocityY)

    @Test
    fun `with nothing recorded the page has not moved`() {
        TrackHistory().offsetAt(page, 1_000L, out)
        assertThat(out).isEqualTo(Point(0, 0))
    }

    @Test
    fun `a moment between two reads is taken from the read before it, carried on at its speed`() {
        // A capture lands between anchor reads; its photos are where the page was at that moment.
        val history = TrackHistory()
        history.record(mapOf(page to track(shiftY = -100, at = 1_000L, velocityY = -2f)))
        history.record(mapOf(page to track(shiftY = -140, at = 1_020L, velocityY = -2f)))

        history.offsetAt(page, 1_010L, out)

        assertThat(out.y).isEqualTo(-120)
    }

    @Test
    fun `a moment before every read is where the first read was`() {
        val history = TrackHistory()
        history.record(mapOf(page to track(shiftY = -100, at = 1_000L, velocityY = -2f)))

        history.offsetAt(page, 900L, out)

        assertThat(out.y).isEqualTo(-100)
    }

    @Test
    fun `a container missing from the reads has not moved`() {
        val history = TrackHistory()
        history.record(mapOf(page to track(shiftY = -100, at = 1_000L)))

        history.offsetAt(2L, 1_000L, out)

        assertThat(out).isEqualTo(Point(0, 0))
    }

    @Test
    fun `old reads are let go`() {
        val history = TrackHistory(keepMillis = 100L)
        history.record(mapOf(page to track(shiftY = -100, at = 1_000L)))
        history.record(mapOf(page to track(shiftY = -300, at = 1_500L)))

        history.offsetAt(page, 1_000L, out)

        // The earlier read is gone; the earliest kept is the best there is.
        assertThat(out.y).isEqualTo(-300)
    }

    @Test
    fun `clearing forgets every read`() {
        val history = TrackHistory()
        history.record(mapOf(page to track(shiftY = -100, at = 1_000L)))
        history.clear()

        history.offsetAt(page, 1_000L, out)

        assertThat(out).isEqualTo(Point(0, 0))
    }
}
