package com.example.childsafetyimpl.platform.browserimages

import com.example.childsafetyimpl.data.prefs.SafetySettings
import com.example.childsafetyimpl.core.protection.ProtectionFeature
import com.example.childsafetyimpl.platform.content.BlurMode
import com.example.childsafetyimpl.platform.content.ProtectionLevel
import com.example.childsafetyimpl.platform.content.ValuesCategory

/**
 * The parent's blur settings, as the browser image-search path reads them: which of the two
 * models' scores cover a photo, and at what strength of evidence.
 *
 * One policy for both models because the parent chose one thing — explicit imagery — and drug
 * imagery is part of it. The NSFW model's porn and hentai scores are explicit, its "sexy" score is
 * suggestive, and the drug model's share is explicit too. Only [ProtectionLevel] moves the
 * thresholds; the numbers per level are SafeScreen's tiers, measured against its test photos
 * (tools/mobileclip/make_drug_prompts.py for the drug ones).
 *
 * [from] returns null wherever this path cannot deliver what the parent asked for, and the screen
 * capture pipeline keeps the browser as it does today:
 *
 * - A category these models cannot see — gambling, alcohol and smoking. Handing the page over would
 *   quietly stop covering it (rule 12).
 * - A blur mode other than per-picture. Whole-screen and cover-people are answers to different
 *   questions, and a patch per failing photo is neither.
 * - Neither imagery category on, where there is nothing for this path to judge.
 */
data class BrowserImagePolicy(
    val threshold: Float,
    val countsExplicit: Boolean,
    val countsSuggestive: Boolean,
    /** Null when drug imagery is not covered; see the class comment for why it rides on explicit. */
    val drugThreshold: Float?,
) {

    /** Whether a photo with these scores stays covered. */
    fun covers(scores: NsfwScores, drugScore: Float?): Boolean = coversForNudity(scores) || coversForDrugs(drugScore)

    fun coversForNudity(scores: NsfwScores): Boolean {
        val signal = (if (countsExplicit) scores.explicit else 0f) + (if (countsSuggestive) scores.suggestive else 0f)
        return signal >= threshold
    }

    fun coversForDrugs(drugScore: Float?): Boolean {
        val line = drugThreshold ?: return false
        // Asked for and not measured is not clean.
        return drugScore == null || drugScore >= line
    }

    companion object {

        /** The categories this path's two models can actually judge. */
        val judged: Set<ValuesCategory> = setOf(ValuesCategory.EXPLICIT_IMAGERY, ValuesCategory.SUGGESTIVE_IMAGERY)

        fun from(settings: SafetySettings): BrowserImagePolicy? {
            val on = settings.protectionEnabled &&
                settings.isEnabled(ProtectionFeature.VIDEO_BLURRING) &&
                settings.videoBlurEnabled
            if (!on || settings.blurMode != BlurMode.IMAGES) return null
            return from(settings.blurProtectionLevel, settings.blurCategories)
        }

        fun from(level: ProtectionLevel, categories: Set<ValuesCategory>): BrowserImagePolicy? {
            if (categories.isEmpty() || !judged.containsAll(categories)) return null
            val explicit = ValuesCategory.EXPLICIT_IMAGERY in categories
            val (threshold, drugThreshold) = when (level) {
                ProtectionLevel.RELAXED -> 0.85f to 0.70f
                ProtectionLevel.BALANCED -> 0.70f to 0.45f
                ProtectionLevel.STRICT -> 0.50f to 0.30f
            }
            return BrowserImagePolicy(
                threshold = threshold,
                countsExplicit = explicit,
                countsSuggestive = ValuesCategory.SUGGESTIVE_IMAGERY in categories,
                drugThreshold = drugThreshold.takeIf { explicit },
            )
        }
    }
}
