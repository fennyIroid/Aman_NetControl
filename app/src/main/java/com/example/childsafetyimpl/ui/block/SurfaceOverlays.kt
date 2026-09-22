package com.example.childsafetyimpl.ui.block

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VisibilityOff
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.childsafetyimpl.platform.enforcement.SurfaceBlockDecision
import com.example.childsafetyimpl.ui.components.IconBadge
import com.example.childsafetyimpl.ui.components.shadowSoft
import com.example.childsafetyimpl.ui.theme.ChildSafetyTheme
import com.example.childsafetyimpl.ui.theme.SafetyMotion
import com.example.childsafetyimpl.ui.theme.SafetyPalette
import com.example.childsafetyimpl.ui.theme.SafetyRadius
import kotlin.math.sin
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * The card shown after a restricted screen inside a permitted app was closed.
 *
 * Small, top-anchored and self-dismissing. The child has just been handed back to the app they
 * were in, and a full block screen at that moment would read as a punishment for something they
 * may have reached by tapping the wrong tab.
 */
@Composable
fun SurfaceBlockNotice(decision: SurfaceBlockDecision) {
    ChildSafetyTheme {
        var visible by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { visible = true }

        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(SafetyMotion.screenEnter()) { -it } +
                fadeIn(SafetyMotion.contentFade()),
            exit = slideOutVertically(SafetyMotion.screenExit()) { -it } +
                fadeOut(SafetyMotion.contentFade()),
        ) {
            Row(
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .fillMaxWidth()
                    .shadowSoft(SafetyRadius.card, strong = true)
                    .clip(SafetyRadius.card)
                    .background(SafetyPalette.Surface)
                    .padding(horizontal = 15.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBadge(
                    icon = Icons.Rounded.Shield,
                    tint = SafetyPalette.Indigo,
                    container = SafetyPalette.IndigoSoft,
                    size = 38,
                )
                Spacer(Modifier.size(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = decision.surface.childFacingName,
                        style = MaterialTheme.typography.titleSmall,
                        color = SafetyPalette.Ink,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        // When the app refused the back action the child is still looking at the
                        // feed, and saying "closed" would be a visible lie.
                        text = if (decision.closed) {
                            decision.message
                        } else {
                            "${decision.message} ${decision.appLabel} did not close it, so please " +
                                "leave this screen yourself."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = SafetyPalette.Slate,
                    )
                }
            }
        }
    }
}

/**
 * The frosted layer drawn over unsafe video.
 *
 * Three things happen at once, because no single one of them is enough.
 *
 *  - From Android 12 the window manager can blur what is behind a window, and the controller asks
 *    it to. That is a real blur of the live picture, and it is the part that destroys detail.
 *  - Under that, a heavy wash. A tint alone only lowers contrast: every edge and silhouette
 *    survives it, which is why a lightly dimmed photograph is still perfectly readable. The wash is
 *    therefore weighted to hide rather than to dim, and it carries the whole job on a device or a
 *    build where the window manager will not blur.
 *  - Over that, a slow-drifting fog, so the layer reads as frosted glass rather than as a device
 *    that has switched itself off.
 *
 * What it cannot be is opaque. This cover passes touches through, so that the child can still reach
 * the video's own pause and back controls, and from Android 12 the system caps the opacity of any
 * pass-through overlay at 0.8 whatever the app asks for. The wash below is written for the cover it
 * would be without that cap, and the cap is the reason covering individual pictures is the default
 * mode and this one is the fallback: a picture-sized cover takes its own touches, so it is allowed
 * to be opaque, and it destroys the detail in the pixels rather than washing over it.
 *
 * The child is told what happened, and nothing else. Which model fired, what it scored and how much
 * skin tone was measured are the parent's business, and they are written to the event log where the
 * parent can read them. Reciting them over the video explains to a child exactly what to avoid
 * showing the camera next time.
 */
@Composable
fun VideoBlurContent(strength: Float) {
    ChildSafetyTheme {
        // Weighted to hide, and the weighting is not a matter of taste. What survives a wash is
        // (1 - alpha) of the original contrast, and the eye reads a silhouette at a few percent
        // of it: at 0.90 a photograph is still perfectly legible through the cover, which is the
        // whole reason a dimmed screen is not a covered one. The range below leaves at most about
        // one percent, and the lowest setting a parent can choose still has to be a cover.
        val wash = 0.955f + strength.coerceIn(0f, 1f) * 0.04f
        val drift = rememberDrift()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(SafetyPalette.Ink.copy(alpha = wash))
                .fogLayer(drift),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(Color(0x2EFFFFFF)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.VisibilityOff,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(32.dp),
                    )
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    text = "Restricted content",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "This is covered while it plays.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xCCFFFFFF),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "It clears on its own when the video changes.",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0x99FFFFFF),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Overlapping soft blooms, drawn over the wash rather than under it.
 *
 * Two jobs. It stops a cover this heavy from reading as a screen that has switched itself off, and
 * because it sits on top it takes another slice out of whatever contrast the wash left behind.
 *
 * Sized and placed from the measured window, because a Brush offset is in pixels: a fraction here
 * would pin every bloom to the top-left corner on a phone and leave the rest of the screen flat.
 */
private fun Modifier.fogLayer(drift: Float): Modifier = drawWithCache {
    val w = size.width
    val h = size.height
    val blooms = listOf(
        Triple(Offset(w * 0.24f, h * 0.30f), w * (0.78f + drift * 0.05f), 0.15f),
        Triple(Offset(w * 0.80f, h * 0.22f), w * (0.62f - drift * 0.04f), 0.11f),
        Triple(Offset(w * 0.58f, h * 0.74f), w * (0.86f + drift * 0.06f), 0.14f),
        Triple(Offset(w * 0.12f, h * 0.88f), w * (0.55f - drift * 0.05f), 0.09f),
    ).map { (centre, radius, peak) ->
        Brush.radialGradient(
            colors = listOf(
                Color.White.copy(alpha = peak),
                Color.White.copy(alpha = peak * 0.35f),
                Color.Transparent,
            ),
            center = centre,
            radius = radius.coerceAtLeast(1f),
        )
    }
    onDrawWithContent {
        drawContent()
        blooms.forEach { drawRect(brush = it) }
    }
}

/** A slow oscillation, so the fog never reads as a frozen screenshot. */
@Composable
private fun rememberDrift(): Float {
    val transition = rememberInfiniteTransition(label = "blurDrift")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(7000, easing = LinearEasing),
        ),
        label = "blurPhase",
    )
    return sin(phase)
}

/**
 * One covered picture: where it is, and what it looked like.
 *
 * [preview] is a heavily reduced copy of the picture taken at the moment it was judged, which the
 * cover draws back in place of it. That is what makes this read as a blurred photograph rather
 * than a rectangle painted over one, and the difference is not cosmetic: a solid patch says the
 * page is broken and invites the child to go and find the picture somewhere this app is not
 * watching, while a blur says something decided this on purpose. It is null when the picture could
 * not be sampled, and the cover falls back to a wash.
 */
data class CoveredPatch(
    val area: androidx.compose.ui.geometry.Rect,
    val preview: ImageBitmap?,
)

/**
 * One covered picture, filling its own window.
 *
 * A patch gets a window to itself rather than being painted into one transparent layer over the
 * whole screen, so that only the picture is covered and the page around it is untouched.
 *
 * [capped] is the platform rule this has to survive. From Android 12 an overlay that lets touches
 * pass through to the app underneath has its opacity capped by the system at
 * `maximum_obscuring_opacity_for_touch`, 0.8 by default, so that a person can always see what they
 * are about to touch. The cap applies only to *untrusted* windows, and a window added through an
 * accessibility service is trusted — which is the route the covers take when the parent has
 * granted that service, and why they are normally both opaque and invisible to touch.
 *
 * Without it the cover is capped, and a cover at 0.8 is barely a cover: a fifth of the original
 * picture is composited over the blur at full sharpness, so what is on screen is a blurred ghost
 * over the real thing — a double exposure, which is what "it doesn't look like a blur, it looks
 * like a shaded overlay" describes exactly. Nothing drawn here can undo that; the scrim goes
 * heavier to hold the contrast down and the video blurring screen says what the grant would buy.
 *
 * What is deliberately *not* done in the capped case is taking the touches instead. A cover that
 * absorbs touches is tolerable over one picture and unusable over a screen full of them, which is
 * the screen this feature exists for, and a page the child cannot scroll is the version of this
 * that gets uninstalled. Weaker, never heavier: that is the direction the fallback goes.
 */
@Composable
fun PatchCover(patch: CoveredPatch?, strength: Float, capped: Boolean = false) {
    ChildSafetyTheme {
        // No tint at all over a real blur, and that is the whole difference between this reading
        // as a blurred photograph and reading as a panel laid over one.
        //
        // The detail is already gone before this file sees it: `ImageBlur.blurPreview` destroyed
        // it in the pixels, and the window is opaque, so nothing of the original is reaching the
        // screen for a scrim to hide. Every bit of darkening added on top was therefore buying no
        // protection whatsoever and costing the one thing that makes a blur look like a blur,
        // which is the picture's own colour and composition. What a browser extension does to a
        // thumbnail — blur it and leave it otherwise alone — is the target, and a tint is exactly
        // what it does not do.
        //
        // The two remaining numbers are for the cases where something really is showing through.
        // A capped window composites a fifth of the sharp original back over this, and a cover
        // with no picture to draw has no blur to rely on: rule 10 applies in full to both.
        val clamped = strength.coerceIn(0f, 1f)
        // A capped window composites 20% of the sharp original back over whatever is drawn here,
        // so the scrim has to work against an image it can no longer fully hide. Heavier is the
        // least bad answer: it holds the contrast of what bleeds through down to where a
        // silhouette stops being readable. It is not as good as the trusted route and is not
        // meant to look as though it is.
        val tint = if (capped) CAPPED_TINT else 0f
        // Solid, where the system allows it. A wash is a compromise forced by the cap and nothing
        // else: rule 10 is about what survives a translucent layer, and the answer for a window
        // that is allowed to be opaque is nothing. Only the capped fallback still has to pick a
        // number, and it picks the highest one that leaves the edges of the window readable.
        val wash = if (capped) 0.955f + clamped * 0.04f else 1f

        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            if (width <= 0f || height <= 0f) return@Canvas

            // Rounded, but only just. A heavy radius was reading as a card laid over the page — a
            // frame around the picture rather than the picture being out of focus — and the
            // corners it cut off were showing the sharp original inside the area that was supposed
            // to be covered.
            val radius = (minOf(width, height) * CORNER_FRACTION).coerceAtMost(MAX_CORNER_PX)
            val shape = Path().apply {
                addRoundRect(
                    RoundRect(
                        rect = Rect(Offset.Zero, Size(width, height)),
                        cornerRadius = CornerRadius(radius, radius),
                    ),
                )
            }

            clipPath(shape) {
                val preview = patch?.preview
                if (preview != null) {
                    drawImage(
                        image = preview,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(preview.width, preview.height),
                        dstOffset = IntOffset.Zero,
                        // Rounded up, not truncated. Truncation left a one-pixel seam of the live
                        // screen along the right and bottom edge.
                        dstSize = IntSize(
                            kotlin.math.ceil(width).toInt(),
                            kotlin.math.ceil(height).toInt(),
                        ),
                        // Bilinear on the way up, which is the last of the several scaling passes
                        // that together make this look like a lens out of focus. Drawing at a
                        // harder filter quality would re-introduce the pixel grid that the
                        // progressive upscale in ImageBlur exists to remove.
                        filterQuality = FilterQuality.Low,
                        // Src, not the default SrcOver. This is a replacement, not a layer: the
                        // point of the patch is that the picture underneath stops being on screen.
                        blendMode = BlendMode.Src,
                    )
                    if (tint > 0f) drawRect(color = SafetyPalette.Ink.copy(alpha = tint))
                } else {
                    drawRect(color = SafetyPalette.Ink.copy(alpha = wash))
                }
            }
        }
    }
}

/**
 * The single marker that says something on this screen is covered.
 *
 * Placed once in its own small window rather than stamped on every patch. A child looking at four
 * covered pictures does not need to be told four times, and labelling each one turns a soft effect
 * back into visible censorship. It says what happened and nothing else: not which model, not what
 * it scored, not which category matched. That is the parent's reading, in the event log. See
 * rule 17.
 */
@Composable
fun CoverNotice() {
    ChildSafetyTheme {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(SafetyPalette.Ink.copy(alpha = 0.72f))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.VisibilityOff,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Some of this is covered",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
            )
        }
    }
}

/** How round a patch's corners are, as a fraction of its shorter side. */
/** What the scrim goes to when the system will not let the window be opaque. See [PatchCover]. */
private const val CAPPED_TINT = 0.62f

private const val CORNER_FRACTION = 0.05f

/** And the ceiling, so a large picture does not end up with a card's corner radius. */
private const val MAX_CORNER_PX = 24f
