package com.example.childsafetyimpl.platform.browserimages

/**
 * Output of the NSFW classifier. The five classes match the label order of the
 * bundled MobileNetV2 model; see [NsfwClassifier].
 *
 * Values are softmax probabilities and sum to approximately 1.0.
 */
data class NsfwScores(
    val drawings: Float,
    val hentai: Float,
    val neutral: Float,
    val porn: Float,
    val sexy: Float,
) {
    /** Explicit content, regardless of whether it is photographic or drawn. */
    val explicit: Float get() = porn + hentai

    /** Suggestive but not explicit — swimwear, lingerie, partial nudity. */
    val suggestive: Float get() = sexy
}
