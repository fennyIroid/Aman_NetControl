package com.example.childsafetyimpl.platform.browserimages

import android.graphics.Rect
import kotlin.math.sqrt

/** Turning photo pixels into what the NSFW model reads. Pure, so it is JVM-testable. */
object ModelInput {

    /** The NSFW model's square input side, in pixels. */
    const val SIDE = 224

    /** The drug model's (MobileCLIP-S0's) square input side, in pixels. */
    const val CLIP_SIDE = 256

    /**
     * The largest square in the middle of [rect]: what the drug model looks at. CLIP models are
     * trained on centre crops, and a photo squashed square reads as something else.
     */
    fun centreSquare(rect: Rect): Rect {
        val side = minOf(rect.width(), rect.height())
        val left = rect.left + (rect.width() - side) / 2
        val top = rect.top + (rect.height() - side) / 2
        return Rect(left, top, left + side, top + side)
    }

    /**
     * Below this luminance spread (0-255 scale) a crop is a flat placeholder, not a photo. Real
     * photos measure in the tens; Google's loading tiles are a single colour.
     */
    private const val MIN_LUMINANCE_SPREAD = 6.0

    /** ARGB pixels to RGB floats in 0..1, row order — how the model was trained (pixels / 255). */
    fun fill(pixels: IntArray, input: FloatArray) {
        require(input.size == pixels.size * 3) { "input must hold 3 floats per pixel" }
        pixels.forEachIndexed { index, pixel ->
            input[index * 3] = (pixel shr 16 and 0xFF) / 255f
            input[index * 3 + 1] = (pixel shr 8 and 0xFF) / 255f
            input[index * 3 + 2] = (pixel and 0xFF) / 255f
        }
    }

    /**
     * False for a crop with no detail in it — a placeholder where the photo has not arrived. It
     * must not be scored: a grey tile scores clean, and a clean verdict would reveal the photo the
     * moment it loaded.
     */
    fun looksLoaded(pixels: IntArray): Boolean {
        if (pixels.isEmpty()) return false
        var sum = 0.0
        var sumOfSquares = 0.0
        for (pixel in pixels) {
            val luminance = 0.299 * (pixel shr 16 and 0xFF) +
                0.587 * (pixel shr 8 and 0xFF) +
                0.114 * (pixel and 0xFF)
            sum += luminance
            sumOfSquares += luminance * luminance
        }
        val mean = sum / pixels.size
        val variance = (sumOfSquares / pixels.size - mean * mean).coerceAtLeast(0.0)
        return sqrt(variance) >= MIN_LUMINANCE_SPREAD
    }
}
