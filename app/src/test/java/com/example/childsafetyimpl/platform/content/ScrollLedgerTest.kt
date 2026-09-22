package com.example.childsafetyimpl.platform.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollLedgerTest {

    @Test
    fun `reported deltas add up to how far the page went`() {
        val ledger = ScrollLedger()
        listOf(106, 118, 131, 121, 99, 45, 13).forEachIndexed { i, delta ->
            ledger.record(at = T + i * 100L, packageName = CHROME, source = CONTENT, delta = delta, position = null)
        }
        assertEquals(633L, ledger.total())
    }

    @Test
    fun `an app reporting both a delta and a position for one scroll is counted once`() {
        // What Chrome does, recorded on the device: its content view reports deltas and its
        // WebView reports the absolute offset of the same scroll. The two agreed to two pixels
        // over a fling of six hundred; added together they would move every cover twice as far
        // as the page.
        val ledger = ScrollLedger()
        ledger.record(T, CHROME, WEBVIEW, delta = null, position = 3939)
        ledger.record(T + 100, CHROME, CONTENT, delta = 106, position = null)
        ledger.record(T + 200, CHROME, CONTENT, delta = 118, position = null)
        ledger.record(T + 250, CHROME, WEBVIEW, delta = null, position = 4163)
        ledger.record(T + 300, CHROME, CONTENT, delta = 0 + 0, position = null)

        assertEquals(224L, ledger.total())
    }

    @Test
    fun `an app that only reports positions is measured from them`() {
        val ledger = ScrollLedger()
        ledger.record(T, OTHER, WEBVIEW, delta = null, position = 1000)
        ledger.record(T + 100, OTHER, WEBVIEW, delta = null, position = 1180)
        ledger.record(T + 200, OTHER, WEBVIEW, delta = null, position = 1090)

        assertEquals(90L, ledger.total())
    }

    @Test
    fun `positions from two different scrollers are never subtracted from each other`() {
        val ledger = ScrollLedger()
        ledger.record(T, OTHER, "1/list", delta = null, position = 5000)
        ledger.record(T + 100, OTHER, "2/sheet", delta = null, position = 20)

        assertEquals(0L, ledger.total())
    }

    @Test
    fun `a frame between two reports reads the page part of the way between them`() {
        val ledger = ScrollLedger()
        ledger.record(T, CHROME, CONTENT, delta = 100, position = null)
        ledger.record(T + 100, CHROME, CONTENT, delta = 100, position = null)

        assertEquals(150L, ledger.totalAt(T + 50))
        assertEquals(100L, ledger.totalAt(T))
        assertEquals(200L, ledger.totalAt(T + 500))
    }

    @Test
    fun `the first report after a pause is not smeared back over the pause`() {
        // A report describes movement since the one before it, but after two seconds of nothing
        // that movement plainly did not take two seconds. Spreading it that far would place a
        // cover from a frame taken during the pause as though the page had already been moving.
        val ledger = ScrollLedger()
        ledger.record(T, CHROME, CONTENT, delta = 100, position = null)
        ledger.record(T + 2_000, CHROME, CONTENT, delta = 150, position = null)

        assertEquals(100L, ledger.totalAt(T + 1_000))
        assertEquals(100L, ledger.totalAt(T + 2_000 - ScrollLedger.SPREAD_CAP_MILLIS))
        assertEquals(175L, ledger.totalAt(T + 2_000 - ScrollLedger.SPREAD_CAP_MILLIS / 2))
    }

    @Test
    fun `a frame older than every report reads the page before any of them`() {
        val ledger = ScrollLedger()
        ledger.record(T, CHROME, CONTENT, delta = 100, position = null)

        assertEquals(0L, ledger.totalAt(T - 1_000))
    }

    @Test
    fun `it knows when the page is moving and when it has stopped`() {
        val ledger = ScrollLedger()
        ledger.record(T, CHROME, CONTENT, delta = 40, position = null)

        assertTrue(ledger.movedWithin(T + 300, window = 400))
        // A report stamped just after a frame is still about that frame.
        assertTrue(ledger.movedWithin(T - 50, window = 400))
        assertFalse(ledger.movedWithin(T + 1_000, window = 400))
    }

    @Test
    fun `a report of no movement is not movement`() {
        val ledger = ScrollLedger()
        ledger.record(T, CHROME, CONTENT, delta = 0, position = null)

        assertFalse(ledger.movedWithin(T, window = 400))
        assertEquals(0L, ledger.total())
    }

    private companion object {
        const val T = 500_000L
        const val CHROME = "com.android.chrome"
        const val OTHER = "com.example.reader"
        const val CONTENT = "7/android.widget.FrameLayout"
        const val WEBVIEW = "7/android.webkit.WebView"
    }
}
