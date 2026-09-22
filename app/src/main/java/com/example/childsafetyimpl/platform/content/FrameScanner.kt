package com.example.childsafetyimpl.platform.content

import android.graphics.Bitmap
import androidx.core.graphics.scale
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Judges a captured frame one picture at a time.
 *
 * The old pipeline asked the model a single question per frame — "is this screen unsafe" — and
 * that question has no useful answer on the screens people actually look at. A grid of search
 * results is mostly grid: whatever the model reports describes the layout, and the one picture
 * that matters is a twelfth of the evidence. Worse, a screenshot of a browser reliably labels as
 * "Web page", "Text" and "Screenshot", and those were counted as evidence of safety, so scoring a
 * browser screen actively pushed the verdict down. The feature was close to blind in the place it
 * was most needed.
 *
 * This asks the question once per picture instead. [ScreenRegions] finds the pictures, each one is
 * cropped and classified on its own, and what comes back is a list of the ones that failed and
 * where they are. A verdict about a thumbnail is a verdict about a thumbnail, and it can be acted
 * on by covering that thumbnail.
 *
 * Two things it deliberately does not do. It does not classify ground the app has already covered:
 * the capture mirrors our own cover, so that would be scoring our own output. And it does not
 * treat an empty region list as a clean frame — a full-bleed video has no internal structure to
 * find, so [Scan.wholeFrame] says the frame was judged entire and the caller can act accordingly.
 */
@Singleton
class FrameScanner @Inject constructor(
    private val classifier: ImageSafetyClassifier,
) {

    /**
     * What the last few frames said about each picture.
     *
     * Held here, across calls, because a verdict is evidence about a picture rather than a fact
     * about a frame: the same thumbnail scored twice does not score twice the same, and a
     * threshold read off one reading is what made the covers blink. See [VerdictStability].
     */
    private val stability = VerdictStability()

    /** Forgets every picture. Called when a capture session starts, so no verdict crosses one. */
    fun reset() = stability.reset()

    data class Finding(
        val region: ScreenRegions.Region,
        val verdict: ImageSafetyClassifier.Verdict,
    )

    data class Scan(
        /** Regions that failed, in frame fractions. */
        val findings: List<Finding>,
        /** How many regions were actually classified, for the metrics the POC has to report. */
        val regionsExamined: Int,
        /** True when no picture-shaped region was found and the whole frame was judged instead. */
        val wholeFrame: Boolean,
        val worst: ImageSafetyClassifier.Verdict?,
    ) {
        val isUnsafe: Boolean get() = findings.isNotEmpty()
    }

    /**
     * Classifies the pictures in a frame.
     *
     * @param pixels the frame's pixels, read once by the caller and shared rather than re-read
     * @param ignore regions the app is already covering, excluded from proposal and from scoring
     */
    suspend fun scan(
        bitmap: Bitmap,
        pixels: IntArray,
        level: ProtectionLevel,
        categories: Set<ValuesCategory>,
        ignore: List<ScreenRegions.Region>,
    ): Scan = coroutineScope {
        val now = System.currentTimeMillis()
        // The pictures this app is already covering are still on screen and still the same
        // pictures; they simply cannot be looked at. Saying so keeps their history alive, so the
        // examination that lifts a cover has something to compare against instead of meeting the
        // picture as though for the first time.
        stability.stillCovered(ignore, now)

        val proposals = ScreenRegions
            .propose(pixels, bitmap.width, bitmap.height)
            // Already covered. Scoring it would be scoring our own cover, and a cover that scores
            // as content is a cover that holds itself up.
            .filterNot { proposal -> ignore.any { proposal.region.containedBy(it) >= ALREADY_COVERED } }

        val regions = when {
            proposals.isNotEmpty() -> proposals.map { it.region }
            // Nothing picture-shaped and nothing covered: a full-bleed player, a photo viewer, a
            // game. The frame is the picture.
            ignore.isEmpty() -> listOf(ScreenRegions.Region.WHOLE_FRAME)
            // Nothing found and covers are up. There is nothing left to look at, and reporting
            // that as a clean frame would be reporting the cover as evidence.
            else -> emptyList()
        }

        if (regions.isEmpty()) {
            return@coroutineScope Scan(emptyList(), 0, wholeFrame = false, worst = null)
        }

        val verdicts = regions
            .map { region ->
                async {
                    val crop = bitmap.cropTo(region)
                    val verdict = runCatching { classifier.classify(crop) }.getOrNull()
                    if (crop !== bitmap) crop.recycle()
                    region to verdict
                }
            }
            .mapNotNull { it.await().let { (region, verdict) -> verdict?.let { region to it } } }

        val findings = verdicts
            // Settled first and filtered second, deliberately. Short-circuiting on the category
            // would skip the picture's own bookkeeping on any frame the categories did not match,
            // so its history would stall and then be forgotten while it was still on screen.
            .map { (region, verdict) ->
                Triple(
                    region,
                    verdict,
                    stability.isCovering(region, verdict.score, level.threshold, now),
                )
            }
            .filter { (_, verdict, covering) -> covering && verdict.matchesCategories(categories) }
            .map { (region, verdict, _) -> Finding(region, verdict) }

        Scan(
            findings = findings,
            regionsExamined = verdicts.size,
            wholeFrame = proposals.isEmpty(),
            worst = verdicts.maxByOrNull { it.second.score }?.second,
        )
    }

    /**
     * Crops a region out of the frame and sizes it for the model.
     *
     * Scaled up when the crop is small, which thumbnails usually are. The labeller works from a
     * fixed input size, and handing it a 60-pixel crop means it sees a 60-pixel crop stretched by
     * something that is not trying to preserve what the classifier needs. Doing the resize here,
     * with a filter, measurably changes what comes back.
     */
    private fun Bitmap.cropTo(region: ScreenRegions.Region): Bitmap {
        val left = (region.left * width).roundToInt().coerceIn(0, width - 1)
        val top = (region.top * height).roundToInt().coerceIn(0, height - 1)
        val right = (region.right * width).roundToInt().coerceIn(left + 1, width)
        val bottom = (region.bottom * height).roundToInt().coerceIn(top + 1, height)

        val cropWidth = right - left
        val cropHeight = bottom - top
        if (cropWidth >= width && cropHeight >= height) return this

        val crop = Bitmap.createBitmap(this, left, top, cropWidth, cropHeight)
        val longest = maxOf(cropWidth, cropHeight)
        if (longest >= MODEL_INPUT) return crop

        val factor = MODEL_INPUT.toFloat() / longest
        val scaled = crop.scale(
            (cropWidth * factor).roundToInt().coerceAtLeast(1),
            (cropHeight * factor).roundToInt().coerceAtLeast(1),
            filter = true,
        )
        if (scaled !== crop) crop.recycle()
        return scaled
    }

    private companion object {
        /** How much of a proposed region has to sit under an existing cover to be skipped. */
        const val ALREADY_COVERED = 0.6f

        /** What the bundled labeller wants to be fed. Smaller crops are scaled up to meet it. */
        const val MODEL_INPUT = 224
    }
}
