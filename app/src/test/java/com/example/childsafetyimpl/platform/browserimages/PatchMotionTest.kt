package com.example.childsafetyimpl.platform.browserimages

import android.graphics.Rect
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PatchMotionTest {

    private val page = 1L
    private val viewer = 2L
    private val photo = Photo(7L, Rect(100, 1000, 500, 1400), scroller = page)
    private val out = Rect()

    private fun track(shiftY: Int = 0, at: Long = 0L, velocityY: Float = 0f, measured: Boolean = true) =
        Track(shiftX = 0, shiftY = shiftY, atUptime = at, velocityX = 0f, velocityY = velocityY, measured = measured)

    @Test
    fun `a still page leaves the patch where the photo is, plus the margin`() {
        PatchMotion(mapOf(page to track()), margin = 20).place(photo, 500L, out)
        assertThat(out).isEqualTo(Rect(80, 980, 520, 1420))
    }

    @Test
    fun `the patch moves as far as the anchor has moved`() {
        PatchMotion(mapOf(page to track(shiftY = -150)), margin = 0).place(photo, 500L, out)
        assertThat(out).isEqualTo(Rect(100, 850, 500, 1250))
    }

    @Test
    fun `a moving page carries the patch along at the page's speed`() {
        // Scrolling up at 2 px/ms: 100 ms after the anchor was read the photo is 200 px higher.
        val motion = PatchMotion(mapOf(page to track(shiftY = -50, at = 1_000L, velocityY = -2f)), margin = 0)
        motion.place(photo, 1_100L, out)
        assertThat(out.centerY()).isEqualTo(photo.bounds.centerY() - 250)
    }

    @Test
    fun `shifting moves the patch without stretching it`() {
        val motion = PatchMotion(mapOf(page to track(at = 1_000L, velocityY = -2f)), margin = 30)
        motion.shift(photo, 1_100L, out)
        assertThat(out).isEqualTo(Rect(100, 800, 500, 1200))
    }

    @Test
    fun `the faster the page moves, the more the patch is stretched to absorb the lag`() {
        val slow = Rect().also { PatchMotion(mapOf(page to track(velocityY = -0.5f)), 0).place(photo, 0L, it) }
        val fast = Rect().also { PatchMotion(mapOf(page to track(velocityY = -3f)), 0).place(photo, 0L, it) }
        assertThat(fast.height()).isGreaterThan(slow.height())
        assertThat(slow.height()).isGreaterThan(photo.bounds.height())
        // Stretching is along the motion; a vertical scroll does not widen the patch.
        assertThat(fast.width()).isEqualTo(photo.bounds.width())
    }

    @Test
    fun `prediction stops after a while so a patch never runs away when updates stop`() {
        val motion = PatchMotion(mapOf(page to track(velocityY = -2f)), margin = 0)
        val atLimit = Rect().also { motion.place(photo, PatchMotion.MAX_PREDICTION_MILLIS, it) }
        val later = Rect().also { motion.place(photo, PatchMotion.MAX_PREDICTION_MILLIS * 10, it) }
        assertThat(later).isEqualTo(atLimit)
    }

    @Test
    fun `a photo in a container that is not scrolling stays put while another one scrolls`() {
        // Google Images' viewer scrolls inside an overlay; the results behind it do not move.
        val behind = Photo(8L, Rect(100, 1000, 500, 1400), scroller = page)
        val inViewer = Photo(9L, Rect(100, 1000, 500, 1400), scroller = viewer)
        val motion = PatchMotion(mapOf(page to track(), viewer to track(velocityY = -2f)), margin = 0)

        val still = Rect().also { motion.place(behind, 100L, it) }
        val moved = Rect().also { motion.place(inViewer, 100L, it) }

        assertThat(still).isEqualTo(behind.bounds)
        assertThat(moved.centerY()).isEqualTo(inViewer.bounds.centerY() - 200)
    }

    @Test
    fun `a photo whose container is not followed gets room on every side`() {
        val motion = PatchMotion(emptyMap(), margin = 0, unmeasuredMargin = 50)
        motion.place(photo, 100L, out)
        assertThat(out).isEqualTo(Rect(50, 950, 550, 1450))
    }

    @Test
    fun `a container that has moved but has no speed yet gets room on every side too`() {
        // A drag has just begun: where the anchor was is known, how fast it goes is not.
        val motion = PatchMotion(mapOf(page to track(shiftY = -40, measured = false)), margin = 0, unmeasuredMargin = 50)
        motion.place(photo, 100L, out)
        assertThat(out).isEqualTo(Rect(50, 910, 550, 1410))
    }

    @Test
    fun `motion is under way while any container moves`() {
        assertThat(PatchMotion(mapOf(page to track()), 0).isMoving).isFalse()
        assertThat(PatchMotion(mapOf(page to track(), viewer to track(velocityY = 1f)), 0).isMoving).isTrue()
    }
}
