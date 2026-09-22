package com.example.childsafetyimpl.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import com.example.childsafetyimpl.R

/**
 * Plus Jakarta Sans, bundled rather than downloaded so the app has the same voice on a device
 * with no Play Services. Its slightly rounded terminals keep the parenting warmth while the
 * tighter counters stop it reading as a toy.
 */
val Jakarta = FontFamily(
    Font(R.font.jakarta_regular, FontWeight.Normal),
    Font(R.font.jakarta_medium, FontWeight.Medium),
    Font(R.font.jakarta_semibold, FontWeight.SemiBold),
    Font(R.font.jakarta_bold, FontWeight.Bold),
    Font(R.font.jakarta_extrabold, FontWeight.ExtraBold),
)

private val lineHeightStyle = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private fun style(
    size: Int,
    lineHeight: Int,
    weight: FontWeight,
    tracking: Double = 0.0,
) = TextStyle(
    fontFamily = Jakarta,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = tracking.sp,
    lineHeightStyle = lineHeightStyle,
)

val SafetyTypography = Typography(
    displayLarge = style(38, 44, FontWeight.ExtraBold, (-0.8)),
    displayMedium = style(32, 38, FontWeight.ExtraBold, (-0.6)),
    displaySmall = style(28, 34, FontWeight.Bold, (-0.4)),
    headlineLarge = style(24, 30, FontWeight.Bold, (-0.3)),
    headlineMedium = style(21, 27, FontWeight.Bold, (-0.2)),
    headlineSmall = style(18, 24, FontWeight.SemiBold, (-0.1)),
    titleLarge = style(17, 23, FontWeight.SemiBold),
    titleMedium = style(15, 21, FontWeight.SemiBold),
    titleSmall = style(14, 19, FontWeight.SemiBold),
    bodyLarge = style(15, 23, FontWeight.Normal),
    bodyMedium = style(14, 21, FontWeight.Normal),
    bodySmall = style(13, 19, FontWeight.Normal),
    labelLarge = style(14, 18, FontWeight.SemiBold, 0.1),
    labelMedium = style(12, 16, FontWeight.SemiBold, 0.2),
    labelSmall = style(11, 14, FontWeight.Bold, 0.4),
)
