package com.example.childsafetyimpl.ui.illustration

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.sin

/**
 * The illustration on the child's block screen: a moon and a slow, even breath.
 *
 * Chosen against the obvious alternatives on purpose. A padlock or a stop sign frames the parent
 * as an adversary, which is precisely the dynamic that makes children work around these apps. A
 * breathing rhythm, at roughly the pace of a calm exhale, gives the moment a shape instead of a
 * verdict. The animation is slow enough to actually settle someone rather than to decorate.
 */
@Composable
fun PausedMoment(
    modifier: Modifier = Modifier,
    size: Dp = 150.dp,
) {
    val transition = rememberInfiniteTransition(label = "pausedBreath")
    val breath by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4200),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathPhase",
    )

    Box(modifier = modifier.size(size)) {
        Canvas(modifier = Modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height
            val center = Offset(w / 2f, h / 2f)

            // Two haloes expanding on the inhale and settling on the exhale.
            listOf(0.42f, 0.34f).forEachIndexed { index, base ->
                val scale = base + breath * (0.07f - index * 0.02f)
                drawCircle(
                    color = Color.White.copy(alpha = 0.10f - index * 0.03f),
                    radius = w * scale,
                    center = center,
                )
            }

            // The moon, drawn as a disc with a bite taken out of it.
            drawCircle(color = Color(0xFFFFF6E3), radius = w * 0.24f, center = center)
            drawCircle(
                color = Color(0xFF3E6BF0),
                radius = w * 0.20f,
                center = Offset(center.x + w * 0.11f, center.y - w * 0.07f),
            )

            // A few stars, offset from the breath so the scene does not pulse as one object.
            listOf(
                Triple(0.20f, 0.24f, 0.030f),
                Triple(0.80f, 0.30f, 0.022f),
                Triple(0.28f, 0.76f, 0.018f),
                Triple(0.74f, 0.72f, 0.026f),
            ).forEachIndexed { index, (x, y, radius) ->
                val twinkle = 0.55f + 0.45f * sin((breath * 6.2831853f) + index * 1.6f)
                drawCircle(
                    color = Color.White.copy(alpha = twinkle * 0.85f),
                    radius = w * radius,
                    center = Offset(w * x, h * y),
                )
            }

            // The breath itself, as an arc that fills and empties.
            val arcInset = w * 0.06f
            drawArc(
                color = Color.White.copy(alpha = 0.18f),
                startAngle = 130f,
                sweepAngle = 280f,
                useCenter = false,
                topLeft = Offset(arcInset, arcInset),
                size = Size(w - arcInset * 2, h - arcInset * 2),
                style = Stroke(width = w * 0.02f, cap = StrokeCap.Round),
            )
            drawArc(
                color = Color.White.copy(alpha = 0.75f),
                startAngle = 130f,
                sweepAngle = 280f * breath,
                useCenter = false,
                topLeft = Offset(arcInset, arcInset),
                size = Size(w - arcInset * 2, h - arcInset * 2),
                style = Stroke(width = w * 0.02f, cap = StrokeCap.Round),
            )
        }
    }
}
