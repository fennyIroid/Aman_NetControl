package com.example.childsafetyimpl.ui.illustration

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.childsafetyimpl.ui.theme.SafetyPalette
import kotlin.math.abs
import kotlin.math.sin

/**
 * Traffic meeting the filter.
 *
 * Packets travel left to right toward a gate in the middle. Allowed ones pass through and continue;
 * blocked ones stop at the gate and fade. The ratio of the two is driven by the real block count
 * from the running tunnel, so the picture is a readout, not an animation loop.
 */
@Composable
fun NetworkFlow(
    modifier: Modifier = Modifier,
    height: Dp = 128.dp,
    active: Boolean = false,
    /** 0..1 share of recent queries that were blocked. Sets how many packets stop at the gate. */
    blockedShare: Float = 0f,
    accent: Color = SafetyPalette.Indigo,
) {
    val transition = rememberInfiniteTransition(label = "netFlow")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "flowPhase",
    )

    Canvas(modifier = modifier.fillMaxWidth().height(height)) {
        val w = size.width
        val h = size.height
        val gateX = w * 0.52f
        val lanes = listOf(0.28f, 0.5f, 0.72f)

        // The device on the left, the wider internet on the right.
        drawRoundRect(
            color = accent.copy(alpha = 0.10f),
            topLeft = Offset(w * 0.02f, h * 0.22f),
            size = Size(w * 0.13f, h * 0.56f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()),
        )
        drawCircle(
            color = accent.copy(alpha = 0.10f),
            radius = h * 0.30f,
            center = Offset(w * 0.90f, h * 0.5f),
        )

        // The gate itself.
        val gateColor = if (active) accent else SafetyPalette.Mist
        drawRoundRect(
            color = gateColor.copy(alpha = if (active) 0.16f else 0.10f),
            topLeft = Offset(gateX - w * 0.045f, h * 0.10f),
            size = Size(w * 0.09f, h * 0.80f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()),
        )
        drawLine(
            color = gateColor,
            start = Offset(gateX, h * 0.12f),
            end = Offset(gateX, h * 0.88f),
            strokeWidth = 2.5f.dp.toPx(),
            pathEffect = if (active) null
            else PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 7.dp.toPx())),
        )

        lanes.forEachIndexed { laneIndex, laneY ->
            val y = h * laneY
            drawLine(
                color = SafetyPalette.Line,
                start = Offset(w * 0.16f, y),
                end = Offset(w * 0.86f, y),
                strokeWidth = 1.2f.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 6.dp.toPx())),
            )

            // Three packets per lane, evenly offset so the flow never looks synchronised.
            repeat(3) { packetIndex ->
                val offset = (phase + laneIndex * 0.31f + packetIndex * 0.33f) % 1f
                // A lane is a "blocked" lane if its index falls under the blocked share.
                val laneBlocked = active && (laneIndex + 1f) / lanes.size <= blockedShare + 0.001f
                val startX = w * 0.16f
                val endX = if (laneBlocked) gateX - w * 0.055f else w * 0.86f
                val x = startX + (endX - startX) * offset

                val fade = when {
                    laneBlocked && offset > 0.82f -> (1f - (offset - 0.82f) / 0.18f)
                    else -> 1f
                }
                val color = if (laneBlocked) SafetyPalette.Coral else accent
                val wobble = sin((offset * 6.2831853f) + laneIndex) * h * 0.012f

                drawCircle(
                    color = color.copy(alpha = 0.20f * fade),
                    radius = 8.dp.toPx(),
                    center = Offset(x, y + wobble),
                )
                drawCircle(
                    color = color.copy(alpha = fade),
                    radius = 3.5f.dp.toPx(),
                    center = Offset(x, y + wobble),
                )

                // A blocked packet leaves a short recoil mark at the gate.
                if (laneBlocked && offset > 0.86f) {
                    val recoil = (offset - 0.86f) / 0.14f
                    drawArc(
                        color = SafetyPalette.Coral.copy(alpha = (1f - recoil) * 0.6f),
                        startAngle = 120f,
                        sweepAngle = 120f,
                        useCenter = false,
                        topLeft = Offset(
                            gateX - w * 0.035f - abs(recoil) * w * 0.01f,
                            y - h * 0.09f,
                        ),
                        size = Size(w * 0.05f, h * 0.18f),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }
        }
    }
}
