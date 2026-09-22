package com.example.childsafetyimpl.platform.content

/**
 * How long unsafe content is on screen before it is covered.
 *
 * This is the number POC 1 lives or dies by, and it was the one the pipeline was not measuring.
 * Frames analysed per second and milliseconds per classification are engineering metrics; they
 * describe how busy the device is, not whether the protection works. The question a parent is
 * actually asking is *how much did my child see*, and the answer is this class.
 *
 * The exposure window has four parts and only one of them is the model:
 *
 * ```
 *  content appears ──► next sample ──► bitmap ──► classify ──► cover raised
 *                  └── sampling ──┘  └────── pipeline ──────┘  └ compositor ┘
 *                      gap                                         delay
 * ```
 *
 * The sampling gap dominates, and it is the part most people leave out. At two frames a second,
 * content that appears immediately after a sample waits up to 500 ms before anything even looks
 * at it. Classification could be instantaneous and the worst case would still be half a second of
 * visible content. That is the real trade in this feature, and raising the sample rate to close it
 * costs battery on a continuous capture that is already the most expensive thing the app does.
 *
 * So this class measures the whole window rather than the convenient part of it, and reports the
 * worst case as well as the average. An average of 300 ms with a worst case of 900 ms is a very
 * different product from an average of 300 ms with a worst case of 320 ms, and only one of those
 * two numbers tells a parent what their child saw.
 *
 * One measurement honesty note, recorded here because it changes how the figures should be read:
 * the clock starts when a frame *arrives from the capture pipeline*, not when the content was
 * first drawn on the real screen. Android does not expose the latter. The true exposure is
 * therefore this measurement plus an unmeasurable compositor and mirroring delay, typically one
 * to two display frames. The report states the measured window and names the gap rather than
 * quietly presenting one as the other.
 */
data class BlurLatency(
    /** Samples where an unsafe verdict led to a cover going up. */
    val coversRaised: Int = 0,
    /** Sum of the full frame-arrival to cover-raised window. */
    val totalLatencyMillis: Long = 0,
    val worstLatencyMillis: Long = 0,
    val bestLatencyMillis: Long = Long.MAX_VALUE,
    /** The classification step alone, to show how much of the window the model owns. */
    val totalClassifyMillis: Long = 0,
    /** The configured gap between samples, which bounds the part nothing can measure away. */
    val samplingGapMillis: Long = 0,
) {

    val averageLatencyMillis: Long
        get() = if (coversRaised == 0) 0 else totalLatencyMillis / coversRaised

    val averageClassifyMillis: Long
        get() = if (coversRaised == 0) 0 else totalClassifyMillis / coversRaised

    /**
     * The honest worst case, including a full sampling gap.
     *
     * The measured worst case only counts frames that were sampled. Content appearing just after
     * a sample waits the whole gap before it is even seen, and that wait belongs in the number a
     * parent is given.
     */
    val worstCaseExposureMillis: Long
        get() = if (coversRaised == 0) 0 else worstLatencyMillis + samplingGapMillis

    /** Share of the window the model is responsible for, which is where tuning would pay off. */
    val classifyShare: Float
        get() = if (totalLatencyMillis == 0L) 0f
        else totalClassifyMillis.toFloat() / totalLatencyMillis

    fun record(latencyMillis: Long, classifyMillis: Long): BlurLatency = copy(
        coversRaised = coversRaised + 1,
        totalLatencyMillis = totalLatencyMillis + latencyMillis,
        worstLatencyMillis = maxOf(worstLatencyMillis, latencyMillis),
        bestLatencyMillis = minOf(bestLatencyMillis, latencyMillis),
        totalClassifyMillis = totalClassifyMillis + classifyMillis,
    )

    /**
     * The one-line summary for the POC report.
     *
     * Written to be pasted into the results document directly, because a metric that has to be
     * reformatted by hand before anyone can read it is a metric that stops being collected.
     */
    fun summary(): String = if (coversRaised == 0) {
        "No covers were raised, so exposure was not measured."
    } else {
        "Covered $coversRaised frames. Median path ${averageLatencyMillis}ms from frame arrival " +
            "to cover, of which ${averageClassifyMillis}ms was classification " +
            "(${"%.0f%%".format(classifyShare * 100)}). Measured worst case " +
            "${worstLatencyMillis}ms; true worst case ${worstCaseExposureMillis}ms once a full " +
            "${samplingGapMillis}ms sampling gap is included."
    }

    companion object {
        /**
         * What counts as fast enough, and why this number rather than another.
         *
         * Derived from the product requirement rather than from what the pipeline happens to
         * achieve: a cover that lands within about a second is experienced as the content being
         * blocked, and one that takes longer is experienced as having seen it and then having it
         * taken away. The second one is not a protection, and it teaches a child that looking
         * quickly works.
         */
        const val ACCEPTABLE_WORST_CASE_MILLIS = 1_000L

        fun initial(samplingGapMillis: Long) = BlurLatency(samplingGapMillis = samplingGapMillis)
    }
}
