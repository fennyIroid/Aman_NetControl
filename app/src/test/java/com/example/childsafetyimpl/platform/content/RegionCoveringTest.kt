package com.example.childsafetyimpl.platform.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The covering logic, tested without a device.
 *
 * Every case here corresponds to something that was actually wrong when this was driven on a
 * phone: the whole screen covered instead of one picture, the cover never lifting, and a grid of
 * search results scoring as safe because the browser chrome around it labelled as safe. They are
 * written as the behaviour rather than as the bug so that the next person changing a threshold
 * finds out which one they broke.
 */
class RegionCoveringTest {

    // --- what counts as a picture ---------------------------------------------------------------

    @Test
    fun `a flat screen offers nothing to classify`() {
        val frame = fill(120, 240, rgb(245, 245, 247))
        assertTrue(ScreenRegions.propose(frame, 120, 240).isEmpty())
    }

    @Test
    fun `the app's own cover is never proposed as a picture`() {
        // The single most important case in this file. Screen capture mirrors everything on the
        // display, including what this app draws, so a cover that reads as picture material gets
        // classified, scores like the thing it is covering, and holds itself up for the rest of
        // the session. That is precisely the "the blur does not go away" report, and the fix is
        // that a near-flat wash carries no luminance texture and so is never a candidate.
        val ink = rgb(0x14, 0x20, 0x3D)
        val frame = fill(120, 240, ink)
        assertTrue(ScreenRegions.propose(frame, 120, 240).isEmpty())
    }

    @Test
    fun `a photograph on a page background is proposed`() {
        val frame = fill(120, 240, rgb(250, 250, 250))
        frame.paintPhoto(width = 120, left = 20, top = 40, right = 100, bottom = 120, seed = 7)

        val proposals = ScreenRegions.propose(frame, 120, 240)

        assertEquals(1, proposals.size)
        val region = proposals.single().region
        // Covers the photograph, and at least as much rather than less: a cover that stops short
        // leaves a visible border of what it is hiding.
        assertTrue(region.left <= 20 / 120f)
        assertTrue(region.top <= 40 / 240f)
        assertTrue(region.right >= 100 / 120f)
        assertTrue(region.bottom >= 120 / 240f)
    }

    @Test
    fun `body text is not mistaken for a picture`() {
        // Text has as much luminance detail as a photograph and almost no colour, which is the
        // whole reason the colour floor exists. Without it every article, menu and settings
        // screen proposes regions and every one of them gets classified for nothing.
        val frame = fill(120, 240, rgb(255, 255, 255))
        frame.paintText(width = 120, left = 10, top = 20, right = 110, bottom = 200)

        assertTrue(ScreenRegions.propose(frame, 120, 240).isEmpty())
    }

    @Test
    fun `each picture in a grid is proposed separately`() {
        // The case the old whole-frame pipeline could not express. Three thumbnails, one of which
        // may be the problem: unless they arrive as three regions, the verdict is about the grid.
        val frame = fill(120, 300, rgb(252, 252, 252))
        frame.paintPhoto(width = 120, left = 8, top = 10, right = 52, bottom = 70, seed = 1)
        frame.paintPhoto(width = 120, left = 68, top = 10, right = 112, bottom = 70, seed = 2)
        frame.paintPhoto(width = 120, left = 8, top = 150, right = 52, bottom = 210, seed = 3)

        assertEquals(3, ScreenRegions.propose(frame, 120, 300).size)
    }

    @Test
    fun `regions never escape the frame`() {
        val frame = fill(120, 240, rgb(250, 250, 250))
        frame.paintPhoto(width = 120, left = 0, top = 0, right = 120, bottom = 240, seed = 5)

        ScreenRegions.propose(frame, 120, 240).forEach {
            assertTrue(it.region.left >= 0f)
            assertTrue(it.region.top >= 0f)
            assertTrue(it.region.right <= 1f)
            assertTrue(it.region.bottom <= 1f)
        }
    }

    // --- has the screen moved -------------------------------------------------------------------

    @Test
    fun `a still screen does not read as movement`() {
        val frame = fill(120, 240, rgb(200, 180, 160))
        val first = RowProfile.of(frame, 120, 240)
        val second = RowProfile.of(frame.copyOf(), 120, 240)

        assertEquals(FrameMotion.Still, second.motionFrom(first))
    }

    @Test
    fun `a scroll is measured as a displacement, not reported as a new screen`() {
        // The defect this whole type exists for. Read as a new screen, every cover on the page is
        // discarded and re-derived, which on a feed happens several times a second: the covers
        // juggle, and between discarding and re-deriving the picture is on display uncovered.
        val before = fill(120, 240, rgb(250, 250, 250))
        before.paintPhoto(width = 120, left = 10, top = 20, right = 110, bottom = 120, seed = 9)
        val after = fill(120, 240, rgb(250, 250, 250))
        after.paintPhoto(width = 120, left = 10, top = 60, right = 110, bottom = 160, seed = 9)

        val motion = RowProfile.of(after, 120, 240).motionFrom(RowProfile.of(before, 120, 240))

        assertTrue("expected a scroll, got $motion", motion is FrameMotion.Scrolled)
        assertEquals(40 / 240f, (motion as FrameMotion.Scrolled).fraction, 0.02f)
    }

    @Test
    fun `a cover moves with the page it is on`() {
        // What the displacement is for: the cover was judged on the picture, and the picture
        // moved, so the cover moves with it rather than being thrown away and rebuilt.
        val raised = CoverPolicy.next(emptyList(), listOf(picture), FrameMotion.Changed, now = 0L)
        val scrolled = CoverPolicy.next(
            raised,
            found = null,
            motion = FrameMotion.Scrolled(-0.05f),
            now = 300L,
        )

        assertEquals(1, scrolled.size)
        assertEquals(picture.top - 0.05f, scrolled.first().region.top, 0.0001f)
        assertEquals(picture.bottom - 0.05f, scrolled.first().region.bottom, 0.0001f)
        assertEquals(picture.left, scrolled.first().region.left, 0.0001f)
    }

    @Test
    fun `a cover halfway off the screen is clamped to it, not given a negative edge`() {
        // These fractions become a window's position and size. A negative top is not a cover
        // hanging off the top of the screen, it is a layout Android refuses.
        val raised = CoverPolicy.next(emptyList(), listOf(picture), FrameMotion.Changed, now = 0L)
        val half = CoverPolicy.next(raised, null, FrameMotion.Scrolled(-0.25f), now = 300L)

        assertEquals(1, half.size)
        assertEquals(0f, half.first().region.top, 0.0001f)
        assertTrue(half.first().region.height > 0f)
    }

    @Test
    fun `a cover scrolled off the screen is dropped rather than pinned to the edge`() {
        val raised = CoverPolicy.next(emptyList(), listOf(picture), FrameMotion.Changed, now = 0L)
        val gone = CoverPolicy.next(raised, null, FrameMotion.Scrolled(-1f), now = 300L)

        assertTrue(gone.isEmpty())
    }

    @Test
    fun `a new layout still throws every cover away`() {
        val raised = CoverPolicy.next(emptyList(), listOf(picture), FrameMotion.Changed, now = 0L)
        val navigated = CoverPolicy.next(raised, null, FrameMotion.Changed, now = 300L)

        assertTrue(navigated.isEmpty())
    }

    @Test
    fun `a frame that lines up nowhere is a new layout, not a large scroll`() {
        // The dangerous mistake. Any two frames have a best offset; believing one that fits badly
        // moves every cover onto content nobody judged and leaves it there for the whole hold.
        val before = fill(120, 240, rgb(250, 250, 250))
        before.paintPhoto(width = 120, left = 4, top = 4, right = 116, bottom = 230, seed = 3)
        val after = fill(120, 240, rgb(10, 10, 10))
        after.paintPhoto(width = 120, left = 4, top = 4, right = 116, bottom = 60, seed = 77)

        val motion = RowProfile.of(after, 120, 240).motionFrom(RowProfile.of(before, 120, 240))

        assertEquals(FrameMotion.Changed, motion)
    }

    @Test
    fun `an ordinary flicker is worth another look but not a new layout`() {
        // The distinction the covers depend on. Treating every flicker as a new layout tore them
        // down on every frame; treating none of them as worth looking at meant a picture that
        // appeared after the page settled was never judged.
        val before = fill(120, 240, rgb(250, 250, 250))
        before.paintPhoto(width = 120, left = 10, top = 20, right = 110, bottom = 120, seed = 11)
        val after = before.copyOf()
        // One more thumbnail finishing loading further down: a real change worth judging, and
        // nothing like the whole layout moving.
        after.paintPhoto(width = 120, left = 66, top = 170, right = 110, bottom = 215, seed = 12)

        assertTrue(SceneSignature.of(after, 120, 240).movedSince(SceneSignature.of(before, 120, 240)))
        assertEquals(
            FrameMotion.Still,
            RowProfile.of(after, 120, 240).motionFrom(RowProfile.of(before, 120, 240)),
        )
    }

    @Test
    fun `the first frame of a session is always a new layout`() {
        val frame = fill(120, 240, rgb(120, 120, 120))
        assertEquals(FrameMotion.Changed, RowProfile.of(frame, 120, 240).motionFrom(null))
    }

    @Test
    fun `raising a cover is not itself movement`() {
        // If it were, the cover would be torn down on the frame after it went up and rebuilt on
        // the one after that, which is the flicker every hold timer here exists to prevent.
        val frame = fill(120, 240, rgb(250, 250, 250))
        frame.paintPhoto(width = 120, left = 10, top = 20, right = 110, bottom = 120, seed = 4)

        val uncovered = RowProfile.of(frame, 120, 240)

        // The same screen a moment later, with our cover over the picture and the cover excluded.
        val cover = listOf(ScreenRegions.Region(0f, 20 / 240f, 1f, 120 / 240f))
        val covered = RowProfile.of(frame.copyOf(), 120, 240, ignore = cover)

        assertEquals(FrameMotion.Still, covered.motionFrom(uncovered))
    }

    @Test
    fun `covered share counts screen, not overlapping rectangles`() {
        // Five scattered covers can add up to more than the whole screen while most of it is
        // still visible, and the covering policy reads that number to decide whether it can
        // detect movement at all. Summing areas made a lightly covered page look fully covered,
        // which switched every cover onto the short timer meant for a blanked screen and made
        // them blink on and off.
        val frame = fill(120, 240, rgb(200, 190, 180))
        val overlapping = listOf(
            ScreenRegions.Region(0f, 0f, 0.8f, 0.8f),
            ScreenRegions.Region(0.1f, 0.1f, 0.9f, 0.9f),
            ScreenRegions.Region(0.2f, 0.2f, 1f, 1f),
        )

        val share = SceneSignature.of(frame, 120, 240, ignore = overlapping).coveredShare

        assertTrue(share <= 1f)
        assertTrue(share > 0.5f)
        assertEquals(0f, SceneSignature.of(frame, 120, 240).coveredShare, 0.0001f)
    }

    @Test
    fun `a mostly covered screen never reports movement it cannot see`() {
        // Found on a device. With four covers over a grid, the few cells still visible were page
        // margins, and the average over them swung enough to read as a new layout on almost every
        // frame. The covers came down, which uncovered the pictures, which put them back up: a
        // flicker at the sampling rate over exactly the content being hidden.
        val before = fill(120, 240, rgb(250, 250, 250))
        before.paintPhoto(width = 120, left = 4, top = 4, right = 116, bottom = 200, seed = 3)
        val after = fill(120, 240, rgb(20, 40, 90))
        after.paintPhoto(width = 120, left = 4, top = 4, right = 116, bottom = 200, seed = 77)

        val almostEverything = listOf(ScreenRegions.Region(0f, 0f, 1f, 0.88f))

        assertEquals(
            FrameMotion.Unknown,
            RowProfile.of(after, 120, 240, ignore = almostEverything)
                .motionFrom(RowProfile.of(before, 120, 240, ignore = almostEverything)),
        )
        assertFalse(
            SceneSignature.of(after, 120, 240, ignore = almostEverything)
                .movedSince(SceneSignature.of(before, 120, 240, ignore = almostEverything)),
        )
    }

    // --- what actually gets drawn ---------------------------------------------------------------

    @Test
    fun `the seam between two side-by-side covers is trimmed away`() {
        // Found on a device with the accessibility grant off. Each cover is capped at 0.8 opacity
        // on its own, but the system adds occlusion across the stack, so the few pixels where two
        // covers met obscured 0.96 and every touch landing there was dropped. On a two-column
        // grid that seam runs down the middle of the screen, which is where a thumb scrolls.
        val left = ScreenRegions.Region(0f, 0.2f, 0.52f, 0.6f)
        val right = ScreenRegions.Region(0.5f, 0.2f, 1f, 0.6f)

        val drawn = ScreenRegions.withoutOverlaps(listOf(left, right))

        assertEquals(2, drawn.size)
        for (a in drawn.indices) {
            for (b in a + 1 until drawn.size) {
                assertEquals(
                    "covers $a and $b still overlap",
                    0f,
                    drawn[a].overlapWith(drawn[b]),
                    0.0001f,
                )
            }
        }
    }

    @Test
    fun `trimming never uncovers anything`() {
        // The property the whole thing rests on: a cover may only give up ground another cover
        // already holds. Sampled densely rather than reasoned about, because an off-by-one here is
        // a strip of a picture on display.
        val covers = listOf(
            ScreenRegions.Region(0f, 0.2f, 0.52f, 0.6f),
            ScreenRegions.Region(0.5f, 0.2f, 1f, 0.6f),
            ScreenRegions.Region(0.1f, 0.55f, 0.9f, 0.9f),
            ScreenRegions.Region(0.2f, 0.3f, 0.3f, 0.4f),
        )
        val drawn = ScreenRegions.withoutOverlaps(covers)

        for (x in 0..200) {
            for (y in 0..200) {
                val px = x / 200f
                val py = y / 200f
                fun inside(r: ScreenRegions.Region) =
                    px >= r.left && px <= r.right && py >= r.top && py <= r.bottom
                if (covers.any(::inside)) {
                    assertTrue("($px, $py) was covered and now is not", drawn.any(::inside))
                }
            }
        }
    }

    @Test
    fun `a cover entirely inside another is dropped rather than drawn twice`() {
        val big = ScreenRegions.Region(0f, 0f, 0.8f, 0.8f)
        val inside = ScreenRegions.Region(0.2f, 0.2f, 0.4f, 0.4f)

        assertEquals(listOf(big), ScreenRegions.withoutOverlaps(listOf(inside, big)))
    }

    @Test
    fun `two covers meeting at a corner are left alone rather than trimmed unsafely`() {
        // Neither spans the other on either axis, so there is no edge that can be moved without
        // giving up ground nothing else holds. Leaving the overlap is the safe answer.
        val one = ScreenRegions.Region(0f, 0f, 0.5f, 0.5f)
        val other = ScreenRegions.Region(0.4f, 0.4f, 0.9f, 0.9f)

        val drawn = ScreenRegions.withoutOverlaps(listOf(one, other))

        assertEquals(2, drawn.size)
        assertTrue(drawn.containsAll(listOf(one, other)))
    }

    // --- which covers stay up -------------------------------------------------------------------

    private val picture = ScreenRegions.Region(0.1f, 0.1f, 0.5f, 0.4f)
    private val elsewhere = ScreenRegions.Region(0.55f, 0.6f, 0.95f, 0.9f)

    @Test
    fun `a failing region raises a cover`() {
        val covers = CoverPolicy.next(
            current = emptyList(),
            found = listOf(picture),
            motion = FrameMotion.Changed,
            now = 1_000L,
        )
        assertEquals(1, covers.size)
        assertEquals(picture, covers.single().region)
    }

    @Test
    fun `a cover is held while the scan cannot see under it`() {
        // Once the cover is up, the capture shows the cover rather than the picture, so the scan
        // stops reporting it. Reading that silence as "it is safe now" is the mistake: the only
        // reason the evidence disappeared is that we covered it.
        val raised = CoverPolicy.next(emptyList(), listOf(picture), motion = FrameMotion.Changed, now = 0L)
        val later = CoverPolicy.next(raised, found = emptyList(), motion = FrameMotion.Still, now = 2_000L)

        assertEquals(1, later.size)
    }

    @Test
    fun `a cover comes down when the page moves`() {
        val raised = CoverPolicy.next(emptyList(), listOf(picture), motion = FrameMotion.Changed, now = 0L)
        val scrolled = CoverPolicy.next(raised, found = emptyList(), motion = FrameMotion.Changed, now = 500L)

        assertTrue(scrolled.isEmpty())
    }

    @Test
    fun `only one cover at a time is given up for re-examination`() {
        // Every cover is standing on evidence it can no longer see, so they all fall due together.
        // Taking them all down at once uncovers the whole page for the frame it takes to look
        // again, which is what a parent sees as the blur switching itself off and back on.
        val many = listOf(
            CoverPolicy.ActiveCover(ScreenRegions.Region(0.0f, 0.0f, 0.3f, 0.3f), 0L, 0L),
            CoverPolicy.ActiveCover(ScreenRegions.Region(0.4f, 0.0f, 0.7f, 0.3f), 0L, 10L),
            CoverPolicy.ActiveCover(ScreenRegions.Region(0.0f, 0.4f, 0.3f, 0.7f), 0L, 20L),
            CoverPolicy.ActiveCover(ScreenRegions.Region(0.4f, 0.4f, 0.7f, 0.7f), 0L, 30L),
        )

        val after = CoverPolicy.next(
            current = many,
            found = emptyList(),
            // Motion that could not be measured. A page seen to be still keeps its covers; see
            // `a still page keeps its covers past the hold`.
            motion = FrameMotion.Unknown,
            now = CoverPolicy.MAX_HOLD_MILLIS + 1,
            coveredFraction = 0.2f,
        )

        assertEquals(3, after.size)
        // The one that has gone longest without being seen is the one re-examined first.
        assertTrue(after.none { it.lastConfirmedAt == 0L })
    }

    @Test
    fun `a cover cannot outlive the evidence indefinitely`() {
        // The backstop for a missed scene change. Without it, one frame the fingerprint failed to
        // notice leaves a rectangle on the screen for the rest of the session.
        val raised = CoverPolicy.next(emptyList(), listOf(picture), motion = FrameMotion.Changed, now = 0L)
        val held = CoverPolicy.next(
            current = raised,
            found = emptyList(),
            motion = FrameMotion.Unknown,
            now = CoverPolicy.MAX_HOLD_MILLIS + 1,
        )

        assertTrue(held.isEmpty())
    }

    @Test
    fun `a still page keeps its covers past the hold`() {
        // Chrome open, nobody touching it. The hold used to run out regardless, and each cover was
        // lifted in turn to be looked at again: the picture on show for a frame and a fresh cover
        // after it, on a page that had not changed at all.
        var covers = CoverPolicy.next(emptyList(), listOf(picture), motion = FrameMotion.Changed, now = 0L)
        var now = 0L
        while (now < CoverPolicy.MAX_HOLD_MILLIS * 3) {
            now += 500L
            covers = CoverPolicy.next(covers, found = emptyList(), motion = FrameMotion.Still, now = now, coveredFraction = 0.2f)
            assertEquals("lifted at $now", 1, covers.size)
            assertFalse(CoverPolicy.anyDueForRecheck(covers, now + 500L, 0.2f))
        }
    }

    @Test
    fun `a mostly covered screen is still re-examined while it looks still`() {
        // Nothing around the cover is visible to notice a video change under it, so looking still
        // is not evidence of anything and the short hold stands.
        val raised = CoverPolicy.next(emptyList(), listOf(ScreenRegions.Region.WHOLE_FRAME), motion = FrameMotion.Changed, now = 0L)
        val later = CoverPolicy.next(raised, found = emptyList(), motion = FrameMotion.Still, now = CoverPolicy.SHORT_HOLD_MILLIS + 1, coveredFraction = 1f)
        assertTrue(later.isEmpty())
    }

    @Test
    fun `a cover cannot flicker off immediately after going up`() {
        val raised = CoverPolicy.next(emptyList(), listOf(picture), motion = FrameMotion.Changed, now = 0L)
        val instant = CoverPolicy.next(raised, found = emptyList(), motion = FrameMotion.Still, now = 50L)

        assertEquals(1, instant.size)
    }

    @Test
    fun `a re-found region refreshes its cover instead of adding a second`() {
        val raised = CoverPolicy.next(emptyList(), listOf(picture), motion = FrameMotion.Changed, now = 0L)
        val again = CoverPolicy.next(raised, listOf(picture), motion = FrameMotion.Still, now = 3_000L)

        assertEquals(1, again.size)
        assertEquals(3_000L, again.single().lastConfirmedAt)
        assertEquals(0L, again.single().raisedAt)
    }

    @Test
    fun `a region re-found smaller never shrinks its cover`() {
        val raised = CoverPolicy.next(emptyList(), listOf(picture), motion = FrameMotion.Changed, now = 0L)
        val smaller = ScreenRegions.Region(0.15f, 0.15f, 0.4f, 0.3f)
        val again = CoverPolicy.next(raised, listOf(smaller), motion = FrameMotion.Still, now = 100L)

        // A cover that shrinks exposes a strip of exactly what it was covering.
        assertEquals(picture, again.single().region)
    }

    @Test
    fun `a second failing picture gets its own cover`() {
        val raised = CoverPolicy.next(emptyList(), listOf(picture), motion = FrameMotion.Changed, now = 0L)
        val both = CoverPolicy.next(raised, listOf(picture, elsewhere), motion = FrameMotion.Still, now = 500L)

        assertEquals(2, both.size)
    }

    @Test
    fun `no scan leaves the covers exactly as they were`() {
        val raised = CoverPolicy.next(emptyList(), listOf(picture), motion = FrameMotion.Changed, now = 0L)
        val skipped = CoverPolicy.next(raised, found = null, motion = FrameMotion.Still, now = 400L)

        assertEquals(raised, skipped)
    }

    @Test
    fun `proposing regions never throws, whatever is on screen`() {
        // Written after a crash rather than before one. A merge weighted by a count the split path
        // had no value for divided by zero, which ended the capture session; and because capture
        // consent cannot be re-obtained without a parent tapping a system dialog, ending the
        // session is not a degraded protection but a stopped one. Sweeping real-ish layouts is
        // what would have caught it, so that is what runs here.
        val random = Random(20260917)
        repeat(300) { iteration ->
            val width = 60 + random.nextInt(120)
            val height = 100 + random.nextInt(300)
            val frame = fill(width, height, rgb(240 + random.nextInt(16), 240, 245))

            repeat(random.nextInt(6)) {
                val left = random.nextInt(width - 12)
                val top = random.nextInt(height - 12)
                frame.paintPhoto(
                    width = width,
                    left = left,
                    top = top,
                    right = (left + 8 + random.nextInt(width)).coerceAtMost(width),
                    bottom = (top + 8 + random.nextInt(height)).coerceAtMost(height),
                    seed = iteration * 31 + it,
                )
            }
            if (random.nextBoolean()) {
                frame.paintText(width, 0, 0, width, (height / 4).coerceAtLeast(2))
            }

            ScreenRegions.propose(frame, width, height).forEach {
                assertTrue(it.region.left >= 0f && it.region.right <= 1f)
                assertTrue(it.region.top >= 0f && it.region.bottom <= 1f)
                assertTrue(it.region.area > 0f)
            }
        }
    }

    @Test
    fun `a cover over most of the screen is re-examined far sooner`() {
        // The two situations are not comparable and the old single timer treated them as though
        // they were. A patch on a page has the rest of the page to notice a scroll from; a cover
        // over everything has nothing, so the timer is the only thing that ever lifts it. That is
        // the case a parent describes as the blur not going away.
        val whole = listOf(
            CoverPolicy.ActiveCover(ScreenRegions.Region.WHOLE_FRAME, raisedAt = 0L, lastConfirmedAt = 0L),
        )
        val patch = listOf(
            CoverPolicy.ActiveCover(picture, raisedAt = 0L, lastConfirmedAt = 0L),
        )
        val at = CoverPolicy.SHORT_HOLD_MILLIS + 1

        assertTrue(CoverPolicy.next(whole, null, motion = FrameMotion.Still, now = at, coveredFraction = 1f).isEmpty())
        assertEquals(1, CoverPolicy.next(patch, null, motion = FrameMotion.Still, now = at, coveredFraction = 0.12f).size)
    }

    @Test
    fun `a cover about to be re-examined asks for a scan on that same frame`() {
        // Otherwise the cover comes down on one frame and nothing looks at what was under it until
        // the next scheduled scan, which is a second or more of it on display with nothing over it.
        val covers = listOf(
            CoverPolicy.ActiveCover(ScreenRegions.Region.WHOLE_FRAME, raisedAt = 0L, lastConfirmedAt = 0L),
        )

        assertFalse(CoverPolicy.anyDueForRecheck(covers, now = 100L, coveredFraction = 1f))
        assertTrue(
            CoverPolicy.anyDueForRecheck(
                covers,
                now = CoverPolicy.SHORT_HOLD_MILLIS + 1,
                coveredFraction = 1f,
            ),
        )
    }

    // --- what the labels are allowed to prove ---------------------------------------------------

    @Test
    fun `labels describing the medium never move a score`() {
        // These are true of every frame a screen capture produces, including the frame showing the
        // thing this app exists to cover. Counting them as evidence of safety subtracted a large
        // constant from every verdict taken in a browser, which is where it mattered most.
        val classifier = ImageSafetyClassifier(MlKitImageLabeler())
        val exposure = listOf(
            MlKitImageLabeler.Label("Swimwear", 0.9f),
            MlKitImageLabeler.Label("Flesh", 0.8f),
        )
        val onAScreen = exposure + SafetyLabelRisk.mediumLabels.map {
            MlKitImageLabeler.Label(it, 0.95f)
        }

        assertEquals(
            classifier.fuse(exposure, heuristicScore = 0.5f).fused,
            classifier.fuse(onAScreen, heuristicScore = 0.5f).fused,
            0.0001f,
        )
    }

    @Test
    fun `one stray benign label cannot clear a picture the exposure labels agree about`() {
        // Measured on a device: a swimwear photograph came back with Skin 0.91 and Flesh 0.74,
        // and "Dog 0.48" pulled it under the threshold. A wrong label about what else is in the
        // picture is not evidence that the picture is safe. Held here with a clothing label beside
        // them: "Skin" and "Flesh" alone are also exactly what a baby photograph returns, and on
        // their own a picture of a baby is what the benign labels are right to clear.
        val classifier = ImageSafetyClassifier(MlKitImageLabeler())
        val exposure = listOf(
            MlKitImageLabeler.Label("Swimwear", 0.6f),
            MlKitImageLabeler.Label("Skin", 0.91f),
            MlKitImageLabeler.Label("Flesh", 0.74f),
        )

        val alone = classifier.fuse(exposure, heuristicScore = 0.2f).fused
        val withStrayLabel = classifier.fuse(
            exposure + MlKitImageLabeler.Label("Dog", 0.48f),
            heuristicScore = 0.2f,
        ).fused

        assertTrue(withStrayLabel >= alone * 0.75f)
    }

    @Test
    fun `benign labels still clear a weak verdict entirely`() {
        // The other half, which the cap must not break. A page of comics scoring on skin tone with
        // almost no exposure evidence is exactly what the benign table was added for.
        val classifier = ImageSafetyClassifier(MlKitImageLabeler())
        val fusion = classifier.fuse(
            labels = listOf(
                MlKitImageLabeler.Label("Comics", 0.9f),
                MlKitImageLabeler.Label("Cartoon", 0.8f),
                MlKitImageLabeler.Label("Skin", 0.4f),
            ),
            heuristicScore = 0.6f,
        )

        assertTrue(fusion.fused < ImageSafetyClassifier.UNSAFE_THRESHOLD)
    }

    @Test
    fun `an exposure verdict survives being seen through a browser`() {
        val classifier = ImageSafetyClassifier(MlKitImageLabeler())
        val fusion = classifier.fuse(
            labels = listOf(
                MlKitImageLabeler.Label("Swimwear", 0.88f),
                MlKitImageLabeler.Label("Flesh", 0.71f),
                MlKitImageLabeler.Label("Screenshot", 0.95f),
                MlKitImageLabeler.Label("Web page", 0.92f),
            ),
            heuristicScore = 0.55f,
        )

        assertTrue(fusion.fused >= ImageSafetyClassifier.UNSAFE_THRESHOLD)
    }

    // --- where a cover sits, and whether it stays there ------------------------------------------

    @Test
    fun `a cover re-found in the same place does not creep outward`() {
        // The region proposal runs on a coarse grid, so the same photograph measures a little
        // differently from one frame to the next. Union-ing every measurement into the cover only
        // ever grows it, and over a page that sits still long enough to be re-confirmed a few
        // times the cover walks outwards past the picture — visible as a box that keeps growing
        // and reads as a panel over the page rather than as the picture being covered.
        val picture = ScreenRegions.Region(0.20f, 0.30f, 0.60f, 0.62f)
        var covers = CoverPolicy.next(emptyList(), listOf(picture), motion = FrameMotion.Changed, now = 0L)

        val jitter = listOf(
            ScreenRegions.Region(0.198f, 0.302f, 0.601f, 0.618f),
            ScreenRegions.Region(0.202f, 0.297f, 0.604f, 0.622f),
            ScreenRegions.Region(0.196f, 0.301f, 0.598f, 0.624f),
            ScreenRegions.Region(0.203f, 0.299f, 0.603f, 0.617f),
        )
        jitter.forEachIndexed { index, measured ->
            covers = CoverPolicy.next(
                current = covers,
                found = listOf(measured),
                motion = FrameMotion.Still,
                now = 500L * (index + 1),
            )
        }

        assertEquals(1, covers.size)
        assertEquals(picture, covers.single().region)
    }

    @Test
    fun `a cover still grows when the picture is genuinely bigger than it`() {
        // The other half of the same rule. Holding still must not become refusing to follow: a
        // cover that stops short of what it is covering leaves a strip of it on screen, which is
        // worse than not covering it at all because it points at the interesting part.
        val partial = ScreenRegions.Region(0.20f, 0.30f, 0.40f, 0.60f)
        val whole = ScreenRegions.Region(0.20f, 0.30f, 0.52f, 0.60f)

        val raised = CoverPolicy.next(emptyList(), listOf(partial), motion = FrameMotion.Changed, now = 0L)
        val grown = CoverPolicy.next(raised, listOf(whole), motion = FrameMotion.Still, now = 500L)

        assertEquals(whole, grown.single().region)
    }

    // --- how hard a preview is blurred -----------------------------------------------------------

    @Test
    fun `a preview is reduced to the same detail whatever resolution it arrived at`() {
        // The defect this replaced: the blur was a divisor of the source resolution, so the radius
        // tracked the capture scale rather than the picture. Stated as cells across the picture it
        // is the same blur on any phone, and the same blur whatever the capture is sampling at.
        val blur = ImageBlur()

        listOf(60, 150, 400, 1080).forEach { shortSide ->
            val cells = shortSide / blur.previewFactor(shortSide, 0.85f)
            assertTrue(
                "$shortSide px reduced to $cells cells",
                cells <= blur.previewCells(0.85f) + 1,
            )
        }
    }

    @Test
    fun `the strongest setting leaves fewer cells than the weakest`() {
        val blur = ImageBlur()
        assertTrue(blur.previewCells(1f) < blur.previewCells(0f))
    }

    @Test
    fun `no setting reduces a picture to the two colours a shaded box is made of`() {
        // What the cover looked like before this, and what the client reported: not a blur, a
        // shade. Anything under about eight cells is a gradient, not a photograph out of focus.
        val blur = ImageBlur()
        listOf(0f, 0.25f, 0.5f, 0.85f, 1f).forEach { strength ->
            assertTrue(
                "strength $strength left ${blur.previewCells(strength)} cells",
                blur.previewCells(strength) >= 8,
            )
        }
    }

    @Test
    fun `a picture smaller than the target is still blurred`() {
        // A thumbnail covered by a sharp copy of itself is not covered.
        val blur = ImageBlur()
        assertTrue(blur.previewFactor(10, 0.85f) >= 2f)
    }

    // --- fixtures -------------------------------------------------------------------------------

    private fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun fill(width: Int, height: Int, colour: Int) = IntArray(width * height) { colour }

    /**
     * Something with the texture and colour of a photograph.
     *
     * Deterministic per seed, because a test that finds a region four times out of five is worse
     * than no test: it teaches whoever sees it fail to run it again.
     */
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

    /** High luminance detail, no colour. The thing most likely to be mistaken for a picture. */
    private fun IntArray.paintText(width: Int, left: Int, top: Int, right: Int, bottom: Int) {
        for (y in top until bottom) {
            for (x in left until right) {
                val on = (x / 2 + y / 3) % 2 == 0
                this[y * width + x] = if (on) rgb(20, 20, 20) else rgb(255, 255, 255)
            }
        }
    }
}
