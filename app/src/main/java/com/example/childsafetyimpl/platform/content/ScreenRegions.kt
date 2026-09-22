package com.example.childsafetyimpl.platform.content

/**
 * Finds the picture-shaped parts of a screen.
 *
 * This exists because of a specific failure. Classifying a whole screenshot and covering the whole
 * screen when it fails is the wrong shape of protection for the thing people actually do, which is
 * scroll a grid of thumbnails. A search for "beach" returns twelve pictures; if one of them is the
 * problem it contributes a twelfth of the pixels, and a labeller looking at the whole frame reports
 * the grid, not the picture. The frame reads as safe and nothing is covered. Then a frame that does
 * fail takes the entire screen away, including the eleven pictures that were fine and the search box
 * the child needs to get out.
 *
 * So the unit of judgement has to be the picture, not the screen, and something has to find the
 * pictures before the model can judge them. That is this file.
 *
 * **How it works.** The screen is divided into cells, and each cell is measured for two things:
 * how much luminance detail it holds, and how much colour. A photograph has both. A page background
 * has neither. Body text has detail but almost no colour. Cells that look like picture material are
 * joined into connected blocks, and each block's bounding box becomes a region to classify.
 *
 * **The property that matters most is what it refuses to find.** Our own cover is a near-flat wash,
 * so its cells carry no detail and no colour and are never proposed. That is not a nicety: the
 * capture mirrors the screen including anything this app draws on it, so without that property the
 * pipeline classifies its own output, and a cover that reads as content keeps itself up forever.
 * A blur that never lifts is the bug this property prevents, and [DETAIL_FLOOR] is where it lives.
 *
 * Everything here is integer arithmetic over a pixel array with no Android types, so the behaviour
 * that decides what gets covered can be tested without a device.
 */
object ScreenRegions {

    /**
     * A candidate region, in fractions of the frame rather than pixels.
     *
     * Fractions because the capture buffer is a third of screen resolution and the cover is drawn
     * at full resolution. Passing pixels across that boundary is a class of bug worth making
     * impossible rather than remembering not to write.
     */
    data class Region(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        val area: Float get() = width * height

        fun overlapWith(other: Region): Float {
            val w = minOf(right, other.right) - maxOf(left, other.left)
            val h = minOf(bottom, other.bottom) - maxOf(top, other.top)
            if (w <= 0f || h <= 0f) return 0f
            val intersection = w * h
            val union = area + other.area - intersection
            return if (union <= 0f) 0f else intersection / union
        }

        /** How much of *this* region sits inside [other]. Asymmetric, unlike [overlapWith]. */
        fun containedBy(other: Region): Float {
            val w = minOf(right, other.right) - maxOf(left, other.left)
            val h = minOf(bottom, other.bottom) - maxOf(top, other.top)
            if (w <= 0f || h <= 0f) return 0f
            return (w * h) / area.coerceAtLeast(0.0001f)
        }

        fun union(other: Region): Region = Region(
            left = minOf(left, other.left),
            top = minOf(top, other.top),
            right = maxOf(right, other.right),
            bottom = maxOf(bottom, other.bottom),
        )

        /**
         * Grown by a margin on every side, clamped to the frame.
         *
         * Cells are coarse, so a block's bounding box tends to stop just inside the picture it
         * found. A cover that stops just inside leaves a visible border of the thing it is
         * covering, which is worse than not covering it: it says "here is the interesting part".
         */
        fun grownBy(margin: Float): Region = Region(
            left = (left - margin).coerceAtLeast(0f),
            top = (top - margin).coerceAtLeast(0f),
            right = (right + margin).coerceAtMost(1f),
            bottom = (bottom + margin).coerceAtMost(1f),
        )

        /**
         * Moved down the frame by [fraction], or null once it has left the frame entirely.
         *
         * Used to carry a cover through a scroll instead of discarding it. The result is clamped
         * to the frame rather than allowed to hang off the edge, because these fractions become a
         * window's position and size: a negative height is not a smaller cover, it is a cover
         * Android refuses to lay out. A region that has scrolled off returns null so the caller
         * drops it rather than pinning a zero-height sliver to the edge of the screen for as long
         * as its hold lasts.
         */
        fun shiftedBy(fraction: Float): Region? {
            val movedTop = top + fraction
            val movedBottom = bottom + fraction
            if (movedBottom <= 0f || movedTop >= 1f) return null
            return Region(
                left = left,
                top = movedTop.coerceIn(0f, 1f),
                right = right,
                bottom = movedBottom.coerceIn(0f, 1f),
            ).takeIf { it.height > 0f }
        }

        companion object {
            val WHOLE_FRAME = Region(0f, 0f, 1f, 1f)
        }
    }

    /**
     * The same covering, drawn as rectangles that do not overlap.
     *
     * Two covers overlapping by a few pixels look like nothing, and on Android they are the
     * difference between a page that scrolls and one that does not. Without the accessibility
     * grant every cover is an untrusted window, and the system caps each one at
     * `maximum_obscuring_opacity_for_touch` — but it applies that limit to the *stack*, not to
     * each window. Two capped windows over the same pixel obscure `1 - 0.2 * 0.2` of it, which is
     * 0.96, over the limit, and every touch landing there is dropped outright. On a grid of
     * thumbnails the covers are grown by a margin so their edges meet, so the overlap is a seam a
     * few pixels wide running straight down the middle of the screen — which is where a thumb
     * scrolls. That is the whole bug: not a cover that is too dark, a vertical strip of the screen
     * that silently eats input.
     *
     * Only union-preserving trims are made. A cover is shortened along one axis only when the
     * cover it overlaps already spans it completely on the other, so the strip being given up is
     * covered by the neighbour and nothing becomes visible that was not before. Overlaps that
     * cannot be removed that way — two rectangles meeting at a corner — are left alone, because
     * trimming those would expose content, and a seam that drops touches is a worse outcome than
     * this only when nothing is showing through.
     */
    fun withoutOverlaps(regions: List<Region>): List<Region> {
        if (regions.size < 2) return regions
        val kept = mutableListOf<Region>()
        // Largest first, so the trimming falls on the smaller cover and the dominant one on any
        // part of the screen keeps the shape it was judged with.
        regions.sortedByDescending { it.area }.forEach { region ->
            var trimmed: Region? = region
            for (other in kept) {
                trimmed = trimmed?.trimmedAgainst(other)
                if (trimmed == null) break
            }
            val result = trimmed ?: return@forEach
            if (result.width > 0f && result.height > 0f) kept += result
        }
        return kept
    }

    /**
     * This region with its overlap with [other] removed, when that can be done without giving up
     * anything [other] does not already cover. Null when [other] covers all of it.
     */
    private fun Region.trimmedAgainst(other: Region): Region? {
        val overlapWidth = minOf(right, other.right) - maxOf(left, other.left)
        val overlapHeight = minOf(bottom, other.bottom) - maxOf(top, other.top)
        if (overlapWidth <= 0f || overlapHeight <= 0f) return this

        val spansVertically = other.top <= top && other.bottom >= bottom
        val spansHorizontally = other.left <= left && other.right >= right

        if (spansVertically && spansHorizontally) return null
        if (spansVertically) {
            if (other.right in left..right && other.left <= left) return Region(other.right, top, right, bottom)
            if (other.left in left..right && other.right >= right) return Region(left, top, other.left, bottom)
        }
        if (spansHorizontally) {
            if (other.bottom in top..bottom && other.top <= top) return Region(left, other.bottom, right, bottom)
            if (other.top in top..bottom && other.bottom >= bottom) return Region(left, top, right, other.top)
        }
        return this
    }

    /**
     * Per-region measurements, returned alongside the regions so a screen and a log can say why a
     * region was proposed rather than only that it was.
     */
    data class Proposal(
        val region: Region,
        val detail: Int,
        val colour: Int,
    ) {
        /** What the proposal is worth when two are averaged together. Never zero: see [propose]. */
        val weight: Float get() = region.area
    }

    /**
     * Proposes regions from an ARGB pixel array.
     *
     * Returns an empty list when the frame holds nothing picture-shaped, which is a real answer
     * and not a failure: a page of text, a settings screen, or a screen already covered by us.
     * The caller decides what an empty list means; this does not guess.
     */
    fun propose(
        pixels: IntArray,
        width: Int,
        height: Int,
        maxRegions: Int = MAX_REGIONS,
    ): List<Proposal> {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return emptyList()

        val columns = COLUMNS
        val rows = (columns * height / width.toFloat()).toInt().coerceIn(4, 40)
        val cellWidth = width / columns
        val cellHeight = height / rows
        if (cellWidth < 2 || cellHeight < 2) return emptyList()

        val detail = IntArray(columns * rows)
        val colour = IntArray(columns * rows)
        val isContent = BooleanArray(columns * rows)

        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val index = row * columns + column
                measureCell(
                    pixels = pixels,
                    width = width,
                    startX = column * cellWidth,
                    startY = row * cellHeight,
                    cellWidth = cellWidth,
                    cellHeight = cellHeight,
                ).let { (cellDetail, cellColour) ->
                    detail[index] = cellDetail
                    colour[index] = cellColour
                    // Both, not either. Detail alone accepts paragraphs of text, and text blocks
                    // are the single most common thing on the screens this runs over; colour
                    // alone accepts a flat brand-coloured header.
                    isContent[index] = cellDetail >= DETAIL_FLOOR && cellColour >= COLOUR_FLOOR
                }
            }
        }

        val blocks = connectedBlocks(isContent, columns, rows)
        val minimumCells = ((columns * rows) * MIN_AREA_FRACTION).toInt().coerceAtLeast(3)
        val minimumArea = (width * height) * MIN_AREA_FRACTION

        return blocks
            .filter { it.cells >= minimumCells }
            .flatMap { block ->
                // Cells are coarse enough that two thumbnails with a narrow gutter between them
                // land in one block, so the block is cut apart at pixel resolution before it is
                // taken as a picture. Without this a grid proposes one region covering the row,
                // and covering a row because one picture in it failed is the behaviour this whole
                // file exists to replace.
                splitOnGutters(
                    pixels = pixels,
                    width = width,
                    left = block.left * cellWidth,
                    top = block.top * cellHeight,
                    right = ((block.right + 1) * cellWidth).coerceAtMost(width),
                    bottom = ((block.bottom + 1) * cellHeight).coerceAtMost(height),
                )
            }
            .filter { (it.right - it.left).toFloat() * (it.bottom - it.top) >= minimumArea }
            .map { bounds ->
                Proposal(
                    region = Region(
                        left = bounds.left / width.toFloat(),
                        top = bounds.top / height.toFloat(),
                        right = bounds.right / width.toFloat(),
                        bottom = bounds.bottom / height.toFloat(),
                    ).grownBy(GROW_MARGIN),
                    detail = bounds.detail,
                    colour = bounds.colour,
                )
            }
            .let(::mergeOverlapping)
            .sortedByDescending { it.region.area }
            .take(maxRegions)
    }

    private data class Bounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val detail: Int,
        val colour: Int,
    )

    /**
     * Cuts a block apart along the empty lanes running through it.
     *
     * A grid of thumbnails is separated by gutters a few pixels wide, which is narrower than the
     * cells used to find the block in the first place, so the block arrives here as one rectangle
     * spanning several pictures. Measuring each pixel column and each pixel row of the block for
     * activity finds those gutters exactly, and cutting on them recovers the individual pictures.
     *
     * The floors are stricter than the ones used to find content, and deliberately so. The cost of
     * cutting where there is no real gutter is a picture covered in halves, one of which may be
     * left showing; the cost of missing a gutter is one region too many covered. Only a lane that
     * is close to empty counts.
     */
    private fun splitOnGutters(
        pixels: IntArray,
        width: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): List<Bounds> {
        if (right - left < MIN_SIDE || bottom - top < MIN_SIDE) return emptyList()

        val columnCuts = lanes(
            count = right - left,
            measure = { offset -> columnActivity(pixels, width, left + offset, top, bottom) },
        )
        val rowCuts = lanes(
            count = bottom - top,
            measure = { offset -> rowActivity(pixels, width, top + offset, left, right) },
        )

        val result = mutableListOf<Bounds>()
        columnCuts.forEach { (columnStart, columnEnd) ->
            rowCuts.forEach { (rowStart, rowEnd) ->
                val cellLeft = left + columnStart
                val cellTop = top + rowStart
                val cellRight = left + columnEnd
                val cellBottom = top + rowEnd

                // Cutting in both directions at once produces cells at every intersection, and an
                // L-shaped arrangement of pictures has intersections with nothing in them.
                val (detail, colour) = measureCell(
                    pixels = pixels,
                    width = width,
                    startX = cellLeft,
                    startY = cellTop,
                    cellWidth = cellRight - cellLeft,
                    cellHeight = cellBottom - cellTop,
                )
                if (detail >= DETAIL_FLOOR && colour >= COLOUR_FLOOR) {
                    result += Bounds(cellLeft, cellTop, cellRight, cellBottom, detail, colour)
                }
            }
        }

        return result.ifEmpty { emptyList() }
    }

    /**
     * Turns a run of per-lane measurements into the spans between the empty stretches.
     *
     * A stretch has to be [MIN_GUTTER] wide before it separates anything, because a single quiet
     * column happens inside ordinary photographs and is not a boundary between two of them.
     */
    private inline fun lanes(count: Int, measure: (Int) -> Pair<Int, Int>): List<Pair<Int, Int>> {
        val isGutter = BooleanArray(count) {
            val (detail, colour) = measure(it)
            detail < GUTTER_DETAIL && colour < GUTTER_COLOUR
        }

        val spans = mutableListOf<Pair<Int, Int>>()
        var start = -1
        var gutterRun = 0

        for (index in 0..count) {
            val gutter = index < count && isGutter[index]
            if (gutter) {
                gutterRun++
                if (start >= 0 && gutterRun >= MIN_GUTTER) {
                    val end = index - gutterRun + 1
                    if (end - start >= MIN_SIDE) spans += start to end
                    start = -1
                }
            } else {
                gutterRun = 0
                if (index < count && start < 0) start = index
            }
        }
        if (start >= 0 && count - start >= MIN_SIDE) spans += start to count

        return spans.ifEmpty { listOf(0 to count) }
    }

    private fun columnActivity(pixels: IntArray, width: Int, x: Int, top: Int, bottom: Int): Pair<Int, Int> {
        var detailSum = 0
        var colourSum = 0
        var samples = 0
        var previous = -1
        var y = top
        while (y < bottom) {
            val pixel = pixels[y * width + x]
            val r = (pixel ushr 16) and 0xFF
            val g = (pixel ushr 8) and 0xFF
            val b = pixel and 0xFF
            val luminance = (r * 299 + g * 587 + b * 114) / 1000
            if (previous >= 0) detailSum += if (luminance < previous) previous - luminance else luminance - previous
            previous = luminance
            colourSum += maxOf(r, maxOf(g, b)) - minOf(r, minOf(g, b))
            samples++
            y += SAMPLE_STEP
        }
        return if (samples == 0) 0 to 0 else (detailSum / samples) to (colourSum / samples)
    }

    private fun rowActivity(pixels: IntArray, width: Int, y: Int, left: Int, right: Int): Pair<Int, Int> {
        var detailSum = 0
        var colourSum = 0
        var samples = 0
        var previous = -1
        var x = left
        while (x < right) {
            val pixel = pixels[y * width + x]
            val r = (pixel ushr 16) and 0xFF
            val g = (pixel ushr 8) and 0xFF
            val b = pixel and 0xFF
            val luminance = (r * 299 + g * 587 + b * 114) / 1000
            if (previous >= 0) detailSum += if (luminance < previous) previous - luminance else luminance - previous
            previous = luminance
            colourSum += maxOf(r, maxOf(g, b)) - minOf(r, minOf(g, b))
            samples++
            x += SAMPLE_STEP
        }
        return if (samples == 0) 0 to 0 else (detailSum / samples) to (colourSum / samples)
    }

    /**
     * Detail and colour for one cell.
     *
     * Detail is the mean absolute luminance step between neighbouring sampled pixels, which
     * responds to texture rather than to brightness: a dark photograph and a bright one both
     * score, a flat grey panel and a flat white one both do not. Colour is the mean channel
     * spread, which separates a photograph from a page of black text on white.
     *
     * Sampled rather than exhaustive. This runs on every captured frame, and at this cell size
     * every second pixel carries the same answer for a fraction of the cost.
     */
    private fun measureCell(
        pixels: IntArray,
        width: Int,
        startX: Int,
        startY: Int,
        cellWidth: Int,
        cellHeight: Int,
    ): Pair<Int, Int> {
        var detailSum = 0
        var colourSum = 0
        var samples = 0
        var previousLuminance = -1

        var y = startY
        while (y < startY + cellHeight) {
            var x = startX
            while (x < startX + cellWidth) {
                val pixel = pixels[y * width + x]
                val r = (pixel ushr 16) and 0xFF
                val g = (pixel ushr 8) and 0xFF
                val b = pixel and 0xFF

                val luminance = (r * 299 + g * 587 + b * 114) / 1000
                if (previousLuminance >= 0) {
                    val step = luminance - previousLuminance
                    detailSum += if (step < 0) -step else step
                }
                previousLuminance = luminance

                val maxChannel = maxOf(r, maxOf(g, b))
                val minChannel = minOf(r, minOf(g, b))
                colourSum += maxChannel - minChannel

                samples++
                x += SAMPLE_STEP
            }
            previousLuminance = -1
            y += SAMPLE_STEP
        }

        if (samples == 0) return 0 to 0
        return (detailSum / samples) to (colourSum / samples)
    }

    private data class Block(
        var left: Int,
        var top: Int,
        var right: Int,
        var bottom: Int,
        var cells: Int,
        var detailSum: Int,
        var colourSum: Int,
    )

    /**
     * Four-neighbour connected components over the content cells.
     *
     * Iterative, like every fill in this codebase, because a recursive one over a full grid can
     * exhaust the stack on a device and this runs inside the capture loop.
     */
    private fun connectedBlocks(
        isContent: BooleanArray,
        columns: Int,
        rows: Int,
    ): List<Block> {
        val visited = BooleanArray(isContent.size)
        val blocks = mutableListOf<Block>()
        val stack = ArrayDeque<Int>()

        for (start in isContent.indices) {
            if (!isContent[start] || visited[start]) continue

            visited[start] = true
            stack.addLast(start)
            val block = Block(
                left = start % columns,
                top = start / columns,
                right = start % columns,
                bottom = start / columns,
                cells = 0,
                detailSum = 0,
                colourSum = 0,
            )

            while (stack.isNotEmpty()) {
                val index = stack.removeLast()
                val column = index % columns
                val row = index / columns

                block.cells++
                if (column < block.left) block.left = column
                if (column > block.right) block.right = column
                if (row < block.top) block.top = row
                if (row > block.bottom) block.bottom = row

                if (column > 0) push(stack, visited, isContent, index - 1)
                if (column < columns - 1) push(stack, visited, isContent, index + 1)
                if (row > 0) push(stack, visited, isContent, index - columns)
                if (row < rows - 1) push(stack, visited, isContent, index + columns)
            }
            blocks += block
        }
        return blocks
    }

    private fun push(
        stack: ArrayDeque<Int>,
        visited: BooleanArray,
        isContent: BooleanArray,
        index: Int,
    ) {
        if (index in isContent.indices && isContent[index] && !visited[index]) {
            visited[index] = true
            stack.addLast(index)
        }
    }

    /**
     * Joins regions that substantially overlap.
     *
     * Growing every block by a margin makes neighbouring blocks touch, and two covers over one
     * picture with a seam down the middle looks like a mistake even when it hides everything it
     * needed to.
     */
    private fun mergeOverlapping(proposals: List<Proposal>): List<Proposal> {
        if (proposals.size < 2) return proposals
        val merged = mutableListOf<Proposal>()

        proposals.forEach { candidate ->
            val existingIndex = merged.indexOfFirst {
                it.region.overlapWith(candidate.region) >= MERGE_OVERLAP
            }
            if (existingIndex >= 0) {
                val existing = merged[existingIndex]
                // Weighted by area, and the weight is guarded. An earlier version weighted by a
                // cell count that the pixel-level split path had no meaningful value for and set
                // to zero, so merging two split regions divided by zero and took the process down
                // with it. A measurement used as a divisor has to be one that cannot be absent.
                val total = (existing.weight + candidate.weight).coerceAtLeast(0.0001f)
                merged[existingIndex] = Proposal(
                    region = existing.region.union(candidate.region),
                    detail = ((existing.detail * existing.weight + candidate.detail * candidate.weight) / total).toInt(),
                    colour = ((existing.colour * existing.weight + candidate.colour * candidate.weight) / total).toInt(),
                )
            } else {
                merged += candidate
            }
        }
        return merged
    }

    /**
     * How much luminance texture a cell needs before it can be picture material.
     *
     * The load-bearing constant. Set too high and dark or soft-focus photographs stop being
     * proposed; set too low and our own cover is proposed, the pipeline classifies its own
     * output, and the cover never lifts.
     */
    const val DETAIL_FLOOR = 6

    /** How much channel spread a cell needs. This is what keeps body text out. */
    const val COLOUR_FLOOR = 12

    /** How empty a pixel lane has to be to separate two pictures. Stricter than DETAIL_FLOOR. */
    private const val GUTTER_DETAIL = 3

    /** The colour half of the same test. */
    private const val GUTTER_COLOUR = 6

    /** How wide an empty lane has to be before it is a gutter rather than a quiet stripe. */
    private const val MIN_GUTTER = 3

    /** The shortest side a picture can have, in capture pixels. Below this it is an icon. */
    private const val MIN_SIDE = 10

    private const val COLUMNS = 12
    private const val SAMPLE_STEP = 2
    private const val MIN_AREA_FRACTION = 0.012f
    private const val GROW_MARGIN = 0.012f
    private const val MERGE_OVERLAP = 0.18f
    private const val MAX_REGIONS = 6
}
