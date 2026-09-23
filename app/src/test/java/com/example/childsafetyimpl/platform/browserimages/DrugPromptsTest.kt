package com.example.childsafetyimpl.platform.browserimages

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DrugPromptsTest {

    /** Two drug prompts and two others, in a four-dimensional space, as the build script writes them. */
    private fun file(
        magic: String = "SSDP",
        version: Int = 1,
        vectors: List<FloatArray> = listOf(
            floatArrayOf(1f, 0f, 0f, 0f),
            floatArrayOf(0f, 1f, 0f, 0f),
            floatArrayOf(0f, 0f, 1f, 0f),
            floatArrayOf(0f, 0f, 0f, 1f),
        ),
        drugCount: Int = 2,
    ): ByteArray {
        val dimension = vectors.first().size
        return ByteBuffer.allocate(20 + vectors.size * dimension * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(magic.toByteArray(Charsets.US_ASCII))
            putInt(version)
            putInt(dimension)
            putInt(drugCount)
            putInt(vectors.size)
            vectors.forEach { vector -> vector.forEach(::putFloat) }
        }.array()
    }

    @Test
    fun `the file is read as the build script writes it`() {
        val prompts = DrugPrompts.parse(file())
        assertThat(prompts.dimension).isEqualTo(4)
        assertThat(prompts.drugCount).isEqualTo(2)
        assertThat(prompts.count).isEqualTo(4)
    }

    @Test
    fun `a photo like a drug prompt takes nearly all the drug share`() {
        val prompts = DrugPrompts.parse(file())
        assertThat(prompts.drugShare(floatArrayOf(0.9f, 0.1f, 0.1f, 0.1f))).isGreaterThan(0.99f)
    }

    @Test
    fun `a photo like another prompt takes almost none`() {
        val prompts = DrugPrompts.parse(file())
        assertThat(prompts.drugShare(floatArrayOf(0.1f, 0.1f, 0.9f, 0.1f))).isLessThan(0.01f)
    }

    @Test
    fun `the share does not depend on the embedding's length`() {
        // The image encoder's output is not normalised; only its direction counts.
        val prompts = DrugPrompts.parse(file())
        val embedding = floatArrayOf(0.5f, 0.45f, 0.1f, 0.3f)
        val longer = FloatArray(4) { embedding[it] * 37f }
        assertThat(prompts.drugShare(longer)).isWithin(1e-5f).of(prompts.drugShare(embedding))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a file of the wrong kind is refused`() {
        DrugPrompts.parse(file(magic = "NOPE"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a file of a version this app does not know is refused`() {
        DrugPrompts.parse(file(version = 2))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a truncated file is refused rather than misread`() {
        DrugPrompts.parse(file().copyOf(40))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an embedding of the wrong size is refused`() {
        DrugPrompts.parse(file()).drugShare(floatArrayOf(1f, 0f))
    }
}
