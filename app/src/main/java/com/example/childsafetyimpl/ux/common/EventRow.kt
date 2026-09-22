package com.example.childsafetyimpl.ux.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.HideImage
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.ScreenshotMonitor
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.childsafetyimpl.core.common.TimeFormat
import com.example.childsafetyimpl.domain.model.EventAction
import com.example.childsafetyimpl.domain.model.EventSeverity
import com.example.childsafetyimpl.domain.model.SafetyEvent
import com.example.childsafetyimpl.domain.model.SafetyEventType
import com.example.childsafetyimpl.ui.components.IconBadge
import com.example.childsafetyimpl.ui.components.TonalChip
import com.example.childsafetyimpl.ui.theme.SafetyPalette

/**
 * One entry in the safety log.
 *
 * The action chip carries as much weight as the event itself. "Detected, not enforceable" is a
 * genuinely different outcome from "Blocked", and collapsing the two would let the log imply a
 * level of control the app does not have.
 */
@Composable
fun EventRow(
    event: SafetyEvent,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val (icon, accent, container) = eventVisuals(event.type, event.severity)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.background(Color.Transparent)
                } else Modifier
            ),
        verticalAlignment = Alignment.Top,
    ) {
        IconBadge(icon = icon, tint = accent, container = container, size = if (compact) 34 else 40)
        Spacer(Modifier.size(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = event.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = SafetyPalette.Ink,
                    modifier = Modifier.weight(1f),
                    maxLines = if (compact) 1 else 2,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = TimeFormat.relative(event.occurredAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = SafetyPalette.Mist,
                )
            }

            if (!compact || event.appLabel != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = event.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = SafetyPalette.Slate,
                    maxLines = if (compact) 2 else 6,
                )
            }

            if (!compact) {
                Spacer(Modifier.height(9.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    val actionTone = actionTone(event.action)
                    TonalChip(
                        text = event.action.label,
                        content = actionTone.first,
                        container = actionTone.second,
                    )
                    if (event.appLabel != null) {
                        TonalChip(
                            text = event.appLabel,
                            content = SafetyPalette.Slate,
                            container = SafetyPalette.Canvas,
                        )
                    }
                    if (event.parentNotified) {
                        TonalChip(
                            text = "Alerted",
                            content = SafetyPalette.IndigoDeep,
                            container = SafetyPalette.IndigoSoft,
                        )
                    }
                }
            }
        }
    }
}

private fun actionTone(action: EventAction): Pair<Color, Color> = when (action) {
    EventAction.BLOCKED -> Color(0xFF0E8F5E) to SafetyPalette.MintSoft
    EventAction.BLURRED -> Color(0xFF5B44C7) to SafetyPalette.LavenderSoft
    EventAction.WARNED -> Color(0xFFB26A00) to SafetyPalette.WarningSoft
    // The one that matters most: we saw it and could do nothing about it.
    EventAction.NOT_ENFORCEABLE -> Color(0xFFC13438) to SafetyPalette.DangerSoft
    EventAction.LOGGED -> SafetyPalette.Slate to SafetyPalette.Canvas
}

private fun eventVisuals(
    type: SafetyEventType,
    severity: EventSeverity,
): Triple<ImageVector, Color, Color> {
    val icon: ImageVector = when (type) {
        SafetyEventType.VPN_DETECTED -> Icons.Rounded.VpnKey
        SafetyEventType.RESTRICTED_APP_OPENED -> Icons.Rounded.Block
        SafetyEventType.USAGE_LIMIT_REACHED -> Icons.Rounded.HourglassBottom
        SafetyEventType.RESTRICTED_WEBSITE -> Icons.Rounded.Language
        SafetyEventType.RESTRICTED_SEARCH -> Icons.Rounded.SearchOff
        SafetyEventType.UNSAFE_IMAGE -> Icons.Rounded.HideImage
        SafetyEventType.UNSAFE_SCREEN -> Icons.Rounded.ScreenshotMonitor
        SafetyEventType.RESTRICTED_CONTENT_ATTEMPT -> Icons.Rounded.WarningAmber
        SafetyEventType.MONITORING_PERMISSION_LOST -> Icons.Rounded.Shield
        SafetyEventType.MONITORING_STOPPED -> Icons.Rounded.PowerSettingsNew
        SafetyEventType.BYPASS_ATTEMPT -> Icons.Rounded.WarningAmber
        SafetyEventType.CLONE_APP_DETECTED -> Icons.Rounded.ContentCopy
        SafetyEventType.SHORT_FORM_DETECTED -> Icons.Rounded.PlayCircleOutline
        SafetyEventType.COMMUNICATION_SIGNAL -> Icons.Rounded.Forum
        SafetyEventType.SCHEDULE_BLOCK -> Icons.Rounded.NightsStay
        SafetyEventType.INTERNET_PAUSED -> Icons.Rounded.WifiOff
        SafetyEventType.ROUTINE_RESTRICTION -> Icons.Rounded.Schedule
        SafetyEventType.PROTECTION_DEGRADED -> Icons.Rounded.Shield
        SafetyEventType.FEASIBILITY_PROBE -> Icons.Rounded.Science
    }

    val (accent, container) = when (severity) {
        EventSeverity.CRITICAL -> Color(0xFFC13438) to SafetyPalette.DangerSoft
        EventSeverity.HIGH -> Color(0xFFB26A00) to SafetyPalette.WarningSoft
        EventSeverity.MEDIUM -> SafetyPalette.IndigoDeep to SafetyPalette.IndigoSoft
        EventSeverity.LOW -> Color(0xFF0E7C99) to SafetyPalette.SkySoft
        EventSeverity.INFO -> SafetyPalette.Slate to SafetyPalette.Canvas
    }

    return Triple(icon, accent, container)
}
