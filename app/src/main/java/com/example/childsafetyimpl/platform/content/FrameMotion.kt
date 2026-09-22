package com.example.childsafetyimpl.platform.content

import kotlin.math.abs

/**
 * How the screen moved between two frames.
 *
 * The covering pipeline used to ask one question — did the screen change? — and act on the answer
 * by throwing every cover away. On a feed that is wrong in the most damaging way available: a
 * scroll changes the screen on every single frame, so every cover was discarded and re-derived
 * from scratch several times a second. That is what a parent sees as the covers juggling, and for
 * the frame between discarding and re-deriving the picture underneath is on display.
 *
 * A scroll is not a new screen. It is the same screen at a different offset, and the covers on it
 * are still correct once they are moved by that offset. Separating the two cases is the whole
 * point of this type: [Scrolled] keeps the covers and moves them, [Changed] is the only answer
 * that throws them away.
 */
sealed interface FrameMotion {

    /** Nothing moved enough to matter. Every cover stays exactly where it is. */
    data object Still : FrameMotion

    /**
     * The page scrolled, and by how much, as a fraction of screen height.
     *
     * Positive means the content moved *down* the screen, which is what a finger moving down
     * produces. A cover is still over its picture after it is moved by the same amount.
     *
     * [reported] says where the number came from. Measured from pixels, it is an estimate, and a
     * cover carried far on estimates has to earn its place again — see
     * `CoverPolicy.MAX_UNCONFIRMED_DRIFT`. Reported by the app that scrolled, it is the distance
     * the page actually went, and counting it towards that limit would take down every cover on a
     * long scroll one flash at a time for no reason. See [ScrollLedger].
     */
    data class Scrolled(val fraction: Float, val reported: Boolean = false) : FrameMotion

    /** A new layout. Nothing measured on the old one survives it. */
    data object Changed : FrameMotion

    /** Too little uncovered screen to tell. Covers are left alone and their hold timers decide. */
    data object Unknown : FrameMotion
}

/**
 * A frame reduced to one number per row, which is enough to measure how far a page scrolled.
 *
 * Scrolling is a vertical translation, so the cheapest sufficient description of a frame is its
 * vertical profile: the average brightness of each row. Two profiles are aligned by sliding one
 * over the other and taking the offset with the smallest average difference, which costs a few
 * hundred thousand integer operations and answers the only question that matters — how far did
 * everything move.
 *
 * This deliberately measures something different from [SceneSignature], which asks whether the
 * screen is worth looking at again and is tuned to be twitchy about it. Movement and displacement
 * are different questions: one decides whether to run the classifier, the other decides where the
 * covers go. One measurement serving both is what forced a single threshold to be simultaneously
 * sensitive enough to catch a scroll and insensitive enough not to call every flicker a new page,
 * and there is no such threshold.
 *
 * Rows under this app's own covers are excluded, for the reason in rule 17: a cover is painted at
 * a fixed place on the screen while the content slides underneath it, so including covered rows
 * adds a band that is identical at every offset and drags the estimate towards "nothing moved" —
 * most strongly at exactly the moment the most is covered.
 *
 * **Three properties below are what stop the covers wandering**, and each one is here because
 * leaving it out produced a specific thing a parent watched happen on a phone.
 *
 * *A row average is only ever compared with one taken over the same columns.* Excluding the covers
 * means a row crossing one is averaged over what is left of it, and what is left of it changes the
 * moment a cover moves. Comparing a row averaged over 40% of its width against the same row
 * averaged over all of it measures the cover, not the page — so every raise, lift and move of a
 * cover injected a step change into the motion estimate and the covers slid in response to their
 * own movement. Each row therefore records which column bands went into it ([bands]) and rows
 * whose bands differ are not compared at all.
 *
 * *An offset has to be the only good answer, not merely the best one.* A feed is a column of
 * near-identical cards, a page of text is a stack of near-identical lines, and both align about as
 * well at several offsets. Taking the winner of a field of equals is drawing a displacement out of
 * a noise floor, and at the tracking rate that is a random walk: covers drifting off their pictures
 * on a screen nobody touched. [isDistinct] requires the winner to beat every offset that is not
 * near it.
 *
 * *A new layout is a change of the whole screen, not a large change somewhere on it.* The old test
 * was the mean difference across the frame, which a video playing in a third of the screen clears
 * on its own — so every couple of seconds a playing video was read as a navigation and every cover
 * on the page was thrown away and rebuilt. That is the blur disappearing and coming back for no
 * reason the parent can see. A layout change now has to move most of the *rows*, which a
 * navigation does and a video playing inside a page does not.
 */
class RowProfile private constructor(
    private val rows: IntArray,
    /** Which column bands were averaged into each row. Zero means the row is unknown. */
    private val bands: IntArray,
) {

    /** One offset's fit: how far apart the two frames are, and over how much of the screen. */
    private class Alignment(
        val score: Float,
        /** Share of compared rows that differ by more than [ROW_CHANGED]. */
        val changedShare: Float,
    )

    /**
     * How far the content moved between [previous] and this frame.
     *
     * Three outcomes have to be told apart and they are not distinguished by one number. A still
     * page aligns at zero with almost no residual. A scrolled page aligns badly at zero and well
     * somewhere else. A new page aligns badly everywhere, and that last case is the one that must
     * not be mistaken for a large scroll: a wrong offset moves every cover onto the wrong content
     * and leaves it there, which is worse than taking the covers down and looking again.
     *
     * Two things outside this measurement get a say, and they are believed to different degrees
     * because they are evidence of different quality.
     *
     * @param newLayout what the two-dimensional grid says — [SceneSignature.isNewLayoutVersus].
     *   Row averages cannot settle a layout change on their own, see [NEW_LAYOUT_ROW_SHARE], and a
     *   navigation they miss is a cover left standing over the next page. But the grid cannot tell
     *   a new screen from a *moved* one: a page scrolled by a cell's height changes almost every
     *   cell, which is exactly the reading that made the covers flicker on a feed and is what rule
     *   20 exists to prevent. So this is believed only where nothing here measured a displacement.
     *   A distinct, well-fitting offset is the stronger evidence — it says where the content went,
     *   not merely that the picture is different — and it wins.
     * @param windowChanged what the accessibility service reports: the app whose window is in
     *   front is not the one it was. That is not an inference about pixels and nothing measured
     *   here can overturn it. Whatever offset these rows fit at, it is an offset between two
     *   different windows, and moving the covers by it would place every one of them on content
     *   that was never judged.
     */
    fun motionFrom(
        previous: RowProfile?,
        newLayout: Boolean = false,
        windowChanged: Boolean = false,
    ): FrameMotion {
        if (previous == null || previous.rows.size != rows.size) return FrameMotion.Changed
        if (windowChanged) return FrameMotion.Changed

        val height = rows.size
        val still = alignAt(previous, 0) ?: return FrameMotion.Unknown
        // A frame that did not move cannot have scrolled, so there is no displacement here for the
        // grid's verdict to lose to and it is simply applied. This is the case the grid was added
        // for: two screens the rows cannot tell apart line up perfectly at zero, which is what
        // left a page's covers standing over the page that replaced it.
        if (still.score <= STILL_SCORE) {
            return if (newLayout) FrameMotion.Changed else FrameMotion.Still
        }

        val maxShift = (height * MAX_SHIFT_SHARE).toInt()
        var bestShift = 0
        var bestScore = still.score

        var shift = -maxShift
        while (shift <= maxShift) {
            val alignment = alignAt(previous, shift)
            if (alignment != null && alignment.score < bestScore) {
                bestScore = alignment.score
                bestShift = shift
            }
            shift += COARSE_STEP
        }

        // The coarse pass lands within a step of the true offset; this finds the rest of it. A
        // cover placed three rows out is invisible, but the refinement is nearly free and the
        // error would otherwise accumulate across a long scroll.
        for (fine in (bestShift - COARSE_STEP + 1) until (bestShift + COARSE_STEP)) {
            val alignment = alignAt(previous, fine) ?: continue
            if (alignment.score < bestScore) {
                bestScore = alignment.score
                bestShift = fine
            }
        }

        // What this frame means if it did not move. Both halves are required and they rule out
        // different things. The magnitude alone calls a playing video a navigation, because a
        // video changes its own rows completely and the mean across the frame carries it over any
        // threshold worth having. The share alone calls a dimming screen or an autoplaying page
        // fade a navigation, because every row changes a little. A new layout is most of the
        // screen becoming substantially different at once, which is what both together say.
        val inPlace = if (
            still.score >= NEW_LAYOUT_SCORE && still.changedShare >= NEW_LAYOUT_ROW_SHARE
        ) {
            FrameMotion.Changed
        } else {
            FrameMotion.Still
        }

        val measured = when {
            bestShift == 0 -> inPlace
            // Too small to be worth moving a window for, and within the error of the estimate.
            abs(bestShift) < MIN_SHIFT_ROWS -> inPlace
            // Nothing lines up well anywhere, including here. A navigation, an app switch or a
            // video cut: the offset is arithmetic, not evidence, and acting on it would move every
            // cover onto content that was never judged.
            bestScore > ALIGNED_SCORE -> inPlace
            // A page of flat colour, or one repeating band, lines up about as well at every
            // offset. Requiring the winner to beat standing still by a real margin is what stops
            // this reporting a confident scroll for a frame that merely got brighter.
            still.score - bestScore < MIN_IMPROVEMENT -> inPlace
            // And requiring it to beat every *other* offset is what stops this reporting a
            // confident scroll for a frame full of repeating structure, where a dozen offsets fit
            // equally well and the winner is whichever one noise favoured on this frame.
            !isDistinct(previous, bestShift, bestScore, maxShift) -> inPlace
            else -> FrameMotion.Scrolled(bestShift.toFloat() / height)
        }

        // The grid's verdict applies to everything except a measured scroll. See [newLayout]: a
        // page that moved by a cell's height changes nearly every cell, so letting the grid
        // overrule a displacement would throw away every cover on a feed the moment a finger
        // touched it — the exact behaviour rule 20 was written about.
        return if (newLayout && measured !is FrameMotion.Scrolled) FrameMotion.Changed else measured
    }

    /**
     * Whether [bestShift] is the only offset that fits, rather than the luckiest of several.
     *
     * Offsets within [AMBIGUITY_GAP] rows of the winner are its own shoulder and are skipped; the
     * test is against the best fit anywhere else. A real scroll leaves a single sharp minimum,
     * because content misaligned by even a few rows compares badly. A column of identical cards
     * leaves one minimum per card, and this is what refuses to pick between them.
     */
    private fun isDistinct(
        previous: RowProfile,
        bestShift: Int,
        bestScore: Float,
        maxShift: Int,
    ): Boolean {
        var runnerUp = Float.MAX_VALUE
        var shift = -maxShift
        while (shift <= maxShift) {
            if (abs(shift - bestShift) >= AMBIGUITY_GAP) {
                val alignment = alignAt(previous, shift)
                if (alignment != null && alignment.score < runnerUp) runnerUp = alignment.score
            }
            shift += COARSE_STEP
        }
        if (runnerUp == Float.MAX_VALUE) return true
        return runnerUp - bestScore >= DISTINCTNESS
    }

    /**
     * How well this frame's row `y` lines up with [other]'s row `y - shift`.
     *
     * Rows built from different column bands are skipped rather than compared. They are averages
     * of different parts of the screen and the difference between them is the shape of a cover,
     * not the movement of a page — see the class comment.
     *
     * Null when too few rows are comparable, which happens when the covers hide most of the
     * screen. Returning a number there would be reporting a measurement taken over page margins.
     */
    private fun alignAt(other: RowProfile, shift: Int): Alignment? {
        var total = 0L
        var compared = 0
        var changed = 0
        for (y in rows.indices) {
            val mask = bands[y]
            if (mask == UNKNOWN_ROW) continue
            val source = y - shift
            if (source < 0 || source >= rows.size) continue
            if (other.bands[source] != mask) continue
            val delta = abs(rows[y] - other.rows[source])
            total += delta
            if (delta >= ROW_CHANGED) changed++
            compared++
        }
        if (compared < rows.size * MINIMUM_COMPARED_SHARE) return null
        return Alignment(
            score = total.toFloat() / compared,
            changedShare = changed.toFloat() / compared,
        )
    }

    companion object {

        /**
         * Builds a profile, leaving out anything under [ignore].
         *
         * The frame is divided into [BANDS] columns and a row is the average of the bands this app
         * is not covering, recorded alongside the set of bands that produced it. Averaging over
         * whatever happens to be left of a row and then comparing that against a row averaged over
         * a different remainder is the measurement error the whole class comment is about; keeping
         * the mask is what lets [alignAt] refuse those comparisons instead of believing them.
         */
        fun of(
            pixels: IntArray,
            width: Int,
            height: Int,
            ignore: List<ScreenRegions.Region> = emptyList(),
        ): RowProfile {
            val rows = IntArray(height)
            // Every row unknown until a band of it survives the covers. UNKNOWN_ROW is
            // zero, which is what an IntArray starts at.
            val bands = IntArray(height)
            if (width <= 0 || height <= 0 || pixels.size < width * height) {
                return RowProfile(rows, bands)
            }

            val bandWidth = width / BANDS
            if (bandWidth < 1) return RowProfile(rows, bands)

            for (y in 0 until height) {
                val centreY = (y + 0.5f) / height
                // Resolved per row rather than per pixel. A frame is a third of a million pixels
                // and the cover list is never longer than a handful, so the test belongs out here.
                val over = ignore.filter { centreY >= it.top && centreY <= it.bottom }

                var mask = 0
                var used = 0
                var total = 0L
                val rowStart = y * width

                for (band in 0 until BANDS) {
                    // A band a cover touches at all is dropped, rather than one whose centre it
                    // happens to land on. A cover is static paint over a page that is moving, so
                    // any of it left in an average argues that nothing moved — rule 17 — and the
                    // cost of dropping a band that was only partly obscured is nothing at all.
                    val bandLeft = band.toFloat() / BANDS
                    val bandRight = (band + 1f) / BANDS
                    if (over.any { it.left < bandRight && it.right > bandLeft }) continue

                    var bandTotal = 0L
                    var samples = 0
                    var x = band * bandWidth
                    val endX = if (band == BANDS - 1) width else (band + 1) * bandWidth
                    while (x < endX) {
                        val pixel = pixels[rowStart + x]
                        bandTotal += (((pixel ushr 16) and 0xFF) * 299 +
                            ((pixel ushr 8) and 0xFF) * 587 +
                            (pixel and 0xFF) * 114) / 1000
                        samples++
                        x += SAMPLE_STEP
                    }
                    if (samples == 0) continue
                    total += bandTotal / samples
                    mask = mask or (1 shl band)
                    used++
                }

                if (used >= MINIMUM_BANDS) {
                    rows[y] = (total / used).toInt()
                    bands[y] = mask
                }
            }
            return RowProfile(rows, bands)
        }

        /** Below this the frame is the same frame. Tuned above sensor and compression noise. */
        const val STILL_SCORE = 1.6f

        /**
         * The worst residual an offset may have and still be believed.
         *
         * Above this, the two frames are not the same content at a different place. Set from what
         * a correct alignment actually scores, which is not zero: rows enter and leave at the
         * edges, the capture is scaled, and a feed loads new thumbnails as it goes.
         */
        const val ALIGNED_SCORE = 9f

        /**
         * How different a frame has to be *in place* before it can count as a new layout.
         *
         * Necessary and not sufficient: [NEW_LAYOUT_ROW_SHARE] has to agree. Too low and every
         * frame of a playing video is a new layout, so the covers are torn down and rebuilt twice
         * a second. Too high and a page that replaces itself without moving keeps covers over
         * content nobody judged.
         */
        const val NEW_LAYOUT_SCORE = 12f

        /**
         * How much of the screen has to have changed before it is a different screen.
         *
         * The half of the layout test that a playing video cannot pass. A video is a rectangle
         * inside a page: it changes its own rows completely and leaves the rest of them alone, so
         * however violently it changes it never moves most of the screen. A navigation moves all
         * of it. Before this existed the two were indistinguishable, and every scene cut in a
         * video threw away every cover on the page.
         *
         * Set high on purpose, and no longer the only route to [FrameMotion.Changed]. A row
         * average is a spatial average across the whole width, so two screens with nothing in
         * common can have row profiles a few levels apart, and a real navigation clears this bar
         * only when it happens to change the screen's brightness as well. Tuning the number down
         * to catch the rest is how a playing video starts reading as a navigation again: on this
         * measurement the two cases genuinely overlap, and no threshold separates them. What this
         * test is for is the navigation obvious enough to be certain about from rows alone.
         * [SceneSignature.isNewLayoutVersus] catches the rest, on a measurement where a video and
         * a navigation are nowhere near each other.
         */
        const val NEW_LAYOUT_ROW_SHARE = 0.6f

        /** How far apart two row averages have to be before that row counts as changed. */
        const val ROW_CHANGED = 8

        /** How much better than standing still an offset has to be before it counts as a scroll. */
        const val MIN_IMPROVEMENT = 2.5f

        /**
         * How much better than every unrelated offset the winner has to be.
         *
         * Small on the 0..255 scale these scores live on, because a correct alignment is not
         * merely better than the alternatives, it is in a different class from them: content
         * misaligned by a few rows scores tens, content aligned scores about zero. Anything that
         * cannot clear a margin this small is a field of equals.
         */
        const val DISTINCTNESS = 1.5f

        /** How far from the winner an offset has to be before it is a rival rather than a shoulder. */
        const val AMBIGUITY_GAP = 9

        /** Scrolls smaller than this are not worth moving a window for. */
        const val MIN_SHIFT_ROWS = 2

        /** The furthest a page is assumed to travel between two sampled frames. */
        const val MAX_SHIFT_SHARE = 0.45f

        /** How much of the frame has to be uncovered before an alignment means anything. */
        const val MINIMUM_COMPARED_SHARE = 0.25f

        /** How many of a row's column bands have to survive the covers before it means anything. */
        const val MINIMUM_BANDS = 3

        /** Column bands a row is divided into. The unit the cover mask is resolved at. */
        private const val BANDS = 8

        private const val COARSE_STEP = 3
        private const val SAMPLE_STEP = 4
        private const val UNKNOWN_ROW = 0
    }
}
