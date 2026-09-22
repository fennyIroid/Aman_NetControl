package com.example.childsafetyimpl.platform.content

import android.graphics.Bitmap

/**
 * The classifier behind Values-based Blur, behind an interface.
 *
 * This type exists because of the single most important finding in POC 1, and it is worth stating
 * plainly rather than burying in a metric: **Google ships no on-device explicit-content
 * classifier.** [MlKitImageLabeler] is a general-purpose labeller being used as a proxy. It
 * recognises swimwear, bathing, lingerie and adult settings with genuine confidence, and it has
 * no concept of "explicit" at all. It is enough to prove that a frame can be sampled, judged and
 * covered inside the time budget — which is what a POC is for — and it is not enough to carry a
 * safety claim to a parent.
 *
 * So the production question is not "does the pipeline work". The pipeline works, and POC 1
 * measures it. The question is what sits in this slot when the product ships, and separating the
 * slot from the pipeline is what lets that be answered later without rebuilding anything around
 * it. A purpose-trained NSFW model dropped in here changes the verdicts and changes nothing else.
 *
 * Two properties every implementation has to hold, because they are the product's promises and
 * not implementation details:
 *
 * - **Nothing leaves the device.** A frame of a child's screen is the most sensitive thing this
 *   app ever touches. An implementation that sends bitmaps to a server is not a different
 *   trade-off, it is a different product, and it would have to be disclosed as such.
 * - **Nothing is retained.** The bitmap is read and dropped. What survives is a score, a set of
 *   categories and a duration, which is what the event log stores and all it stores.
 */
interface ContentSafetyModel {

    /** What this model is, so a verdict can be traced to the thing that produced it. */
    val identity: ModelIdentity

    /**
     * Scores one frame.
     *
     * Implementations must not retain [bitmap] beyond the call, and must not recycle it: the
     * caller owns it and the fusion step still needs it.
     */
    suspend fun evaluate(bitmap: Bitmap): ModelVerdict
}

/**
 * The provenance of a verdict.
 *
 * [purposeTrained] is the field that matters and the reason this class exists. A UI that claims a
 * protection, and a report that claims a capability, both need to know whether the thing behind it
 * was built for this job or borrowed for it. Every screen that surfaces a blur verdict reads this
 * rather than assuming.
 */
data class ModelIdentity(
    val name: String,
    val version: String,
    /**
     * True only for a model trained to recognise the categories this product acts on.
     *
     * False for the ML Kit labeller, deliberately and permanently. Nothing in the UI may present
     * a verdict from a model with this set to false as a safety determination.
     */
    val purposeTrained: Boolean,
    /** Runs entirely on this device. False would require an explicit privacy disclosure. */
    val onDevice: Boolean,
    /** What this model cannot do, in words that can be shown to a parent. */
    val knownLimitations: String,
) {
    /** The line a protection screen prints under a blur verdict. */
    val confidenceCaveat: String
        get() = if (purposeTrained) {
            "Judged on this device by $name $version."
        } else {
            "Judged on this device by $name, a general image labeller used as a stand-in. " +
                "It is accurate enough to demonstrate the behaviour and is not a content " +
                "classifier. Treat verdicts as indicative."
        }
}

/** One model's opinion about one frame, before fusion with any other signal. */
data class ModelVerdict(
    /** 0..1. Comparable across implementations, which is what makes the slot swappable. */
    val score: Float,
    val categories: Set<ValuesCategory>,
    /** What the model actually saw, kept as evidence for the parent-facing explanation. */
    val evidence: List<MlKitImageLabeler.Label>,
    val durationMillis: Long,
    /** True when the model could not run, so the caller knows not to trust a zero. */
    val unavailable: Boolean = false,
) {
    companion object {
        fun unavailable(reason: String): ModelVerdict = ModelVerdict(
            score = 0f,
            categories = emptySet(),
            evidence = emptyList(),
            durationMillis = 0,
            unavailable = true,
        )
    }
}

/**
 * The content categories Values-based Blur acts on.
 *
 * The brief is explicit that new blur categories must not be invented before the POC requirements
 * are finalised, so this list is deliberately short and maps one-to-one onto what the current
 * signals can actually distinguish. Adding an entry here is a product decision that needs the
 * client's agreement, not a developer's.
 *
 * [enabledByDefault] encodes the difference between the category the client asked for and the ones
 * the pipeline happens to be able to see. Only the first is on out of the box.
 */
enum class ValuesCategory(
    val label: String,
    /** What a parent is told will be covered. Shown on the settings screen verbatim. */
    val description: String,
    val enabledByDefault: Boolean,
    /**
     * How well the current signals actually detect this.
     *
     * Carried in the type because the settings screen has to be honest per category rather than
     * making one blanket claim over categories of very different reliability.
     */
    val detectionQuality: DetectionQuality,
) {
    EXPLICIT_IMAGERY(
        label = "Explicit imagery",
        description = "Nudity and sexual content in pictures and video.",
        enabledByDefault = true,
        detectionQuality = DetectionQuality.PROXY_ONLY,
    ),
    SUGGESTIVE_IMAGERY(
        label = "Suggestive imagery",
        description = "Swimwear, lingerie and revealing clothing, which the current model " +
            "recognises more reliably than explicit content.",
        // On by default, which reverses the original choice for a reason worth recording. With
        // only explicit imagery enabled, the product's behaviour on a beach or swimwear search was
        // to cover nothing: the bundled model has no label for explicit content and infers it only
        // from strong exposure evidence, so the pictures it recognises best fell into a category
        // that was switched off and had no screen to switch it on from. A family filter whose one
        // reliable category is disabled by default is a filter that reads as on and does nothing.
        enabledByDefault = true,
        detectionQuality = DetectionQuality.WORKABLE,
    ),
    GAMBLING_IMAGERY(
        label = "Gambling",
        description = "Casino, betting and slot-machine imagery.",
        enabledByDefault = false,
        detectionQuality = DetectionQuality.WORKABLE,
    ),
    SUBSTANCE_IMAGERY(
        label = "Alcohol and smoking",
        description = "Alcohol, smoking and vaping imagery.",
        enabledByDefault = false,
        detectionQuality = DetectionQuality.WORKABLE,
    );

    companion object {
        val defaults: Set<ValuesCategory> = entries.filter { it.enabledByDefault }.toSet()
    }
}

/**
 * How much a category's verdicts can be trusted.
 *
 * The uncomfortable ordering is the point: the category the product most needs is the one the
 * current model is worst at, because a general labeller has good labels for "swimwear" and no
 * label at all for "explicit". Naming that on the settings screen is better than letting a parent
 * assume the reverse.
 */
enum class DetectionQuality(val label: String, val note: String) {
    WORKABLE(
        "Workable",
        "The bundled labeller has direct labels for this and reports them with real confidence.",
    ),
    PROXY_ONLY(
        "Indicative only",
        "No on-device model available to this app recognises this category directly. It is " +
            "inferred from adjacent labels and pixel measurements, so it will miss content and " +
            "will occasionally cover something harmless. A purpose-trained model is required " +
            "before this carries a safety claim.",
    ),
}

/**
 * How aggressively to act, mapped to a threshold.
 *
 * Exposed to the parent as three words rather than a number, because a slider from 0 to 1 asks a
 * parent to calibrate a classifier they cannot see the output of. The thresholds themselves stay
 * here where they can be tuned against measured data from the POC.
 */
enum class ProtectionLevel(
    val label: String,
    val description: String,
    val threshold: Float,
) {
    RELAXED(
        "Relaxed",
        "Covers only what the model is confident about. Fewest interruptions, most misses.",
        threshold = 0.58f,
    ),
    BALANCED(
        "Balanced",
        "The default. Covers confident verdicts and the stronger borderline ones.",
        threshold = ImageSafetyClassifier.UNSAFE_THRESHOLD,
    ),
    STRICT(
        "Strict",
        "Covers anything suspected. Expect safe content to be covered sometimes.",
        threshold = 0.30f,
    );

    companion object {
        val default: ProtectionLevel = BALANCED
    }
}
