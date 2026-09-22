package com.example.childsafetyimpl.ui.illustration

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.childsafetyimpl.ui.theme.SafetyPalette
import kotlin.math.cos
import kotlin.math.sin

/**
 * A radar sweep with the found items plotted on it.
 *
 * Used wherever the app is enumerating something real: installed packages, clone candidates, the
 * foreground app. Each blip is one actual result, so a sparse sweep genuinely means a sparse scan,
 * and a scan that finds nothing looks like it found nothing.
 */
@Composable
fun RadarSweep(
    modifier: Modifier = Modifier,
    size: Dp = 132.dp,
    blipCount: Int = 0,
    running: Boolean = true,
    accent: Color = SafetyPalette.Indigo,
    /** Blips drawn in the alert colour, e.g. clone tools found rather than apps found. */
    alertCount: Int = 0,
) {
    val transition = rememberInfiniteTransition(label = "radar")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "radarSweep",
    )
    val angle = if (running) sweep else 0f

    Box(modifier = modifier.size(size)) {
        Canvas(modifier = Modifier.size(size)) {
            val r = this.size.minDimension / 2f
            val center = Offset(this.size.width / 2f, this.size.height / 2f)

            listOf(1f, 0.68f, 0.36f).forEach { scale ->
                drawCircle(
                    color = accent.copy(alpha = 0.16f),
                    radius = r * scale,
                    center = center,
                    style = Stroke(width = 1.4f.dp.toPx()),
                )
            }
            drawLine(
                color = accent.copy(alpha = 0.12f),
                start = Offset(center.x - r, center.y),
                end = Offset(center.x + r, center.y),
                strokeWidth = 1.dp.toPx(),
            )
            drawLine(
                color = accent.copy(alpha = 0.12f),
                start = Offset(center.x, center.y - r),
                end = Offset(center.x, center.y + r),
                strokeWidth = 1.dp.toPx(),
            )

            if (running) {
                rotate(degrees = angle, pivot = center) {
                    drawArc(
                        brush = Brush.sweepGradient(
                            colors = listOf(
                                accent.copy(alpha = 0f),
                                accent.copy(alpha = 0.02f),
                                accent.copy(alpha = 0.30f),
                            ),
                            center = center,
                        ),
                        startAngle = -90f,
                        sweepAngle = 78f,
                        useCenter = true,
                        topLeft = Offset(center.x - r, center.y - r),
                        size = Size(r * 2, r * 2),
                    )
                }
            }

            // Blips sit on a fixed pseudo-random spiral so the same scan always looks the same.
            val total = blipCount.coerceAtMost(26)
            repeat(total) { index ->
                val t = index * 2.399963f
                val radius = r * (0.24f + 0.68f * ((index + 1f) / (total + 1f)))
                val position = Offset(
                    center.x + radius * cos(t),
                    center.y + radius * sin(t),
                )
                val isAlert = index < alertCount
                val color = if (isAlert) SafetyPalette.Coral else accent
                drawCircle(color = color.copy(alpha = 0.22f), radius = 7.dp.toPx(), center = position)
                drawCircle(color = color, radius = 3.dp.toPx(), center = position)
            }

            drawCircle(color = accent, radius = 4.dp.toPx(), center = center)
        }
    }
}

/**
 * A shield with a piece missing, drawn as a dashed void rather than a solid mark.
 *
 * This is the picture for every capability Android refuses to give a normal app. It is used
 * deliberately and often: some of these gaps are permanent, and
 * the app should show a gap as a gap rather than as an error the user could fix.
 */
@Composable
fun BlindSpot(
    modifier: Modifier = Modifier,
    size: Dp = 108.dp,
    accent: Color = SafetyPalette.Mist,
) {
    Box(modifier = modifier.size(size)) {
        Canvas(modifier = Modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height
            val shield = shieldPath(w, h, inset = w * 0.18f)

            drawPath(shield, color = Color(0xFFF3F5FA))
            drawPath(
                path = shield,
                color = accent.copy(alpha = 0.55f),
                style = Stroke(
                    width = w * 0.038f,
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(w * 0.07f, w * 0.055f),
                    ),
                ),
            )

            // The void: a wedge cut out of the protected area.
            drawArc(
                color = Color.White,
                startAngle = -58f,
                sweepAngle = 72f,
                useCenter = true,
                topLeft = Offset(w * 0.5f - w * 0.36f, h * 0.5f - w * 0.36f),
                size = Size(w * 0.72f, w * 0.72f),
            )
            drawArc(
                color = accent.copy(alpha = 0.7f),
                startAngle = -58f,
                sweepAngle = 72f,
                useCenter = true,
                topLeft = Offset(w * 0.5f - w * 0.36f, h * 0.5f - w * 0.36f),
                size = Size(w * 0.72f, w * 0.72f),
                style = Stroke(
                    width = w * 0.028f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(w * 0.05f, w * 0.04f)),
                ),
            )
            drawPath(heartPath(w, h), color = accent.copy(alpha = 0.45f))
        }
    }
}
