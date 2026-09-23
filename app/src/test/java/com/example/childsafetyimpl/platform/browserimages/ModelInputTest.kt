package com.example.childsafetyimpl.platform.browserimages

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ModelInputTest {

    @Test
    fun `pixels become RGB floats between 0 and 1, in row order`() {
        val pixels = intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFFFFFF.toInt())
        val input = FloatArray(4 * 3)

        ModelInput.fill(pixels, input)

        assertThat(input.toList()).containsExactly(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f,
            1f, 1f, 1f,
        ).inOrder()
    }

    @Test
    fun `a flat placeholder is not a loaded photo`() {
        // Google shows a solid grey tile until the photo arrives. Scoring that tile clean
        // would reveal the photo the moment it loads.
        val grey = IntArray(224 * 224) { 0xFF303134.toInt() }
        assertThat(ModelInput.looksLoaded(grey)).isFalse()
    }

    @Test
    fun `a photo with detail in it is loaded`() {
        val detail = IntArray(224 * 224) { index ->
            val v = (index * 37) % 256
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        assertThat(ModelInput.looksLoaded(detail)).isTrue()
    }
}
