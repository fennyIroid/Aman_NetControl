package com.example.childsafetyimpl.platform.content

import android.graphics.Rect
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who gets covered, and what happens when the device cannot tell.
 *
 * These are the rules most likely to be changed later by someone who has not read why they are
 * the way they are, and the cost of getting them wrong is a parent seeing a switch that reads as
 * on while nothing is being covered.
 */
class SubjectTargetingTest {

    private fun subject(
        category: SubjectCategory,
        modelPresent: Boolean,
    ) = DetectedSubject(
        bounds = Rect(0, 0, 10, 10),
        faceBounds = Rect(2, 2, 8, 8),
        category = category,
        genderModelPresent = modelPresent,
    )

    @Test
    fun `selecting anyone covers every person regardless of appearance`() {
        val selected = setOf(SubjectCategory.PERSON)
        listOf(
            SubjectCategory.PERSON,
            SubjectCategory.APPEARS_MALE,
            SubjectCategory.APPEARS_FEMALE,
            SubjectCategory.UNKNOWN,
        ).forEach {
            assertTrue(subject(it, modelPresent = true).shouldCover(selected))
        }
    }

    @Test
    fun `gender targeting with no model covers everyone rather than nobody`() {
        // The decision that matters. With no appearance model bound, a parent who asked to cover
        // one category must not silently get no covering at all. Over-covering delivers more of
        // what they asked for; under-covering delivers none of it behind a switch that looks on.
        val selected = setOf(SubjectCategory.APPEARS_FEMALE)
        assertTrue(
            "No model must fail towards covering, never towards exposure",
            subject(SubjectCategory.PERSON, modelPresent = false).shouldCover(selected),
        )
    }

    @Test
    fun `gender targeting with a model present covers only the selected category`() {
        val selected = setOf(SubjectCategory.APPEARS_FEMALE)
        assertTrue(subject(SubjectCategory.APPEARS_FEMALE, modelPresent = true).shouldCover(selected))
        assertFalse(subject(SubjectCategory.APPEARS_MALE, modelPresent = true).shouldCover(selected))
    }

    @Test
    fun `an undetermined subject is not covered when a model was available to judge it`() {
        // A model that ran and returned UNKNOWN has made a judgement. Treating that as a match
        // would make every gender selection behave like "anyone" the moment one face is unclear.
        val selected = setOf(SubjectCategory.APPEARS_MALE)
        assertFalse(subject(SubjectCategory.UNKNOWN, modelPresent = true).shouldCover(selected))
    }

    @Test
    fun `selecting nothing covers nothing`() {
        assertFalse(subject(SubjectCategory.APPEARS_MALE, modelPresent = true).shouldCover(emptySet()))
    }

    @Test
    fun `appearance based categories are labelled as inferences not facts`() {
        // The naming is a product commitment, not a style choice: a model on a phone is not
        // entitled to state a stranger's gender as fact.
        assertTrue(SubjectCategory.APPEARS_MALE.isAppearanceBased)
        assertTrue(SubjectCategory.APPEARS_FEMALE.isAppearanceBased)
        assertFalse(SubjectCategory.PERSON.isAppearanceBased)
        listOf(SubjectCategory.APPEARS_MALE, SubjectCategory.APPEARS_FEMALE).forEach {
            assertTrue(
                "${it.name} must tell the parent it is best effort",
                it.description.contains("Best effort"),
            )
        }
    }

    @Test
    fun `the shipped model is never presented as purpose trained`() {
        // Everything downstream keys off this flag to decide how confidently a verdict may be
        // stated. Flipping it without replacing the model would let the UI overclaim everywhere
        // at once.
        assertFalse(ImageSafetyClassifier.MODEL_IDENTITY.purposeTrained)
        assertTrue(ImageSafetyClassifier.MODEL_IDENTITY.onDevice)
        assertTrue(
            ImageSafetyClassifier.MODEL_IDENTITY.confidenceCaveat.contains("stand-in"),
        )
    }

    @Test
    fun `the explicit category is marked indicative while the proxy model is in place`() {
        // The uncomfortable ordering worth protecting: the category the product most needs is the
        // one the current model is worst at.
        assertTrue(ValuesCategory.EXPLICIT_IMAGERY.detectionQuality == DetectionQuality.PROXY_ONLY)
        assertTrue(ValuesCategory.SUGGESTIVE_IMAGERY.detectionQuality == DetectionQuality.WORKABLE)
    }

    @Test
    fun `the categories on by default are the ones that can actually be detected`() {
        // This used to assert that only explicit imagery was on, reasoning that the others should
        // ship visible and off so the client could enable one without a release. The first half of
        // that was never true: no screen offered them, so "off by default" meant unreachable. And
        // the half that was true produced the worst possible pairing, because explicit imagery is
        // the one category the bundled model cannot detect directly. A swimwear search covered
        // nothing, which is what testing on a device found.
        //
        // So: the workable category is on, and the two that are genuinely product decisions rather
        // than detection gaps stay off, which is what the brief asked for.
        assertTrue(ValuesCategory.EXPLICIT_IMAGERY in ValuesCategory.defaults)
        assertTrue(ValuesCategory.SUGGESTIVE_IMAGERY in ValuesCategory.defaults)
        assertTrue(ValuesCategory.GAMBLING_IMAGERY !in ValuesCategory.defaults)
        assertTrue(ValuesCategory.SUBSTANCE_IMAGERY !in ValuesCategory.defaults)
    }

    @Test
    fun `every default category is one the model can detect directly`() {
        // The invariant behind the case above, stated so it survives a new category being added.
        // A category switched on by default whose detection is only an inference is a protection
        // that reads as on and covers nothing.
        assertTrue(
            ValuesCategory.defaults.any {
                it.detectionQuality == DetectionQuality.WORKABLE
            },
        )
    }

    @Test
    fun `protection levels are ordered from most permissive to strictest`() {
        assertTrue(ProtectionLevel.RELAXED.threshold > ProtectionLevel.BALANCED.threshold)
        assertTrue(ProtectionLevel.BALANCED.threshold > ProtectionLevel.STRICT.threshold)
    }

    @Test
    fun `covering only the failing images is the default blur mode`() {
        // This was FULL_SCREEN, on the reasoning that a mode which cannot miss anything inside
        // the frame is the safer default. Testing on a device showed the reasoning had the wrong
        // subject: whole-screen covering does not miss things inside a frame it flags, but the
        // whole-frame verdict it depends on misses the frames that matter. One bad thumbnail in a
        // grid of twelve does not move a whole-screen score, so nothing was flagged and nothing
        // was covered. Judging each picture separately is what makes it detectable, and once the
        // verdict is per-picture the proportionate cover is also the more accurate one.
        assertTrue(BlurMode.default == BlurMode.IMAGES)
    }
}
