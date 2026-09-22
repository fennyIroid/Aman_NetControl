package com.example.childsafetyimpl.ux.protection

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.childsafetyimpl.core.common.TimeFormat
import com.example.childsafetyimpl.domain.model.InstalledApp
import com.example.childsafetyimpl.navigation.HandleNavigation
import com.example.childsafetyimpl.navigation.LocalNavController
import com.example.childsafetyimpl.ui.components.DetailRow
import com.example.childsafetyimpl.ui.components.IconBadge
import com.example.childsafetyimpl.ui.components.PrimaryButton
import com.example.childsafetyimpl.ui.components.SecondaryButton
import com.example.childsafetyimpl.ui.components.TonalChip
import com.example.childsafetyimpl.ui.illustration.NetworkFlow
import com.example.childsafetyimpl.ui.theme.SafetyPalette
import com.example.childsafetyimpl.ux.common.OutcomeCard
import com.example.childsafetyimpl.ux.common.OutcomeTone
import com.example.childsafetyimpl.ux.common.ProtectionScaffold
import com.example.childsafetyimpl.ux.common.ProtectionSection
import com.example.childsafetyimpl.ux.common.SettingSwitchRow

@Composable
fun VpnProtectionScreen(viewModel: VpnProtectionViewModel) {
    HandleNavigation(viewModel, LocalNavController.current)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val status = state.status ?: return

    ProtectionScaffold(
        status = status,
        onBack = { viewModel.onEvent(VpnProtectionUiEvent.Back) },
        onToggle = { viewModel.onEvent(VpnProtectionUiEvent.ToggleFeature(it)) },
        heroStats = listOf(
            state.vpnApps.size.toString() to "VPN apps",
            state.counters.reclaims.toString() to "Reclaims",
            (if (state.tunnelRunning) "On" else "Off") to "Our tunnel",
        ),
    ) {
        // The live picture first. A parent opening this screen is usually asking one question:
        // is a VPN running right now?
        val posture = state.posture
        when {
            posture == null -> Unit

            posture.foreignTunnelActive -> OutcomeCard(
                headline = "A VPN is running now",
                detail = "A tunnel this app does not own is active" +
                    posture.tunnelInterfaces.takeIf { it.isNotEmpty() }
                        ?.let { " on ${it.joinToString()}" }.orEmpty() +
                    ". While it is up, filtering is going around this app.",
                tone = OutcomeTone.BLOCKED,
                footnote = if (state.reclaimTunnel) {
                    "Protection is taking the tunnel back automatically."
                } else {
                    "Tunnel reclaiming is switched off, so this is a report only."
                },
            )

            posture.encryptedDnsHost != null -> OutcomeCard(
                headline = "Private DNS is bypassing the filter",
                detail = "Name lookups are going to ${posture.encryptedDnsHost} over an encrypted " +
                    "channel that this app cannot read. No app can change that setting for you.",
                tone = OutcomeTone.WARNING,
                footnote = "Turn Private DNS off in Android Settings to restore filtering.",
            )

            else -> OutcomeCard(
                headline = "No VPN is running",
                detail = "Nothing is tunnelling this device's traffic around the filter.",
                tone = OutcomeTone.SUCCESS,
            )
        }

        ProtectionSection(
            title = "How strict",
            icon = Icons.Rounded.Shield,
        ) {
            SettingSwitchRow(
                title = "Close VPN apps on open",
                description = "A VPN app is closed the moment the child opens it.",
                checked = state.blockVpnApps,
                onCheckedChange = { viewModel.onEvent(VpnProtectionUiEvent.ToggleBlockApps(it)) },
            )
            SettingSwitchRow(
                title = "Take the tunnel back",
                description = "Android allows one VPN at a time. Re-establishing ours ends " +
                    "whichever VPN has claimed it.",
                checked = state.reclaimTunnel,
                onCheckedChange = { viewModel.onEvent(VpnProtectionUiEvent.ToggleReclaim(it)) },
            )
            SettingSwitchRow(
                title = "Close the VPN settings screen",
                description = "Leaves Android's own VPN settings if the child opens it. Needs the " +
                    "accessibility service.",
                checked = state.blockVpnSettings,
                onCheckedChange = {
                    viewModel.onEvent(VpnProtectionUiEvent.ToggleBlockSettings(it))
                },
            )
        }

        ProtectionSection(
            title = "VPN apps on this device",
            icon = Icons.Rounded.VpnKey,
            trailing = "${state.vpnApps.size} found",
        ) {
            if (state.vpnApps.isEmpty()) {
                Text(
                    text = if (state.scanning) {
                        "Looking for apps that can create a tunnel."
                    } else {
                        "No app on this device declares a VPN service. Anything installed later " +
                            "is found on the next check."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = SafetyPalette.Slate,
                )
            } else {
                Text(
                    text = "Found by asking Android which apps can create a tunnel, so a VPN " +
                        "client this app has never heard of still appears here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SafetyPalette.Slate,
                )
                Spacer(Modifier.height(12.dp))
                state.vpnApps.forEach { app -> VpnAppRow(app) }
            }

            Spacer(Modifier.height(14.dp))
            SecondaryButton(
                text = if (state.scanning) "Checking" else "Check again",
                onClick = { viewModel.onEvent(VpnProtectionUiEvent.Rescan) },
                icon = Icons.Rounded.Refresh,
                enabled = !state.scanning,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        ProtectionSection(
            title = "The filtering tunnel",
            icon = Icons.Rounded.Dns,
        ) {
            NetworkFlow(active = state.tunnelRunning, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(14.dp))

            if (state.tunnelRunning) {
                DetailRow("Names looked up", state.counters.total.toString())
                DetailRow("Refused", state.counters.blocked.toString())
                DetailRow("Ad hosts refused", state.counters.adsBlocked.toString())
                DetailRow("Tunnel reclaimed", state.counters.reclaims.toString())
                // The address-level half of the encrypted-DNS defence. Its own row because a
                // lookup refused by name and a packet dropped by address are different events,
                // and only the second one proves the bootstrap-IP bypass is closed.
                DetailRow(
                    "Encrypted DNS packets dropped",
                    state.counters.encryptedDnsDrops.toString(),
                )
                DetailRow("Refused while paused", state.counters.pausedDrops.toString())
            } else {
                Text(
                    text = "Our tunnel is not running. VPN apps are still closed on open, but " +
                        "the tunnel cannot be taken back from another VPN until this one has " +
                        "been allowed once.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SafetyPalette.Slate,
                )
                Spacer(Modifier.height(14.dp))
                PrimaryButton(
                    text = "Start the tunnel",
                    onClick = { viewModel.onEvent(VpnProtectionUiEvent.ReclaimNow) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun VpnAppRow(app: InstalledApp, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().height(52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(
            icon = Icons.Rounded.VpnKey,
            tint = SafetyPalette.Danger,
            container = SafetyPalette.DangerSoft,
            size = 34,
        )
        Spacer(Modifier.size(11.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.titleSmall,
                color = SafetyPalette.Ink,
            )
            Text(
                text = "Installed ${TimeFormat.dateTime(app.installedAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = SafetyPalette.Mist,
            )
        }
        TonalChip(
            text = "Closed on open",
            content = SafetyPalette.Danger,
            container = SafetyPalette.DangerSoft,
        )
    }
}
