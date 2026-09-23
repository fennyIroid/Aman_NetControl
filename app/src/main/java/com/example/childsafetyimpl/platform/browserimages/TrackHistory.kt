package com.example.childsafetyimpl.platform.browserimages

import android.graphics.Point

/**
 * The last second or so of anchor reads, so the page's position at a past moment can be looked
 * up: where the photos were when a capture of the moving page was taken, to frost each from its
 * own pixels.
 *
 * Not thread-safe; [snapshot] hands another thread a copy.
 */
class TrackHistory(private val keepMillis: Long = KEEP_MILLIS) {

    private val reads = ArrayDeque<Map<Long, Track>>()

    fun record(tracks: Map<Long, Track>) {
        if (tracks.isEmpty()) return
        reads.addLast(tracks)
        val newest = tracks.values.maxOf { it.atUptime }
        while (reads.size > 1 && newest - reads.first().values.maxOf { it.atUptime } > keepMillis) {
            reads.removeFirst()
        }
    }

    fun clear() = reads.clear()

    fun snapshot(): TrackHistory = TrackHistory(keepMillis).also { it.reads.addAll(reads) }

    /**
     * How far [scroller] had moved at [uptime]: from the last read made by then, carried on at its
     * speed; before every read, the first one. Zero for a container never read.
     */
    fun offsetAt(scroller: Long, uptime: Long, out: Point) {
        val track = reads.lastOrNull { (it[scroller]?.atUptime ?: Long.MAX_VALUE) <= uptime }?.get(scroller)
            ?: reads.firstNotNullOfOrNull { it[scroller] }
        if (track == null) {
            out.set(0, 0)
            return
        }
        track.offsetAt(uptime, out)
    }

    companion object {
        /** Longer than a capture takes to come back. */
        const val KEEP_MILLIS = 1_000L
    }
}
