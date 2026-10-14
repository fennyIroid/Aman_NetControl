package com.example.childsafetyimpl.platform.content

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fusion rule, tested without a device.
 *
 * The behaviour that matters is the false positive the old skin-only scorer produced: a portrait
 * or a beach photo has a high skin ratio and is entirely harmless. Pixel evidence must not be able
 * to flag an image on its own.
 */
class ImageFusionTest {

    private val classifier = ImageSafetyClassifier(MlKitImageLabeler())

    private fun label(text: String, confidence: Float) =
        MlKitImageLabeler.Label(text, confidence)

    @Test
    fun `a portrait with high skin and no exposure label is not flagged`() {
        // The exact case that used to be covered: lots of skin, nothing suggesting exposure.
        val fusion = classifier.fuse(
            labels = listOf(label("Selfie", 0.82f), label("Smile", 0.30f)),
            heuristicScore = 0.9f,
        )
        assertFalse(
            "A portrait must not be covered on skin proportion alone",
            fusion.fused >= ImageSafetyClassifier.UNSAFE_THRESHOLD,
        )
    }

    @Test
    fun `a landscape with benign labels stays well clear of the threshold`() {
        val fusion = classifier.fuse(
            labels = listOf(
                label("Mountain", 0.94f),
                label("Sky", 0.90f),
                label("Plant", 0.71f),
            ),
            heuristicScore = 0.35f,
        )
        assertTrue(fusion.fused < ImageSafetyClassifier.BORDERLINE_THRESHOLD)
    }

    @Test
    fun `exposure labels plus corroborating pixels do flag`() {
        val fusion = classifier.fuse(
            labels = listOf(
                label("Swimwear", 0.88f),
                label("Undergarment", 0.72f),
                label("Abdomen", 0.66f),
            ),
            heuristicScore = 0.75f,
        )
        assertTrue(fusion.corroborated)
        assertTrue(
            "Model and pixels agreeing should clear the threshold",
            fusion.fused >= ImageSafetyClassifier.UNSAFE_THRESHOLD,
        )
    }

    @Test
    fun `gambling is flagged on the label alone, with no pixel evidence`() {
        val fusion = classifier.fuse(
            labels = listOf(label("Casino", 0.9f)),
            heuristicScore = 0.0f,
        )
        assertFalse(fusion.corroborated)
        assertTrue(fusion.fused >= ImageSafetyClassifier.UNSAFE_THRESHOLD)
    }

    @Test
    fun `benign labels pull an exposure score back down`() {
        val withoutBenign = classifier.fuse(
            labels = listOf(label("Skin", 0.7f), label("Flesh", 0.6f)),
            heuristicScore = 0.5f,
        )
        val withBenign = classifier.fuse(
            labels = listOf(
                label("Skin", 0.7f),
                label("Flesh", 0.6f),
                label("Comics", 0.85f),
                label("Cartoon", 0.7f),
            ),
            heuristicScore = 0.5f,
        )
        assertTrue(
            "Counter-evidence must reduce the score",
            withBenign.fused < withoutBenign.fused,
        )
    }

    @Test
    fun `body labels on a baby, a cat or a clothed person do not cover it`() {
        // "Skin" and "Flesh" describe a body, not a state of dress, and the labeller reports them
        // on faces, fur and landscapes — on the device, "Flesh 0.49" on a mountain at dusk.
        val cases = mapOf(
            "baby" to listOf(label("Skin", 0.9f), label("Flesh", 0.8f), label("Baby", 0.9f)),
            "cat" to listOf(label("Cat", 0.92f), label("Flesh", 0.6f), label("Skin", 0.5f)),
            "clothed woman" to listOf(label("Skin", 0.8f), label("Jeans", 0.8f), label("Jacket", 0.7f), label("Model", 0.7f)),
            "portrait" to listOf(label("Skin", 0.85f), label("Flesh", 0.7f), label("Selfie", 0.8f), label("Lipstick", 0.6f)),
            "landscape" to listOf(label("Sky", 0.81f), label("Rock", 0.63f), label("Flesh", 0.49f)),
        )
        cases.forEach { (name, labels) ->
            val fusion = classifier.fuse(labels = labels, heuristicScore = 0.8f)
            assertTrue(
                "$name scored ${fusion.fused}",
                fusion.fused < ProtectionLevel.BALANCED.threshold,
            )
        }
    }

    @Test
    fun `body labels strong together still cover nudity no clothing label describes`() {
        val fusion = classifier.fuse(
            labels = listOf(
                label("Skin", 0.92f),
                label("Flesh", 0.88f),
                label("Muscle", 0.7f),
                label("Abdomen", 0.65f),
            ),
            heuristicScore = 0.8f,
        )
        assertTrue(fusion.corroborated)
        assertTrue("scored ${fusion.fused}", fusion.fused >= ProtectionLevel.BALANCED.threshold)
    }

    @Test
    fun `a pet in the picture cannot talk a swimwear photograph down`() {
        val fusion = classifier.fuse(
            labels = listOf(label("Swimwear", 0.85f), label("Skin", 0.7f), label("Dog", 0.8f)),
            heuristicScore = 0.6f,
        )
        assertTrue("scored ${fusion.fused}", fusion.fused >= ProtectionLevel.BALANCED.threshold)
    }

    @Test
    fun `with no labels at all the pixel score is damped rather than trusted`() {
        val fusion = classifier.fuse(labels = emptyList(), heuristicScore = 1.0f)
        assertTrue("The heuristic alone must not reach full confidence", fusion.fused < 1.0f)
        assertTrue(fusion.kinds.isEmpty())
    }

    /**
     * Driven on a Galaxy M36: a peach home-screen wallpaper, which the labeller had nothing to say
     * about, scored 0.49 on skin tone alone and was covered.
     */
    @Test
    fun `pixel evidence alone never covers at any strictness`() {
        val fusion = classifier.fuse(labels = emptyList(), heuristicScore = 1.0f)
        ProtectionLevel.entries.forEach { level ->
            assertTrue(
                "${level.name}: pixels alone scored ${fusion.fused}, threshold ${level.threshold}",
                fusion.fused < level.threshold,
            )
        }
    }

    @Test
    fun `body labels the pixels agree with still cover when nothing else is in the picture`() {
        // The case the ceiling must not cost: no clothing label, strong body labels, and a pixel
        // reading that agrees. This is what the labeller says about nudity.
        val fusion = classifier.fuse(
            labels = listOf(label("Flesh", 0.92f), label("Skin", 0.9f), label("Abdomen", 0.8f)),
            heuristicScore = 0.8f,
        )
        assertTrue("scored ${fusion.fused}", fusion.fused >= ProtectionLevel.BALANCED.threshold)
    }

    @Test
    fun `the risk table covers exposure and stays auditable`() {
        assertTrue(SafetyLabelRisk.mappedLabelCount > 20)
        assertTrue(SafetyLabelRisk.exposureLabelCount >= 8)
        // No single label may be strong enough to flag an image by itself at full confidence,
        // except the standalone categories that do not depend on a body being present.
        listOf("Swimwear", "Skin", "Flesh", "Muscle", "Bathing").forEach { text ->
            val weight = SafetyLabelRisk.weightFor(text)?.weight ?: 0f
            assertTrue(
                "$text alone should not exceed the unsafe threshold",
                weight < ImageSafetyClassifier.UNSAFE_THRESHOLD + 0.2f,
            )
        }
    }
}
