package com.example.childsafetyimpl.platform.content

/**
 * Keeps one picture's verdict steady across the frames it appears in.
 *
 * Everything else in this pipeline treats a frame as the unit of decision, and for *where* things
 * are that is right. For *what they are* it is not, because the same picture classified twice does
 * not score twice the same. The bundled labeller is a general model reading a thumbnail scaled up
 * from a third-resolution capture: between two frames of a video that a person would call
 * identical, a label appears at 0.36 and is gone at 0.34, the crop lands two pixels differently,
 * and the fused score moves by a tenth. A threshold applied to that directly is a coin being
 * flipped several times a second, and the cover it controls blinks on and off over exactly the
 * content it is meant to hide — which is both the worst thing to look at and an advertisement for
 * where to look.
 *
 * So a score is not read as a fact about this frame. It is evidence about a picture, averaged into
 * what the last few frames said about the same picture ([SMOOTHING]), and the averaged score is
 * read through two thresholds rather than one: a picture has to be clearly unsafe to be covered,
 * and clearly safe to be uncovered again ([RELEASE_SHARE]). In between it stays as it is. That gap
 * is what a single threshold cannot express and is the whole reason this class exists.
 *
 * Deliberately not a safety relaxation. The raise threshold is exactly the parent's chosen
 * strictness, unchanged; the hysteresis only makes a cover *harder* to remove, never easier to
 * avoid. And a picture nobody has seen for [FORGET_MILLIS] is forgotten, so this can never hold a
 * verdict over ground that has become something else.
 */
class VerdictStability {

    private class Tracked(
        var region: ScreenRegions.Region,
        var score: Float,
        var covering: Boolean,
        var seenAt: Long,
    )

    private val tracked = mutableListOf<Tracked>()

    /**
     * Whether this region should be covered, given what this frame's classifier said about it.
     *
     * @param raiseAt the parent's chosen strictness. Applied unchanged to a picture not already
     *   covered; a covered one is released at [RELEASE_SHARE] of it.
     */
    @Synchronized
    fun isCovering(
        region: ScreenRegions.Region,
        score: Float,
        raiseAt: Float,
        now: Long,
    ): Boolean {
        forget(now)

        val existing = tracked
            .filterNot { it.seenAt == now }
            .maxByOrNull { it.region.overlapWith(region) }
            ?.takeIf { it.region.overlapWith(region) >= SAME_PICTURE }

        val entry = existing ?: Tracked(region, score, covering = false, seenAt = now).also {
            tracked += it
        }

        if (existing != null) {
            entry.score = entry.score * (1f - SMOOTHING) + score * SMOOTHING
            entry.region = region
        }
        entry.seenAt = now

        entry.covering = if (entry.covering) {
            entry.score >= raiseAt * RELEASE_SHARE
        } else {
            entry.score >= raiseAt
        }
        return entry.covering
    }

    /**
     * Tells this what the covering logic actually did, for the pictures it could not look at.
     *
     * A covered picture is not classified — the capture shows the cover, not the picture, so
     * scoring it would be scoring our own output (rule 17). It therefore stops being seen here the
     * moment it is covered, and without this it would be forgotten while still on screen: the
     * cover lifts for its scheduled re-examination, the picture is classified as though for the
     * first time, and has to clear the raise threshold again. A borderline video cleared it on one
     * examination and missed it on the next, which is the cover coming back every few seconds and
     * then not coming back, with no change on screen to explain either.
     */
    @Synchronized
    fun stillCovered(regions: List<ScreenRegions.Region>, now: Long) {
        forget(now)
        regions.forEach { region ->
            tracked
                .maxByOrNull { it.region.overlapWith(region) }
                ?.takeIf { it.region.overlapWith(region) >= SAME_PICTURE }
                ?.let {
                    it.region = region
                    it.seenAt = now
                }
        }
    }

    /** Drops pictures nothing has seen for a while. Bounded memory and no stale verdicts. */
    private fun forget(now: Long) {
        tracked.removeAll { now - it.seenAt > FORGET_MILLIS }
    }

    /**
     * Forgets everything. Called when capture restarts, so no verdict crosses a session.
     *
     * Synchronised along with the two readers above, and not for contention: a session is released
     * from the main thread while a scan may still be running on the capture thread, and a list
     * cleared underneath an iteration throws. The loop that would catch it is the one that must
     * never stop, because capture consent cannot be re-obtained without a parent tapping a system
     * dialog.
     */
    @Synchronized
    fun reset() = tracked.clear()

    companion object {

        /**
         * How much of the averaged score one frame is worth.
         *
         * High enough that a picture which genuinely becomes unsafe is covered within a couple of
         * frames — this runs at the classification rate, so the delay this buys is paid in
         * exposure. Low enough that one noisy reading cannot decide anything on its own.
         */
        const val SMOOTHING = 0.5f

        /**
         * How far below the raise threshold a picture has to fall before its cover comes off.
         *
         * The gap, and the only number here that is a judgement rather than a measurement. Too
         * close to 1 and there is no hysteresis at all. Too far below and a picture that really
         * has been replaced by something safe keeps a cover over it.
         */
        const val RELEASE_SHARE = 0.7f

        /** How much two rectangles have to agree before they are the same picture. */
        const val SAME_PICTURE = 0.3f

        /**
         * How long a picture is remembered after nothing has seen it.
         *
         * Longer than the hold that lifts a cover for re-examination, and that is the point: the
         * examination has to be able to find what it is re-examining.
         */
        const val FORGET_MILLIS = 20_000L
    }
}
