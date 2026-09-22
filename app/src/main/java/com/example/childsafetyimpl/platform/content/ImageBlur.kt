package com.example.childsafetyimpl.platform.content

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Blurs an image that failed the safety check.
 *
 * Blurring rather than replacing with a placeholder is a deliberate product decision. A page full
 * of grey boxes reads as broken and invites a child to go and find the same picture somewhere the
 * app is not watching. A blurred image reads as covered on purpose: the layout survives, the
 * subject does not, and it is obvious that something decided this rather than that the network
 * failed.
 *
 * The blur is done by downscaling and scaling back up with bilinear filtering, repeated a few
 * times. That is not a shortcut for a real convolution: it is faster than one by an order of
 * magnitude, it runs on any device with no RenderScript or RenderEffect version gate, and at the
 * radii this needs it is visually indistinguishable from a Gaussian.
 *
 * The important guarantee is what leaves this class. The original bytes are decoded here and
 * discarded, and only the blurred image is encoded and handed on. A child never receives the
 * pixels of an image that failed, which is a materially stronger promise than blurring something
 * already on screen.
 */
@Singleton
class ImageBlur @Inject constructor() {

    /**
     * Returns a blurred JPEG of [source].
     *
     * @param strength 0..1. Maps to how far down the image is scaled before being scaled back,
     *   which is what actually determines how much detail survives.
     */
    fun blurToJpeg(source: Bitmap, strength: Float, quality: Int = 72): ByteArray {
        val blurred = blur(source, strength)
        val output = ByteArrayOutputStream()
        blurred.compress(Bitmap.CompressFormat.JPEG, quality, output)
        if (blurred !== source) blurred.recycle()
        return output.toByteArray()
    }

    fun blur(source: Bitmap, strength: Float): Bitmap {
        val clamped = strength.coerceIn(0f, 1f)
        if (source.width < 2 || source.height < 2) return source

        // The divisor is what controls the blur. At full strength the image passes through a
        // thumbnail barely 3% of its width, which destroys every recognisable detail while
        // keeping the colour composition that makes it read as a covered photo.
        val divisor = 4 + (clamped * 28).roundToInt()
        val smallWidth = (source.width / divisor).coerceAtLeast(2)
        val smallHeight = (source.height / divisor).coerceAtLeast(2)

        var working = source.scale(smallWidth, smallHeight, filter = true)

        // Each extra pass widens the effective kernel. Two is enough to remove the blockiness a
        // single downscale leaves behind, and more than three costs time without looking better.
        repeat(BLUR_PASSES) {
            val next = working.scale(
                (smallWidth * 0.75f).roundToInt().coerceAtLeast(2),
                (smallHeight * 0.75f).roundToInt().coerceAtLeast(2),
                filter = true,
            )
            if (working !== source) working.recycle()
            working = next
        }

        val result = working.scale(source.width, source.height, filter = true)
        // scale returns the same instance when the dimensions already match, so recycling
        // `working` unconditionally could destroy the bitmap being returned.
        if (working !== source && working !== result) working.recycle()

        // A faint wash so a blurred photo cannot be mistaken for one that is merely out of focus.
        Canvas(result).drawColor(Color.argb((26 * clamped).roundToInt(), 46, 91, 255))
        return result
    }

    /**
     * A blurred copy of a picture that stays on screen next to the original page.
     *
     * Separate from [blur] because the two are solving different problems and the right answer
     * to one is the wrong answer to the other.
     *
     * [blur] runs in the browser, where the original bytes never reach the child at all. Nothing
     * is lost by crushing the image to a handful of pixels, so it crushes hard.
     *
     * This one is drawn *over* a live screen the child is already looking at, and it has to read
     * as the picture being out of focus rather than as a panel laid on top of it. At the crush
     * [blur] uses, a region ends up around three pixels across: blown back up to the size of a
     * photograph that is a smear of two colours, and with any wash over it the result is what a
     * person looking at the phone calls a shaded box, not a blur.
     *
     * So the crush here is stated as **how many cells survive across the picture**, not as a
     * divisor of whatever resolution the capture happened to be. That is the blur radius in the
     * only terms that matter: the same number of cells looks the same on every screen size and
     * every capture scale, and a face inside a picture reduced to sixteen cells is two cells wide,
     * which no one can read. What survives is the colour composition, and that is the part that
     * has to survive — it is the difference between a picture that is covered and a page that
     * looks broken.
     *
     * The way back up matters as much as the way down. A thumbnail stretched to full size in one
     * step shows the interpolation itself: soft diamonds with visible seams between them. Doubling
     * repeatedly instead runs a bilinear pass at every scale, and stacked bilinear passes are a
     * close enough approximation of a Gaussian that the result reads as an out-of-focus photograph.
     */
    fun blurPreview(source: Bitmap, strength: Float): Bitmap {
        val clamped = strength.coerceIn(0f, 1f)
        if (source.width < MIN_PREVIEW_SIDE || source.height < MIN_PREVIEW_SIDE) return source

        val factor = previewFactor(minOf(source.width, source.height), clamped)

        var working = source.scale(
            (source.width / factor).roundToInt().coerceAtLeast(2),
            (source.height / factor).roundToInt().coerceAtLeast(2),
            filter = true,
        )
        if (working === source) return source

        while (working.width * 2 <= source.width && working.height * 2 <= source.height) {
            val next = working.scale(working.width * 2, working.height * 2, filter = true)
            if (next === working) break
            working.recycle()
            working = next
        }

        if (working.width == source.width && working.height == source.height) return working

        val result = working.scale(source.width, source.height, filter = true)
        if (result !== working) working.recycle()
        return result
    }

    /**
     * How many cells survive across the short side of a preview at a given strength.
     *
     * The blur radius, stated in the only terms that hold still. It was previously a divisor of
     * the source resolution, which meant the radius silently tracked whatever scale the screen
     * capture happened to run at: the same photograph blurred to a different degree depending on
     * the size of the phone, and at the capture scale this app actually uses it came out around
     * three cells, which is not a blur of a picture but two smeared colours.
     */
    internal fun previewCells(strength: Float): Int =
        (CELLS_LIGHTEST - strength.coerceIn(0f, 1f) * (CELLS_LIGHTEST - CELLS_HEAVIEST))
            .coerceAtLeast(CELLS_HEAVIEST)
            .roundToInt()

    /**
     * How far down a picture goes before it comes back up.
     *
     * A picture already at or below the target resolution is still halved: returning it untouched
     * would mean a small thumbnail was covered by a sharp copy of itself.
     */
    internal fun previewFactor(shortSide: Int, strength: Float): Float =
        (shortSide.toFloat() / previewCells(strength)).coerceAtLeast(2f)

    /**
     * The image handed to a page when the original could not be decoded at all.
     *
     * There is nothing to blur in that case, and passing the undecodable bytes through would mean
     * shipping an image nothing has checked. A woven placeholder is the honest answer.
     */
    fun coveredPlaceholder(): ByteArray {
        cached?.let { return it }

        val size = 320
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = Color.parseColor("#EBF0FF")
        canvas.drawRect(Rect(0, 0, size, size), paint)

        paint.color = Color.parseColor("#C7D6FF")
        paint.strokeWidth = 10f
        var offset = -size
        while (offset < size * 2) {
            canvas.drawLine(offset.toFloat(), 0f, (offset + size).toFloat(), size.toFloat(), paint)
            offset += 44
        }

        paint.color = Color.parseColor("#2E5BFF")
        paint.textSize = 26f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("Covered", size / 2f, size / 2f + 9f, paint)

        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        bitmap.recycle()

        return output.toByteArray().also { cached = it }
    }

    private var cached: ByteArray? = null

    private companion object {
        const val BLUR_PASSES = 2

        /** Cells across the short side of a preview at the gentlest setting a parent can choose. */
        const val CELLS_LIGHTEST = 18f

        /** And at the strongest. Below this the preview stops reading as a photograph at all. */
        const val CELLS_HEAVIEST = 8f

        /** Under this there is nothing to reduce, and reducing it produces a single flat colour. */
        const val MIN_PREVIEW_SIDE = 8
    }
}
