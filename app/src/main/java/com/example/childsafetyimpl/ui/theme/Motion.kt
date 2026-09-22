package com.example.childsafetyimpl.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * One motion vocabulary for the whole app.
 *
 * Screens ease, controls spring. Keeping those two families separate is what stops a UI full of
 * animation from feeling arbitrary: navigation is a place changing, a press is a thing responding.
 */
object SafetyMotion {

    /** Decelerating curve for anything entering the screen. */
    val enterEasing: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** Accelerating curve for anything leaving. */
    val exitEasing: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    const val SCREEN_ENTER_MS = 340
    const val SCREEN_EXIT_MS = 240
    const val CONTENT_FADE_MS = 200

    fun <T> screenEnter(): FiniteAnimationSpec<T> = tween(SCREEN_ENTER_MS, easing = enterEasing)

    fun <T> screenExit(): FiniteAnimationSpec<T> = tween(SCREEN_EXIT_MS, easing = exitEasing)

    fun <T> contentFade(delayMs: Int = 0): FiniteAnimationSpec<T> =
        tween(CONTENT_FADE_MS, delayMillis = delayMs, easing = enterEasing)

    /** The press response: quick, slightly springy, never bouncy enough to look playful. */
    fun <T> press(): FiniteAnimationSpec<T> = spring(
        dampingRatio = 0.62f,
        stiffness = Spring.StiffnessMediumLow,
    )

    /** Value changes that should feel alive: a ring filling, a bar growing. */
    fun <T> value(): FiniteAnimationSpec<T> = spring(
        dampingRatio = 0.85f,
        stiffness = Spring.StiffnessLow,
    )

    /** Staggered list entry. Capped so a long list never feels like it is loading slowly. */
    fun staggerDelay(index: Int, step: Int = 45, max: Int = 320): Int =
        (index * step).coerceAtMost(max)
}
