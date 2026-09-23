package com.example.childsafetyimpl.platform.browserimages

import com.google.common.truth.Truth.assertThat

import org.junit.Test

class TfliteNsfwClassifierTest {

    @Test
    fun `model outputs map to scores in the model's label order`() {
        // drawings, hentai, neutral, porn, sexy — the order GantMan's predict.py labels them in.
        val scores = TfliteNsfwClassifier.scoresFrom(floatArrayOf(0.1f, 0.2f, 0.3f, 0.15f, 0.25f))
        assertThat(scores).isEqualTo(
            NsfwScores(drawings = 0.1f, hentai = 0.2f, neutral = 0.3f, porn = 0.15f, sexy = 0.25f),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an output of the wrong size is refused rather than misread`() {
        TfliteNsfwClassifier.scoresFrom(floatArrayOf(0.5f, 0.5f))
    }
}
