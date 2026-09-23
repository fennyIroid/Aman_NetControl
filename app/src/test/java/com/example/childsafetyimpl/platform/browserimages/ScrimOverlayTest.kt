package com.example.childsafetyimpl.platform.browserimages

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.WindowManager
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ScrimOverlayTest {

    private lateinit var windowManager: WindowManager
    private lateinit var view: ScrimView
    private lateinit var overlay: ScrimOverlay

    @Before
    fun setUp() {
        windowManager = mockk(relaxed = true)
        view = ScrimView(RuntimeEnvironment.getApplication())
        overlay = ScrimOverlay(windowManager) { view }
    }

    @Test
    fun `attach adds the view exactly once`() {
        overlay.attach(RuntimeEnvironment.getApplication())
        overlay.attach(RuntimeEnvironment.getApplication())
        verify(exactly = 1) { windowManager.addView(any(), any()) }
        assertThat(overlay.isAttached).isTrue()
    }

    @Test
    fun `attach leaves the scrim invisible`() {
        overlay.attach(RuntimeEnvironment.getApplication())
        assertThat(view.visibility).isEqualTo(View.INVISIBLE)
        assertThat(overlay.isShowing).isFalse()
    }

    @Test
    fun `covering never calls addView - it is a visibility toggle`() {
        // Adding the view at trigger time costs tens of milliseconds and loses the race to
        // Chrome's paint. This is the invariant the whole design rests on.
        overlay.attach(RuntimeEnvironment.getApplication())
        overlay.cover(Rect(0, 300, 1080, 2340))
        verify(exactly = 1) { windowManager.addView(any(), any()) }
        assertThat(view.visibility).isEqualTo(View.VISIBLE)
        assertThat(overlay.isShowing).isTrue()
    }

    @Test
    fun `covering twice does not re-add the view`() {
        overlay.attach(RuntimeEnvironment.getApplication())
        overlay.cover(Rect(0, 300, 1080, 2340))
        overlay.cover(Rect(0, 400, 1080, 2340))
        verify(exactly = 1) { windowManager.addView(any(), any()) }
    }

    @Test
    fun `hide leaves the view attached`() {
        overlay.attach(RuntimeEnvironment.getApplication())
        overlay.cover(Rect(0, 300, 1080, 2340))
        overlay.hide()
        assertThat(view.visibility).isEqualTo(View.INVISIBLE)
        assertThat(overlay.isAttached).isTrue()
        verify(exactly = 0) { windowManager.removeView(any()) }
    }

    @Test
    fun `the window type is accessibility overlay and never system alert window`() {
        val params = slot<WindowManager.LayoutParams>()
        every { windowManager.addView(any(), capture(params)) } returns Unit
        overlay.attach(RuntimeEnvironment.getApplication())
        assertThat(params.captured.type)
            .isEqualTo(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        assertThat(params.captured.type)
            .isNotEqualTo(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
    }

    @Test
    fun `the window is not touchable and not focusable`() {
        // The scrim must never steal input or block scrolling.
        val params = slot<WindowManager.LayoutParams>()
        every { windowManager.addView(any(), capture(params)) } returns Unit
        overlay.attach(RuntimeEnvironment.getApplication())
        val flags = params.captured.flags
        assertThat(flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE).isNotEqualTo(0)
        assertThat(flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE).isNotEqualTo(0)
    }

    @Test
    fun `detach removes the view`() {
        overlay.attach(RuntimeEnvironment.getApplication())
        overlay.detach()
        verify(exactly = 1) { windowManager.removeView(view) }
        assertThat(overlay.isAttached).isFalse()
    }

    @Test
    fun `cover before attach is a no-op rather than a crash`() {
        overlay.cover(Rect(0, 0, 100, 100))
        assertThat(overlay.isShowing).isFalse()
        verify(exactly = 0) { windowManager.addView(any(), any()) }
    }

    @Test
    fun `a freshly raised scrim is opaque until a backdrop arrives`() {
        overlay.attach(RuntimeEnvironment.getApplication())
        overlay.cover(Rect(0, 300, 1080, 2340))
        assertThat(view.hasBackdrop).isFalse()
    }

    @Test
    fun `a backdrop is shown inside the raised scrim`() {
        overlay.attach(RuntimeEnvironment.getApplication())
        overlay.cover(Rect(0, 300, 1080, 2340))
        overlay.showBackdrop(Bitmap.createBitmap(36, 70, Bitmap.Config.ARGB_8888), Rect(0, 300, 1080, 2340))
        assertThat(view.hasBackdrop).isTrue()
        assertThat(overlay.isShowing).isTrue()
    }

    @Test
    fun `a backdrop that lands after the scrim is lowered is dropped`() {
        // Captures are asynchronous. One finishing after the user left the page must not
        // be left waiting in the scrim for the next, unrelated raise.
        overlay.attach(RuntimeEnvironment.getApplication())
        overlay.showBackdrop(Bitmap.createBitmap(36, 70, Bitmap.Config.ARGB_8888), Rect(0, 300, 1080, 2340))
        overlay.showPhotoFrosts(mapOf(1L to frost()))
        assertThat(view.hasBackdrop).isFalse()
    }

    @Test
    fun `hiding clears the backdrop so the next raise starts opaque`() {
        overlay.attach(RuntimeEnvironment.getApplication())
        overlay.cover(Rect(0, 300, 1080, 2340))
        overlay.showBackdrop(Bitmap.createBitmap(36, 70, Bitmap.Config.ARGB_8888), Rect(0, 300, 1080, 2340))
        overlay.showPhotoFrosts(mapOf(1L to frost()))
        overlay.hide()
        overlay.cover(Rect(0, 300, 1080, 2340))
        assertThat(view.hasBackdrop).isFalse()
    }

    @Test
    fun `covering with no regions covers the whole area`() {
        overlay.attach(RuntimeEnvironment.getApplication())
        overlay.cover(Rect(0, 300, 1080, 2340))
        assertThat(view.coveredPhotos).isNull()
        assertThat(view.area).isEqualTo(Rect(0, 300, 1080, 2340))
    }

    @Test
    fun `covering photos covers only those photos, in screen coordinates`() {
        overlay.attach(RuntimeEnvironment.getApplication())
        val photos = listOf(Photo(1L, Rect(10, 310, 530, 700)), Photo(2L, Rect(550, 800, 1070, 1200)))
        overlay.cover(Rect(0, 300, 1080, 2340), photos)
        assertThat(view.coveredPhotos).containsExactlyElementsIn(photos).inOrder()
        assertThat(overlay.isShowing).isTrue()
    }

    @Test
    fun `covering the whole area again after photos drops the photos`() {
        overlay.attach(RuntimeEnvironment.getApplication())
        overlay.cover(Rect(0, 300, 1080, 2340), listOf(Photo(1L, Rect(10, 310, 530, 700))))
        overlay.cover(Rect(0, 300, 1080, 2340))
        assertThat(view.coveredPhotos).isNull()
    }

    @Test
    fun `covering never relayouts the window, whatever the area`() {
        // A relayout lands a frame or more after the patches are redrawn, and in between every
        // patch sat off its photo by however far the area moved — the toolbar's height when
        // Chrome slid it away. The window fills the screen once; the area is only a clip.
        overlay.attach(RuntimeEnvironment.getApplication())
        overlay.cover(Rect(0, 300, 1080, 2340))
        overlay.cover(Rect(0, 0, 1080, 2340), listOf(Photo(1L, Rect(10, 310, 530, 700))))
        overlay.cover(Rect(0, 300, 1080, 2200))
        verify(exactly = 0) { windowManager.updateViewLayout(any(), any()) }
        assertThat(view.area).isEqualTo(Rect(0, 300, 1080, 2200))
    }

    @Test
    fun `the window fills the screen and is not pushed in by the system bars`() {
        val params = slot<WindowManager.LayoutParams>()
        every { windowManager.addView(any(), capture(params)) } returns Unit
        overlay.attach(RuntimeEnvironment.getApplication())
        assertThat(params.captured.width).isEqualTo(WindowManager.LayoutParams.MATCH_PARENT)
        assertThat(params.captured.height).isEqualTo(WindowManager.LayoutParams.MATCH_PARENT)
        assertThat(params.captured.fitInsetsTypes).isEqualTo(0)
        assertThat(params.captured.layoutInDisplayCutoutMode)
            .isEqualTo(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS)
    }

    @Test
    fun `each photo's frost is handed to the view`() {
        overlay.attach(RuntimeEnvironment.getApplication())
        overlay.cover(Rect(0, 300, 1080, 2340), listOf(Photo(1L, Rect(10, 310, 530, 700))))
        val frost = frost()
        overlay.showPhotoFrosts(mapOf(1L to frost))
        assertThat(view.photoFrosts).containsExactly(1L, frost)
    }

    @Test
    fun `the window is translucent so the page shows between photos`() {
        val params = slot<WindowManager.LayoutParams>()
        every { windowManager.addView(any(), capture(params)) } returns Unit
        overlay.attach(RuntimeEnvironment.getApplication())
        assertThat(params.captured.format).isEqualTo(PixelFormat.TRANSLUCENT)
    }

    @Test
    fun `what the scrim paints over a photo is fully opaque`() {
        // The window is translucent, so any alpha in the ground would let the photo through.
        assertThat(Color.alpha(ScrimView.GROUND)).isEqualTo(0xFF)
    }

    @Test
    fun `moving patches are handed to the view with their motion`() {
        overlay.attach(RuntimeEnvironment.getApplication())
        val motion = PatchMotion(mapOf(0L to Track(0, -40, atUptime = 10L, velocityX = 0f, velocityY = -2f)), margin = 30)
        val photos = listOf(Photo(1L, Rect(10, 310, 530, 700)))
        overlay.cover(Rect(0, 300, 1080, 2340), photos, motion)
        assertThat(view.coveredPhotos).isEqualTo(photos)
        assertThat(view.motion).isEqualTo(motion)
    }

    @Test
    fun `a still cover drops any motion`() {
        overlay.attach(RuntimeEnvironment.getApplication())
        val motion = PatchMotion(mapOf(0L to Track(0, -40, atUptime = 10L, velocityX = 0f, velocityY = -2f)), margin = 30)
        val photos = listOf(Photo(1L, Rect(10, 310, 530, 700)))
        overlay.cover(Rect(0, 300, 1080, 2340), photos, motion)
        overlay.cover(Rect(0, 300, 1080, 2340), photos)
        assertThat(view.motion).isNull()
    }

    private fun frost() = PhotoFrost(Bitmap.createBitmap(17, 13, Bitmap.Config.ARGB_8888), RectF(0f, 0f, 1f, 1f))
}
