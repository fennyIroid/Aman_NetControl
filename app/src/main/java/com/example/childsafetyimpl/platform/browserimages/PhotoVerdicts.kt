package com.example.childsafetyimpl.platform.browserimages

/**
 * What the classifier has said about each photo on the current page.
 *
 * A photo with no verdict is covered. Only [Verdict.CLEAN] uncovers one, so every way of not
 * getting a verdict — not loaded yet, off screen, classifier failed — leaves it covered.
 */
class PhotoVerdicts {

    enum class Verdict { CLEAN, BLOCKED }

    private val verdicts = mutableMapOf<Long, Verdict>()

    fun record(id: Long, verdict: Verdict) {
        verdicts[id] = verdict
    }

    /** Forget everything: a new page's photos have not been looked at. */
    fun reset() = verdicts.clear()

    /** The photos that must stay covered: all but those scored clean. */
    fun toCover(photos: List<Photo>): List<Photo> = photos.filter { verdicts[it.id] != Verdict.CLEAN }

    /** The photos still waiting for a verdict. */
    fun unscreened(photos: List<Photo>): List<Photo> = photos.filter { it.id !in verdicts }
}
