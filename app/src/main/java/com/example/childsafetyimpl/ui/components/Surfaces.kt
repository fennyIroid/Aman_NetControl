package com.example.childsafetyimpl.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ripple
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.childsafetyimpl.ui.theme.SafetyMotion
import com.example.childsafetyimpl.ui.theme.SafetyPalette
import com.example.childsafetyimpl.ui.theme.SafetyRadius

/**
 * The workhorse card. A single soft shadow and a hairline border, because a page of cards with
 * heavy elevation stops reading as calm very quickly.
 */
@Composable
fun SafetyCard(
    modifier: Modifier = Modifier,
    shape: Shape = SafetyRadius.card,
    background: Color = SafetyPalette.Surface,
    borderColor: Color = SafetyPalette.Line,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val (interactionSource, scaleModifier) = rememberPressScale(enabled = onClick != null)
    Box(
        modifier = modifier
            .then(scaleModifier)
            .shadowSoft(shape)
            .clip(shape)
            .background(background)
            .border(1.dp, borderColor, shape)
            .then(
                if (onClick != null) {
                    Modifier
                        .clickable(
                            interactionSource = interactionSource,
                            indication = ripple(color = SafetyPalette.Indigo),
                            onClick = onClick,
                        )
                } else {
                    Modifier
                }
            ),
    ) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

/**
 * A low, wide shadow. Compose's shadow is drawn under the shape, so a large radius with low alpha
 * gives the lifted-paper look without the grey halo a default elevation produces on a tinted page.
 */
fun Modifier.shadowSoft(shape: Shape, strong: Boolean = false): Modifier = this.graphicsLayer {
    this.shape = shape
    clip = false
    shadowElevation = if (strong) 18.dp.toPx() else 8.dp.toPx()
    ambientShadowColor = Color(0x1A1B2A5B)
    spotShadowColor = Color(0x241B2A5B)
}

/** Section label above a group of cards. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = SafetyPalette.Ink,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = SafetyPalette.Slate,
                )
            }
        }
        if (trailing != null) trailing()
    }
}

/** Round tinted icon badge used in list rows and stat tiles. */
@Composable
fun IconBadge(
    icon: ImageVector,
    tint: Color,
    container: Color,
    modifier: Modifier = Modifier,
    size: Int = 42,
    shape: Shape = RoundedCornerShape(14.dp),
) {
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(shape)
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size((size * 0.5f).dp),
        )
    }
}

/**
 * Staggered entrance for list content. The delay is capped in [SafetyMotion.staggerDelay] so a
 * long list still finishes arriving quickly.
 */
@Composable
fun StaggeredEntrance(
    index: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(SafetyMotion.contentFade(SafetyMotion.staggerDelay(index))) +
            slideInVertically(
                animationSpec = SafetyMotion.screenEnter(),
                initialOffsetY = { it / 6 },
            ),
        modifier = modifier,
    ) {
        content()
    }
}

/** Placeholder shimmer used while a probe or a query is still running. */
@Composable
fun ShimmerBlock(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp),
) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerProgress",
    )
    Box(
        modifier = modifier
            .clip(shape)
            .background(SafetyPalette.Line)
            .drawWithContent {
                drawContent()
                val sweep = size.width * 1.6f
                val start = -sweep + (progress * (size.width + sweep))
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, Color(0x66FFFFFF), Color.Transparent),
                        startX = start,
                        endX = start + sweep,
                    ),
                    size = size,
                )
            },
    )
}

/** Thin horizontal rule that matches the card border weight. */
@Composable
fun HairLine(modifier: Modifier = Modifier, inset: Int = 0) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = inset.dp)
            .height(1.dp)
            .background(SafetyPalette.Line),
    )
}

/** Key/value row used throughout the capability detail and probe result surfaces. */
@Composable
fun DetailRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = SafetyPalette.Ink,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = SafetyPalette.Slate,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = valueColor,
            modifier = Modifier.weight(1.3f),
        )
    }
}

/** Dimming helper for content that is present but currently inert. */
fun Modifier.dimmedWhen(condition: Boolean): Modifier =
    if (condition) this.alpha(0.45f) else this
