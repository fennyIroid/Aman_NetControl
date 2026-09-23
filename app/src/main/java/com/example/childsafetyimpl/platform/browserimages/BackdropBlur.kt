package com.example.childsafetyimpl.platform.browserimages

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import androidx.annotation.RequiresApi
import kotlin.math.roundToInt

/**
 * The frost of one photo's patch, and the part of the patch it fills, as fractions of the patch:
 * all of it, unless the photo was partly off screen when it was captured.
 */
class PhotoFrost(val bitmap: Bitmap, val part: RectF)

/**
 * Turns a capture of the browser window into the frosted image shown inside the scrim.
 *
 * The capture is shrunk to a thumbnail a few dozen pixels wide and box-blurred there, so what the
 * scrim holds has no detail left in it: a photo on the page becomes a patch of colour. Shrinking
 * is what carries that guarantee, not a GPU effect. `RenderEffect` silently does nothing in a
 * software-rendered window, and a scrim that showed the page sharp because a flag was missing
 * would be the worst failure this app could have.
 *
 * No capture is kept, written anywhere or logged. Only the thumbnail outlives [frost].
 */
object BackdropBlur {

    /** About 30 screen pixels per thumbnail pixel on a phone — a whole image result is a few pixels. */
    const val THUMBNAIL_WIDTH = 36
    const val BLUR_RADIUS = 2
    const val BLUR_PASSES = 3

    /**
     * Capture pixels behind each thumbnail pixel of a single photo's frost — as coarse as the
     * whole-window frost of a phone-width capture, so a photo frosted on its own shows no more.
     */
    const val CAPTURE_PIXELS_PER_THUMBNAIL_PIXEL = 30

    fun thumbnailSize(width: Int, height: Int, targetWidth: Int = THUMBNAIL_WIDTH): Pair<Int, Int> {
        if (width <= targetWidth) return width to height
        val scaledHeight = (height.toLong() * targetWidth / width.toDouble()).roundToInt()
        return targetWidth to scaledHeight.coerceAtLeast(1)
    }

    /**
     * The part of a window capture that lies under [area], in the capture's own pixel coordinates.
     * Null when the two do not overlap.
     */
    fun cropRect(area: Rect, windowBounds: Rect, captureWidth: Int, captureHeight: Int): Rect? {
        if (windowBounds.isEmpty) return null
        val scaleX = captureWidth.toFloat() / windowBounds.width()
        val scaleY = captureHeight.toFloat() / windowBounds.height()
        val crop = Rect(
            ((area.left - windowBounds.left) * scaleX).roundToInt(),
            ((area.top - windowBounds.top) * scaleY).roundToInt(),
            ((area.right - windowBounds.left) * scaleX).roundToInt(),
            ((area.bottom - windowBounds.top) * scaleY).roundToInt(),
        )
        if (!crop.intersect(0, 0, captureWidth, captureHeight)) return null
        return crop.takeUnless { it.isEmpty }
    }

    /**
     * Separable box blur over ARGB pixels, repeated [passes] times — three passes approximate a
     * gaussian. Edges are clamped, so nothing dark bleeds in from outside the image.
     */
    fun boxBlur(
        pixels: IntArray,
        width: Int,
        height: Int,
        radius: Int = BLUR_RADIUS,
        passes: Int = BLUR_PASSES,
    ): IntArray {
        require(pixels.size == width * height) { "pixels do not match $width x $height" }
        val current = pixels.copyOf()
        if (radius <= 0 || width == 0 || height == 0) return current

        val scratch = IntArray(current.size)
        repeat(passes) {
            blurAxis(current, scratch, width, height, radius, horizontal = true)
            blurAxis(scratch, current, width, height, radius, horizontal = false)
        }
        return current
    }

    /**
     * Crop [capture] to [crop], shrink and blur it. [capture] must be a software bitmap. It is not
     * recycled here, and the result is never [capture] itself, so the caller may recycle it at once.
     */
    fun frost(capture: Bitmap, crop: Rect, targetWidth: Int = THUMBNAIL_WIDTH): Bitmap {
        val (thumbWidth, thumbHeight) = thumbnailSize(crop.width(), crop.height(), targetWidth)

        // Halving step by step averages every source pixel. A single jump from 1080 to 36 with
        // bilinear filtering would sample a handful of pixels and flicker as the page scrolls.
        var current = Bitmap.createBitmap(capture, crop.left, crop.top, crop.width(), crop.height())
        while (current.width / 2 >= thumbWidth && current.height / 2 >= 1) {
            current = current.replacedBy(capture) {
                Bitmap.createScaledBitmap(it, it.width / 2, it.height / 2, true)
            }
        }
        current = current.replacedBy(capture) {
            Bitmap.createScaledBitmap(it, thumbWidth, thumbHeight, true)
        }

        val pixels = IntArray(thumbWidth * thumbHeight)
        current.getPixels(pixels, 0, thumbWidth, 0, 0, thumbWidth, thumbHeight)
        val blurred = boxBlur(pixels, thumbWidth, thumbHeight)

        val frosted = Bitmap.createBitmap(thumbWidth, thumbHeight, Bitmap.Config.ARGB_8888)
        frosted.setPixels(blurred, 0, thumbWidth, 0, 0, thumbWidth, thumbHeight)
        if (current !== capture) current.recycle()
        return frosted
    }

    /**
     * Run [use] on a software copy of a window screenshot from
     * `AccessibilityService.takeScreenshotOfWindow()`.
     *
     * The hardware buffer is closed and the copy recycled before this returns, so nothing of the
     * capture outlives [use] except what it returns. Null when nothing usable was captured.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun <T> readScreenshot(screenshot: AccessibilityService.ScreenshotResult, use: (Bitmap) -> T): T? =
        screenshot.hardwareBuffer.use { buffer ->
            val hardware = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace) ?: return null
            // Hardware bitmaps cannot be read or drawn in software; copy once, then work on that.
            val software = hardware.copy(Bitmap.Config.ARGB_8888, false)
            hardware.recycle()
            if (software == null) return null
            try {
                use(software)
            } finally {
                software.recycle()
            }
        }

    /** Frost the part of [capture] under [area]; null when the two do not overlap. */
    fun frost(capture: Bitmap, area: Rect, windowBounds: Rect): Bitmap? =
        cropRect(area, windowBounds, capture.width, capture.height)?.let { frost(capture, it) }

    /**
     * Frost one photo's [patch], in screen coordinates, from a capture of the window at
     * [windowBounds]. The frost travels with the patch, so a scrolling photo stays blurred under
     * its own colours rather than showing whatever was at that spot of the screen when the whole
     * window was last frosted. Null when none of the patch was on screen.
     */
    fun frostPhoto(capture: Bitmap, patch: Rect, windowBounds: Rect): PhotoFrost? {
        if (patch.isEmpty) return null
        val crop = cropRect(patch, windowBounds, capture.width, capture.height) ?: return null
        val visible = Rect(patch)
        if (!visible.intersect(windowBounds)) return null

        val width = (crop.width().toFloat() / CAPTURE_PIXELS_PER_THUMBNAIL_PIXEL).roundToInt().coerceAtLeast(1)
        val part = RectF(
            (visible.left - patch.left).toFloat() / patch.width(),
            (visible.top - patch.top).toFloat() / patch.height(),
            (visible.right - patch.left).toFloat() / patch.width(),
            (visible.bottom - patch.top).toFloat() / patch.height(),
        )
        return PhotoFrost(frost(capture, crop, targetWidth = width), part)
    }

    private inline fun Bitmap.replacedBy(capture: Bitmap, next: (Bitmap) -> Bitmap): Bitmap {
        val replacement = next(this)
        if (replacement !== this && this !== capture) recycle()
        return replacement
    }

    private fun blurAxis(
        source: IntArray,
        target: IntArray,
        width: Int,
        height: Int,
        radius: Int,
        horizontal: Boolean,
    ) {
        val lines = if (horizontal) height else width
        val length = if (horizontal) width else height
        val window = radius * 2 + 1

        fun indexOf(line: Int, position: Int): Int {
            val clamped = position.coerceIn(0, length - 1)
            return if (horizontal) line * width + clamped else clamped * width + line
        }

        for (line in 0 until lines) {
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            for (position in -radius..radius) {
                val pixel = source[indexOf(line, position)]
                a += pixel ushr 24
                r += pixel shr 16 and 0xFF
                g += pixel shr 8 and 0xFF
                b += pixel and 0xFF
            }
            for (position in 0 until length) {
                target[indexOf(line, position)] =
                    (a / window shl 24) or (r / window shl 16) or (g / window shl 8) or (b / window)

                val leaving = source[indexOf(line, position - radius)]
                val entering = source[indexOf(line, position + radius + 1)]
                a += (entering ushr 24) - (leaving ushr 24)
                r += (entering shr 16 and 0xFF) - (leaving shr 16 and 0xFF)
                g += (entering shr 8 and 0xFF) - (leaving shr 8 and 0xFF)
                b += (entering and 0xFF) - (leaving and 0xFF)
            }
        }
    }
}
