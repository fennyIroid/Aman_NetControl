package com.example.childsafetyimpl.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val SafetyColorScheme = lightColorScheme(
    primary = SafetyPalette.Indigo,
    onPrimary = SafetyPalette.Surface,
    primaryContainer = SafetyPalette.IndigoSoft,
    onPrimaryContainer = SafetyPalette.IndigoInk,
    secondary = SafetyPalette.Apricot,
    onSecondary = SafetyPalette.Ink,
    secondaryContainer = SafetyPalette.ApricotSoft,
    onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFF6B4A00),
    tertiary = SafetyPalette.Lavender,
    onTertiary = SafetyPalette.Surface,
    tertiaryContainer = SafetyPalette.LavenderSoft,
    onTertiaryContainer = androidx.compose.ui.graphics.Color(0xFF3B2C8F),
    error = SafetyPalette.Danger,
    onError = SafetyPalette.Surface,
    errorContainer = SafetyPalette.DangerSoft,
    onErrorContainer = androidx.compose.ui.graphics.Color(0xFF8E1F22),
    background = SafetyPalette.Canvas,
    onBackground = SafetyPalette.Ink,
    surface = SafetyPalette.Surface,
    onSurface = SafetyPalette.Ink,
    surfaceVariant = SafetyPalette.SurfaceMuted,
    onSurfaceVariant = SafetyPalette.Slate,
    outline = SafetyPalette.Line,
    outlineVariant = SafetyPalette.Line,
    scrim = androidx.compose.ui.graphics.Color(0x99000000),
)

/**
 * The app is intentionally light-only. A child-safety dashboard is read in daylight by a parent
 * checking in, and the warm neutral ground is a large part of the product's identity, so it does
 * not follow the system dark setting.
 */
@Composable
fun ChildSafetyTheme(content: @Composable () -> Unit) {
    @Suppress("UNUSED_EXPRESSION")
    isSystemInDarkTheme()

    // The status bar only exists when this theme is inside an Activity. The block screen, the
    // surface notice and the video blur layer are windows added through the window manager with
    // the application context, and casting that to an Activity is how a parental control crashes
    // at the exact moment it is supposed to be blocking something.
    val view = LocalView.current
    if (!view.isInEditMode) {
        val window = view.context.findActivity()?.window
        if (window != null) {
            SideEffect {
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = SafetyColorScheme,
        typography = SafetyTypography,
        shapes = SafetyShapes,
        content = content,
    )
}

/**
 * Walks the context wrappers looking for an Activity, and returns null when there is not one.
 *
 * A ComposeView inside an overlay window is built on the application context, so the usual cast
 * has nothing to find. Returning null rather than throwing lets the same theme serve both the app
 * and the windows drawn over other apps.
 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
