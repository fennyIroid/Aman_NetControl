package com.example.childsafetyimpl.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import com.example.childsafetyimpl.ui.theme.SafetyMotion

/**
 * Gives a composable the springy press response used across the app.
 *
 * Returned as a pair so the caller passes the same interaction source to its own clickable; that
 * keeps the visual feedback tied to the real press state rather than to a second, guessed one.
 */
@Composable
fun rememberPressScale(
    pressedScale: Float = 0.972f,
    enabled: Boolean = true,
): Pair<MutableInteractionSource, Modifier> {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        animationSpec = SafetyMotion.press(),
        label = "pressScale",
    )
    return interactionSource to Modifier.scale(scale)
}
