package com.example.childsafetyimpl.platform.content

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.common.model.LocalModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.custom.CustomImageLabelerOptions
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * The slot a model actually built for this job sits in.
 *
 * [ContentSafetyModel] has always said that a purpose-trained classifier dropped into this
 * pipeline "changes the verdicts and changes nothing else". That was an intention with nothing
 * behind it: there was no loading path, no capability reporting that moved, and no place to put a
 * model. This is that place.
 *
 * **What ML Kit can and cannot be asked for.** The bundled labeller cannot be trained, tuned or
 * corrected — it is a fixed network returning 430 general labels, and "explicit" is not among
 * them. [SafetyLabelRisk] reads swimwear, bathing, lingerie and adult settings out of it and
 * infers the rest, which is a proxy and is recorded as one. What ML Kit *does* support is running
 * a classifier of your own: an image-classification TFLite model with metadata, bundled in the APK
 * and run through the same on-device runtime. That is what this loads, and it is the honest answer
 * to making this feature accurate.
 *
 * **Why not a vision model in the cloud.** A frame of a child's screen is the most sensitive thing
 * this app touches, and this feature looks at every frame of everything they watch. Sending that
 * to a server is not a better classifier, it is a different product with a different privacy
 * promise, and the one printed on [ContentSafetyModel] would have to be withdrawn. It is also not
 * available at this rate: covering has to happen inside the time a frame is on screen, which is
 * the measurement [BlurLatency] exists to report, and a network round trip per frame is one to two
 * orders of magnitude outside it. A hosted model has a place in this product — reviewing what was
 * flagged, tuning what is bundled — and it is not in the covering loop.
 *
 * **Nothing ships until the weights do.** No model file is bundled here: which classifier to use
 * is a licensing and accuracy decision, not a developer's, and the model's own licence travels
 * with it. Until one is added at [MODEL_ASSET] this reports [ready] as false, the classifier falls
 * back to the labeller exactly as before, and [ModelIdentity.purposeTrained] stays false all the
 * way to the screen — which is rule 11 doing precisely what it is for.
 */
interface PurposeTrainedLabeler {

    /** True only when a model is actually bundled and loaded. Nothing downstream may assume it. */
    val ready: Boolean

    /** What is in the slot, for the verdict to be traceable to it. */
    val identity: ModelIdentity

    /**
     * How explicit this image is, or null when there is no model or it could not run.
     *
     * Null rather than zero, and the distinction is the whole of rule 1: a model that did not run
     * has not found the image safe.
     */
    suspend fun evaluate(bitmap: Bitmap): Explicitness?

    /** One purpose-trained verdict: a score, what it is about, and what the model actually said. */
    data class Explicitness(
        val score: Float,
        val category: ValuesCategory,
        val evidence: List<MlKitImageLabeler.Label>,
    )

    /** The slot, empty. What every build without a bundled model uses, and what tests use. */
    object NotBundled : PurposeTrainedLabeler {
        override val ready = false
        override val identity = ModelIdentity(
            name = "None",
            version = "not bundled",
            purposeTrained = false,
            onDevice = true,
            knownLimitations = "No purpose-trained classifier is bundled in this build. " +
                "Verdicts come from the general labeller used as a proxy.",
        )

        override suspend fun evaluate(bitmap: Bitmap): Explicitness? = null
    }

    companion object {
        /**
         * Where the model goes: `app/src/main/assets/nsfw.tflite`.
         *
         * An image-classification TFLite model with TFLite Model Metadata attached, which is what
         * ML Kit's custom labelling requires to read the class names back out. A model without
         * metadata loads and then returns labels called "0", "1", "2", which [NsfwClasses]
         * recognises as nothing at all — so the build falls back to the proxy rather than acting
         * on class names it cannot read.
         */
        const val MODEL_ASSET = "nsfw.tflite"
    }
}

/**
 * Loads [PurposeTrainedLabeler.MODEL_ASSET] if it is there, and stands aside if it is not.
 *
 * The absence of a model is an ordinary state of this build and not an error, so it is detected
 * once, reported through [ready], and never retried per frame.
 */
@Singleton
class BundledNsfwLabeler @Inject constructor(
    @ApplicationContext private val context: Context,
) : PurposeTrainedLabeler {

    private val bundled: Boolean by lazy {
        runCatching {
            context.assets.list("")?.contains(PurposeTrainedLabeler.MODEL_ASSET) == true
        }.getOrDefault(false)
    }

    private val labeler by lazy {
        if (!bundled) return@lazy null
        runCatching {
            ImageLabeling.getClient(
                CustomImageLabelerOptions.Builder(
                    LocalModel.Builder()
                        .setAssetFilePath(PurposeTrainedLabeler.MODEL_ASSET)
                        .build(),
                )
                    // Every class, including the safe ones. The safe classes are what make a
                    // verdict readable — "neutral 0.97" says something a missing label does not —
                    // and NsfwClasses decides which of them mean anything.
                    .setConfidenceThreshold(0.01f)
                    .setMaxResultCount(NsfwClasses.MAX_RESULTS)
                    .build(),
            )
        }.onFailure { Log.w(TAG, "A model is bundled but would not load", it) }.getOrNull()
    }

    override val ready: Boolean get() = labeler != null

    override val identity: ModelIdentity
        get() = if (ready) {
            ModelIdentity(
                name = "Bundled content classifier",
                version = PurposeTrainedLabeler.MODEL_ASSET,
                // The one place in this app where this is allowed to be true, and only when a
                // model is genuinely loaded. See rule 11.
                purposeTrained = true,
                onDevice = true,
                knownLimitations = "Judges still frames of a screen capture. Accuracy on small " +
                    "thumbnails is lower than on full images, and it classifies pictures rather " +
                    "than reading text or understanding context.",
            )
        } else {
            PurposeTrainedLabeler.NotBundled.identity
        }

    override suspend fun evaluate(bitmap: Bitmap): PurposeTrainedLabeler.Explicitness? {
        val client = labeler ?: return null
        val labels: List<MlKitImageLabeler.Label> = suspendCancellableCoroutine { continuation ->
            try {
                client.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { result ->
                        if (continuation.isActive) {
                            continuation.resume(
                                result.map { MlKitImageLabeler.Label(it.text, it.confidence) },
                            )
                        }
                    }
                    .addOnFailureListener {
                        // Null, not an empty list: a model that failed has not cleared the image.
                        if (continuation.isActive) continuation.resume(emptyList())
                    }
            } catch (_: Exception) {
                if (continuation.isActive) continuation.resume(emptyList())
            }
        }
        if (labels.isEmpty()) return null
        return NsfwClasses.read(labels)
    }

    private companion object {
        const val TAG = "BundledNsfwLabeler"
    }
}

/**
 * What a bundled classifier's class names mean, kept as data for the same reason
 * [SafetyLabelRisk] is: a table someone can read and argue with is auditable, and a mapping buried
 * in a scoring function is not.
 *
 * The names here are the ones the widely used open NSFW classifiers emit. A different model with
 * different class names needs a line adding, not a rewrite, and a model whose names match none of
 * them reports nothing rather than guessing — which falls back to the labeller rather than to a
 * confident zero.
 */
object NsfwClasses {

    /** Class names that mean explicit content, whatever wording the model was trained with. */
    private val explicit = setOf("porn", "nsfw", "explicit", "sexual", "hentai", "nudity", "nude")

    /** Class names that mean suggestive but not explicit. */
    private val suggestive = setOf("sexy", "suggestive", "racy", "lingerie", "underwear")

    /** Class names that mean the picture is fine. Present so a verdict can say why it is low. */
    private val safe = setOf("neutral", "safe", "sfw", "drawings", "normal", "other")

    /**
     * Reads a set of class confidences as one verdict.
     *
     * Explicit and suggestive are not added together. A model that splits a nude photograph across
     * two adjacent classes would have its confidence summed into a certainty it never expressed;
     * the stronger claim stands on its own, which is what the model actually said.
     */
    fun read(labels: List<MlKitImageLabeler.Label>): PurposeTrainedLabeler.Explicitness? {
        var explicitScore = 0f
        var suggestiveScore = 0f
        var recognised = false

        labels.forEach { label ->
            val name = label.text.lowercase().trim()
            when {
                name in explicit -> {
                    explicitScore = maxOf(explicitScore, label.confidence)
                    recognised = true
                }
                name in suggestive -> {
                    suggestiveScore = maxOf(suggestiveScore, label.confidence)
                    recognised = true
                }
                name in safe -> recognised = true
            }
        }

        // None of the model's classes mean anything here. Reporting a zero would be reporting a
        // model we cannot read as having cleared the picture.
        if (!recognised) return null

        val evidence = labels.sortedByDescending { it.confidence }.take(4)
        return if (explicitScore >= suggestiveScore) {
            PurposeTrainedLabeler.Explicitness(
                score = explicitScore,
                category = ValuesCategory.EXPLICIT_IMAGERY,
                evidence = evidence,
            )
        } else {
            PurposeTrainedLabeler.Explicitness(
                score = suggestiveScore,
                category = ValuesCategory.SUGGESTIVE_IMAGERY,
                evidence = evidence,
            )
        }
    }

    /** Class names this can read, so a test can hold the table against the scoring that uses it. */
    val known: Set<String> get() = explicit + suggestive + safe

    fun meansExplicit(name: String): Boolean = name.lowercase().trim() in explicit

    fun meansSuggestive(name: String): Boolean = name.lowercase().trim() in suggestive

    /** How many classes to ask the model for. Enough for any of the usual five-class models. */
    const val MAX_RESULTS = 8
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ContentModelModule {

    @Binds
    @Singleton
    abstract fun bindPurposeTrainedLabeler(impl: BundledNsfwLabeler): PurposeTrainedLabeler
}
