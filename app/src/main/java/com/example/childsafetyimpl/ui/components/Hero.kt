package com.example.childsafetyimpl.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.example.childsafetyimpl.ui.theme.SafetyRadius
import com.example.childsafetyimpl.ui.theme.brandGradient

/**
 * The blue hero that anchors every screen.
 *
 * Two slowly drifting light blooms sit behind the content. They are the one piece of ambient
 * motion in the app: a safety product should feel awake without demanding attention, and a static
 * gradient reads as a screenshot rather than a live monitor.
 */
@Composable
fun HeroHeader(
    modifier: Modifier = Modifier,
    brush: Brush = brandGradient,
    shape: Shape = SafetyRadius.hero,
    animated: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "heroBloom")
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(9000),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "heroDrift",
    )
    val phase = if (animated) drift else 0.5f

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(brush)
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0x33FFFFFF), Color.Transparent),
                        center = Offset(size.width * (0.82f - phase * 0.08f), size.height * 0.12f),
                        radius = size.width * 0.55f,
                    ),
                    radius = size.width * 0.55f,
                    center = Offset(size.width * (0.82f - phase * 0.08f), size.height * 0.12f),
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0x26FFFFFF), Color.Transparent),
                        center = Offset(size.width * (0.12f + phase * 0.1f), size.height * 0.88f),
                        radius = size.width * 0.45f,
                    ),
                    radius = size.width * 0.45f,
                    center = Offset(size.width * (0.12f + phase * 0.1f), size.height * 0.88f),
                )
            },
    ) {
        Column(
            modifier = Modifier
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            content = content,
        )
    }
}

/** Hero variant carrying a back arrow, a title and an optional trailing action. */
@Composable
fun DetailHero(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    brush: Brush = brandGradient,
    trailing: (@Composable () -> Unit)? = null,
    belowTitle: (@Composable ColumnScope.() -> Unit)? = null,
) {
    HeroHeader(modifier = modifier, brush = brush) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircleIconButton(onClick = onBack)
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xCCFFFFFF),
                )
            }
            if (trailing != null) trailing()
        }
        if (belowTitle != null) {
            Spacer(Modifier.height(18.dp))
            belowTitle()
        }
        Spacer(Modifier.height(4.dp))
    }
}

/** Translucent round button used on top of the hero wash. */
@Composable
fun CircleIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Rounded.ArrowBackIosNew,
    tint: Color = Color.White,
    container: Color = Color(0x2EFFFFFF),
    size: Int = 38,
) {
    val (interactionSource, scaleModifier) = rememberPressScale(pressedScale = 0.9f)
    Box(
        modifier = modifier
            .then(scaleModifier)
            .size(size.dp)
            .clip(CircleShape)
            .background(container)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size((size * 0.42f).dp),
        )
    }
}

/** Row of translucent stat blocks that sits inside a hero. */
@Composable
fun HeroStatRow(
    stats: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        stats.forEach { (value, label) ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(SafetyRadius.tile)
                    .background(Color(0x24FFFFFF))
                    .padding(horizontal = 12.dp, vertical = 11.dp),
            ) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xB8FFFFFF),
                )
            }
        }
    }
}

/** Lets a caller overlay a card that straddles the hero's bottom edge. */
@Composable
fun BoxScope.OverlapCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    SafetyCard(
        modifier = modifier
            .align(Alignment.BottomCenter)
            .padding(horizontal = 20.dp),
        content = content,
    )
}
