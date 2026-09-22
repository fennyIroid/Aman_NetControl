package com.example.childsafetyimpl.platform.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What happens when a classifier built for this job is bundled, and what happens while none is.
 *
 * The slot exists because everything upstream of it is honest about the model being a stand-in,
 * and that honesty is worth nothing if dropping a real model in does not actually change the
 * verdicts — or, worse, changes them in the wrong direction. The cases here are the ways that
 * could go wrong.
 */
class ContentModelSlotTest {

    private val classifier = ImageSafetyClassifier(MlKitImageLabeler())

    @Test
    fun `a build with no model bundled never claims one`() {
        // Rule 11, at the only place it can be enforced rather than asserted. Every screen that
        // prints a caveat reads this.
        assertFalse(PurposeTrainedLabeler.NotBundled.ready)
        assertFalse(PurposeTrainedLabeler.NotBundled.identity.purposeTrained)
        assertFalse(classifier.identity.purposeTrained)
    }

    @Test
    fun `a bundled classifier decides, and a stray proxy label cannot talk it down`() {
        // The outcome that would make the whole exercise pointless: bundling a real model and
        // having a general labeller's opinion that the picture contains a dog pull the verdict
        // back under the threshold. The proxies raise and never clear.
        val trained = PurposeTrainedLabeler.Explicitness(
            score = 0.86f,
            category = ValuesCategory.EXPLICIT_IMAGERY,
            evidence = listOf(MlKitImageLabeler.Label("porn", 0.86f)),
        )
        val fusion = classifier.fuse(
            labels = listOf(
                MlKitImageLabeler.Label("Dog", 0.7f),
                MlKitImageLabeler.Label("Comics", 0.6f),
                MlKitImageLabeler.Label("Cartoon", 0.6f),
            ),
            heuristicScore = 0f,
            trained = trained,
        )

        assertEquals(0.86f, fusion.fused, 0.0001f)
    }

    @Test
    fun `the proxy can still raise a verdict the bundled classifier is unsure about`() {
        // The other direction, and the reason the proxy is damped rather than switched off. A
        // content classifier has never heard of gambling or alcohol, and bundling one must not
        // silently turn those categories off. Rule 12: fail towards covering.
        val unsure = PurposeTrainedLabeler.Explicitness(
            score = 0.05f,
            category = ValuesCategory.EXPLICIT_IMAGERY,
            evidence = listOf(MlKitImageLabeler.Label("neutral", 0.95f)),
        )
        val fusion = classifier.fuse(
            labels = listOf(MlKitImageLabeler.Label("Casino", 0.9f)),
            heuristicScore = 0f,
            trained = unsure,
        )

        assertTrue("the proxy was silenced: ${fusion.fused}", fusion.fused > unsure.score)
    }

    @Test
    fun `a trained verdict resolves its category without inferring anything`() {
        // The proxy can only reach ValuesCategory.EXPLICIT_IMAGERY by inference from exposure
        // labels, which is why the floor for doing so is set where it is. A model that reports the
        // category directly does not infer, and a parent who asked only for suggestive imagery to
        // be covered has not asked for explicit imagery to be shown.
        val verdict = verdictWith(trainedCategory = ValuesCategory.EXPLICIT_IMAGERY)

        assertTrue(ValuesCategory.EXPLICIT_IMAGERY in verdict.categories)
        assertTrue(ValuesCategory.SUGGESTIVE_IMAGERY in verdict.categories)
        assertTrue(verdict.matchesCategories(setOf(ValuesCategory.SUGGESTIVE_IMAGERY)))
    }

    @Test
    fun `a trained verdict still only covers the categories a parent switched on`() {
        val verdict = verdictWith(trainedCategory = ValuesCategory.SUGGESTIVE_IMAGERY)

        assertFalse(verdict.matchesCategories(setOf(ValuesCategory.GAMBLING_IMAGERY)))
    }

    // --- reading a model's class names -----------------------------------------------------------

    @Test
    fun `class names the table cannot read are not reported as a clean picture`() {
        // A model bundled without TFLite metadata loads and returns its classes as "0", "1", "2".
        // Scoring that as zero would be a model nobody can read clearing every picture on screen,
        // which is rule 1 in the place it would do the most damage.
        assertNull(
            NsfwClasses.read(
                listOf(
                    MlKitImageLabeler.Label("0", 0.9f),
                    MlKitImageLabeler.Label("1", 0.1f),
                ),
            ),
        )
    }

    @Test
    fun `a model that says the picture is safe is read as saying so`() {
        // The counterpart. "neutral 0.98" is a real answer and must not be confused with the
        // unreadable case above, or a bundled model would never clear anything.
        val read = NsfwClasses.read(
            listOf(
                MlKitImageLabeler.Label("neutral", 0.98f),
                MlKitImageLabeler.Label("porn", 0.01f),
            ),
        )

        assertEquals(0.01f, read?.score ?: -1f, 0.0001f)
    }

    @Test
    fun `explicit and suggestive confidences are never added together`() {
        // A model that splits one photograph across two adjacent classes would otherwise have its
        // uncertainty summed into a certainty it never expressed.
        val read = NsfwClasses.read(
            listOf(
                MlKitImageLabeler.Label("porn", 0.45f),
                MlKitImageLabeler.Label("sexy", 0.40f),
            ),
        )

        assertEquals(0.45f, read?.score ?: -1f, 0.0001f)
        assertEquals(ValuesCategory.EXPLICIT_IMAGERY, read?.category)
    }

    @Test
    fun `the class table and the score agree about what each name means`() {
        // Held so the table stays the readable, arguable thing it is meant to be: a name added to
        // one half of it and not the other is a class that means something to nobody.
        NsfwClasses.known.forEach { name ->
            val read = NsfwClasses.read(listOf(MlKitImageLabeler.Label(name, 0.9f)))
            val expected = when {
                NsfwClasses.meansExplicit(name) -> ValuesCategory.EXPLICIT_IMAGERY
                NsfwClasses.meansSuggestive(name) -> ValuesCategory.SUGGESTIVE_IMAGERY
                else -> null
            }
            if (expected != null) {
                assertEquals("$name read as the wrong thing", expected, read?.category)
                assertEquals("$name lost its confidence", 0.9f, read?.score ?: -1f, 0.0001f)
            } else {
                assertEquals("$name scored as unsafe", 0f, read?.score ?: -1f, 0.0001f)
            }
        }
    }

    private fun verdictWith(trainedCategory: ValuesCategory) = ImageSafetyClassifier.Verdict(
        score = 0.8f,
        skinRatio = 0f,
        largestRegionRatio = 0f,
        edgeDensity = 0f,
        analysedPixels = 0,
        durationMillis = 0L,
        explanation = "",
        trainedCategory = trainedCategory,
    )
}
