package com.example.childsafetyimpl.platform.browserimages

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.common.truth.Truth.assertThat

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoScreenerTest {

    private val clean = NsfwScores(drawings = 0f, hentai = 0f, neutral = 1f, porn = 0f, sexy = 0f)

    private class FakeClassifier(private val answer: (FloatArray) -> NsfwScores) : NsfwClassifier {
        val inputs = mutableListOf<FloatArray>()
        override fun classify(input: FloatArray): NsfwScores {
            inputs += input.copyOf()
            return answer(input)
        }
        override fun close() = Unit
    }

    /** 1080×2400 capture: flat grey everywhere, a noisy reddish photo in the top-left quarter. */
    private fun capture(): Bitmap {
        val width = 1080
        val height = 2400
        val pixels = IntArray(width * height) { 0xFF303134.toInt() }
        for (y in 300 until 800) for (x in 0 until 500) {
            val noise = (x * 31 + y * 17) % 64
            pixels[y * width + x] = (0xFF shl 24) or ((190 + noise) shl 16) or (noise shl 8) or noise
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private val window = Rect(0, 0, 1080, 2400)

    @Test
    fun `a loaded photo is scored under its own id, from the pixels under it`() {
        val classifier = FakeClassifier { clean }

        val results = PhotoScreener(classifier).screen(capture(), listOf(Photo(42L, Rect(0, 300, 500, 800))), window)

        assertThat(results).containsExactly(PhotoScreener.Result.Scored(42L, clean))
        val input = classifier.inputs.single()
        val red = (0 until input.size / 3).map { input[it * 3] }.average()
        val green = (0 until input.size / 3).map { input[it * 3 + 1] }.average()
        assertThat(red).isGreaterThan(green)
    }

    @Test
    fun `a placeholder is reported not loaded and never reaches the classifier`() {
        val classifier = FakeClassifier { clean }

        val results = PhotoScreener(classifier).screen(capture(), listOf(Photo(7L, Rect(540, 300, 1040, 800))), window)

        assertThat(results).containsExactly(PhotoScreener.Result.NotLoaded(7L))
        assertThat(classifier.inputs).isEmpty()
    }

    @Test
    fun `a photo outside the capture is left for later`() {
        val classifier = FakeClassifier { clean }

        val results = PhotoScreener(classifier).screen(capture(), listOf(Photo(9L, Rect(0, 2600, 500, 2900))), window)

        assertThat(results).containsExactly(PhotoScreener.Result.NotLoaded(9L))
    }

    private class FakeDrugClassifier(private val answer: () -> Float) : DrugClassifier {
        val inputs = mutableListOf<FloatArray>()
        override fun drugScore(input: FloatArray): Float {
            inputs += input.copyOf()
            return answer()
        }
        override fun close() = Unit
    }

    @Test
    fun `with a drug model each photo is scored for drugs too, from its own pixels`() {
        val drugs = FakeDrugClassifier { 0.9f }

        val results = PhotoScreener(FakeClassifier { clean }, drugs)
            .screen(capture(), listOf(Photo(42L, Rect(0, 300, 500, 800))), window)

        assertThat(results).containsExactly(PhotoScreener.Result.Scored(42L, clean, drugScore = 0.9f))
        val input = drugs.inputs.single()
        assertThat(input.size).isEqualTo(ModelInput.CLIP_SIDE * ModelInput.CLIP_SIDE * 3)
        val red = (0 until input.size / 3).map { input[it * 3] }.average()
        val green = (0 until input.size / 3).map { input[it * 3 + 1] }.average()
        assertThat(red).isGreaterThan(green)
    }

    @Test
    fun `without a drug model a photo has no drug score`() {
        val results = PhotoScreener(FakeClassifier { clean })
            .screen(capture(), listOf(Photo(42L, Rect(0, 300, 500, 800))), window)

        assertThat(results).containsExactly(PhotoScreener.Result.Scored(42L, clean, drugScore = null))
    }

    @Test
    fun `a drug model that throws fails the photo, which stays covered`() {
        val results = PhotoScreener(FakeClassifier { clean }, FakeDrugClassifier { error("delegate crashed") })
            .screen(capture(), listOf(Photo(3L, Rect(0, 300, 500, 800))), window)

        assertThat(results).containsExactly(PhotoScreener.Result.Failed(3L))
    }

    @Test
    fun `the drug model sees the middle of a photo at its own proportions`() {
        // It was trained on centre crops; a squashed photo reads as something else.
        assertThat(ModelInput.centreSquare(Rect(0, 0, 400, 200))).isEqualTo(Rect(100, 0, 300, 200))
        assertThat(ModelInput.centreSquare(Rect(10, 20, 110, 320))).isEqualTo(Rect(10, 120, 110, 220))
    }

    @Test
    fun `a classifier that throws fails that photo only`() {
        val classifier = FakeClassifier { error("delegate crashed") }

        val results = PhotoScreener(classifier).screen(capture(), listOf(Photo(3L, Rect(0, 300, 500, 800))), window)

        assertThat(results).containsExactly(PhotoScreener.Result.Failed(3L))
    }
}
