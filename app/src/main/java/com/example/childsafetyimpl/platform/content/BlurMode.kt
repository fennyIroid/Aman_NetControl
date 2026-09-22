package com.example.childsafetyimpl.platform.content

/**
 * Whether a failing frame is covered entirely or only where the subjects are.
 *
 * Both are real products and the choice is not a preference, so it is a setting rather than a
 * constant. Covering the whole screen says "do not watch this". Covering the subjects says "this
 * page is fine, that part of it is not", which is what the client's reference shows and what a
 * values filter is usually asked for: the article stays readable and only the thing the family
 * chose to avoid is hidden.
 *
 * They also fail differently, which is the part worth knowing before choosing. A whole-screen
 * cover cannot miss anything inside the frame, and it takes the entire screen away over one
 * uncertain verdict. A region cover is proportionate and misses whatever the detector did: a
 * person turned away, a face too small, a body with no face in shot.
 */
enum class BlurMode(val label: String, val description: String) {

    /**
     * Cover the pictures that failed, and nothing else. The default.
     *
     * This is the behaviour the reference asks for and the one a values filter is normally wanted
     * for: the page stays usable and only the picture the family chose to avoid is hidden. It is
     * also the only mode that does anything useful on the screen people are most often looking at.
     * A grid of search results is mostly grid, so a verdict about the whole frame is a verdict
     * about the layout; judging each picture separately is what makes one bad thumbnail among
     * twelve detectable at all.
     *
     * It is the default despite covering less, because covering everything over one uncertain
     * verdict is not the stronger choice it looks like. A parent whose child's homework search
     * goes black turns the protection off, and then nothing is covered at all.
     */
    IMAGES(
        label = "Cover only the images",
        description = "Covers the pictures that fail and leaves the rest of the screen usable. " +
            "Each picture is judged on its own, so one image among many can be covered.",
    ),

    /**
     * Cover everything in the frame.
     *
     * Nothing inside the frame can escape a cover that covers all of it, so this remains the
     * strongest setting and the right one where any exposure is unacceptable. The cost is that a
     * single uncertain verdict takes the whole screen, including the way out of it.
     */
    FULL_SCREEN(
        label = "Cover the whole screen",
        description = "The strongest setting. Nothing in the picture can be missed, and the " +
            "whole screen is taken away while it applies.",
    ),

    /**
     * Cover every person on screen, whatever they are doing.
     *
     * The mode that is not about safety at all, and the distinction is the reason it exists. The
     * other two ask whether a picture is unsafe; this one asks only whether there is a person in
     * it. A family who has chosen not to see people, or not to see people of one appearance, means
     * an ordinary clothed photograph too, and routing that through a safety classifier answers a
     * question they did not ask — which is what it used to do, so a page of perfectly ordinary
     * portraits went uncovered behind a switch that read as on.
     *
     * It depends on face detection finding people, so it inherits that detector's misses: someone
     * turned away, far from the camera, or with their face out of shot. Stated on the settings
     * screen rather than discovered by a parent whose child found the angle that is not covered.
     */
    SUBJECTS(
        label = "Cover people only",
        description = "Covers every person on screen and leaves the rest readable, whether or " +
            "not the picture is unsafe. Depends on each person being detected, so someone " +
            "turned away or far from the camera may not be covered.",
    );

    companion object {
        val default: BlurMode = IMAGES
    }
}
