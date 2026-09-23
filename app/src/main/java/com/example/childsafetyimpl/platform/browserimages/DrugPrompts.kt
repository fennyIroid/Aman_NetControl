package com.example.childsafetyimpl.platform.browserimages

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * The text side of the drug check: descriptions of drug photos and of other photos, as MobileCLIP
 * embeddings made once by `tools/mobileclip/make_drug_prompts.py`, drug descriptions first.
 *
 * A photo's drug share is how much of the probability the drug descriptions take between them
 * when its embedding is compared with every description, the way CLIP classifies zero-shot. Pure,
 * so it is JVM-testable.
 */
class DrugPrompts private constructor(
    val dimension: Int,
    val drugCount: Int,
    private val vectors: Array<FloatArray>,
) {
    val count: Int get() = vectors.size

    /** The drug descriptions' share, 0..1, for an image embedding of [dimension] floats. */
    fun drugShare(embedding: FloatArray): Float {
        require(embedding.size == dimension) { "embedding has ${embedding.size} floats, expected $dimension" }
        var squares = 0.0
        for (value in embedding) squares += value.toDouble() * value
        val length = sqrt(squares)
        require(length > 0.0) { "the image encoder returned an empty embedding" }

        val logits = DoubleArray(count) { index ->
            var dot = 0.0
            val vector = vectors[index]
            for (i in 0 until dimension) dot += vector[i].toDouble() * embedding[i]
            LOGIT_SCALE * dot / length
        }
        val highest = logits.max()
        var total = 0.0
        var drug = 0.0
        logits.forEachIndexed { index, logit ->
            val weight = exp(logit - highest)
            total += weight
            if (index < drugCount) drug += weight
        }
        return (drug / total).toFloat()
    }

    companion object {
        /** CLIP's temperature: cosine similarities are scaled by this before the softmax. */
        const val LOGIT_SCALE = 100.0

        private const val MAGIC = "SSDP"
        private const val VERSION = 1
        private const val HEADER_BYTES = 20

        /** Read the file `make_drug_prompts.py` writes. Refuses anything else rather than misread it. */
        fun parse(bytes: ByteArray): DrugPrompts {
            require(bytes.size >= HEADER_BYTES) { "prompt file too short" }
            require(String(bytes, 0, 4, Charsets.US_ASCII) == MAGIC) { "not a drug prompt file" }
            val buffer = ByteBuffer.wrap(bytes, 4, bytes.size - 4).order(ByteOrder.LITTLE_ENDIAN)
            val version = buffer.int
            require(version == VERSION) { "prompt file version $version, expected $VERSION" }
            val dimension = buffer.int
            val drugCount = buffer.int
            val count = buffer.int
            require(dimension > 0 && drugCount in 1 until count) { "prompt file header is inconsistent" }
            require(bytes.size == HEADER_BYTES + count * dimension * 4) { "prompt file is truncated" }
            val vectors = Array(count) { FloatArray(dimension) { buffer.float } }
            return DrugPrompts(dimension, drugCount, vectors)
        }
    }
}
