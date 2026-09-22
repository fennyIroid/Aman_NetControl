package com.example.childsafetyimpl.ui.illustration

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.childsafetyimpl.ui.theme.SafetyMotion
import com.example.childsafetyimpl.ui.theme.SafetyPalette

/** How much of the protection is actually running, expressed as the shield's own state. */
enum class ShieldState { ACTIVE, PARTIAL, INACTIVE }

/**
 * The app's primary mark: a shield holding a heart.
 *
 * It is state-bearing rather than decorative. A shield that is fully set up breathes with a steady
 * outward pulse; a partly set up one carries a broken outline and a slower, uneven pulse; an
 * inactive one goes grey and stops moving altogether. A parent can read protection status from
 * across the room without reading a word.
 */
@Composable
fun ShieldPulse(
    state: ShieldState,
    modifier: Modifier = Modifier,
    size: Dp = 108.dp,
    /** 0..1 share of the protection that is live. Drives how much of the shield is filled. */
    coverage: Float = 1f,
) {
    val transition = rememberInfiniteTransition(label = "shieldPulse")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (state == ShieldState.PARTIAL) 3400 else 2400),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulsePhase",
    )

    val accent by animateColorAsState(
        targetValue = when (state) {
            ShieldState.ACTIVE -> SafetyPalette.Mint
            ShieldState.PARTIAL -> SafetyPalette.Apricot
            ShieldState.INACTIVE -> SafetyPalette.Mist
        },
        animationSpec = SafetyMotion.value(),
        label = "shieldAccent",
    )
    val body by animateColorAsState(
        targetValue = when (state) {
            ShieldState.ACTIVE -> Color.White
            ShieldState.PARTIAL -> Color.White
            ShieldState.INACTIVE -> Color(0xFFF0F2F8)
        },
        animationSpec = SafetyMotion.value(),
        label = "shieldBody",
    )

    Box(modifier = modifier.size(size)) {
        Canvas(modifier = Modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height

            // Outward pulse rings: the visible sign that monitoring is live.
            if (state != ShieldState.INACTIVE) {
                listOf(pulse, (pulse + 0.5f) % 1f).forEach { phase ->
                    val radius = w * (0.34f + phase * 0.28f)
                    drawCircle(
                        color = accent.copy(alpha = (1f - phase) * 0.22f),
                        radius = radius,
                        center = Offset(w / 2f, h / 2f),
                    )
                }
            }

            val shield = shieldPath(w, h, inset = w * 0.18f)

            // Filled coverage: the shield only fills as far as the protection actually reaches.
            drawPath(shield, color = body)
            if (coverage > 0.001f) {
                clipRectFraction(coverage) {
                    drawPath(shield, color = accent.copy(alpha = 0.18f))
                }
                // A level line at the fill boundary. Without it the clip edge reads as a seam in
                // the artwork rather than as a gauge showing how far protection reaches.
                if (coverage < 0.995f) {
                    val level = h * (1f - coverage.coerceIn(0f, 1f))
                    clipRectFraction(coverage) {
                        drawLine(
                            color = accent.copy(alpha = 0.55f),
                            start = Offset(w * 0.16f, level),
                            end = Offset(w * 0.84f, level),
                            strokeWidth = w * 0.016f,
                        )
                    }
                }
            }
            drawPath(
                path = shield,
                color = accent,
                style = Stroke(width = w * 0.045f),
            )

            // A partial setup shows a literal gap in the outline, top-right.
            if (state == ShieldState.PARTIAL) {
                drawArc(
                    color = body,
                    startAngle = -70f,
                    sweepAngle = 46f,
                    useCenter = false,
                    topLeft = Offset(w * 0.12f, h * 0.10f),
                    size = Size(w * 0.76f, h * 0.72f),
                    style = Stroke(width = w * 0.07f),
                )
            }

            drawPath(heartPath(w, h), color = accent)
        }
    }
}

/** A rounded shield outline sized to the canvas. */
internal fun shieldPath(w: Float, h: Float, inset: Float): Path = Path().apply {
    val left = inset
    val right = w - inset
    val top = h * 0.14f
    val bottom = h * 0.90f
    val midX = w / 2f
    val shoulder = h * 0.44f

    moveTo(midX, top)
    cubicTo(midX + (right - midX) * 0.55f, top, right, top + h * 0.05f, right, top + h * 0.09f)
    lineTo(right, shoulder)
    cubicTo(right, bottom - h * 0.16f, midX + (right - midX) * 0.52f, bottom - h * 0.02f, midX, bottom)
    cubicTo(midX - (midX - left) * 0.52f, bottom - h * 0.02f, left, bottom - h * 0.16f, left, shoulder)
    lineTo(left, top + h * 0.09f)
    cubicTo(left, top + h * 0.05f, midX - (midX - left) * 0.55f, top, midX, top)
    close()
}

/** The heart at the shield's centre: what is being protected, not just that something is. */
internal fun heartPath(w: Float, h: Float): Path = Path().apply {
    val cx = w / 2f
    val cy = h * 0.50f
    val s = w * 0.115f

    moveTo(cx, cy + s * 1.05f)
    cubicTo(cx - s * 2.0f, cy - s * 0.25f, cx - s * 0.85f, cy - s * 1.55f, cx, cy - s * 0.42f)
    cubicTo(cx + s * 0.85f, cy - s * 1.55f, cx + s * 2.0f, cy - s * 0.25f, cx, cy + s * 1.05f)
    close()
}

/** Clips drawing to the bottom [fraction] of the canvas, so fill rises rather than wipes across. */
internal inline fun DrawScope.clipRectFraction(fraction: Float, block: DrawScope.() -> Unit) {
    val clipTop = size.height * (1f - fraction.coerceIn(0f, 1f))
    clipRect(left = 0f, top = clipTop, right = size.width, bottom = size.height) { block() }
}
