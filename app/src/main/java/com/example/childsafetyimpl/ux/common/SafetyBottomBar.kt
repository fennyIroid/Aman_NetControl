package com.example.childsafetyimpl.ux.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.childsafetyimpl.navigation.Routes
import com.example.childsafetyimpl.ui.components.rememberPressScale
import com.example.childsafetyimpl.ui.components.shadowSoft
import com.example.childsafetyimpl.ui.theme.SafetyMotion
import com.example.childsafetyimpl.ui.theme.SafetyPalette

/**
 * The bottom bar.
 *
 * The selected item grows a tinted pill and reveals its label; the others stay as icons. That
 * behaviour does the work a highlight colour alone cannot: it keeps four destinations legible at
 * phone width while making the current one unmistakable at a glance.
 */
@Composable
fun SafetyBottomBar(
    currentRoute: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    unreadEvents: Int = 0,
) {
    val items = listOf(
        BarItem(Routes.dashboard.pattern, "Home", Icons.Rounded.Dashboard),
        BarItem(Routes.protections.pattern, "Protection", Icons.Rounded.Shield),
        BarItem(Routes.controls.pattern, "Controls", Icons.Rounded.Tune),
        BarItem(Routes.events.pattern, "Activity", Icons.Rounded.NotificationsActive),
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .shadowSoft(RoundedCornerShape(26.dp), strong = true)
                .clip(RoundedCornerShape(26.dp))
                .background(SafetyPalette.Surface)
                .padding(horizontal = 8.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { item ->
                BarButton(
                    item = item,
                    selected = currentRoute == item.route,
                    badge = if (item.route == Routes.events.pattern) unreadEvents else 0,
                    onClick = { onSelect(item.route) },
                )
            }
        }
    }
}

private data class BarItem(val route: String, val label: String, val icon: ImageVector)

@Composable
private fun BarButton(
    item: BarItem,
    selected: Boolean,
    badge: Int,
    onClick: () -> Unit,
) {
    val (interactionSource, scaleModifier) = rememberPressScale(pressedScale = 0.9f)

    val containerColor by animateColorAsState(
        targetValue = if (selected) SafetyPalette.IndigoSoft else Color.Transparent,
        animationSpec = SafetyMotion.value(),
        label = "barContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) SafetyPalette.Indigo else SafetyPalette.Mist,
        animationSpec = SafetyMotion.value(),
        label = "barContent",
    )
    val horizontalPadding by animateDpAsState(
        targetValue = if (selected) 16.dp else 14.dp,
        animationSpec = SafetyMotion.value(),
        label = "barPadding",
    )
    Row(
        modifier = Modifier
            .then(scaleModifier)
            .animateContentSize(animationSpec = SafetyMotion.value())
            .clip(RoundedCornerShape(percent = 50))
            .background(containerColor)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = horizontalPadding, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Icon(
                imageVector = item.icon,
                contentDescription = item.label,
                tint = contentColor,
                modifier = Modifier.size(21.dp),
            )
            if (badge > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(start = 10.dp)
                        .size(8.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(SafetyPalette.Coral),
                )
            }
        }
        // The label is added and removed rather than measured into a computed width; letting
        // animateContentSize handle the pill keeps long labels from being clipped.
        if (selected) {
            Spacer(Modifier.width(7.dp))
            Text(
                text = item.label,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/** Reserves the vertical space the floating bar occupies, so content never sits underneath it. */
@Composable
fun BottomBarSpacer() {
    Spacer(Modifier.height(94.dp))
}
