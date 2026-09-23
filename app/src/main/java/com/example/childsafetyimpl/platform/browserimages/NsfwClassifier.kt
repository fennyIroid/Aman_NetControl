package com.example.childsafetyimpl.platform.browserimages


/** Scores one photo. Implementations are not required to be thread-safe; call from one thread. */
interface NsfwClassifier : AutoCloseable {

    /** [input] is [ModelInput.SIDE]² RGB floats in 0..1, row order — see [ModelInput.fill]. */
    fun classify(input: FloatArray): NsfwScores
}
