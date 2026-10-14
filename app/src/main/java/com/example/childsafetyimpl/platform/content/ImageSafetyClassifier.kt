package com.example.childsafetyimpl.platform.content

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.scale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * On-device image safety scoring.
 *
 * Two signals, fused, both running entirely on the device:
 *
 *  1. **A bundled neural network.** ML Kit's on-device image labeller returns semantic labels with
 *     confidences. This is the primary signal, and [SafetyLabelRisk] maps its labels onto risk in
 *     a table anyone can audit.
 *  2. **A pixel heuristic.** Skin-tone proportion, region contiguity and surrounding detail. This
 *     was the original scorer and is now demoted to corroboration.
 *
 * The fusion rule is the part that matters. Skin measurements alone can never cover an image:
 * a portrait or a beach photo scores high on skin proportion and would previously have been
 * covered. The heuristic now only amplifies a verdict the model has already given semantic reason
 * to suspect, which is what removes that class of false positive.
 *
 * The honest limitation, recorded against the capability rather than hidden: Google ships no
 * on-device explicit-content classifier, so this is a general labeller used as a proxy. It
 * recognises swimwear, bathing and adult settings with real confidence. It cannot recognise
 * "explicit", and a purpose-trained model would still be needed before this carries a safety claim.
 */
@Singleton
class ImageSafetyClassifier @Inject constructor(
    private val labeler: MlKitImageLabeler,
    /**
     * A classifier actually built for this, when the build has one bundled.
     *
     * Defaulted so that the pure fusion rule below can be exercised without one, and so that a
     * build with no model behaves exactly as this class always has. See [PurposeTrainedLabeler].
     */
    private val purposeTrained: PurposeTrainedLabeler = PurposeTrainedLabeler.NotBundled,
) {

    data class Verdict(
        val score: Float,
        val skinRatio: Float,
        val largestRegionRatio: Float,
        val edgeDensity: Float,
        val analysedPixels: Int,
        val durationMillis: Long,
        val explanation: String,
        /** What the bundled model actually saw, kept as evidence for the verdict. */
        val labels: List<MlKitImageLabeler.Label> = emptyList(),
        val modelScore: Float = 0f,
        val heuristicScore: Float = 0f,
        val riskKinds: Set<SafetyLabelRisk.RiskKind> = emptySet(),
        /**
         * What a purpose-trained classifier said this was about, when one judged it.
         *
         * Separate from [riskKinds] because the two are different kinds of claim. A risk kind is
         * what a general labeller's vocabulary was read as meaning; this is what a model built for
         * the job reported directly, and only it may resolve [ValuesCategory.EXPLICIT_IMAGERY]
         * without inference.
         */
        val trainedCategory: ValuesCategory? = null,
        /** True when the model contributed nothing, so the verdict rests on pixels alone. */
        val modelUnavailable: Boolean = false,
    ) {
        val isUnsafe: Boolean get() = score >= UNSAFE_THRESHOLD
        val isBorderline: Boolean get() = score in BORDERLINE_THRESHOLD..<UNSAFE_THRESHOLD

        /**
         * The same verdict judged at a parent's chosen strictness.
         *
         * [isUnsafe] is the fixed default; this is what enforcement should actually call, so that
         * moving the Relaxed/Balanced/Strict control has a real effect rather than a cosmetic one.
         */
        fun isUnsafeAt(level: ProtectionLevel): Boolean = score >= level.threshold

        /**
         * Which of the configurable categories this verdict touches.
         *
         * Derived from the model's risk kinds rather than stored, so a category added to
         * [ValuesCategory] cannot silently fail to appear here.
         */
        val categories: Set<ValuesCategory>
            get() = buildSet {
                trainedCategory?.let {
                    add(it)
                    // Explicit implies suggestive. A parent who asked for suggestive imagery to be
                    // covered and not explicit has not asked for explicit imagery to be shown.
                    if (it == ValuesCategory.EXPLICIT_IMAGERY) add(ValuesCategory.SUGGESTIVE_IMAGERY)
                }
                if (SafetyLabelRisk.RiskKind.EXPOSURE in riskKinds ||
                    SafetyLabelRisk.RiskKind.ADULT_CONTEXT in riskKinds
                ) {
                    add(ValuesCategory.SUGGESTIVE_IMAGERY)
                    if (score >= UNSAFE_THRESHOLD) add(ValuesCategory.EXPLICIT_IMAGERY)
                }
                if (SafetyLabelRisk.RiskKind.GAMBLING in riskKinds) {
                    add(ValuesCategory.GAMBLING_IMAGERY)
                }
                if (SafetyLabelRisk.RiskKind.SUBSTANCE in riskKinds) {
                    add(ValuesCategory.SUBSTANCE_IMAGERY)
                }
            }

        /**
         * Whether this verdict should act, given what a parent actually switched on.
         *
         * A verdict scoring high on gambling imagery must not cover anything when the parent only
         * enabled explicit content. Checking the score alone and ignoring the category is the bug
         * this function exists to prevent.
         */
        fun shouldCover(level: ProtectionLevel, enabled: Set<ValuesCategory>): Boolean =
            isUnsafeAt(level) && matchesCategories(enabled)

        /**
         * Whether what this verdict is *about* is something the parent asked to be covered,
         * ignoring how strongly.
         *
         * Split out from [shouldCover] because the two halves of that question are settled in
         * different places once a verdict is one frame's evidence rather than a decision. What a
         * picture is does not change between two frames of the same video; how strongly a general
         * labeller says so does, by a tenth either way. So the category half is answered here, per
         * frame, and the strength half is answered by [VerdictStability] across frames.
         */
        fun matchesCategories(enabled: Set<ValuesCategory>): Boolean {
            val touched = categories
            // No category resolved at all: fall back to the score, because an unexplained high
            // score is still evidence and refusing to act on it would be the wrong default for a
            // safety product.
            if (touched.isEmpty()) return ValuesCategory.EXPLICIT_IMAGERY in enabled
            return touched.any { it in enabled }
        }
    }

    /**
     * What produced the verdicts, so a screen can state its provenance honestly.
     *
     * Resolved from what is actually loaded rather than declared as a constant. A build with a
     * model bundled says so, a build without one says the proxy is judging, and no screen has to
     * know which kind of build it is running in. Rule 11 lives on this line.
     */
    val identity: ModelIdentity
        get() = if (purposeTrained.ready) purposeTrained.identity else MODEL_IDENTITY

    /**
     * The real entry point: runs the bundled model and the pixel heuristic, and fuses them.
     */
    suspend fun classify(bitmap: Bitmap): Verdict {
        val startedAt = System.nanoTime()
        val trained = purposeTrained.evaluate(bitmap)
        val labels = labeler.label(bitmap)
        val heuristic = classifyPixels(bitmap)

        val fusion = fuse(labels, heuristic.score, trained)
        val duration = (System.nanoTime() - startedAt) / 1_000_000

        return heuristic.copy(
            score = fusion.fused,
            durationMillis = duration,
            labels = (trained?.evidence.orEmpty() + labels)
                .sortedByDescending { it.confidence }
                .take(6),
            modelScore = fusion.modelScore,
            heuristicScore = heuristic.score,
            riskKinds = fusion.kinds,
            trainedCategory = trained?.category,
            // A purpose-trained verdict is a model having run. Only a build with nothing at all
            // to say about the picture reports the model as unavailable.
            modelUnavailable = labels.isEmpty() && trained == null,
            explanation = explainFused(
                labels = labels,
                heuristic = heuristic,
                fusion = fusion,
            ),
        )
    }

    /** The outcome of combining the signals, kept separate so it can be reasoned about alone. */
    data class Fusion(
        val fused: Float,
        val modelScore: Float,
        val kinds: Set<SafetyLabelRisk.RiskKind>,
        /** True when the model saw something about a body, which is what licenses pixel evidence. */
        val corroborated: Boolean,
        /** What a purpose-trained model said, when one judged the picture. */
        val trained: PurposeTrainedLabeler.Explicitness? = null,
    )

    /**
     * Pure fusion, extracted so the rule that fixes the portrait false positive can be tested
     * without a device, a bitmap or a model.
     *
     * [trained] is a verdict from a classifier built for this job, when the build has one. Where
     * it is present it decides, and the two proxy signals are demoted to what they have always
     * actually been: evidence that can raise a verdict and never evidence that can clear one. A
     * general labeller's opinion that a picture contains a dog is not a reason to disbelieve a
     * content classifier, and letting it be one would make bundling a real model *reduce* what
     * gets covered on some pictures — which is the one outcome that would make the whole exercise
     * pointless. See [PROXY_SHARE_WHEN_TRAINED].
     */
    fun fuse(
        labels: List<MlKitImageLabeler.Label>,
        heuristicScore: Float,
        trained: PurposeTrainedLabeler.Explicitness? = null,
    ): Fusion {
        var clothing = 0f
        var body = 0f
        var context = 0f
        var benign = 0f
        var standalone = 0f
        val kinds = mutableSetOf<SafetyLabelRisk.RiskKind>()

        labels.forEach { label ->
            val weighted = SafetyLabelRisk.weightFor(label.text) ?: return@forEach
            val contribution = weighted.weight * label.confidence
            kinds += weighted.kind
            when (weighted.kind) {
                SafetyLabelRisk.RiskKind.EXPOSURE ->
                    if (weighted.bodyOnly) body += contribution else clothing += contribution
                SafetyLabelRisk.RiskKind.ADULT_CONTEXT -> context += contribution
                SafetyLabelRisk.RiskKind.BENIGN -> benign += contribution
                SafetyLabelRisk.RiskKind.GAMBLING,
                SafetyLabelRisk.RiskKind.SUBSTANCE,
                -> standalone = maxOf(standalone, contribution)
                // Deliberately weightless. See RiskKind.MEDIUM.
                SafetyLabelRisk.RiskKind.MEDIUM -> Unit
            }
        }

        // Body labels on their own are what covered cats, babies and ordinary portraits: "Skin"
        // and "Flesh" describe a body, not a state of dress, and the labeller reports them on fur
        // and on landscapes too. Next to a clothing label they count in full. Without one they
        // count for part — enough, when several of them are strong at once, to still cover the
        // nudity no clothing label describes.
        val exposure = if (clothing > 0f) clothing + body else body * BODY_ALONE_SHARE

        // Counter-evidence is capped against the exposure it is arguing with. A general labeller
        // returns a long tail of confident-sounding wrong labels, and one of them landing in the
        // benign table used to be able to cancel the verdict outright: a swimwear photograph
        // scoring 0.57 on exposure was talked down below the threshold by "Dog 0.48", which is not
        // evidence that a swimwear photograph is safe. Benign labels can still pull a weak verdict
        // down to nothing, which is the job they were added for; they can no longer overrule a
        // clothing label. Body labels are always the weak kind, so they can be cancelled entirely.
        val cancellable = if (clothing > 0f) clothing * MAX_COUNTER_EVIDENCE_SHARE + body else exposure
        val counterEvidence = minOf(benign * 0.8f, cancellable)
        val modelScore = (exposure + context * 0.6f - counterEvidence).coerceIn(0f, 1f)
        // Pixel skin measurements only get a say once the model has seen exposure rather than a
        // face: a skin-tone heuristic agreeing with "Skin" on a baby is two readings of one thing.
        val corroborated = clothing > 0f || body >= BODY_CORROBORATES

        val fused = when {
            // No semantic evidence at all. Pixels alone may flag a picture for the log and never
            // cover it, at any strictness. A labeller with nothing to say about a picture is
            // almost always looking at a drawing, a gradient or a piece of interface, and those are
            // what a skin-tone count gets wrong: driven on a Galaxy M36, a peach home-screen
            // wallpaper scored 0.49 here and was covered. A photograph of a body is the thing the
            // labeller does have words for — Skin, Flesh, Muscle — so explicit content still
            // reaches the branches below. See PIXEL_ONLY_CEILING.
            labels.isEmpty() -> minOf(heuristicScore * 0.65f, PIXEL_ONLY_CEILING)

            // The model saw a body. Only now do pixel measurements get to weigh in.
            corroborated -> (modelScore * 0.7f + heuristicScore * 0.3f)

            // Risky but not about a body: gambling and alcohol land here, and skin tone is
            // irrelevant to them. This branch is what stops a portrait being covered.
            else -> maxOf(modelScore, standalone)
        }

        // A model built for this job outranks both proxies, and the proxies can only add to it.
        val decided = if (trained != null) {
            maxOf(trained.score, fused * PROXY_SHARE_WHEN_TRAINED)
        } else {
            fused
        }

        return Fusion(
            fused = decided.coerceIn(0f, 1f),
            modelScore = modelScore,
            kinds = kinds,
            corroborated = corroborated,
            trained = trained,
        )
    }

    private fun explainFused(
        labels: List<MlKitImageLabeler.Label>,
        heuristic: Verdict,
        fusion: Fusion,
    ): String {
        val fused = fusion.fused
        val corroborated = fusion.corroborated

        // A purpose-trained verdict is named as what it is, and named first. The whole reason the
        // proxy's explanations read the way they do is that a parent has to be able to tell a
        // measurement from an inference, and this is the case where it is finally a measurement.
        fusion.trained?.let { trained ->
            val classes = trained.evidence
                .take(3)
                .joinToString { "${it.text} ${"%.2f".format(it.confidence)}" }
            return "The bundled content classifier scored this " +
                "${"%.2f".format(trained.score)} for ${trained.category.label.lowercase()} " +
                "($classes). Judged on this device; the general labeller and the pixel " +
                "measurements were corroboration only."
        }

        if (labels.isEmpty()) {
            return "The bundled model returned no labels it was confident about, so this rests on " +
                "pixel measurements alone, which can flag a picture but never cover it. " +
                heuristic.explanation
        }

        val top = labels.sortedByDescending { it.confidence }
            .take(3)
            .joinToString { "${it.text} ${"%.2f".format(it.confidence)}" }

        return when {
            fused >= UNSAFE_THRESHOLD && corroborated ->
                "The model saw $top, and the pixel measurements agree: " +
                    "${"%.0f%%".format(heuristic.skinRatio * 100)} skin tone in one region covering " +
                    "${"%.0f%%".format(heuristic.largestRegionRatio * 100)}. Both signals point the same way."

            fused >= UNSAFE_THRESHOLD ->
                "The model saw $top, which is enough on its own for this category. " +
                    "Pixel measurements were not the deciding factor."

            corroborated ->
                "The model saw $top and the pixels are consistent, but the combined score stays " +
                    "under the threshold. Flagged for review rather than covered."

            heuristic.skinRatio > 0.3f ->
                // Warm rock, wood, sepia paper and human faces all read as skin tone to a pixel
                // scorer. Naming the disagreement is the most useful thing this line can do.
                "Pixel measurements read ${"%.0f%%".format(heuristic.skinRatio * 100)} skin tone, which " +
                    "on its own used to be enough to cover an image. The model saw $top, none of " +
                    "which suggests exposure, so the pixel reading is overruled."

            else ->
                "The model saw $top and nothing in it maps to a safety risk."
        }
    }

    /**
     * The pixel heuristic. Downsamples first: at 64 by 64 the measurements it depends on are
     * unchanged, and the cost drops from tens of milliseconds to roughly one, which is what makes
     * per-image scoring viable inside a page load at all.
     */
    fun classifyPixels(bitmap: Bitmap): Verdict {
        val startedAt = System.nanoTime()

        val sample = if (bitmap.width > SAMPLE_SIZE || bitmap.height > SAMPLE_SIZE) {
            bitmap.scale(SAMPLE_SIZE, SAMPLE_SIZE, filter = true)
        } else {
            bitmap
        }

        val width = sample.width
        val height = sample.height
        val total = width * height
        if (total == 0) {
            return Verdict(0f, 0f, 0f, 0f, 0, 0, "The image had no pixels to analyse.")
        }

        val pixels = IntArray(total)
        sample.getPixels(pixels, 0, width, 0, 0, width, height)

        val skinMask = BooleanArray(total)
        var skinCount = 0
        for (index in pixels.indices) {
            if (isSkinTone(pixels[index])) {
                skinMask[index] = true
                skinCount++
            }
        }

        val skinRatio = skinCount.toFloat() / total
        val largestRegion = largestConnectedRegion(skinMask, width, height).toFloat() / total
        val edgeDensity = edgeDensity(pixels, width, height)

        // Three signals, weighted. A high skin ratio alone is a beach photo or a close-up face;
        // a large contiguous region with little surrounding structure is the pattern that matters.
        val rawScore = (skinRatio * 0.42f) +
            (largestRegion * 0.44f) +
            ((1f - edgeDensity.coerceIn(0f, 1f)) * 0.14f * min(1f, skinRatio * 3f))

        val score = rawScore.coerceIn(0f, 1f)
        val duration = (System.nanoTime() - startedAt) / 1_000_000

        if (sample !== bitmap) sample.recycle()

        return Verdict(
            score = score,
            skinRatio = skinRatio,
            largestRegionRatio = largestRegion,
            edgeDensity = edgeDensity,
            analysedPixels = total,
            durationMillis = duration,
            explanation = explain(skinRatio, largestRegion, edgeDensity, score),
        )
    }

    /**
     * Skin-tone test in normalised RGB plus a chrominance check.
     *
     * Chrominance is included because a plain RGB rule fails badly under warm lighting and on
     * darker skin. It is still a poor detector across the full range of human skin tones, which is
     * one of several reasons this classifier is not fit to ship.
     */
    private fun isSkinTone(pixel: Int): Boolean {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)

        if (r < 60 || g < 30 || b < 15) return false
        if (abs(r - g) < 12) return false
        if (r <= g || r <= b) return false

        val maxChannel = max(r, max(g, b))
        val minChannel = min(r, min(g, b))
        if (maxChannel - minChannel < 12) return false

        // YCbCr chrominance window, the standard range used for skin segmentation.
        val cb = 128 - 0.168736 * r - 0.331264 * g + 0.5 * b
        val cr = 128 + 0.5 * r - 0.418688 * g - 0.081312 * b
        return cb in 77.0..133.0 && cr in 133.0..180.0
    }

    /**
     * Largest contiguous run of skin pixels, found with an iterative flood fill.
     *
     * Iterative rather than recursive on purpose: a recursive fill over even a small image can
     * exhaust the stack, and this runs on a background thread inside a page load.
     */
    private fun largestConnectedRegion(mask: BooleanArray, width: Int, height: Int): Int {
        val visited = BooleanArray(mask.size)
        var largest = 0
        val stack = ArrayDeque<Int>()

        for (start in mask.indices) {
            if (!mask[start] || visited[start]) continue

            var regionSize = 0
            stack.addLast(start)
            visited[start] = true

            while (stack.isNotEmpty()) {
                val index = stack.removeLast()
                regionSize++

                val x = index % width
                val y = index / width

                if (x > 0) push(stack, visited, mask, index - 1)
                if (x < width - 1) push(stack, visited, mask, index + 1)
                if (y > 0) push(stack, visited, mask, index - width)
                if (y < height - 1) push(stack, visited, mask, index + width)
            }
            if (regionSize > largest) largest = regionSize
        }
        return largest
    }

    private fun push(stack: ArrayDeque<Int>, visited: BooleanArray, mask: BooleanArray, index: Int) {
        if (index in mask.indices && mask[index] && !visited[index]) {
            visited[index] = true
            stack.addLast(index)
        }
    }

    /** Fraction of pixels sitting on a strong luminance edge. A proxy for structural detail. */
    private fun edgeDensity(pixels: IntArray, width: Int, height: Int): Float {
        var edges = 0
        var compared = 0
        for (y in 0 until height - 1) {
            for (x in 0 until width - 1) {
                val index = y * width + x
                val here = luminance(pixels[index])
                val right = luminance(pixels[index + 1])
                val below = luminance(pixels[index + width])
                if (abs(here - right) > EDGE_THRESHOLD || abs(here - below) > EDGE_THRESHOLD) {
                    edges++
                }
                compared++
            }
        }
        return if (compared == 0) 0f else edges.toFloat() / compared
    }

    private fun luminance(pixel: Int): Int =
        (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000

    private fun explain(
        skinRatio: Float,
        largestRegion: Float,
        edgeDensity: Float,
        score: Float,
    ): String {
        val skin = "%.0f%%".format(skinRatio * 100)
        val region = "%.0f%%".format(largestRegion * 100)
        val detail = "%.0f%%".format(edgeDensity * 100)

        return when {
            score >= UNSAFE_THRESHOLD ->
                "$skin of the image reads as skin tone, with one continuous region covering $region " +
                    "and only $detail structural detail around it. That combination is what this " +
                    "heuristic treats as likely explicit."

            score >= BORDERLINE_THRESHOLD ->
                "$skin skin tone and a largest region of $region. Enough to flag, not enough to be " +
                    "confident. A portrait or a beach photo lands here routinely."

            skinRatio > 0.25f ->
                "$skin skin tone, but broken into small regions with $detail structural detail, " +
                    "which usually means faces or a crowd rather than explicit content."

            else ->
                "Only $skin of the image reads as skin tone. Nothing here for this heuristic to flag."
        }
    }

    companion object {
        /**
         * Mirrors [MlKitSafetyModel.identity].
         *
         * Held here as well because this class is constructed directly in tests and by the
         * browser engine, and a verdict that cannot say what judged it is a verdict a UI will
         * eventually overclaim.
         */
        val MODEL_IDENTITY = ModelIdentity(
            name = "ML Kit image labeller",
            version = "bundled",
            purposeTrained = false,
            onDevice = true,
            knownLimitations = "A general labeller used as a proxy for content classification. " +
                "Recognises swimwear, bathing and adult settings; has no label for explicit " +
                "content and infers it. A purpose-trained model is required before this " +
                "carries a safety claim.",
        )

        /**
         * How much of the exposure evidence benign labels are allowed to cancel.
         *
         * Not a full veto. The labeller is confident and frequently wrong about individual labels,
         * so a single stray benign hit must not be able to clear a picture the exposure labels
         * agree about.
         */
        const val MAX_COUNTER_EVIDENCE_SHARE = 0.5f

        /** How much body labels count for with no clothing label beside them. */
        const val BODY_ALONE_SHARE = 0.5f

        /**
         * How much body-label evidence it takes, alone, for the pixel measurements to join in.
         *
         * Two of them confident at once — "Skin" and "Flesh" both near 0.9, with "Muscle" or
         * "Abdomen" beside them — rather than the one or two middling readings a face or a pet
         * produces.
         */
        const val BODY_CORROBORATES = 0.6f

        /**
         * How much of a proxy verdict survives once a real classifier is judging alongside it.
         *
         * Not zero. The proxies see things a content classifier does not — gambling and alcohol
         * have nothing to do with nudity and are read out of the labeller's vocabulary — and a
         * bundled model that has never heard of them must not silently switch those categories
         * off. Not one either: a signal known to be unreliable should not be able to carry a
         * picture over the line by itself once something better is in the room.
         */
        const val PROXY_SHARE_WHEN_TRAINED = 0.6f

        /**
         * The most a verdict built from pixels alone can score.
         *
         * Just under the strictest level's threshold, so it can still be flagged as borderline in
         * the log but no setting a parent can choose makes it cover. A test holds it under every
         * level, so a stricter level added later cannot quietly bring pixel-only covers back.
         */
        const val PIXEL_ONLY_CEILING = 0.29f

        const val UNSAFE_THRESHOLD = 0.42f
        const val BORDERLINE_THRESHOLD = 0.28f
        private const val SAMPLE_SIZE = 64
        private const val EDGE_THRESHOLD = 26
    }
}
