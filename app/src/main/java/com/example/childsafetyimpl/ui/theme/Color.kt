package com.example.childsafetyimpl.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.example.childsafetyimpl.core.protection.ProtectionState

/**
 * The product palette.
 *
 * The brief asks for a soft, parent-facing feel that still reads as a serious safety tool. The
 * resolution is a confident indigo-blue as the working colour, warm secondary tones reserved for
 * moments that involve the child, and a cool near-white ground so the cards do the talking.
 * Light only, deliberately: a safety dashboard a parent checks in daylight.
 */
object SafetyPalette {
    // Brand
    val Indigo = Color(0xFF2E5BFF)
    val IndigoDeep = Color(0xFF1B3BB8)
    val IndigoInk = Color(0xFF15266E)
    val IndigoSoft = Color(0xFFEBF0FF)
    val IndigoMuted = Color(0xFFC7D6FF)

    // Warm, child-facing accents
    val Apricot = Color(0xFFFFB43D)
    val ApricotSoft = Color(0xFFFFF3DE)
    val Coral = Color(0xFFFF7A6B)
    val CoralSoft = Color(0xFFFFEDEA)
    val Lavender = Color(0xFF8B7CF6)
    val LavenderSoft = Color(0xFFF0EDFE)
    val Mint = Color(0xFF17B978)
    val MintSoft = Color(0xFFE3F7EE)
    val Sky = Color(0xFF29B6E8)
    val SkySoft = Color(0xFFE4F6FD)

    // Neutrals
    val Ink = Color(0xFF14203D)
    val Slate = Color(0xFF5C6884)
    val Mist = Color(0xFF98A2BC)
    val Line = Color(0xFFE7EBF5)
    val Canvas = Color(0xFFF4F6FC)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceMuted = Color(0xFFFAFBFE)

    // Semantics
    val Danger = Color(0xFFE5484D)
    val DangerSoft = Color(0xFFFDECEC)
    val Warning = Color(0xFFF08C00)
    val WarningSoft = Color(0xFFFFF4E2)
    val Success = Mint
    val SuccessSoft = MintSoft
}

/** Foreground / background pair for a status chip, so callers never mix the two by hand. */
@Immutable
data class ToneColors(val content: Color, val container: Color)

/**
 * Each protection state gets its own tone. The mapping is meaningful rather than decorative:
 * green is running, blue is running with something still to gain, amber is a protection that
 * wants a grant it does not have, and grey is one the parent chose to leave off.
 */
val ProtectionState.tone: ToneColors
    get() = when (this) {
        ProtectionState.ACTIVE ->
            ToneColors(Color(0xFF0E8F5E), SafetyPalette.MintSoft)
        ProtectionState.PARTIAL ->
            ToneColors(SafetyPalette.IndigoDeep, SafetyPalette.IndigoSoft)
        ProtectionState.NEEDS_PERMISSION ->
            ToneColors(Color(0xFFB26A00), SafetyPalette.WarningSoft)
        ProtectionState.OFF ->
            ToneColors(SafetyPalette.Slate, Color(0xFFEFF1F7))
    }

/** The header wash used on the dashboard and on every module hero. */
val brandGradient: Brush
    get() = Brush.linearGradient(
        colors = listOf(Color(0xFF3A6BFF), Color(0xFF2E5BFF), Color(0xFF244AD8)),
    )

val calmGradient: Brush
    get() = Brush.linearGradient(listOf(Color(0xFF6FA8FF), Color(0xFF2E5BFF)))

val warmGradient: Brush
    get() = Brush.linearGradient(listOf(Color(0xFFFFC46B), Color(0xFFFF9C3D)))

val alertGradient: Brush
    get() = Brush.linearGradient(listOf(Color(0xFFFF9A8B), Color(0xFFE5484D)))
