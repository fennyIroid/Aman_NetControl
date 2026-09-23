package com.example.childsafetyimpl.platform.browserimages

/** Scores one photo for drugs. Implementations are not required to be thread-safe; call from one thread. */
interface DrugClassifier : AutoCloseable {

    /**
     * [input] is [ModelInput.CLIP_SIDE]² RGB floats in 0..1, row order — see [ModelInput.fill].
     * Returns the drug share, 0..1; see [DrugPrompts.drugShare].
     */
    fun drugScore(input: FloatArray): Float
}
