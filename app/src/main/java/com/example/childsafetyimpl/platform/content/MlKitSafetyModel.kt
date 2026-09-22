package com.example.childsafetyimpl.platform.content

import android.graphics.Bitmap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The [ContentSafetyModel] the app ships with today: ML Kit's general labeller, used as a proxy.
 *
 * Everything unusual about this class is a consequence of one fact it does not try to hide. It is
 * not a content classifier. It is a labeller that knows what a swimsuit looks like, pressed into
 * service for a job it was not trained for, and [identity] says exactly that so no screen and no
 * report can quietly present its output as a safety determination.
 *
 * It earns its place anyway. POC 1 has to answer whether a frame can be captured, judged and
 * covered fast enough to matter, and that question is about the pipeline rather than the model.
 * This runs on the real device at the real frame rate and produces the real latency figures. When
 * a purpose-trained model replaces it, every number POC 1 measured except accuracy still holds.
 *
 * The mapping from labels to [ValuesCategory] lives here rather than in the classifier because it
 * is model-specific: a different model reports different labels, and the fusion rule downstream
 * should not have to know which model produced them.
 */
@Singleton
class MlKitSafetyModel @Inject constructor(
    private val labeler: MlKitImageLabeler,
) : ContentSafetyModel {

    override val identity: ModelIdentity = ModelIdentity(
        name = "ML Kit image labeller",
        version = "bundled",
        // The field this whole class exists to set correctly.
        purposeTrained = false,
        onDevice = true,
        knownLimitations = "Recognises swimwear, bathing, lingerie and adult settings. Has no " +
            "label for explicit content and cannot report it directly. Skin-tone measurement " +
            "corroborates but never decides, so portraits and beach photographs are not " +
            "covered on skin proportion alone.",
    )

    override suspend fun evaluate(bitmap: Bitmap): ModelVerdict {
        val startedAt = System.nanoTime()
        val labels = labeler.label(bitmap)
        val duration = (System.nanoTime() - startedAt) / 1_000_000

        if (labels.isEmpty()) {
            // An empty result is genuinely ambiguous: the model may have run and found nothing,
            // or it may not have run at all. Reporting it as a confident zero would be the same
            // mistake the usage-stats layer refuses to make, so it is reported as unavailable.
            return ModelVerdict(
                score = 0f,
                categories = emptySet(),
                evidence = emptyList(),
                durationMillis = duration,
                unavailable = true,
            )
        }

        var exposure = 0f
        var context = 0f
        var benign = 0f
        var standalone = 0f
        val categories = mutableSetOf<ValuesCategory>()

        labels.forEach { label ->
            val weighted = SafetyLabelRisk.weightFor(label.text) ?: return@forEach
            val contribution = weighted.weight * label.confidence
            when (weighted.kind) {
                SafetyLabelRisk.RiskKind.EXPOSURE -> {
                    exposure += contribution
                    // Exposure labels map to the suggestive category with confidence and to the
                    // explicit one only as an inference, which is the honest reading of what
                    // "Swimwear at 0.8" actually tells you.
                    categories += ValuesCategory.SUGGESTIVE_IMAGERY
                }
                SafetyLabelRisk.RiskKind.ADULT_CONTEXT -> {
                    context += contribution
                    categories += ValuesCategory.SUGGESTIVE_IMAGERY
                }
                SafetyLabelRisk.RiskKind.GAMBLING -> {
                    standalone = maxOf(standalone, contribution)
                    categories += ValuesCategory.GAMBLING_IMAGERY
                }
                SafetyLabelRisk.RiskKind.SUBSTANCE -> {
                    standalone = maxOf(standalone, contribution)
                    categories += ValuesCategory.SUBSTANCE_IMAGERY
                }
                SafetyLabelRisk.RiskKind.BENIGN -> benign += contribution
                // Deliberately weightless. See RiskKind.MEDIUM.
                SafetyLabelRisk.RiskKind.MEDIUM -> Unit
            }
        }

        val score = maxOf(
            (exposure + context * 0.6f - benign * 0.8f).coerceIn(0f, 1f),
            standalone,
        )

        // Strong exposure evidence is the only thing that licenses the explicit inference, and it
        // remains an inference. DetectionQuality.PROXY_ONLY is what carries that caveat to the UI.
        if (exposure >= EXPLICIT_INFERENCE_FLOOR) {
            categories += ValuesCategory.EXPLICIT_IMAGERY
        }

        return ModelVerdict(
            score = score,
            categories = categories,
            evidence = labels.sortedByDescending { it.confidence }.take(6),
            durationMillis = duration,
        )
    }

    private companion object {
        /**
         * How much exposure evidence before "explicit" is inferred at all.
         *
         * Set high deliberately. The cost of inferring it too readily is a child's ordinary
         * holiday photograph being covered and a parent being told explicit content was found,
         * which is a worse failure than a miss: it destroys trust in every later verdict.
         */
        const val EXPLICIT_INFERENCE_FLOOR = 0.55f
    }
}
