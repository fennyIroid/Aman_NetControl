package com.example.childsafetyimpl.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.childsafetyimpl.core.protection.ProtectionState
import com.example.childsafetyimpl.ui.theme.SafetyPalette
import com.example.childsafetyimpl.ui.theme.SafetyRadius
import com.example.childsafetyimpl.ui.theme.tone

/** The protection chip. Its colour is derived from the state, so it can never contradict the label. */
@Composable
fun StatusChip(
    status: ProtectionState,
    modifier: Modifier = Modifier,
    useShortLabel: Boolean = true,
) {
    val tone = status.tone
    Row(
        modifier = modifier
            .clip(SafetyRadius.chip)
            .background(tone.container)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(tone.content),
        )
        Text(
            text = if (useShortLabel) status.shortLabel else status.label,
            style = MaterialTheme.typography.labelMedium,
            color = tone.content,
        )
    }
}

/** Generic tinted chip for counts, categories and permission names. */
@Composable
fun TonalChip(
    text: String,
    content: Color,
    container: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .clip(SafetyRadius.chip)
            .background(container)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(13.dp))
        }
        Text(text = text, style = MaterialTheme.typography.labelMedium, color = content)
    }
}

/** Chip that reads a live grant state: granted, missing, or impossible on this device. */
@Composable
fun GrantChip(
    text: String,
    granted: Boolean,
    modifier: Modifier = Modifier,
    unavailable: Boolean = false,
) {
    val content = when {
        unavailable -> SafetyPalette.Slate
        granted -> Color(0xFF0E8F5E)
        else -> Color(0xFFB26A00)
    }
    val container = when {
        unavailable -> Color(0xFFEFF1F7)
        granted -> SafetyPalette.MintSoft
        else -> SafetyPalette.WarningSoft
    }
    TonalChip(text = text, content = content, container = container, modifier = modifier)
}

/** Filter pill used on the report and event screens. */
@Composable
fun FilterPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: Int? = null,
) {
    val (interactionSource, scaleModifier) = rememberPressScale(pressedScale = 0.94f)
    Row(
        modifier = modifier
            .then(scaleModifier)
            .clip(SafetyRadius.chip)
            .background(if (selected) SafetyPalette.Indigo else SafetyPalette.Surface)
            .then(
                if (selected) Modifier
                else Modifier.background(SafetyPalette.Surface)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Color.White else SafetyPalette.Slate,
        )
        if (count != null) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) Color(0xCCFFFFFF) else SafetyPalette.Mist,
            )
        }
    }
}
