package com.example.childsafetyimpl.platform.content

import kotlin.math.abs

/**
 * Decides which covers are on screen, from one frame to the next.
 *
 * Separated from the capture service and kept free of Android types because this is the logic that
 * gets a cover wrong, and getting it wrong has two failure modes that are easy to confuse and have
 * opposite fixes.
 *
 * **A cover that never lifts.** Everything this app draws is mirrored back into its own capture, so
 * a cover left up is content the pipeline will keep seeing. If lifting required the classifier to
 * declare the covered area clean, it never would, because the classifier is looking at the cover.
 * So lifting is never decided by re-classifying covered ground. It is decided by [FrameMotion],
 * which is measured from the parts of the screen that are not covered, and by [MAX_HOLD_MILLIS],
 * which forces a cover down periodically so the next frame has to re-earn it.
 *
 * **A cover that flickers.** The opposite of the above, and worse to look at than no cover at all,
 * because a cover blinking on and off points at exactly the thing it is meant to hide. A borderline
 * verdict oscillates frame to frame, so a cover is held for [MIN_HOLD_MILLIS] before anything is
 * allowed to take it down.
 *
 * Between those two, a cover survives while the screen it belongs to survives.
 *
 * **A cover that wanders** is the third one, and it was the last to be understood. Following a
 * scroll means applying a measured displacement ten times a second, and a displacement measured
 * with any error at all is an error applied ten times a second. Nothing here re-confirms a cover
 * while it stands — the scan cannot see under it — so nothing ever pulled it back, and the covers
 * drifted off their pictures on a screen nobody was touching. Two things answer it. [FrameMotion]
 * now refuses to report a displacement it cannot distinguish from noise, and a cover here carries
 * [ActiveCover.driftSinceConfirmed]: however well each individual step was measured, a cover that
 * has been moved a screen's worth since anything last saw its picture has to come down and earn
 * its place again.
 */
object CoverPolicy {

    data class ActiveCover(
        val region: ScreenRegions.Region,
        val raisedAt: Long,
        val lastConfirmedAt: Long,
        /**
         * How far this cover has been carried, in total, since the evidence under it was last
         * seen. Accumulated as an absolute distance rather than a net one: a cover nudged up and
         * down by a noisy estimate has gone nowhere on paper and is nowhere near its picture.
         */
        val driftSinceConfirmed: Float = 0f,
        /**
         * How many times this cover has been taken down and immediately re-earned.
         *
         * A cover over most of the screen can only be re-examined by lifting it, and lifting it
         * shows the child what is underneath. A video that has failed the last four examinations
         * is not a borderline case, and asking it again every two and a half seconds buys a
         * verdict nobody is in doubt about at the price of a flash of the content each time. See
         * [holdMillis].
         */
        val confirmations: Int = 0,
    )

    /**
     * The next set of covers.
     *
     * @param current what is on screen now
     * @param found regions this frame's scan judged unsafe, or null when no scan ran
     * @param motion how the screen moved since the last frame
     * @param now the clock
     */
    fun next(
        current: List<ActiveCover>,
        found: List<ScreenRegions.Region>?,
        motion: FrameMotion,
        now: Long,
        coveredFraction: Float = 0f,
    ): List<ActiveCover> {
        // What the screen did decides what happens to the covers on it, and the three answers are
        // genuinely different.
        //
        // A new layout invalidates every cover's claim to its position at once: whatever is there
        // now is not what was judged, and holding a cover over it would be covering an arbitrary
        // rectangle — not protection, just an obstruction in the wrong place.
        //
        // A scroll is the opposite. It is the same layout at a different offset, and a cover that
        // moves with it is still over the picture it was judged on. Treating a scroll as a new
        // layout is what made the covers flicker: on a feed the screen moves on every frame, so
        // every cover was discarded and rebuilt several times a second, and in the frame between
        // the two the picture was on display uncovered. Moving them is both steadier to look at
        // and strictly safer.
        val surviving = when (motion) {
            is FrameMotion.Changed -> emptyList()
            is FrameMotion.Scrolled -> current.mapNotNull { cover ->
                cover.region.shiftedBy(motion.fraction)?.let {
                    cover.copy(
                        region = it,
                        driftSinceConfirmed = cover.driftSinceConfirmed +
                            if (motion.reported) 0f else abs(motion.fraction),
                    )
                }
            }
            // A page that has not moved, with enough of it visible to have seen it move, is itself
            // evidence that every picture is still where it was judged. Letting the hold run out
            // anyway lifted each cover in turn on a screen nobody was touching — the picture on
            // show for a frame, then a fresh cover with a fresh blurred copy — which is what a
            // parent reports as the blur changing by itself. A mostly covered screen cannot see
            // a change and keeps its short hold; see [holdMillis]. Only on a frame that scanned,
            // so a skipped scan still changes nothing — and a cover falling due forces one.
            FrameMotion.Still -> if (found != null && coveredFraction < MOSTLY_COVERED) {
                current.map { it.copy(lastConfirmedAt = now) }
            } else {
                current
            }
            else -> current
        }

        // Only ever one cover is given up per pass, however many are due. Every cover on screen
        // is standing on evidence it cannot see any more, so they all come due at once, and taking
        // them all down together uncovers the entire page for the frame it takes to look again —
        // which is the flicker a parent reports as the blur switching itself off. Staggered, the
        // cost of re-examining is one picture visible for one frame.
        val givingUp = surviving
            .filterNot { it.stillEarnsItsPlace(now, coveredFraction) }
            .minByOrNull { it.lastConfirmedAt }

        if (found == null) {
            // No scan this frame. Nothing new to add and nothing re-confirmed, so covers age.
            return surviving.filterNot { it === givingUp }
        }

        val kept = mutableListOf<ActiveCover>()
        val claimed = BooleanArray(found.size)

        surviving.forEach { cover ->
            val match = found.indices
                .filterNot { claimed[it] }
                .maxByOrNull { cover.region.overlapWith(found[it]) }
                ?.takeIf { cover.region.overlapWith(found[it]) >= SAME_REGION_OVERLAP }

            if (match != null) {
                claimed[match] = true
                kept += cover.copy(
                    region = reconciled(cover.region, found[match]),
                    lastConfirmedAt = now,
                    driftSinceConfirmed = 0f,
                    confirmations = cover.confirmations + 1,
                )
            } else if (cover !== givingUp) {
                // Not re-found, which is the expected case: this cover is standing on the
                // evidence, so the scan cannot see it. Held until its turn to be re-examined.
                kept += cover
            }
        }

        found.forEachIndexed { index, region ->
            if (!claimed[index]) {
                kept += ActiveCover(region = region, raisedAt = now, lastConfirmedAt = now)
            }
        }

        return kept
    }

    /**
     * Where a cover sits once the picture under it has been seen again.
     *
     * A region re-found essentially where the cover already is leaves the cover exactly alone.
     * Union alone only ever grows, and a proposal measured on a coarse grid lands a little wider
     * on one side one frame and the other side the next, so a page that sits still long enough to
     * be re-confirmed a few times watched its covers creep outwards in visible steps until they
     * read as panels laid over the page rather than as the pictures being out of focus.
     *
     * Anything else grows the cover, and only grows it. It is tempting to move a cover onto a
     * region re-found well away from it — that shape is what a drifted cover looks like — but the
     * same shape is what a *correctly placed* cover looks like, because the scan cannot see the
     * ground the cover is standing on and so re-finds only the strip of the picture sticking out
     * from under it. Re-anchoring onto that strip would shrink the cover onto it and put the rest
     * of the picture on screen. A cover that has genuinely drifted is dealt with where it can be
     * recognised for what it is: by [MAX_UNCONFIRMED_DRIFT], which takes it down and makes it earn
     * its place from a frame nothing is obscuring.
     */
    private fun reconciled(
        cover: ScreenRegions.Region,
        found: ScreenRegions.Region,
    ): ScreenRegions.Region =
        if (found.containedBy(cover) >= SETTLED_CONTAINMENT) cover else cover.union(found)

    /**
     * Whether the blurred copy already being drawn in a place may be kept for the cover there now.
     *
     * A cover draws a reduced copy of the picture underneath it, taken on the frame that picture
     * was last actually seen. Keeping that copy while the cover stands is not an optimisation: the
     * capture shows the cover in that place, so re-sampling would blur the blur, and a patch
     * re-sampled for a few seconds fades to the flat rectangle the whole design exists not to be.
     *
     * The rule is about which picture, not which rectangle, and reading it as geometry alone is
     * what put the wrong photograph on screen. A page turns, a fresh cover goes up over the new
     * page's first thumbnail, and it lands close enough to where the old cover was that the old
     * blurred copy still "fits" — so the child is shown a blurred copy of the *previous* page's
     * picture, painted over a picture nobody has looked at. A cover raised on this frame has no
     * history to keep: whatever was drawn in that place belonged to something that is gone.
     *
     * @param raisedAt when the cover now in this place first went up
     * @param carriedOverlap how much of this cover the existing copy covers, 0 when there is none
     */
    fun mayReusePreview(raisedAt: Long, now: Long, carriedOverlap: Float): Boolean =
        raisedAt != now && carriedOverlap >= PREVIEW_STILL_FITS

    /**
     * Whether an unconfirmed cover stays up.
     *
     * Three clocks, doing different jobs. [MIN_HOLD_MILLIS] is measured from when the cover went
     * up and stops it flickering. The hold is measured from the last time the evidence was
     * actually seen, and stops a cover outliving what it covered. And the drift is not a clock at
     * all but a distance: a cover carried far enough that nothing it was judged on can still be
     * underneath it has to be re-derived however recently it was confirmed.
     */
    private fun ActiveCover.stillEarnsItsPlace(now: Long, coveredFraction: Float): Boolean {
        if (now - raisedAt < MIN_HOLD_MILLIS) return true
        if (driftSinceConfirmed >= MAX_UNCONFIRMED_DRIFT) return false
        return now - lastConfirmedAt < holdMillis(coveredFraction)
    }

    /**
     * Whether any cover is about to be taken down for re-examination.
     *
     * The caller uses this to run a scan on precisely that frame. Without it, a cover expires on
     * one frame and the screen underneath is not looked at again until the next scheduled scan,
     * which is a second or more of the content on display with nothing over it — the protection
     * failing at the exact moment it was trying to verify itself.
     */
    fun anyDueForRecheck(current: List<ActiveCover>, now: Long, coveredFraction: Float): Boolean =
        current.any { !it.stillEarnsItsPlace(now, coveredFraction) }

    /**
     * How long a cover stands on evidence it can no longer see.
     *
     * Two very different numbers, because the two situations are not comparable. A cover over part
     * of a page leaves most of the screen visible, so a scroll or a navigation is detected the
     * moment it happens and the cover comes down with it; re-examining on a timer as well buys
     * almost nothing and costs a visible flash of the thing being covered every time. A cover over
     * most of the screen leaves nothing to detect change from, so the timer is the only way it
     * ever lifts, and it has to be short. Getting this backwards is what makes a blur feel like it
     * will not go away.
     *
     * The short hold lengthens each time the cover is re-earned. Every re-examination of a cover
     * that hides the screen is a flash of what is underneath, and paying that every two and a half
     * seconds for the whole length of a video the classifier has already rejected four times is
     * the blinking a parent reports. A verdict that keeps coming back the same is not borderline,
     * and asking it less often costs nothing except how quickly a cover lifts once the video
     * finally changes — which [FrameMotion] notices anyway, the moment it happens.
     */
    private fun ActiveCover.holdMillis(coveredFraction: Float): Long =
        if (coveredFraction >= MOSTLY_COVERED) {
            (SHORT_HOLD_MILLIS * (1 + confirmations)).coerceAtMost(SETTLED_HOLD_MILLIS)
        } else {
            MAX_HOLD_MILLIS
        }

    /** Two frames at the sampling rate. Long enough that a borderline verdict cannot strobe. */
    const val MIN_HOLD_MILLIS = 1_200L

    /** The hold for a cover with plenty of uncovered screen around it to notice change from. */
    const val MAX_HOLD_MILLIS = 20_000L

    /** The hold when the cover is itself what is blocking the view of the evidence. */
    const val SHORT_HOLD_MILLIS = 2_500L

    /** The longest that hold grows to for a cover that keeps being re-earned. */
    const val SETTLED_HOLD_MILLIS = 15_000L

    /** How much of the screen has to be covered before change can no longer be detected around it. */
    const val MOSTLY_COVERED = 0.5f

    /** How much two rectangles have to agree before they are the same picture. */
    const val SAME_REGION_OVERLAP = 0.25f

    /** How much of a re-found region must already sit under a cover for the cover to stay put. */
    const val SETTLED_CONTAINMENT = 0.9f

    /**
     * How exactly a cover has to sit where the last one did before it keeps its blurred copy.
     *
     * Nearly exactly. The copy is a picture, not a texture: dragged even a little off the picture
     * it was taken from it reads as a smear that does not belong to what is underneath. This only
     * ever holds a copy still for a cover that has not moved, and [mayReusePreview] is where the
     * more important half of the rule lives.
     */
    const val PREVIEW_STILL_FITS = 0.97f

    /**
     * How far a cover may be carried, in screen heights, before it has to be re-derived.
     *
     * A cover that has travelled this far without anything seeing its picture is either following
     * a page that has taken it somewhere it does not belong, or being nudged back and forth by an
     * estimate that is measuring noise. Both end the same way — a rectangle over content nobody
     * judged — and both are fixed by making it earn its place from a frame.
     */
    const val MAX_UNCONFIRMED_DRIFT = 1f
}
