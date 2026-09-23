package com.example.childsafetyimpl.platform.browserimages

import android.graphics.Point
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * How one scroll container has moved since its photos were placed: [shiftX] and [shiftY] pixels
 * as of [atUptime], going on at [velocityX] and [velocityY] pixels per millisecond. [measured] is
 * false while the container has moved but its speed is not known yet — a drag that has just
 * begun.
 */
data class Track(
    val shiftX: Int,
    val shiftY: Int,
    val atUptime: Long,
    val velocityX: Float,
    val velocityY: Float,
    val measured: Boolean = true,
) {
    /**
     * How far the container has moved as of [nowUptime]: the shift, carried along at its speed
     * for at most [PatchMotion.MAX_PREDICTION_MILLIS].
     */
    fun offsetAt(nowUptime: Long, out: Point) {
        val elapsed = (nowUptime - atUptime).coerceIn(0L, PatchMotion.MAX_PREDICTION_MILLIS)
        out.set(shiftX + (velocityX * elapsed).roundToInt(), shiftY + (velocityY * elapsed).roundToInt())
    }
}

/**
 * Keeps the patches on the photos while the page scrolls.
 *
 * Every photo was placed while the page was still. Its patch moves by its scroll container's
 * [Track] — read from the browser many times a second by [AnchorTracker] — and in between reads
 * is carried along at the container's speed, then stretched along the motion by as far as the
 * page can travel in the time a read may be behind. A still page gets neither.
 *
 * Tracks are per scroll container, keyed by [Photo.scroller]. Google Images' photo viewer scrolls
 * inside an overlay while the results behind it stay put; one motion for the whole page left the
 * viewer's patches standing while its photos slid out from under them.
 *
 * [margin] is extra room on every side of every patch; [unmeasuredMargin] is the room for a photo
 * whose container has no speed yet, for a finger that has landed before any is known.
 */
data class PatchMotion(
    val tracks: Map<Long, Track>,
    val margin: Int,
    val unmeasuredMargin: Int = margin,
) {
    val isMoving: Boolean get() = tracks.values.any { it.velocityX != 0f || it.velocityY != 0f }

    /** The latest moment any track describes; prediction runs out [MAX_PREDICTION_MILLIS] after. */
    val latestUptime: Long get() = tracks.values.maxOfOrNull { it.atUptime } ?: 0L

    private val offset = Point()

    /** Where [photo] is at [nowUptime], with no room added. */
    fun shift(photo: Photo, nowUptime: Long, out: Rect) {
        out.set(photo.bounds)
        val track = tracks[photo.scroller] ?: return
        track.offsetAt(nowUptime, offset)
        out.offset(offset.x, offset.y)
    }

    /** The patch for [photo] at [nowUptime]: where it is, plus room for how far off that may be. */
    fun place(photo: Photo, nowUptime: Long, out: Rect) {
        shift(photo, nowUptime, out)
        val track = tracks[photo.scroller]
        if (track == null || !track.measured) {
            out.inset(-unmeasuredMargin, -unmeasuredMargin)
            return
        }
        val stretchX = (abs(track.velocityX) * LOOKAHEAD_MILLIS).roundToInt()
        val stretchY = (abs(track.velocityY) * LOOKAHEAD_MILLIS).roundToInt()
        out.inset(-(margin + stretchX), -(margin + stretchY))
    }

    companion object {
        /**
         * Carrying a patch along stops here. Past it the page has either stopped — and the next
         * read will say so — or reads have stopped coming, and a patch must not wander off.
         */
        const val MAX_PREDICTION_MILLIS = 250L

        /** How far a read of the page's position may be off: the time between the browser's updates. */
        const val LOOKAHEAD_MILLIS = 60L
    }
}
