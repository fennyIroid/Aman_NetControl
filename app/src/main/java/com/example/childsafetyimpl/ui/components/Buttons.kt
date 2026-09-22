package com.example.childsafetyimpl.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.childsafetyimpl.ui.theme.SafetyMotion
import com.example.childsafetyimpl.ui.theme.SafetyPalette
import com.example.childsafetyimpl.ui.theme.SafetyRadius
import com.example.childsafetyimpl.ui.theme.calmGradient

/** Filled action button. Springs on press and fades while a probe is running. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    brush: Brush = calmGradient,
    /** Explicit, because the button is also used on light fills where white text would vanish. */
    contentColor: Color = Color.White,
    /** Trailing rather than leading, for actions that move the user forward. */
    iconAtEnd: Boolean = false,
) {
    val (interactionSource, scaleModifier) = rememberPressScale(enabled = enabled && !loading)
    val alpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.45f,
        animationSpec = SafetyMotion.press(),
        label = "buttonAlpha",
    )
    Box(
        modifier = modifier
            .then(scaleModifier)
            .alpha(alpha)
            .shadowSoft(SafetyRadius.field)
            .clip(SafetyRadius.field)
            .background(brush)
            .clickable(
                enabled = enabled && !loading,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .defaultMinSize(minHeight = 52.dp)
            .padding(horizontal = 22.dp, vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(17.dp),
                    color = contentColor,
                    strokeWidth = 2.dp,
                )
            } else if (icon != null && !iconAtEnd) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
            )
            if (icon != null && iconAtEnd && !loading) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** Outlined counterpart, used for secondary or reversible actions. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    contentColor: Color = SafetyPalette.Indigo,
) {
    val (interactionSource, scaleModifier) = rememberPressScale(enabled = enabled)
    Box(
        modifier = modifier
            .then(scaleModifier)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(SafetyRadius.field)
            .background(SafetyPalette.Surface)
            .border(1.5.dp, SolidColor(contentColor.copy(alpha = 0.35f)), SafetyRadius.field)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .defaultMinSize(minHeight = 52.dp)
            .padding(horizontal = 20.dp, vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(18.dp))
            }
            Text(text = text, style = MaterialTheme.typography.labelLarge, color = contentColor)
        }
    }
}

/** Compact text action for inline use inside cards. */
@Composable
fun InlineAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    color: Color = SafetyPalette.Indigo,
) {
    val (interactionSource, scaleModifier) = rememberPressScale(pressedScale = 0.93f)
    Row(
        modifier = modifier
            .then(scaleModifier)
            .clip(SafetyRadius.chip)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge, color = color)
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(15.dp))
        }
    }
}
