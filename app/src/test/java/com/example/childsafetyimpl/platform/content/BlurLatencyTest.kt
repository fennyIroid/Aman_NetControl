package com.example.childsafetyimpl.platform.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The exposure arithmetic behind POC 1's headline number.
 *
 * Worth testing without a device because the mistake this class exists to prevent is a reporting
 * mistake rather than a runtime one: quoting the classifier's time as though it were the time the
 * content was visible.
 */
class BlurLatencyTest {

    private val gap = 500L

    @Test
    fun `the worst case includes a full sampling gap`() {
        // Content appearing just after a sample waits the whole gap before anything looks at it.
        // Reporting only the measured path would understate real exposure by half a second.
        val latency = BlurLatency.initial(gap).record(latencyMillis = 200, classifyMillis = 80)
        assertEquals(200, latency.worstLatencyMillis)
        assertEquals(700, latency.worstCaseExposureMillis)
    }

    @Test
    fun `averages are taken over covers raised rather than frames seen`() {
        val latency = BlurLatency.initial(gap)
            .record(300, 100)
            .record(100, 50)
        assertEquals(2, latency.coversRaised)
        assertEquals(200, latency.averageLatencyMillis)
        assertEquals(75, latency.averageClassifyMillis)
    }

    @Test
    fun `the classifier share shows how much of the window tuning could recover`() {
        val latency = BlurLatency.initial(gap).record(400, 100)
        assertEquals(0.25f, latency.classifyShare, 0.001f)
    }

    @Test
    fun `no covers means no fabricated figures`() {
        // A zero here would read as instantaneous protection, which is the most flattering
        // possible misreading of "we never covered anything".
        val latency = BlurLatency.initial(gap)
        assertEquals(0, latency.averageLatencyMillis)
        assertEquals(0, latency.worstCaseExposureMillis)
        assertTrue(latency.summary().contains("No covers"))
    }

    @Test
    fun `the summary reports measured and true worst case separately`() {
        val summary = BlurLatency.initial(gap).record(250, 90).summary()
        assertTrue(summary.contains("250ms"))
        assertTrue("The true worst case must appear alongside the measured one", summary.contains("750ms"))
    }
}
