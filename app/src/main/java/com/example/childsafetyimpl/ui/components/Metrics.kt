package com.example.childsafetyimpl.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.childsafetyimpl.ui.theme.SafetyMotion
import com.example.childsafetyimpl.ui.theme.SafetyPalette
import com.example.childsafetyimpl.ui.theme.SafetyRadius
import kotlin.math.min

/**
 * The screen-time ring.
 *
 * Drawn rather than assembled from a progress indicator so the track, the sweep and the small
 * end cap can share one geometry. The cap matters: it is what makes a nearly-full ring still read
 * as "nearly", instead of snapping visually to complete.
 */
@Composable
fun ProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    diameter: Dp = 168.dp,
    strokeWidth: Dp = 15.dp,
    trackColor: Color = SafetyPalette.IndigoSoft,
    brush: Brush = Brush.sweepGradient(
        listOf(SafetyPalette.Sky, SafetyPalette.Indigo, SafetyPalette.Lavender, SafetyPalette.Sky),
    ),
    center: @Composable () -> Unit = {},
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = SafetyMotion.value(),
        label = "ringProgress",
    )
    Box(modifier = modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = strokeWidth.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            if (animated > 0.001f) {
                drawArc(
                    brush = brush,
                    startAngle = -90f,
                    sweepAngle = 360f * animated,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        center()
    }
}

/** Ring plus the label stack the dashboard puts inside it. */
@Composable
fun ScreenTimeRing(
    progress: Float,
    headline: String,
    caption: String,
    modifier: Modifier = Modifier,
    footnote: String? = null,
) {
    ProgressRing(progress = progress, modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = headline,
                style = MaterialTheme.typography.displaySmall,
                color = SafetyPalette.Ink,
            )
            Text(
                text = caption,
                style = MaterialTheme.typography.labelMedium,
                color = SafetyPalette.Slate,
                textAlign = TextAlign.Center,
            )
            if (footnote != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = footnote,
                    style = MaterialTheme.typography.labelSmall,
                    color = SafetyPalette.Mist,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Animated horizontal meter used for per-app usage and per-category budgets. */
@Composable
fun MeterBar(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 8.dp,
    color: Color = SafetyPalette.Indigo,
    trackColor: Color = SafetyPalette.Line,
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = SafetyMotion.value(),
        label = "meterProgress",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(percent = 50))
            .background(trackColor),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animated)
                .height(height)
                .clip(RoundedCornerShape(percent = 50))
                .background(color),
        )
    }
}

/**
 * The stacked status bar: one segment per protection state, sized by share.
 *
 * This is the densest element in the dashboard: it answers, at a glance, how much of the
 * protection a parent switched on is genuinely running right now.
 */
@Composable
fun StatusStackBar(
    segments: List<Triple<String, Int, Color>>,
    modifier: Modifier = Modifier,
    height: Dp = 12.dp,
) {
    val total = segments.sumOf { it.second }.coerceAtLeast(1)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(percent = 50)),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        segments.filter { it.second > 0 }.forEach { (_, count, color) ->
            val animated by animateFloatAsState(
                targetValue = count.toFloat() / total,
                animationSpec = SafetyMotion.value(),
                label = "stackSegment",
            )
            Box(
                modifier = Modifier
                    .weight(animated.coerceAtLeast(0.02f))
                    .fillMaxWidth()
                    .height(height)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(color),
            )
        }
    }
}

/** Compact number tile for the dashboard grid. */
@Composable
fun StatTile(
    value: String,
    label: String,
    accent: Color,
    container: Color,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: (() -> Unit)? = null,
) {
    SafetyCard(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(15.dp),
        onClick = onClick,
    ) {
        if (icon != null) {
            IconBadge(icon = icon, tint = accent, container = container, size = 34)
            Spacer(Modifier.height(11.dp))
        }
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            color = SafetyPalette.Ink,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = SafetyPalette.Slate,
        )
    }
}

/** Sparkline for the seven-day usage trend. Bars, not a line: daily totals are discrete. */
@Composable
fun DayBars(
    values: List<Float>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    highlightIndex: Int = values.lastIndex,
    barHeight: Dp = 92.dp,
) {
    val max = (values.maxOrNull() ?: 0f).coerceAtLeast(0.0001f)
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        values.forEachIndexed { index, value ->
            val fraction = min(1f, value / max)
            val animated by animateFloatAsState(
                targetValue = fraction,
                animationSpec = SafetyMotion.value(),
                label = "dayBar$index",
            )
            val isHighlight = index == highlightIndex
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(barHeight),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(barHeight)
                            .clip(SafetyRadius.chip)
                            .background(SafetyPalette.Canvas),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height((barHeight.value * animated.coerceAtLeast(0.04f)).dp)
                            .clip(SafetyRadius.chip)
                            .background(
                                if (isHighlight) SafetyPalette.Indigo else SafetyPalette.IndigoMuted,
                            ),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = labels.getOrElse(index) { "" },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isHighlight) SafetyPalette.Indigo else SafetyPalette.Mist,
                )
            }
        }
    }
}

/** Legend entry paired with the stacked verdict bar. */
@Composable
fun LegendDot(
    color: Color,
    label: String,
    count: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(color),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = SafetyPalette.Slate,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(2.dp))
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.titleSmall,
            color = SafetyPalette.Ink,
        )
    }
}
