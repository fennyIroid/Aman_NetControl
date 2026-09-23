package com.example.childsafetyimpl.platform.browserimages

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackdropBlurTest {

    @Test
    fun `the thumbnail keeps the aspect ratio at the target width`() {
        assertThat(BackdropBlur.thumbnailSize(1080, 2180, targetWidth = 36)).isEqualTo(36 to 73)
    }

    @Test
    fun `the thumbnail never collapses to zero`() {
        assertThat(BackdropBlur.thumbnailSize(1080, 5, targetWidth = 36)).isEqualTo(36 to 1)
    }

    @Test
    fun `a source narrower than the target is not enlarged`() {
        assertThat(BackdropBlur.thumbnailSize(20, 40, targetWidth = 36)).isEqualTo(20 to 40)
    }

    @Test
    fun `the crop is the covered area in window coordinates`() {
        val crop = BackdropBlur.cropRect(
            area = Rect(0, 228, 1080, 2408),
            windowBounds = Rect(0, 100, 1080, 2408),
            captureWidth = 1080,
            captureHeight = 2308,
        )
        assertThat(crop).isEqualTo(Rect(0, 128, 1080, 2308))
    }

    @Test
    fun `the crop follows a capture taken at a different scale than the window`() {
        val crop = BackdropBlur.cropRect(
            area = Rect(0, 200, 1080, 2400),
            windowBounds = Rect(0, 0, 1080, 2400),
            captureWidth = 540,
            captureHeight = 1200,
        )
        assertThat(crop).isEqualTo(Rect(0, 100, 540, 1200))
    }

    @Test
    fun `the crop is clamped to the capture`() {
        val crop = BackdropBlur.cropRect(
            area = Rect(-50, 228, 1200, 3000),
            windowBounds = Rect(0, 0, 1080, 2408),
            captureWidth = 1080,
            captureHeight = 2408,
        )
        assertThat(crop).isEqualTo(Rect(0, 228, 1080, 2408))
    }

    @Test
    fun `an area outside the captured window has no crop`() {
        val crop = BackdropBlur.cropRect(
            area = Rect(0, 3000, 1080, 3200),
            windowBounds = Rect(0, 0, 1080, 2408),
            captureWidth = 1080,
            captureHeight = 2408,
        )
        assertThat(crop).isNull()
    }

    @Test
    fun `blurring a uniform image leaves it unchanged, edges included`() {
        val grey = 0xFF808080.toInt()
        val pixels = IntArray(8 * 6) { grey }
        assertThat(BackdropBlur.boxBlur(pixels, 8, 6).toList()).containsExactlyElementsIn(pixels.toList())
    }

    @Test
    fun `blurring spreads a bright pixel into its neighbours`() {
        val black = 0xFF000000.toInt()
        val pixels = IntArray(9 * 9) { black }.also { it[4 * 9 + 4] = 0xFFFFFFFF.toInt() }

        val blurred = BackdropBlur.boxBlur(pixels, 9, 9, radius = 1, passes = 1)

        val centre = blurred[4 * 9 + 4] and 0xFF
        val neighbour = blurred[4 * 9 + 5] and 0xFF
        assertThat(centre).isLessThan(0xFF)
        assertThat(neighbour).isGreaterThan(0)
    }

    @Test
    fun `blurring does not touch the input array`() {
        val pixels = IntArray(4 * 4) { if (it == 5) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
        val before = pixels.copyOf()
        BackdropBlur.boxBlur(pixels, 4, 4)
        assertThat(pixels.toList()).isEqualTo(before.toList())
    }

    @Test
    fun `frosting reduces the covered area to a thumbnail`() {
        val capture = Bitmap.createBitmap(1080, 2408, Bitmap.Config.ARGB_8888)

        val frosted = BackdropBlur.frost(capture, Rect(0, 228, 1080, 2408))

        assertThat(frosted.width).isEqualTo(BackdropBlur.THUMBNAIL_WIDTH)
        assertThat(frosted.height).isEqualTo(BackdropBlur.thumbnailSize(1080, 2180).second)
    }

    @Test
    fun `a photo's frost is as coarse as the whole window's`() {
        // Every thumbnail pixel stands for about as many screen pixels as in the whole-window
        // frost, so a photo frosted on its own shows no more detail.
        val capture = Bitmap.createBitmap(1080, 2408, Bitmap.Config.ARGB_8888)

        val frost = BackdropBlur.frostPhoto(capture, Rect(0, 300, 540, 700), windowBounds = Rect(0, 0, 1080, 2408))!!

        assertThat(frost.bitmap.width).isEqualTo(540 / BackdropBlur.CAPTURE_PIXELS_PER_THUMBNAIL_PIXEL)
        assertThat(frost.part).isEqualTo(RectF(0f, 0f, 1f, 1f))
    }

    @Test
    fun `a photo cut off by the screen edge is frosted for the part that shows`() {
        val capture = Bitmap.createBitmap(1080, 2408, Bitmap.Config.ARGB_8888)

        val frost = BackdropBlur.frostPhoto(capture, Rect(0, -100, 540, 300), windowBounds = Rect(0, 0, 1080, 2408))!!

        assertThat(frost.part).isEqualTo(RectF(0f, 0.25f, 1f, 1f))
    }

    @Test
    fun `a photo off screen has no frost`() {
        val capture = Bitmap.createBitmap(1080, 2408, Bitmap.Config.ARGB_8888)

        val frost = BackdropBlur.frostPhoto(capture, Rect(0, 3000, 540, 3400), windowBounds = Rect(0, 0, 1080, 2408))

        assertThat(frost).isNull()
    }

    @Test
    fun `frosting never hands back the capture itself`() {
        // The caller recycles the capture straight away. If frost returned the same object the
        // scrim would be left holding a recycled bitmap.
        val capture = Bitmap.createBitmap(20, 40, Bitmap.Config.ARGB_8888)

        val frosted = BackdropBlur.frost(capture, Rect(0, 0, 20, 40))

        assertThat(frosted).isNotSameInstanceAs(capture)
    }
}
