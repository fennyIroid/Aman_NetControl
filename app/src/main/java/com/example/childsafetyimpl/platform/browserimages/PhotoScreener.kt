package com.example.childsafetyimpl.platform.browserimages

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * Crops each photo out of a capture of the browser window and scores it: for explicit content,
 * and — with a [drugClassifier] — for drugs.
 *
 * Runs on a background thread. Crops are small scaled copies, recycled as soon as they are read;
 * nothing is kept, written or logged.
 */
class PhotoScreener(
    private val classifier: NsfwClassifier,
    private val drugClassifier: DrugClassifier? = null,
) {

    sealed interface Result {
        val id: Long

        /** [drugScore] is null where no drug model ran. */
        data class Scored(override val id: Long, val scores: NsfwScores, val drugScore: Float? = null) : Result

        /** Not on screen in this capture, or still a placeholder. Try again later. */
        data class NotLoaded(override val id: Long) : Result

        /** A classifier threw. The photo stays covered. */
        data class Failed(override val id: Long) : Result
    }

    private val pixels = IntArray(ModelInput.SIDE * ModelInput.SIDE)
    private val input = FloatArray(ModelInput.SIDE * ModelInput.SIDE * 3)
    private val clipPixels = IntArray(ModelInput.CLIP_SIDE * ModelInput.CLIP_SIDE)
    private val clipInput = FloatArray(ModelInput.CLIP_SIDE * ModelInput.CLIP_SIDE * 3)

    /** [capture] is a software bitmap of the window at [windowBounds]; photo bounds are screen coordinates. */
    fun screen(capture: Bitmap, photos: List<Photo>, windowBounds: Rect): List<Result> =
        photos.map { photo -> screenOne(capture, photo, windowBounds) }

    private fun screenOne(capture: Bitmap, photo: Photo, windowBounds: Rect): Result {
        val crop = BackdropBlur.cropRect(photo.bounds, windowBounds, capture.width, capture.height)
            ?: return Result.NotLoaded(photo.id)

        // A photo cut off by the screen edge is not scored on the part that shows: the rest of it
        // could be anything.
        if (crop.width() < photo.bounds.width() * capture.width / windowBounds.width() - 1 ||
            crop.height() < photo.bounds.height() * capture.height / windowBounds.height() - 1
        ) {
            return Result.NotLoaded(photo.id)
        }

        read(capture, crop, ModelInput.SIDE, pixels)
        if (!ModelInput.looksLoaded(pixels)) return Result.NotLoaded(photo.id)

        ModelInput.fill(pixels, input)
        val scores = runCatching { classifier.classify(input) }.getOrElse { return Result.Failed(photo.id) }

        val drugScore = drugClassifier?.let { drugs ->
            read(capture, ModelInput.centreSquare(crop), ModelInput.CLIP_SIDE, clipPixels)
            ModelInput.fill(clipPixels, clipInput)
            runCatching { drugs.drugScore(clipInput) }.getOrElse { return Result.Failed(photo.id) }
        }
        return Result.Scored(photo.id, scores, drugScore)
    }

    /** [crop] of [capture], scaled to a [side]-pixel square, into [out]. */
    private fun read(capture: Bitmap, crop: Rect, side: Int, out: IntArray) {
        val scaled = Bitmap.createBitmap(capture, crop.left, crop.top, crop.width(), crop.height())
            .let { cropped ->
                val resized = Bitmap.createScaledBitmap(cropped, side, side, true)
                if (resized !== cropped && cropped !== capture) cropped.recycle()
                resized
            }
        scaled.getPixels(out, 0, side, 0, 0, side, side)
        if (scaled !== capture) scaled.recycle()
    }
}
