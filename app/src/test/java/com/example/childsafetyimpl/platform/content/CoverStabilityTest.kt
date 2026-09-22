package com.example.childsafetyimpl.platform.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Why a cover stays where it is, and stays at all.
 *
 * Every case here comes from one report after driving this on a phone: the covers slid around
 * while the screen was still, they slid around while it scrolled, and they vanished and came back
 * for no reason visible on screen. Three separate causes turned out to be behind it, and this file
 * is one section per cause. They are written as the behaviour rather than as the bug, so that
 * whoever next moves a threshold in [RowProfile], [CoverPolicy] or [VerdictStability] finds out
 * which of the three they brought back.
 */
class CoverStabilityTest {

    // --- a playing video is not a new page -------------------------------------------------------

    @Test
    fun `a video playing inside a page is not a new layout`() {
        // The report was that the blur disappears most of the time and comes back at random. This
        // is why. A new layout throws every cover on the page away, and the test for one was the
        // mean difference across the frame — which a video clears on its own, because it changes
        // its own rows completely. So every second or so of playback was read as a navigation,
        // every cover was discarded, and the next scan put them back somewhere slightly different.
        // A scene cut inside the player: a bright shot becomes a dark one. Nothing else on the
        // page moves. The mean difference across the frame clears any threshold worth having,
        // because the rows that did change, changed completely.
        val before = page(seed = 5)
        before.paintScene(top = PLAYER_TOP, bottom = PLAYER_BOTTOM, base = 210, seed = 61)
        val after = before.copyOf()
        after.paintScene(top = PLAYER_TOP, bottom = PLAYER_BOTTOM, base = 20, seed = 62)

        val motion = RowProfile.of(after, WIDTH, HEIGHT).motionFrom(RowProfile.of(before, WIDTH, HEIGHT))

        assertFalse("a playing video read as a new layout: $motion", motion is FrameMotion.Changed)
    }

    @Test
    fun `the frame a playing video produces would clear the old whole-frame test on its own`() {
        // Guards the test above from quietly stopping being about anything. If a scene cut ever
        // stops being violent enough to have tripped the rule this replaced, the case is no longer
        // the case that was reported and the threshold below needs re-deriving rather than
        // congratulating itself.
        val before = page(seed = 5)
        before.paintScene(top = PLAYER_TOP, bottom = PLAYER_BOTTOM, base = 210, seed = 61)
        val after = before.copyOf()
        after.paintScene(top = PLAYER_TOP, bottom = PLAYER_BOTTOM, base = 20, seed = 62)

        val rows = (PLAYER_BOTTOM - PLAYER_TOP).toFloat() / HEIGHT
        assertTrue("the player covers $rows of the screen", rows < RowProfile.NEW_LAYOUT_ROW_SHARE)
        assertTrue(
            "mean row difference ${meanRowDifference(before, after)} would not have tripped it",
            meanRowDifference(before, after) >= RowProfile.NEW_LAYOUT_SCORE,
        )
    }

    @Test
    fun `a navigation is still a new layout`() {
        // The other half, and the one the rule above must not cost. A page replaced by a different
        // page has to throw its covers away: they were judged on content that is gone, and holding
        // them is an obstruction sitting over whatever arrived.
        val before = page(seed = 5)
        val after = fill(WIDTH, HEIGHT, rgb(18, 18, 22))
        after.paintPhoto(WIDTH, left = 4, top = 8, right = WIDTH - 4, bottom = HEIGHT - 8, seed = 91)

        assertEquals(
            FrameMotion.Changed,
            RowProfile.of(after, WIDTH, HEIGHT).motionFrom(RowProfile.of(before, WIDTH, HEIGHT)),
        )
    }

    // --- a still screen produces no displacement --------------------------------------------------

    @Test
    fun `capture noise on a still screen is not a scroll`() {
        // The report was that the covers keep moving by themselves on a screen nobody is touching.
        // A capture is scaled, quantised and re-encoded, so two frames of a motionless screen are
        // never identical; the old code searched for the offset that fitted best and believed
        // whichever one won. Ten times a second, that is a random walk, and the covers walk with it.
        val before = page(seed = 7)
        val after = before.copyOf()
        after.addNoise(seed = 8, amplitude = 6)

        val motion = RowProfile.of(after, WIDTH, HEIGHT).motionFrom(RowProfile.of(before, WIDTH, HEIGHT))

        assertFalse("noise read as a displacement: $motion", motion is FrameMotion.Scrolled)
    }

    @Test
    fun `a feed of identical cards produces no displacement at all rather than a plausible one`() {
        // A feed is a column of near-identical cards, so several offsets explain any two frames of
        // it equally well and one of them is the negative of another. Taking the winner of a field
        // of equals is picking a direction out of noise: the covers slide off their pictures, and
        // as often as not they slide the wrong way. There is no right answer here, and saying so
        // leaves the covers where the last frame that could see them put them.
        val before = repeatingCards()
        val after = before.shiftedDownBy(CARD_PITCH / 2)

        val motion = RowProfile.of(after, WIDTH, HEIGHT).motionFrom(RowProfile.of(before, WIDTH, HEIGHT))

        assertFalse("an ambiguous offset was reported as fact: $motion", motion is FrameMotion.Scrolled)
    }

    @Test
    fun `a real scroll is still measured through the same noise`() {
        // And the rule above must not cost this, or following a scroll stops working and the
        // covers go back to being thrown away on every frame of a feed.
        val before = page(seed = 7)
        val after = before.shiftedDownBy(30)
        after.addNoise(seed = 9, amplitude = 6)

        val motion = RowProfile.of(after, WIDTH, HEIGHT).motionFrom(RowProfile.of(before, WIDTH, HEIGHT))

        assertTrue("expected a scroll, got $motion", motion is FrameMotion.Scrolled)
        assertEquals(30f / HEIGHT, (motion as FrameMotion.Scrolled).fraction, 0.02f)
    }

    @Test
    fun `a cover moving does not itself read as the page moving`() {
        // The measurement error that made the covers chase themselves. Excluding the covers means
        // a row crossing one is averaged over what is left of it, and what is left of it changes
        // the moment the cover moves. Comparing that against the same row averaged over the whole
        // width measures the cover, not the page: raising, lifting or nudging a cover injected a
        // step into the estimate, the covers moved in response, which changed the estimate again.
        // A two-column page: a dark thumbnail beside light text, which is what a feed row is.
        // Averaging a row over only its right half and comparing that against the same row
        // averaged over only its left half is a difference of nearly the whole luminance range,
        // with nothing on the page having moved at all.
        val frame = twoColumnPage()

        val left = RowProfile.of(frame, WIDTH, HEIGHT, ignore = listOf(band(0f, 0.45f)))
        val right = RowProfile.of(frame.copyOf(), WIDTH, HEIGHT, ignore = listOf(band(0.5f, 0.95f)))

        assertEquals(
            "a cover moving across a still page read as movement",
            FrameMotion.Still,
            right.motionFrom(left),
        )
    }

    // --- a cover that is being carried has to prove itself eventually -----------------------------

    @Test
    fun `a cover nudged back and forth forever is eventually made to earn its place`() {
        // The last resort, and the reason it exists: nothing re-confirms a cover while it stands,
        // because the scan cannot see under it. Whatever the estimate does, a cover that has been
        // carried a screen's worth since anything saw its picture is not over that picture any
        // more, and no amount of careful measurement upstream can be relied on to prevent it.
        var covers = CoverPolicy.next(
            current = emptyList(),
            found = listOf(picture),
            motion = FrameMotion.Changed,
            now = 0L,
        )

        var now = CoverPolicy.MIN_HOLD_MILLIS + 100L
        var passes = 0
        while (covers.isNotEmpty() && passes < 200) {
            covers = CoverPolicy.next(
                current = covers,
                found = null,
                motion = FrameMotion.Scrolled(if (passes % 2 == 0) 0.02f else -0.02f),
                now = now,
            )
            now += 100L
            passes++
        }

        assertTrue("the cover was never made to earn its place", covers.isEmpty())
        // And it was not taken down at the first nudge: that would throw away a cover on a page
        // that is genuinely being scrolled, which is the behaviour all of this replaced.
        assertTrue("taken down after only $passes passes", passes > 20)
    }

    @Test
    fun `seeing the picture again restores the whole allowance`() {
        // Otherwise a long scroll down a feed would retire every cover on it in turn, however
        // often the pictures under them were re-found.
        var covers = CoverPolicy.next(emptyList(), listOf(picture), FrameMotion.Changed, now = 0L)
        var now = CoverPolicy.MIN_HOLD_MILLIS + 100L

        repeat(3) {
            repeat(20) {
                covers = CoverPolicy.next(covers, null, FrameMotion.Scrolled(0.02f), now)
                covers = CoverPolicy.next(covers, null, FrameMotion.Scrolled(-0.02f), now)
                now += 100L
            }
            // The page settles and the scan sees the picture where the cover is.
            covers = CoverPolicy.next(covers, listOf(covers.single().region), FrameMotion.Still, now)
            now += 100L
        }

        assertEquals(1, covers.size)
    }

    // --- a cover over the whole screen stops blinking -------------------------------------------

    @Test
    fun `a cover re-earned again and again is re-examined less often`() {
        // A cover over most of the screen can only be re-examined by lifting it, and lifting it is
        // a flash of what is underneath. Paying that every two and a half seconds for the length
        // of a video the classifier has rejected four times running is the blinking a parent
        // reports, and the verdict was never in doubt.
        val fresh = CoverPolicy.ActiveCover(whole, raisedAt = 0L, lastConfirmedAt = 0L)
        val settled = fresh.copy(confirmations = 4)
        val at = CoverPolicy.SHORT_HOLD_MILLIS + 1

        assertTrue(CoverPolicy.anyDueForRecheck(listOf(fresh), now = at, coveredFraction = 1f))
        assertFalse(CoverPolicy.anyDueForRecheck(listOf(settled), now = at, coveredFraction = 1f))
    }

    @Test
    fun `however settled, a cover is never allowed to stop being examined`() {
        val settled = CoverPolicy.ActiveCover(whole, raisedAt = 0L, lastConfirmedAt = 0L, confirmations = 99)

        assertTrue(
            CoverPolicy.anyDueForRecheck(
                listOf(settled),
                now = CoverPolicy.SETTLED_HOLD_MILLIS + 1,
                coveredFraction = 1f,
            ),
        )
    }

    // --- one picture, one verdict ----------------------------------------------------------------

    @Test
    fun `a picture whose score wobbles below the threshold keeps its cover`() {
        // The third cause, and the one that is about the model rather than the pixels. The same
        // thumbnail scored twice does not score twice the same, so a threshold read off a single
        // frame is a coin flipped at the sampling rate — and the cover it controls blinks over
        // exactly the content it is meant to hide.
        val stability = VerdictStability()
        val raise = 0.5f

        assertTrue(stability.isCovering(picture, score = 0.62f, raiseAt = raise, now = 0L))
        listOf(0.46f, 0.51f, 0.44f, 0.49f).forEachIndexed { index, score ->
            assertTrue(
                "the cover came off at $score",
                stability.isCovering(picture, score, raise, now = 500L * (index + 1)),
            )
        }
    }

    @Test
    fun `a picture that really is safe loses its cover`() {
        // The hysteresis must be a gap, not a latch. A cover that can never come off is the other
        // failure this whole pipeline is built around.
        val stability = VerdictStability()
        assertTrue(stability.isCovering(picture, score = 0.7f, raiseAt = 0.5f, now = 0L))

        var now = 0L
        repeat(6) {
            now += 500L
            stability.isCovering(picture, score = 0.05f, raiseAt = 0.5f, now = now)
        }

        assertFalse(stability.isCovering(picture, score = 0.05f, raiseAt = 0.5f, now = now + 500L))
    }

    @Test
    fun `the bar for covering something is never lowered`() {
        // The gap only ever makes a cover harder to remove. A picture that has never been covered
        // is judged at exactly the strictness the parent chose, or the control on the screen is
        // not the control it says it is.
        val stability = VerdictStability()
        var now = 0L
        repeat(8) {
            assertFalse(
                stability.isCovering(picture, score = 0.44f, raiseAt = 0.5f, now = now),
            )
            now += 500L
        }
    }

    @Test
    fun `a picture is remembered across the examination that lifts its cover`() {
        // A covered picture cannot be classified — the capture shows the cover. Without being told
        // it is still there it would be forgotten while still on screen, and the examination that
        // lifts the cover would meet it as though for the first time and make it clear the full
        // threshold again. A borderline video cleared it one time and missed it the next, which is
        // the cover coming back every few seconds and then not coming back.
        val stability = VerdictStability()
        assertTrue(stability.isCovering(picture, score = 0.68f, raiseAt = 0.5f, now = 0L))

        var now = 0L
        repeat(10) {
            now += 500L
            stability.stillCovered(listOf(picture), now)
        }

        assertTrue(
            "the picture was met as though for the first time",
            stability.isCovering(picture, score = 0.42f, raiseAt = 0.5f, now = now + 500L),
        )
    }

    @Test
    fun `a session boundary forgets every verdict`() {
        val stability = VerdictStability()
        assertTrue(stability.isCovering(picture, score = 0.9f, raiseAt = 0.5f, now = 0L))
        stability.reset()

        assertFalse(stability.isCovering(picture, score = 0.42f, raiseAt = 0.5f, now = 500L))
    }

    // --- fixtures ---------------------------------------------------------------------------------

    private val picture = ScreenRegions.Region(0.1f, 0.2f, 0.7f, 0.55f)
    private val whole = ScreenRegions.Region.WHOLE_FRAME

    private fun band(left: Float, right: Float) = ScreenRegions.Region(left, 0.15f, right, 0.85f)

    /** A dark column beside a light one, so which half a row is averaged over matters enormously. */
    private fun twoColumnPage(): IntArray {
        val frame = IntArray(WIDTH * HEIGHT) { rgb(246, 246, 248) }
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH / 2) {
                frame[y * WIDTH + x] = rgb(30, 26, 34)
            }
        }
        frame.paintText(WIDTH, WIDTH / 2 + 4, 0, WIDTH - 4, HEIGHT)
        return frame
    }

    /** A page with enough structure that sliding it over itself costs something. */
    private fun page(seed: Int): IntArray {
        val frame = fill(WIDTH, HEIGHT, rgb(248, 248, 250))
        frame.paintText(WIDTH, 2, 4, WIDTH - 2, 60)
        frame.paintPhoto(WIDTH, left = 4, top = 70, right = WIDTH - 4, bottom = 150, seed = seed)
        frame.paintText(WIDTH, 2, 158, WIDTH - 2, 210)
        frame.paintPhoto(WIDTH, left = 4, top = 216, right = WIDTH - 4, bottom = 300, seed = seed + 40)
        frame.paintText(WIDTH, 2, 308, WIDTH - 2, HEIGHT - 4)
        return frame
    }

    private fun IntArray.shiftedDownBy(rows: Int): IntArray {
        // Not fill(): inside an IntArray extension that name resolves to the stdlib's own fill,
        // which returns Unit.
        val moved = IntArray(WIDTH * HEIGHT) { rgb(248, 248, 250) }
        for (y in 0 until HEIGHT) {
            val source = y - rows
            if (source < 0 || source >= HEIGHT) continue
            System.arraycopy(this, source * WIDTH, moved, y * WIDTH, WIDTH)
        }
        return moved
    }

    private fun IntArray.addNoise(seed: Int, amplitude: Int) {
        val random = Random(seed)
        for (index in indices) {
            val pixel = this[index]
            fun jitter(channel: Int) =
                (channel + random.nextInt(-amplitude, amplitude + 1)).coerceIn(0, 255)
            this[index] = rgb(
                jitter((pixel ushr 16) and 0xFF),
                jitter((pixel ushr 8) and 0xFF),
                jitter(pixel and 0xFF),
            )
        }
    }

    private fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun fill(width: Int, height: Int, colour: Int) = IntArray(width * height) { colour }

    private fun IntArray.paintPhoto(
        width: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        seed: Int,
    ) {
        val random = Random(seed)
        for (y in top until bottom) {
            for (x in left until right) {
                this[y * width + x] = rgb(
                    random.nextInt(40, 240),
                    random.nextInt(20, 200),
                    random.nextInt(10, 170),
                )
            }
        }
    }

    private fun IntArray.paintText(width: Int, left: Int, top: Int, right: Int, bottom: Int) {
        for (y in top until bottom) {
            for (x in left until right) {
                val on = (x / 2 + y / 3) % 2 == 0
                this[y * width + x] = if (on) rgb(20, 20, 20) else rgb(255, 255, 255)
            }
        }
    }

    /** A flat-ish shot at a given brightness. What a video looks like between scene cuts. */
    private fun IntArray.paintScene(top: Int, bottom: Int, base: Int, seed: Int) {
        val random = Random(seed)
        for (y in top until bottom) {
            for (x in 4 until WIDTH - 4) {
                val jitter = random.nextInt(-24, 25)
                this[y * WIDTH + x] = rgb(
                    (base + jitter).coerceIn(0, 255),
                    (base + jitter / 2).coerceIn(0, 255),
                    (base - jitter / 2).coerceIn(0, 255),
                )
            }
        }
    }

    /** What the rule this replaced actually measured, so a test can say it would have tripped. */
    private fun meanRowDifference(before: IntArray, after: IntArray): Float {
        var total = 0L
        for (y in 0 until HEIGHT) {
            fun mean(frame: IntArray): Int {
                var sum = 0L
                for (x in 0 until WIDTH) {
                    val pixel = frame[y * WIDTH + x]
                    sum += (((pixel ushr 16) and 0xFF) * 299 +
                        ((pixel ushr 8) and 0xFF) * 587 +
                        (pixel and 0xFF) * 114) / 1000
                }
                return (sum / WIDTH).toInt()
            }
            total += kotlin.math.abs(mean(before) - mean(after))
        }
        return total.toFloat() / HEIGHT
    }

    /** A column of cards that look the same as each other, which is what a feed is. */
    private fun repeatingCards(): IntArray {
        val frame = IntArray(WIDTH * HEIGHT) { rgb(248, 248, 250) }
        var top = 0
        while (top + CARD_PITCH <= HEIGHT) {
            frame.paintPhoto(WIDTH, 6, top + 4, WIDTH - 6, top + CARD_PITCH - 12, seed = 77)
            frame.paintText(WIDTH, 6, top + CARD_PITCH - 10, WIDTH - 6, top + CARD_PITCH - 2)
            top += CARD_PITCH
        }
        return frame
    }

    private companion object {
        const val WIDTH = 160
        const val HEIGHT = 360
        const val PLAYER_TOP = 70
        const val PLAYER_BOTTOM = 170
        const val CARD_PITCH = 36
    }
}
