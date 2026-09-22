package com.example.childsafetyimpl.platform.content

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * The bundled on-device model.
 *
 * An important finding sits behind this class: **Google ships no on-device explicit-content
 * classifier.** SafeSearch adult detection is a Cloud Vision feature, and sending a child's screen
 * or browsing images to a server is exactly what the privacy rule here rules out. What is on
 * device is ML Kit's general image labeller, a real trained network bundled into the APK, which
 * returns 430 semantic labels with confidences.
 *
 * So this is a purpose-built model used as a proxy rather than a purpose-built NSFW model. That
 * distinction is recorded against the capability instead of being papered over: the labeller can
 * say "Swimwear at 0.81" with real confidence, and it cannot say "explicit".
 *
 * Everything runs locally. The bundled model means no download at first use and no network call
 * at classification time.
 */
@Singleton
class MlKitImageLabeler @Inject constructor() {

    data class Label(val text: String, val confidence: Float)

    private val labeler by lazy {
        ImageLabeling.getClient(
            ImageLabelerOptions.Builder()
                // Low, because the risk map weighs confidence itself. Discarding a 0.4 "Swimwear"
                // before it can be combined with other evidence would lose the signal that matters.
                .setConfidenceThreshold(0.35f)
                .build()
        )
    }

    /**
     * Labels a bitmap. Returns an empty list rather than throwing: a labelling failure must
     * degrade to "no semantic evidence" and let the pixel heuristic stand alone, never break the
     * page load or the capture loop that called it.
     */
    suspend fun label(bitmap: Bitmap): List<Label> = suspendCancellableCoroutine { continuation ->
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            labeler.process(image)
                .addOnSuccessListener { labels ->
                    if (continuation.isActive) {
                        continuation.resume(labels.map { Label(it.text, it.confidence) })
                    }
                }
                .addOnFailureListener {
                    if (continuation.isActive) continuation.resume(emptyList())
                }
        } catch (_: Exception) {
            if (continuation.isActive) continuation.resume(emptyList())
        }
    }
}

/**
 * Maps ML Kit's general labels onto child-safety risk.
 *
 * Kept as data so the mapping can be reviewed and argued with, which is the whole point: a table
 * someone can read is auditable in a way that a black-box score is not. The weights below are
 * calibrated so that no single label can flag an image on its own; corroboration is required.
 */
object SafetyLabelRisk {

    enum class RiskKind(val label: String) {
        EXPOSURE("Body exposure"),
        ADULT_CONTEXT("Adult setting"),
        GAMBLING("Gambling"),
        SUBSTANCE("Alcohol"),
        BENIGN("Clearly benign"),

        /**
         * Labels that describe the medium rather than what is in it.
         *
         * "Screenshot" and "Web page" are true of every frame a screen capture produces, including
         * the frame showing the thing this app exists to cover. They were counted as evidence of
         * safety, which meant scoring a browser screen subtracted a large constant from the verdict
         * and the feature was close to blind wherever it mattered most. They are kept in the table
         * because knowing the model saw them is useful; they contribute nothing to the score.
         */
        MEDIUM("Describes the medium"),
    }

    data class Weighted(
        val kind: RiskKind,
        val weight: Float,
        /**
         * An exposure label that describes a body rather than a state of dress.
         *
         * "Skin", "Flesh" and "Muscle" are true of a face, a baby, a cat's fur and — measured on
         * the device — a mountain at dusk, so on their own they are not evidence of exposure at
         * all, and counting them in full covered exactly those pictures. They count in full next to
         * a clothing label ("Swimwear", "Lingerie"); without one they count for part, and a picture
         * clearly of something else cancels them. See `ImageSafetyClassifier.fuse`.
         */
        val bodyOnly: Boolean = false,
    )

    /**
     * Exposure labels are the ones that matter most, and they are deliberately weak individually.
     * "Skin" and "Flesh" fire on any close-up of a face; only their combination with clothing or
     * setting labels moves the score meaningfully.
     */
    private val map: Map<String, Weighted> = mapOf(
        "Swimwear" to Weighted(RiskKind.EXPOSURE, 0.55f),
        "Wetsuit" to Weighted(RiskKind.EXPOSURE, 0.20f, bodyOnly = true),
        "Trunk" to Weighted(RiskKind.EXPOSURE, 0.30f, bodyOnly = true),
        "Flesh" to Weighted(RiskKind.EXPOSURE, 0.40f, bodyOnly = true),
        "Skin" to Weighted(RiskKind.EXPOSURE, 0.30f, bodyOnly = true),
        "Muscle" to Weighted(RiskKind.EXPOSURE, 0.30f, bodyOnly = true),
        "Bathing" to Weighted(RiskKind.EXPOSURE, 0.35f, bodyOnly = true),
        "Undergarment" to Weighted(RiskKind.EXPOSURE, 0.60f),
        "Lingerie" to Weighted(RiskKind.EXPOSURE, 0.65f),
        "Abdomen" to Weighted(RiskKind.EXPOSURE, 0.45f, bodyOnly = true),
        "Leggings" to Weighted(RiskKind.EXPOSURE, 0.15f, bodyOnly = true),
        "Tights" to Weighted(RiskKind.EXPOSURE, 0.15f, bodyOnly = true),

        "Bedroom" to Weighted(RiskKind.ADULT_CONTEXT, 0.20f),
        "Bathroom" to Weighted(RiskKind.ADULT_CONTEXT, 0.20f),
        "Nightclub" to Weighted(RiskKind.ADULT_CONTEXT, 0.30f),
        "Bar" to Weighted(RiskKind.ADULT_CONTEXT, 0.20f),
        "Selfie" to Weighted(RiskKind.ADULT_CONTEXT, 0.10f),
        "Model" to Weighted(RiskKind.ADULT_CONTEXT, 0.15f),
        "Lipstick" to Weighted(RiskKind.ADULT_CONTEXT, 0.10f),

        "Casino" to Weighted(RiskKind.GAMBLING, 0.70f),

        "Alcohol" to Weighted(RiskKind.SUBSTANCE, 0.45f),
        "Wine" to Weighted(RiskKind.SUBSTANCE, 0.35f),

        // Counter-evidence. A page of comics or a plate of food that happens to score high on
        // skin tone should be pulled back down rather than flagged.
        "Comics" to Weighted(RiskKind.BENIGN, 0.45f),
        "Cartoon" to Weighted(RiskKind.BENIGN, 0.45f),
        "Text" to Weighted(RiskKind.BENIGN, 0.40f),
        "Screenshot" to Weighted(RiskKind.MEDIUM, 0f),
        "Web page" to Weighted(RiskKind.MEDIUM, 0f),
        "Font" to Weighted(RiskKind.MEDIUM, 0f),
        "Food" to Weighted(RiskKind.BENIGN, 0.40f),
        "Cuisine" to Weighted(RiskKind.BENIGN, 0.35f),
        "Plant" to Weighted(RiskKind.BENIGN, 0.35f),
        "Flower" to Weighted(RiskKind.BENIGN, 0.35f),
        "Vehicle" to Weighted(RiskKind.BENIGN, 0.35f),
        "Car" to Weighted(RiskKind.BENIGN, 0.35f),
        "Building" to Weighted(RiskKind.BENIGN, 0.30f),
        "Mountain" to Weighted(RiskKind.BENIGN, 0.35f),
        "Sky" to Weighted(RiskKind.BENIGN, 0.25f),
        "Dog" to Weighted(RiskKind.BENIGN, 0.35f),
        "Cat" to Weighted(RiskKind.BENIGN, 0.35f),
        // What the body labels fire on when nothing is exposed: babies, pets and people dressed
        // for the street. Enough to cancel body labels standing alone, never a clothing label.
        "Baby" to Weighted(RiskKind.BENIGN, 0.45f),
        "Bird" to Weighted(RiskKind.BENIGN, 0.35f),
        "Horse" to Weighted(RiskKind.BENIGN, 0.35f),
        "Pet" to Weighted(RiskKind.BENIGN, 0.35f),
        "Jacket" to Weighted(RiskKind.BENIGN, 0.30f),
        "Jeans" to Weighted(RiskKind.BENIGN, 0.30f),
        "Dress" to Weighted(RiskKind.BENIGN, 0.25f),
        "Toy" to Weighted(RiskKind.BENIGN, 0.35f),
        "School" to Weighted(RiskKind.BENIGN, 0.30f),
    )

    fun weightFor(label: String): Weighted? = map[label]

    val mappedLabelCount: Int get() = map.size

    val exposureLabelCount: Int
        get() = map.values.count { it.kind == RiskKind.EXPOSURE }

    /**
     * Labels that must never move a score.
     *
     * Exposed so a test can hold the line: anything describing the medium a frame arrived in is
     * not evidence about what the frame contains, in either direction.
     */
    val mediumLabels: Set<String>
        get() = map.filterValues { it.kind == RiskKind.MEDIUM }.keys
}
