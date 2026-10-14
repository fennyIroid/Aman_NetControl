package com.example.childsafetyimpl.platform.content

import com.example.childsafetyimpl.data.prefs.SafetySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A private tab captures as black, so what is tested here is how that is recognised, how the
 * recognition survives this app's own covers, and where the covers go. Each failure mode is silent
 * on a device: covers that never come, covers that argue themselves down, or covers left over the
 * next app.
 */
class PrivateBrowsingTest {

    private val w = 400
    private val h = 800

    /** A frame black everywhere except the system bars and [lit]. */
    private fun frame(lit: List<Box> = emptyList(), barsLit: Boolean = true): (Int, Int) -> Int =
        { x, y ->
            val inBars = y < h * 0.05f || y >= h * 0.93f
            when {
                barsLit && inBars -> 200
                lit.any { it.contains(x, y) } -> 180
                else -> 0
            }
        }

    // --- SecureFrame ------------------------------------------------------------------------

    @Test
    fun `a black window is recognised even with the status and navigation bars lit`() {
        assertEquals(true, SecureFrame.isBlackedOut(w, h, emptyList(), frame()))
    }

    @Test
    fun `an ordinary page is not`() {
        assertEquals(false, SecureFrame.isBlackedOut(w, h, emptyList()) { _, _ -> 240 })
    }

    /** Chrome's dark theme paints #202124, which is dark to a person and nowhere near black. */
    @Test
    fun `a dark-theme page is not mistaken for a secure one`() {
        assertEquals(false, SecureFrame.isBlackedOut(w, h, emptyList()) { _, _ -> 33 })
    }

    /** A site with a pure black background, in an ordinary tab: the toolbar is still lit. */
    @Test
    fun `a black website under an ordinary toolbar is never taken for a private tab`() {
        val toolbar = Box(0, 40, w, 110)
        assertTrue(SecureFrame.isBlackedOut(w, h, emptyList(), frame(lit = listOf(toolbar))) != true)
    }

    /** Rule 17: the covers show in the capture, and must not end the private tab they are on. */
    @Test
    fun `this app's own covers are left out of the test`() {
        val covers = listOf(Box(20, 120, 380, 380), Box(20, 400, 380, 700))
        assertEquals(false, SecureFrame.isBlackedOut(w, h, emptyList(), frame(lit = covers)))
        assertEquals(true, SecureFrame.isBlackedOut(w, h, covers, frame(lit = covers)))
    }

    /** Measured on a device: a few stray lit pixels on a still incognito tab dropped every cover. */
    @Test
    fun `a few lit pixels do not make a black window lit`() {
        val speck = listOf(Box(200, 400, 260, 460))
        assertEquals(true, SecureFrame.isBlackedOut(w, h, emptyList(), frame(lit = speck)))
        val patch = listOf(Box(100, 300, 300, 420))
        assertTrue(SecureFrame.isBlackedOut(w, h, emptyList(), frame(lit = patch)) != false)
    }

    @Test
    fun `a frame almost entirely under covers gives no answer rather than a wrong one`() {
        val page = listOf(Box(0, 0, w, h))
        assertNull(SecureFrame.isBlackedOut(w, h, page, frame(lit = page)))
    }

    // --- PrivateTabTracker ------------------------------------------------------------------

    private val chrome = "com.android.chrome"
    private val bank = "com.example.bank"

    private fun privateChrome() = PrivateTabTracker().also {
        it.next(chrome, foregroundIsBrowser = true, blackedOut = true, now = 0)
    }

    @Test
    fun `a browser in front with a black window is a private tab`() {
        assertEquals(chrome, privateChrome().current)
    }

    @Test
    fun `a black window in an app that is not a browser is not`() {
        assertNull(PrivateTabTracker().next(bank, foregroundIsBrowser = false, blackedOut = true, now = 0))
    }

    @Test
    fun `a browser showing an ordinary page is not`() {
        assertNull(PrivateTabTracker().next(chrome, foregroundIsBrowser = true, blackedOut = false, now = 0))
    }

    @Test
    fun `another app in use ends it at once`() {
        assertNull(privateChrome().next(bank, foregroundIsBrowser = false, blackedOut = true, now = 10))
    }

    /**
     * Measured on a realme RMX3870: mid-scroll the capture still showed the covers where they had
     * been, one frame read as not black, and every cover dropped for half a second.
     */
    @Test
    fun `one frame that is not black does not end it`() {
        val tracker = privateChrome()
        assertEquals(chrome, tracker.next(chrome, true, blackedOut = false, now = 100))
        assertEquals(chrome, tracker.next(chrome, true, blackedOut = true, now = 200))
        assertEquals(chrome, tracker.next(chrome, true, blackedOut = false, now = 300))
    }

    @Test
    fun `a page that stays not black ends it`() {
        val tracker = privateChrome()
        tracker.next(chrome, true, blackedOut = false, now = 100)
        assertEquals(chrome, tracker.next(chrome, true, blackedOut = false, now = 300))
        assertNull(tracker.next(chrome, true, blackedOut = false, now = 100 + PrivateTabTracker.EXIT_AFTER_MILLIS))
    }

    @Test
    fun `two lit readings far apart are not a tab that turned normal`() {
        val tracker = privateChrome()
        tracker.next(chrome, true, blackedOut = false, now = 1_000)
        repeat(3) { tracker.next(chrome, true, blackedOut = null, now = 2_000L + it * 500) }
        assertEquals(chrome, tracker.next(chrome, true, blackedOut = false, now = 5_000))
    }

    @Test
    fun `a tab adopted on return ends only on the usual evidence`() {
        val tracker = PrivateTabTracker()
        tracker.adopt(chrome)
        assertEquals(chrome, tracker.next(chrome, true, blackedOut = false, now = 0))
        assertNull(tracker.next(chrome, true, blackedOut = false, now = PrivateTabTracker.EXIT_AFTER_MILLIS))
    }

    /**
     * Driven on a Galaxy M36: back in Chrome on an ordinary results page after leaving it while
     * private, one clear frame arrived, the page sat still, and no second frame ever came. A box
     * stood over the Google logo until the child scrolled.
     */
    @Test
    fun `a still screen after a clear frame ends it without a second frame`() {
        val tracker = PrivateTabTracker()
        tracker.adopt(chrome)
        tracker.next(chrome, true, blackedOut = false, now = 1_000)
        assertEquals(chrome, tracker.confirmStill(now = 1_000 + PrivateTabTracker.EXIT_AFTER_MILLIS - 1))
        assertNull(tracker.confirmStill(now = 1_000 + PrivateTabTracker.EXIT_AFTER_MILLIS))
    }

    @Test
    fun `a still screen ends nothing when the latest frame was not clear`() {
        // The frame on screen is the latest one, so only its verdict may stand for a still page.
        val tracker = privateChrome()
        tracker.next(chrome, true, blackedOut = false, now = 100)
        tracker.next(chrome, true, blackedOut = true, now = 200)
        assertEquals(chrome, tracker.confirmStill(now = 5_000))

        val covered = privateChrome()
        covered.next(chrome, true, blackedOut = false, now = 100)
        covered.next(chrome, true, blackedOut = null, now = 200)
        assertEquals(chrome, covered.confirmStill(now = 5_000))
    }

    @Test
    fun `a still black screen stays private`() {
        assertEquals(chrome, privateChrome().confirmStill(now = 60_000))
    }

    @Test
    fun `a frame too covered to judge keeps the private tab`() {
        val tracker = privateChrome()
        repeat(5) { assertEquals(chrome, tracker.next(chrome, true, blackedOut = null, now = 1_000L * it)) }
    }

    // --- PrivateCoverGeometry ---------------------------------------------------------------

    private val page = Box(0, 120, 400, 760)

    @Test
    fun `an unfinished walk covers the whole page`() {
        assertEquals(listOf(page), PrivateCoverGeometry.place(null, scrolledSinceWalk = 0, page = page))
    }

    @Test
    fun `covers move with the page by the scroll reported since the walk`() {
        val photo = Box(10, 300, 190, 480)
        val placed = PrivateCoverGeometry.place(listOf(photo), scrolledSinceWalk = 100, page = page)
        assertEquals(listOf(Box(10, 200, 190, 380)), placed)
    }

    @Test
    fun `covers are kept off the browser's toolbar and a photo scrolled away gets none`() {
        val underToolbar = Box(10, 60, 190, 200)
        val offScreen = Box(10, -400, 190, -200)
        val placed = PrivateCoverGeometry.place(listOf(underToolbar, offScreen), scrolledSinceWalk = 0, page = page)
        assertEquals(listOf(Box(10, 120, 190, 200)), placed)
    }

    @Test
    fun `a page with no photos gets no covers`() {
        assertTrue(PrivateCoverGeometry.place(emptyList(), scrolledSinceWalk = 0, page = page).isEmpty())
    }

    // --- setting ----------------------------------------------------------------------------

    /** Rule 9 in spirit: an update must not quietly turn private browsing into a way round. */
    @Test
    fun `covering every picture is the default`() {
        assertEquals(PrivateTabAction.COVER_PICTURES, PrivateTabAction.default)
        assertEquals(PrivateTabAction.COVER_PICTURES, SafetySettings().privateTabAction)
        assertFalse(PrivateTabAction.default == PrivateTabAction.LEAVE_TAB)
    }
}
