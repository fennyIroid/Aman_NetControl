package com.example.childsafetyimpl.platform.content

/**
 * A cheap fingerprint of what is on screen, used to tell "the page moved" from "the page is still".
 *
 * The capture loop cannot afford to run a model over every region of every frame, and it should
 * not want to: the answer only changes when the screen changes. This reduces a frame to a small
 * grid of average luminances so two frames can be compared in microseconds, and the expensive
 * work runs on the frames where it can produce a different answer.
 *
 * It answers one question — is this frame worth looking at again — and deliberately no more.
 * Where everything went is a different measurement with a different shape, and [RowProfile] is
 * the one that takes it.
 */
class SceneSignature private constructor(
    private val cells: IntArray,
    /** Cells whose centre is under a cover. See [coveredShare] for why this is not [cells]' count. */
    private val coveredCells: Int,
) {

    /**
     * Mean absolute difference between two fingerprints, on the 0..255 luminance scale.
     *
     * Cells ignored on either side are left out of the average rather than compared. This is the
     * detail that stops the covering logic oscillating: covers come and go, so the ignored set
     * differs between two consecutive frames, and treating a newly ignored cell as a change would
     * mean raising a cover is itself a scene change. That reading would tear the cover down on the
     * next frame, put it back on the one after, and produce exactly the flicker the hold timers
     * exist to prevent.
     *
     * Returns [Float.MAX_VALUE] for mismatched shapes rather than throwing. A rotation changes the
     * grid shape, and a rotation is a scene change, so the honest answer to "how different" is
     * "entirely". Two frames with nothing left to compare return zero: a fully covered screen
     * cannot report movement, and CoverPolicy.MAX_HOLD_MILLIS is what covers that case.
     */
    fun distanceTo(other: SceneSignature): Float {
        if (other.cells.size != cells.size) return Float.MAX_VALUE
        var total = 0L
        var compared = 0
        for (index in cells.indices) {
            val mine = cells[index]
            val theirs = other.cells[index]
            if (mine == IGNORED_CELL || theirs == IGNORED_CELL) continue
            val delta = mine - theirs
            total += if (delta < 0) -delta else delta
            compared++
        }
        // Too little left to compare is not evidence of anything. When most of the screen is
        // under a cover, the handful of cells still visible are page margins and chrome, and the
        // average over them swings far enough to read as a new layout on almost every frame. That
        // reading tore the covers down, which uncovered the pictures, which put the covers back up
        // — a flicker at the sampling rate over exactly the content being covered. A screen this
        // covered cannot report movement, and CoverPolicy's short hold is what re-examines it.
        return if (compared < cells.size * MINIMUM_COMPARED_SHARE) 0f else total.toFloat() / compared
    }

    /**
     * Whether this is a different screen, rather than the same one with something happening on it.
     *
     * This is the question [RowProfile] was answering and could not, and getting it wrong is what
     * a parent watches happen. Answer "no" to a real navigation and every cover judged on the old
     * page is carried onto the new one, where it sits as a rectangle over content nobody looked at
     * until a hold timer finally expires — covers left standing over the next page, over the app
     * switcher, over anything. Answer "yes" to a video playing inside a page and every cover on
     * that page is thrown away and rebuilt twice a second.
     *
     * The reason a row profile cannot decide it is that a row is an average across the whole
     * width, and averaging away the horizontal axis throws away most of what makes two screens
     * different. A white page of photographs and the grey app switcher have row averages a few
     * levels apart; a "most rows changed" test does not fire, and the navigation is missed. The
     * same reduction is exactly right for *displacement*, which is why [RowProfile] keeps it and
     * this does not: a scroll is a vertical translation, so collapsing the horizontal axis costs
     * nothing there and costs almost everything here.
     *
     * On the grid the two cases separate cleanly. A navigation replaces the whole screen, so
     * nearly every cell changes. A video is a rectangle inside a page: it changes its own cells
     * completely and leaves every other cell alone, so however violently it changes it can never
     * move most of the grid. And the worst version of that case — a video this app is already
     * covering — cannot contribute at all, because covered cells are ignored on both sides.
     *
     * What this cannot tell apart is a new screen from a *moved* one, and it must never be asked
     * to. A page scrolled by one cell's height changes almost every cell, so on its own this would
     * call every scroll a navigation and throw away every cover on a feed — rule 20, from the
     * other side. Displacement is [RowProfile]'s measurement and it outranks this one wherever it
     * finds an offset; see `RowProfile.motionFrom`.
     *
     * Conservative where it cannot tell. Too little uncovered screen to compare is not evidence of
     * a new layout, so the covers stay and their hold timers decide; rule 12.
     */
    fun isNewLayoutVersus(other: SceneSignature?): Boolean {
        if (other == null) return false
        if (other.cells.size != cells.size) return true
        var compared = 0
        var changed = 0
        for (index in cells.indices) {
            val mine = cells[index]
            val theirs = other.cells[index]
            if (mine == IGNORED_CELL || theirs == IGNORED_CELL) continue
            compared++
            val delta = mine - theirs
            if ((if (delta < 0) -delta else delta) >= CELL_CHANGED) changed++
        }
        if (compared < cells.size * MINIMUM_COMPARED_SHARE) return false
        return changed.toFloat() / compared >= NEW_LAYOUT_CELL_SHARE
    }

    /**
     * Whether anything moved at all. The cue to look again.
     *
     * Deliberately twitchy. Looking costs a classifier pass and finds anything that has just
     * appeared, so being wrong here is cheap in both directions.
     */
    fun movedSince(other: SceneSignature?): Boolean =
        other == null || distanceTo(other) >= MOVEMENT_THRESHOLD

    /**
     * How much of the screen is under a cover, measured as the share of cells left out.
     *
     * This is the honest measure of how much is left to notice movement in, and it is what the
     * covering policy needs. Adding up the areas of the covers is not: overlapping covers count
     * twice, so five scattered patches that together hide a quarter of the screen can add up to
     * more than all of it and make the policy behave as though nothing is visible.
     *
     * Counted by cell centre, not by the wider rule that decides which cells are compared. A cover
     * is grown so its edges meet its neighbours', so on a grid of thumbnails nearly every cell
     * touches one, and counting every touched cell as covered would read a page with a few pictures
     * covered as a screen with nothing left to see — which switches the covers onto the short hold
     * and re-examines them, each one a flash of the picture, every few seconds.
     */
    val coveredShare: Float
        get() = coveredCells.toFloat() / cells.size

    companion object {

        /**
         * Enough movement to be worth another look, which is very little.
         *
         * The only threshold here, and it answers the only question this class is asked. Whether
         * the layout moved far enough that the covers on it are misplaced was once decided here
         * too, from the same number, and there was no value that worked for both: sensitive
         * enough to catch a scroll meant every flicker on a page counted as a new layout, so the
         * covers were torn down and rebuilt on every frame. That question is displacement rather
         * than difference, and it belongs to [RowProfile], which measures it directly.
         */
        const val MOVEMENT_THRESHOLD = 2.5f

        /** How much of the screen has to be visible before a comparison means anything. */
        const val MINIMUM_COMPARED_SHARE = 0.3f

        /**
         * How far one cell's average has to move before that part of the screen counts as changed.
         *
         * A cell is a block of the screen roughly a thumbnail wide, so this is not sensor noise
         * territory: compression, a caret blinking and a page dimming all sit well under it, and a
         * cell whose content was replaced sits well over it.
         */
        const val CELL_CHANGED = 12

        /**
         * How much of the visible grid has to change before this is a different screen.
         *
         * The number that separates a navigation from a video, and the reason it can be set at all
         * is that the two are not close together on this measurement. A navigation replaces
         * essentially every cell. The largest thing that is *not* a navigation is a full-width
         * player occupying the top of a page, which is about two fifths of the grid — and only
         * while it is uncovered, because a covered player's cells are ignored on both sides.
         */
        const val NEW_LAYOUT_CELL_SHARE = 0.5f

        private const val COLUMNS = 8
        private const val ROWS = 16

        /**
         * Builds a fingerprint, optionally ignoring parts of the frame.
         *
         * [ignore] is where the app's own covers are. They are excluded because they are static by
         * construction: including them would dilute every comparison towards zero and make a
         * heavily covered screen look permanently unchanged, which is precisely when noticing a
         * change matters most. Ignored cells are held at a fixed value so the grid shape stays
         * comparable.
         *
         * A cell is ignored when a cover takes up more than a sliver of it, not only when the
         * cover reaches its centre. A cell straddling a cover's edge averages some of the cover
         * in, and the cover's part is what changes when the covers do: on a page of six covered
         * thumbnails those edge cells were a large share of the grid, so raising or re-placing
         * the covers moved enough of them to pass [NEW_LAYOUT_CELL_SHARE] on its own. The page
         * read as a navigation, every cover was dropped, the cells changed back, and the covers
         * went up again — a flicker driven by nothing but this app's own output, which is rule 17.
         */
        fun of(
            pixels: IntArray,
            width: Int,
            height: Int,
            ignore: List<ScreenRegions.Region> = emptyList(),
        ): SceneSignature {
            val cells = IntArray(COLUMNS * ROWS) { IGNORED_CELL }
            if (width <= 0 || height <= 0 || pixels.size < width * height) {
                return SceneSignature(cells, coveredCells = cells.size)
            }

            val cellWidth = (width / COLUMNS).coerceAtLeast(1)
            val cellHeight = (height / ROWS).coerceAtLeast(1)
            var coveredCells = 0

            for (row in 0 until ROWS) {
                for (column in 0 until COLUMNS) {
                    val centreX = (column + 0.5f) / COLUMNS
                    val centreY = (row + 0.5f) / ROWS
                    val centreCovered = ignore.any { region ->
                        centreX >= region.left && centreX <= region.right &&
                            centreY >= region.top && centreY <= region.bottom
                    }
                    if (centreCovered) coveredCells++
                    if (centreCovered || ignore.any { it.shareOfCell(column, row) > EDGE_SLIVER }) {
                        continue
                    }

                    var total = 0L
                    var samples = 0
                    var y = row * cellHeight
                    val endY = minOf(y + cellHeight, height)
                    val endX = minOf(column * cellWidth + cellWidth, width)
                    while (y < endY) {
                        var x = column * cellWidth
                        while (x < endX) {
                            val pixel = pixels[y * width + x]
                            total += (((pixel ushr 16) and 0xFF) * 299 +
                                ((pixel ushr 8) and 0xFF) * 587 +
                                (pixel and 0xFF) * 114) / 1000
                            samples++
                            x += SAMPLE_STEP
                        }
                        y += SAMPLE_STEP
                    }
                    cells[row * COLUMNS + column] =
                        if (samples == 0) IGNORED_CELL else (total / samples).toInt()
                }
            }
            return SceneSignature(cells, coveredCells)
        }

        /** How much of one grid cell this region takes up, 0..1. */
        private fun ScreenRegions.Region.shareOfCell(column: Int, row: Int): Float {
            val cellLeft = column.toFloat() / COLUMNS
            val cellTop = row.toFloat() / ROWS
            val overlapX = minOf(right, cellLeft + 1f / COLUMNS) - maxOf(left, cellLeft)
            val overlapY = minOf(bottom, cellTop + 1f / ROWS) - maxOf(top, cellTop)
            if (overlapX <= 0f || overlapY <= 0f) return 0f
            return overlapX * COLUMNS * overlapY * ROWS
        }

        /**
         * How much of a cell a cover may take up and the cell still be compared.
         *
         * Not zero, because covers are laid out edge to edge and a cover that reaches a hair into
         * the next column would otherwise take that whole column of cells out of the comparison
         * along with it. A tenth of a cell is a few pixels of capture, well under what moves a
         * cell's average by [CELL_CHANGED].
         */
        private const val EDGE_SLIVER = 0.1f

        private const val SAMPLE_STEP = 4
        private const val IGNORED_CELL = -1
    }
}
