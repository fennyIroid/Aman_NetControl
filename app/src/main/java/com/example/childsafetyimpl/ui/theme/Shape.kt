package com.example.childsafetyimpl.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Generous, evenly rounded corners. Cards at 22dp, sheets at 28dp, chips fully round: soft enough
 * to feel domestic, regular enough not to feel like a game.
 */
val SafetyShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

object SafetyRadius {
    val chip = RoundedCornerShape(percent = 50)
    val card = RoundedCornerShape(22.dp)
    val tile = RoundedCornerShape(18.dp)
    val sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val hero = RoundedCornerShape(bottomStart = 30.dp, bottomEnd = 30.dp)
    val field = RoundedCornerShape(16.dp)
}
