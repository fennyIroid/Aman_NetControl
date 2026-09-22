package com.example.childsafetyimpl.platform.content

/**
 * How far the app in front says it has scrolled, as a running total of screen pixels.
 *
 * Exists because the capture cannot measure a scroll where it matters most. [RowProfile] measures
 * displacement from the parts of the screen this app is *not* covering, which is correct — the
 * covers are this app's own paint, rule 17 — and it means that on a page of covered pictures,
 * which is precisely the page the covers are for, there is almost nothing left to measure from.
 * Driven on a phone over an image search, the estimator returned no displacement at all through
 * five flings: the covers stood still while the page slid out from under them, and at the end of
 * each fling the whole distance looked like a new page and every cover came down.
 *
 * The app doing the scrolling knows exactly how far it went, and tells the accessibility service:
 * a `TYPE_VIEW_SCROLLED` with a vertical delta (RecyclerView, and Chrome's content view, both
 * report one) or with an absolute position (Chrome's WebView reports that instead). Both are in
 * screen pixels. Chrome sends both for the same scroll, and on the device the two agreed to two
 * pixels over a fling of six hundred — so they are two reports of one movement, and adding them
 * would count it twice. A package that has reported a delta recently is trusted for deltas and
 * its absolute positions are only remembered; a package that only reports positions is measured
 * from them.
 *
 * Only numbers are recorded: a time, a total, and per source the last position seen. No text, no
 * node, no content — rule 3.
 *
 * Thread-safe: written from the accessibility service's thread and read from the capture loop.
 */
class ScrollLedger {

    private val times = LongArray(CAPACITY)
    private val totals = LongArray(CAPACITY)
    private var head = 0
    private var count = 0
    private var total = 0L

    /** The total before the oldest report still remembered. */
    private var base = 0L
    private var lastMovedAt = Long.MIN_VALUE / 2

    private val lastPosition = HashMap<String, Int>()
    private val lastDeltaAt = HashMap<String, Long>()

    /**
     * Records one scroll report.
     *
     * @param at the event's time, on the uptime clock
     * @param packageName who reported it; used only to decide which of its reports to believe
     * @param source a key for the scrolling view within that app, so positions from two different
     *   scrollers are never subtracted from each other
     * @param delta how far the content scrolled, in pixels, or null when the report has none
     * @param position the scroller's absolute offset in pixels, or null when the report has none
     */
    @Synchronized
    fun record(at: Long, packageName: String, source: String, delta: Int?, position: Int?) {
        val moved = when {
            delta != null -> {
                lastDeltaAt[packageName] = at
                delta
            }
            position != null -> {
                val before = lastPosition[source]
                val reportsDeltas = at - (lastDeltaAt[packageName] ?: Long.MIN_VALUE / 2) <
                    DELTAS_TRUSTED_MILLIS
                if (before == null || reportsDeltas) 0 else position - before
            }
            else -> 0
        }
        if (position != null) lastPosition[source] = position
        if (moved == 0) return

        total += moved
        lastMovedAt = at
        head = (head + 1) % CAPACITY
        if (count == CAPACITY) base = totals[head]
        times[head] = at
        totals[head] = total
        if (count < CAPACITY) count++
    }

    /** The running total now, in pixels. Positive means the content has moved up the screen. */
    @Synchronized
    fun total(): Long = total

    /**
     * The running total as it stood at [at], interpolated between reports.
     *
     * A report says how far the content went since the one before it, and arrives after the
     * movement it describes. Reading the total at a frame's own capture time, rather than at the
     * moment the frame happens to be processed, is what keeps a cover placed from that frame on
     * the picture that frame showed. The movement a report describes is spread over the time
     * since the previous one, capped at [SPREAD_CAP_MILLIS] so that the first report after a
     * pause is not smeared back across the idle time before it.
     */
    @Synchronized
    fun totalAt(at: Long): Long {
        var index = head
        var laterTime = Long.MAX_VALUE
        var laterTotal = total
        repeat(count) {
            val time = times[index]
            val value = totals[index]
            if (time <= at) return interpolate(time, value, laterTime, laterTotal, at)
            laterTime = time
            laterTotal = value
            index = (index - 1 + CAPACITY) % CAPACITY
        }
        return interpolate(Long.MIN_VALUE / 2, base, laterTime, laterTotal, at)
    }

    private fun interpolate(time: Long, value: Long, laterTime: Long, laterTotal: Long, at: Long): Long {
        if (laterTime == Long.MAX_VALUE) return value
        val start = maxOf(time, laterTime - SPREAD_CAP_MILLIS)
        if (at <= start) return value
        val share = (at - start).toFloat() / (laterTime - start)
        return value + ((laterTotal - value) * share).toLong()
    }

    /**
     * Whether movement was reported within [window] milliseconds of [at], either side of it. A
     * report arrives after the movement it describes, so one stamped just after a frame is still
     * about that frame.
     */
    @Synchronized
    fun movedWithin(at: Long, window: Long): Boolean = lastMovedAt >= at - window

    companion object {
        /** Enough reports for a few seconds of a fling at the rate apps send them. */
        private const val CAPACITY = 64

        /** How recently a package must have sent a delta for its absolute positions to be ignored. */
        const val DELTAS_TRUSTED_MILLIS = 1_000L

        /** The longest a single report's movement is assumed to have taken. */
        const val SPREAD_CAP_MILLIS = 150L
    }
}
