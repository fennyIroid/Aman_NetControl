package com.example.childsafetyimpl.platform.browserimages

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs

/**
 * Follows each scroll container while the page moves by re-reading one of its photos — the
 * anchor — straight from the browser, many times a second.
 *
 * Walking the whole tree to find where the photos had gone took 10-500 ms and kept Chrome's UI
 * thread busy answering, so the patches trailed the photos and the page itself scrolled less
 * smoothly. One node is a single short request, and its bounds hold everything that moves the
 * photo: the page's scroll, a nested scroll, the toolbar sliding away.
 *
 * Not thread-safe: [place] and [sample] must be called from one thread.
 */
class AnchorTracker {

    /** One photo that can stand for its container: its node, and where it was when placed. */
    private class Candidate(val node: AccessibilityNodeInfo, val placed: Rect)

    private class Container(val candidates: List<Candidate>) {
        var anchor = 0
        var shiftX = 0
        var shiftY = 0
        var changedAt = Long.MIN_VALUE
        var velocityX = 0f
        var velocityY = 0f
        var measured = false
    }

    private val containers = HashMap<Long, Container>()
    private var placedAt = 0L

    /**
     * Start following [photos], all read with the page still, from their [nodes] by photo id. Each
     * container's anchor is its photo nearest the middle of [focus], the one likeliest to stay on
     * the page; the others stand by in case it goes.
     */
    fun place(photos: List<Photo>, nodes: Map<Long, AccessibilityNodeInfo>, focus: Rect, atUptime: Long = 0L) {
        containers.clear()
        placedAt = atUptime
        photos.groupBy { it.scroller }.forEach { (scroller, inContainer) ->
            val candidates = inContainer
                .sortedBy { abs(it.bounds.centerX() - focus.centerX()) + abs(it.bounds.centerY() - focus.centerY()) }
                .mapNotNull { photo -> nodes[photo.id]?.let { Candidate(it, Rect(photo.bounds)) } }
            if (candidates.isNotEmpty()) containers[scroller] = Container(candidates)
        }
    }

    fun clear() = containers.clear()

    /**
     * Re-read every anchor at [now] with [read], which asks the browser where a node is now and
     * gives null for one gone from the page. Returns each followed container's [Track]; a
     * container whose photos are all gone is left out.
     */
    fun sample(now: Long, read: (AccessibilityNodeInfo) -> Rect?): Map<Long, Track> {
        val tracks = HashMap<Long, Track>()
        containers.forEach { (scroller, container) ->
            val shift = currentShift(container, read) ?: return@forEach
            update(container, shift.first, shift.second, now)
            tracks[scroller] = Track(
                shiftX = container.shiftX,
                shiftY = container.shiftY,
                // The browser's own view of the page trails the screen; the read describes then.
                atUptime = if (container.changedAt == Long.MIN_VALUE) placedAt else container.changedAt - BROWSER_LAG_MILLIS,
                velocityX = container.velocityX,
                velocityY = container.velocityY,
                measured = container.measured || (container.shiftX == 0 && container.shiftY == 0),
            )
        }
        return tracks
    }

    /** How far the anchor — or, if it has gone, the next photo that has not — has moved. */
    private fun currentShift(container: Container, read: (AccessibilityNodeInfo) -> Rect?): Pair<Int, Int>? {
        while (container.anchor < container.candidates.size) {
            val candidate = container.candidates[container.anchor]
            val now = read(candidate.node)
            if (now != null && !now.isEmpty) {
                return (now.left - candidate.placed.left) to (now.top - candidate.placed.top)
            }
            container.anchor++
        }
        return null
    }

    private fun update(container: Container, shiftX: Int, shiftY: Int, now: Long) {
        val changed = shiftX != container.shiftX || shiftY != container.shiftY
        if (changed) {
            val elapsed = now - container.changedAt
            if (container.changedAt != Long.MIN_VALUE && elapsed in 1..STOPPED_AFTER_MILLIS) {
                val rawX = (shiftX - container.shiftX).toFloat() / elapsed
                val rawY = (shiftY - container.shiftY).toFloat() / elapsed
                // Half the old estimate, half the new: the browser's updates are not evenly spaced.
                container.velocityX = if (container.measured) (container.velocityX + rawX) / 2 else rawX
                container.velocityY = if (container.measured) (container.velocityY + rawY) / 2 else rawY
                container.measured = true
            } else {
                // The first move after standing still: the time since says nothing of the speed.
                container.velocityX = 0f
                container.velocityY = 0f
                container.measured = false
            }
            container.shiftX = shiftX
            container.shiftY = shiftY
            container.changedAt = now
        } else if (container.changedAt != Long.MIN_VALUE && now - container.changedAt > STOPPED_AFTER_MILLIS) {
            container.velocityX = 0f
            container.velocityY = 0f
            container.measured = true
        }
    }

    companion object {
        /**
         * No change for this long and the container has stopped. Longer than the browser's
         * slowest update interval while scrolling, so a repeated read is not taken for a stop.
         */
        const val STOPPED_AFTER_MILLIS = 150L

        /**
         * How far the browser's idea of where the page is trails what is on the screen: about a
         * frame. Recorded at zero, fast swipes left slivers of photo showing at the patches' edges.
         */
        const val BROWSER_LAG_MILLIS = 16L
    }
}
