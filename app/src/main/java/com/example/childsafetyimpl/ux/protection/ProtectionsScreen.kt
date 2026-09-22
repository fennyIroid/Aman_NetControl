package com.example.childsafetyimpl.ux.protection

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.HideImage
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.childsafetyimpl.core.protection.ProtectionFeature
import com.example.childsafetyimpl.core.protection.ProtectionState
import com.example.childsafetyimpl.core.protection.ProtectionStatus
import com.example.childsafetyimpl.navigation.HandleNavigation
import com.example.childsafetyimpl.navigation.LocalNavController
import com.example.childsafetyimpl.ui.components.HeroHeader
import com.example.childsafetyimpl.ui.components.IconBadge
import com.example.childsafetyimpl.ui.components.SafetyCard
import com.example.childsafetyimpl.ui.components.SectionHeader
import com.example.childsafetyimpl.ui.components.StaggeredEntrance
import com.example.childsafetyimpl.ui.components.StatusChip
import com.example.childsafetyimpl.ui.components.StatusStackBar
import com.example.childsafetyimpl.ui.theme.SafetyPalette
import com.example.childsafetyimpl.ui.theme.tone
import com.example.childsafetyimpl.ux.common.BottomBarSpacer

/**
 * The protection tab.
 *
 * The two groups are the ones the brief asks for and are not relabelled into something tidier:
 * the first five are what a parent is told matters most, and presenting them alongside the rest as
 * one undifferentiated list would quietly discard that judgement.
 */
@Composable
fun ProtectionsScreen(viewModel: ProtectionsViewModel) {
    val navController = LocalNavController.current
    HandleNavigation(viewModel, navController)

    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SafetyPalette.Canvas),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            HeroHeader {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Protection",
                            style = MaterialTheme.typography.displaySmall,
                            color = Color.White,
                        )
                        Text(
                            text = if (state.protectionEnabled) {
                                "${state.summary.running} of ${state.summary.total} running"
                            } else {
                                "Everything is switched off"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xCCFFFFFF),
                        )
                    }
                    Switch(
                        checked = state.protectionEnabled,
                        onCheckedChange = {
                            viewModel.onEvent(ProtectionsUiEvent.ToggleMaster(it))
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0x59FFFFFF),
                            checkedBorderColor = Color(0x40FFFFFF),
                            uncheckedThumbColor = Color(0xB8FFFFFF),
                            uncheckedTrackColor = Color(0x26FFFFFF),
                            uncheckedBorderColor = Color(0x33FFFFFF),
                        ),
                    )
                }

                Spacer(Modifier.height(18.dp))

                StatusStackBar(
                    segments = listOf(
                        Triple("Running", state.summary.active, SafetyPalette.Mint),
                        Triple("Partial", state.summary.partial, SafetyPalette.Sky),
                        Triple("Blocked", state.summary.blocked, SafetyPalette.Apricot),
                        Triple("Off", state.summary.off, Color(0x40FFFFFF)),
                    ),
                )

                if (state.summary.needsAttention > 0) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "${state.summary.needsAttention} " +
                            (if (state.summary.needsAttention == 1) "protection needs" else "protections need") +
                            " a permission before they can run. Open Permissions to finish setup.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xE0FFFFFF),
                    )
                }
                Spacer(Modifier.height(4.dp))
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionHeader(
                    title = "First priority",
                    subtitle = "The five the product leads with",
                )
                state.primary.forEachIndexed { index, status ->
                    StaggeredEntrance(index) {
                        ProtectionCard(
                            status = status,
                            onOpen = { viewModel.onEvent(ProtectionsUiEvent.Open(status.feature)) },
                            onToggle = {
                                viewModel.onEvent(
                                    ProtectionsUiEvent.ToggleFeature(status.feature, it),
                                )
                            },
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))

                SectionHeader(
                    title = "Everything else",
                    subtitle = "Content and advertising controls",
                )
                state.secondary.forEachIndexed { index, status ->
                    StaggeredEntrance(index + state.primary.size) {
                        ProtectionCard(
                            status = status,
                            onOpen = { viewModel.onEvent(ProtectionsUiEvent.Open(status.feature)) },
                            onToggle = {
                                viewModel.onEvent(
                                    ProtectionsUiEvent.ToggleFeature(status.feature, it),
                                )
                            },
                        )
                    }
                }
            }

            BottomBarSpacer()
        }
    }
}

@Composable
private fun ProtectionCard(
    status: ProtectionStatus,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tone = status.state.tone

    SafetyCard(modifier = modifier, onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(
                icon = status.feature.icon,
                tint = tone.content,
                container = tone.container,
                size = 42,
            )
            Spacer(Modifier.size(13.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = status.feature.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = SafetyPalette.Ink,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = status.headline,
                    style = MaterialTheme.typography.labelMedium,
                    color = tone.content,
                )
            }
            Spacer(Modifier.size(10.dp))
            Switch(
                checked = status.enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = SafetyPalette.Indigo,
                    checkedBorderColor = SafetyPalette.Indigo,
                ),
            )
        }

        Spacer(Modifier.height(12.dp))

        Text(
            text = status.detail,
            style = MaterialTheme.typography.bodySmall,
            color = SafetyPalette.Slate,
        )

        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusChip(status.state)
            Spacer(Modifier.weight(1f))
            Text(
                text = if (status.state == ProtectionState.NEEDS_PERMISSION) "Fix" else "Open",
                style = MaterialTheme.typography.labelLarge,
                color = SafetyPalette.Indigo,
            )
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = SafetyPalette.Indigo,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

/**
 * One icon per protection, defined once here.
 *
 * On the UI side rather than on the enum so the core model stays free of Compose types, which is
 * what lets the protection catalog be used from the services as well as from the screens.
 */
val ProtectionFeature.icon: ImageVector
    get() = when (this) {
        ProtectionFeature.VPN_RESTRICTION -> Icons.Rounded.VpnKey
        ProtectionFeature.CLONE_CONTROL -> Icons.Rounded.ContentCopy
        ProtectionFeature.SHORT_FORM_CONTROL -> Icons.Rounded.SmartDisplay
        ProtectionFeature.STATUS_CONTROL -> Icons.Rounded.WifiTethering
        ProtectionFeature.AI_GAME_MONITORING -> Icons.Rounded.Psychology
        ProtectionFeature.APP_RESTRICTION -> Icons.Rounded.Apps
        ProtectionFeature.SEARCH_FILTERING -> Icons.Rounded.SearchOff
        ProtectionFeature.AD_REMOVAL -> Icons.Rounded.Block
        ProtectionFeature.IMAGE_BLURRING -> Icons.Rounded.HideImage
        ProtectionFeature.VIDEO_BLURRING -> Icons.Rounded.Videocam
    }
