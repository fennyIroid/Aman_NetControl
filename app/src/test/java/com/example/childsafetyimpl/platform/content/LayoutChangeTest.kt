package com.example.childsafetyimpl.platform.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the covering loop tells "a different screen" from "the same screen, doing something".
 *
 * Every case here comes from one report with two photographs attached: covers from one page still
 * standing over the next one, and covers still standing over the app switcher half a minute after
 * the child had left the app. Both are the same failure — a navigation that nothing recognised —
 * and the reason nothing recognised it is that the question was being asked of a measurement that
 * cannot answer it. A row profile is an average across the whole width, so it can see a screen get
 * brighter and cannot see it get rearranged.
 *
 * The cases are written as the behaviour rather than as the bug, and several of them assert what
 * the *other* measurement says as well. Those are not redundant: they are what stops someone
 * deleting a guard here on the grounds that the tests still pass.
 */
class LayoutChangeTest {

    // --- a navigation is a navigation ------------------------------------------------------------

    @Test
    fun `a screen that is rearranged rather than redrawn is still a new layout`() {
        // The photograph was a page of thumbnails replaced by the next page of thumbnails, with
        // the covers from the first still on screen. Two pages from the same app share a palette,
        // so what changes between them is where things are, not how bright the screen is. Averaged
        // across the width that difference disappears completely: these two frames have *identical*
        // row profiles and nothing in common.
        val before = columnPage(darkOnLeft = true)
        val after = columnPage(darkOnLeft = false)

        assertTrue(gridOf(after).isNewLayoutVersus(gridOf(before)))
    }

    @Test
    fun `the row profile cannot tell those two screens apart at all`() {
        // The meta-test, and the reason the grid had to be added rather than the row thresholds
        // retuned. If this ever starts failing, the fixture stopped standing for the thing it was
        // built to stand for and the test above is no longer proving anything.
        val before = columnPage(darkOnLeft = true)
        val after = columnPage(darkOnLeft = false)

        val motion = RowProfile.of(after, WIDTH, HEIGHT).motionFrom(RowProfile.of(before, WIDTH, HEIGHT))

        assertEquals(FrameMotion.Still, motion)
    }

    @Test
    fun `a new layout on the grid clears the covers even when the rows saw nothing`() {
        val before = RowProfile.of(columnPage(darkOnLeft = true), WIDTH, HEIGHT)
        val after = RowProfile.of(columnPage(darkOnLeft = false), WIDTH, HEIGHT)

        assertEquals(FrameMotion.Changed, after.motionFrom(before, newLayout = true))
    }

    // --- a video inside a page is still not a navigation -----------------------------------------

    @Test
    fun `a full width player cutting from a bright shot to a dark one is not a new layout`() {
        // The other half of the same decision, and the one that was got wrong first. A player is
        // the largest thing on a page that changes completely without the page having changed at
        // all, so it is the case that decides how sensitive this is allowed to be.
        val before = page()
        val after = before.copyOf()
        after.paintBand(top = PLAYER_TOP, bottom = PLAYER_BOTTOM, value = 24)

        assertFalse(gridOf(after).isNewLayoutVersus(gridOf(before)))
    }

    @Test
    fun `that player really did change violently`() {
        // Guard against the test above passing because the fixture barely changed. The frame this
        // produces is far past the threshold for "worth looking at again"; what it is not is a
        // different screen.
        val before = page()
        val after = before.copyOf()
        after.paintBand(top = PLAYER_TOP, bottom = PLAYER_BOTTOM, value = 24)

        assertTrue(gridOf(after).distanceTo(gridOf(before)) > SceneSignature.MOVEMENT_THRESHOLD * 4)
    }

    @Test
    fun `a player this app is already covering cannot report a new layout at all`() {
        // The worst version of the case, and the one that used to tear its own cover down. A cover
        // is over the player, so the player is the part of the screen this app is looking at its
        // own output in — rule 17 — and its cells are left out on both sides.
        // Wide enough to take in the cells the player's edges fall in. A cell is ignored when its
        // centre is under a cover, so a cover flush with the player leaves the two boundary cells
        // comparable and they carry a sliver of it.
        val cover = listOf(ScreenRegions.Region(0f, 0.15f, 1f, 0.56f))
        val before = page()
        val after = before.copyOf()
        after.paintBand(top = PLAYER_TOP, bottom = PLAYER_BOTTOM, value = 24)

        val moved = SceneSignature.of(after, WIDTH, HEIGHT, cover)
        val still = SceneSignature.of(before, WIDTH, HEIGHT, cover)

        assertFalse(moved.isNewLayoutVersus(still))
        assertEquals(0f, moved.distanceTo(still), 0.001f)
    }

    // --- a scroll is not a navigation, whatever the grid thinks -----------------------------------

    @Test
    fun `the grid alone would call an ordinary scroll a new layout`() {
        // Stated first because it is the hazard the next test exists to contain. A cell is a
        // sixteenth of the screen; a feed under a finger travels further than that between two
        // frames, so every cell ends up averaging different content. Acting on this reading would
        // discard every cover on a feed the moment it moved, which is rule 20 exactly.
        val before = feedPage()
        val after = before.shiftedDownBy(SCROLL_ROWS)

        assertTrue(gridOf(after).isNewLayoutVersus(gridOf(before)))
    }

    @Test
    fun `a measured displacement outranks the grid`() {
        val before = RowProfile.of(feedPage(), WIDTH, HEIGHT)
        val after = RowProfile.of(feedPage().shiftedDownBy(SCROLL_ROWS), WIDTH, HEIGHT)

        val motion = after.motionFrom(before, newLayout = true)

        assertTrue("a scroll was thrown away as a new layout: $motion", motion is FrameMotion.Scrolled)
        assertEquals(
            SCROLL_ROWS.toFloat() / HEIGHT,
            (motion as FrameMotion.Scrolled).fraction,
            1f / HEIGHT,
        )
    }

    // --- the window is ground truth and pixels are not --------------------------------------------

    @Test
    fun `a window change ends every cover however well the frames line up`() {
        // The app switcher case. Nothing here is an inference about pixels: the accessibility
        // service was told which app is in front, and the covers on screen were judged in a
        // different one. Even a perfect alignment is then an offset between two windows, and
        // moving the covers by it would place every one of them on content nobody judged.
        val before = RowProfile.of(feedPage(), WIDTH, HEIGHT)
        val after = RowProfile.of(feedPage().shiftedDownBy(SCROLL_ROWS), WIDTH, HEIGHT)

        assertEquals(FrameMotion.Changed, after.motionFrom(before, windowChanged = true))
    }

    @Test
    fun `a still screen in the same window is left alone`() {
        val before = RowProfile.of(page(), WIDTH, HEIGHT)
        val after = RowProfile.of(page(), WIDTH, HEIGHT)

        assertEquals(FrameMotion.Still, after.motionFrom(before, newLayout = false, windowChanged = false))
    }

    // --- when it cannot tell, the covers stay ------------------------------------------------------

    @Test
    fun `a screen with almost nothing visible never reports a new layout`() {
        // Fail towards covering, rule 12. When the covers hide most of the screen the cells left
        // are page margins, and the honest answer to what they show is nothing. Reporting a new
        // layout from them would take every cover down over exactly the content that earned them.
        val cover = listOf(ScreenRegions.Region(0f, 0f, 1f, 0.88f))
        val before = SceneSignature.of(columnPage(darkOnLeft = true), WIDTH, HEIGHT, cover)
        val after = SceneSignature.of(columnPage(darkOnLeft = false), WIDTH, HEIGHT, cover)

        assertFalse(after.isNewLayoutVersus(before))
    }

    @Test
    fun `the first frame of a session is not a new layout`() {
        assertFalse(gridOf(page()).isNewLayoutVersus(null))
    }

    // --- the covers cannot report themselves as a new layout -------------------------------------

    @Test
    fun `covers coming down over a page that did not move are not a new layout`() {
        // The flicker measured on the device: nobody touching the screen, and the loop reporting
        // Changed as the covers changed. A cell straddling a cover's edge averaged part of the
        // cover in, so the cover's own paint changed the cell — and with edges crossing most of
        // the grid, that alone passed the new-layout share and dropped every cover.
        val page = page()
        val covered = page.copyOf().apply { paint(straddlingCovers, value = 0) }

        val withCovers = SceneSignature.of(covered, WIDTH, HEIGHT, straddlingCovers)
        val withoutCovers = gridOf(page)

        assertFalse(withoutCovers.isNewLayoutVersus(withCovers))
        assertFalse(withCovers.isNewLayoutVersus(withoutCovers))
    }

    @Test
    fun `those covers really do cross most of the grid`() {
        // The guard on the test above. Measured without leaving the covers out, the two frames
        // are a new layout — so it is the ignore rule that stops it, not a lenient fixture.
        val page = page()
        val covered = page.copyOf().apply { paint(straddlingCovers, value = 0) }

        assertTrue(gridOf(covered).isNewLayoutVersus(gridOf(page)))
    }

    @Test
    fun `a cover edge crossing a cell does not count that cell as covered`() {
        // How much is covered is what chooses the hold, and counting every cell a cover touches
        // reads a page of a few covered pictures as a screen with nothing visible — which puts
        // the covers on the short hold and re-examines them, a flash each, every few seconds.
        val grid = SceneSignature.of(page(), WIDTH, HEIGHT, straddlingCovers)

        assertEquals(0f, grid.coveredShare, 0f)
    }

    // --- a cover never inherits the previous page's photograph ------------------------------------

    @Test
    fun `a cover raised on this frame never keeps the blurred copy already drawn there`() {
        // The first photograph in the report: a blurred picture on screen that did not match
        // anything behind it. A page turns, a fresh cover goes up over the new page's first
        // thumbnail, and it lands where the old cover was — so the copy that "still fits" is a
        // picture of the page that is gone, painted over one nobody has looked at yet.
        assertFalse(CoverPolicy.mayReusePreview(raisedAt = NOW, now = NOW, carriedOverlap = 1f))
    }

    @Test
    fun `a cover that has been standing keeps its copy rather than sampling itself`() {
        // The rule this one is protecting: the capture shows the cover in that place, so a copy
        // taken again would be a blur of a blur, and a few seconds of that is a flat rectangle.
        assertTrue(CoverPolicy.mayReusePreview(raisedAt = NOW - 5_000L, now = NOW, carriedOverlap = 1f))
    }

    @Test
    fun `a cover that has moved off the copy takes a new one`() {
        assertFalse(CoverPolicy.mayReusePreview(raisedAt = NOW - 5_000L, now = NOW, carriedOverlap = 0.8f))
    }

    // --- fixtures ---------------------------------------------------------------------------------

    private fun gridOf(pixels: IntArray) = SceneSignature.of(pixels, WIDTH, HEIGHT)

    /**
     * A page whose dark half is on one side or the other.
     *
     * Built so that both versions have exactly the same row profile: the dark half is four of the
     * eight column bands either way, so every row averages to the same number. That is not a trick
     * played on the row profile, it is what the row profile is — an average across the width — and
     * two screens from the same app differing in where things sit is the ordinary case, not a
     * contrived one.
     */
    private fun columnPage(darkOnLeft: Boolean): IntArray {
        val pixels = IntArray(WIDTH * HEIGHT) { rgb(245) }
        for (y in CONTENT_TOP until CONTENT_BOTTOM) {
            for (x in 0 until WIDTH) {
                val onLeft = x < WIDTH / 2
                pixels[y * WIDTH + x] = if (onLeft == darkOnLeft) rgb(40) else rgb(245)
            }
        }
        return pixels
    }

    /** A plain page with enough structure for a cell average to mean something. */
    private fun page(): IntArray = IntArray(WIDTH * HEIGHT) { index ->
        val x = index % WIDTH
        val y = index / WIDTH
        rgb(if ((x / 20 + y / 8) % 4 == 0) 70 else 240)
    }

    /**
     * A column of cards, each its own shade.
     *
     * The shades are what make this scrollable in the sense the estimator needs: a feed of
     * identical cards aligns equally well at one offset per card, and `RowProfile.isDistinct`
     * correctly refuses to pick between them. Giving each card its own shade leaves one offset
     * that fits, which is what a real page of different photographs does.
     */
    private fun feedPage(): IntArray {
        val pixels = IntArray(WIDTH * HEIGHT)
        for (y in 0 until HEIGHT) {
            val card = y / CARD_PITCH
            val inCard = y % CARD_PITCH < CARD_HEIGHT
            val shade = 30 + (card * 53) % 150
            for (x in 0 until WIDTH) {
                pixels[y * WIDTH + x] = when {
                    !inCard -> rgb(250)
                    (x / 20 + y / 8) % 4 == 0 -> rgb(255 - shade)
                    else -> rgb(shade)
                }
            }
        }
        return pixels
    }

    /** The same page further down, as a finger moving down the screen produces. */
    private fun IntArray.shiftedDownBy(rows: Int): IntArray {
        val moved = IntArray(WIDTH * HEIGHT) { rgb(250) }
        for (y in rows until HEIGHT) {
            System.arraycopy(this, (y - rows) * WIDTH, moved, y * WIDTH, WIDTH)
        }
        return moved
    }

    /** Repaints a full-width band, as a player does when the shot cuts. */
    private fun IntArray.paintBand(top: Int, bottom: Int, value: Int) {
        for (y in top until bottom) {
            for (x in 0 until WIDTH) {
                this[y * WIDTH + x] = rgb(if ((x / 12 + y / 6) % 3 == 0) value + 30 else value)
            }
        }
    }

    /**
     * Four covers, each straddling a boundary between two columns of the grid without reaching
     * either column's centre, over the top two thirds of the screen: what covers grown by a margin
     * look like against a grid they were never aligned to.
     */
    private val straddlingCovers = listOf(0.07f, 0.32f, 0.57f, 0.82f).map { left ->
        ScreenRegions.Region(left, 0.05f, left + 0.11f, 0.65f)
    }

    /** Paints this app's covers into a frame, as the capture sees them. */
    private fun IntArray.paint(regions: List<ScreenRegions.Region>, value: Int) {
        regions.forEach { region ->
            for (y in (region.top * HEIGHT).toInt() until (region.bottom * HEIGHT).toInt()) {
                for (x in (region.left * WIDTH).toInt() until (region.right * WIDTH).toInt()) {
                    this[y * WIDTH + x] = rgb(value)
                }
            }
        }
    }

    private fun rgb(value: Int): Int =
        (0xFF shl 24) or (value shl 16) or (value shl 8) or value

    private companion object {
        const val WIDTH = 160
        const val HEIGHT = 360
        const val CONTENT_TOP = 45
        const val CONTENT_BOTTOM = 315
        const val PLAYER_TOP = 80
        const val PLAYER_BOTTOM = 180
        const val CARD_PITCH = 60
        const val CARD_HEIGHT = 44
        const val SCROLL_ROWS = 32
        const val NOW = 1_000_000L
    }
}
