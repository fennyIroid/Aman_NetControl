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
import com.example.childsafetyimpl.ui.components.SecondaryButton
import com.example.childsafetyimpl.ui.components.TonalChip
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
            state.refusedRequests.toString() to "Requests refused",
            state.lockouts.toString() to "Apps blocked",
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
                    ". While it is up, its traffic goes around the Private DNS filter.",
                tone = OutcomeTone.BLOCKED,
                footnote = if (state.vpnLockout) {
                    "Every app but Settings and the phone is blocked until it is switched off."
                } else {
                    "Blocking the phone while a VPN is on is switched off, so this is a report only."
                },
            )

            posture.encryptedDnsHost != null -> OutcomeCard(
                headline = "Private DNS is bypassing the filter",
                detail = "Name lookups are going to ${posture.encryptedDnsHost}, a resolver that " +
                    "does not filter, over an encrypted channel this app cannot read.",
                tone = OutcomeTone.WARNING,
                footnote = "Put the filtering hostname back from Controls, How filtering works.",
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
                title = "Refuse VPN requests and close VPN apps",
                description = "Cancels Android's VPN connection request before it can be " +
                    "accepted, and closes a VPN app the moment the child opens it. Refusing " +
                    "requests needs the accessibility service.",
                checked = state.blockVpnApps,
                onCheckedChange = { viewModel.onEvent(VpnProtectionUiEvent.ToggleBlockApps(it)) },
            )
            SettingSwitchRow(
                title = "Block the phone while a VPN is on",
                description = "If a VPN connects anyway, every app but Settings and the phone " +
                    "is blocked until it is switched off.",
                checked = state.vpnLockout,
                onCheckedChange = { viewModel.onEvent(VpnProtectionUiEvent.ToggleLockout(it)) },
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
            title = "How a VPN is stopped",
            icon = Icons.Rounded.Dns,
        ) {
            if (state.vpnForbiddenByPolicy) {
                Text(
                    text = "This device is managed, and this app has forbidden VPN setup by " +
                        "policy. Android refuses to start any VPN, so the steps below are a " +
                        "backstop.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SafetyPalette.Mint,
                )
                Spacer(Modifier.height(12.dp))
            }
            DetailRow("Connection requests refused", state.refusedRequests.toString())
            DetailRow("VPN apps closed", state.blockedLaunches.toString())
            DetailRow("Apps blocked while a VPN was on", state.lockouts.toString())
            DetailRow(
                "Refusing requests",
                if (state.canRefuseRequests) "Ready" else "Needs the accessibility service",
            )
        }

        ProtectionSection(title = "What to expect", icon = Icons.Rounded.Shield) {
            Text(
                text = "This app uses no VPN of its own for filtering, so it cannot end another " +
                    "VPN by taking its place. Instead a VPN is refused before it starts, its app " +
                    "is closed, and if one connects anyway — one allowed before this app was " +
                    "installed, or one set up by hand in Settings — the phone is blocked until " +
                    "it is off. The child is sent to Settings to switch it off, which they can " +
                    "always reach. Without the accessibility service a new VPN is not refused; " +
                    "without usage access or permission to draw over apps it is reported, not " +
                    "blocked. Only a managed device can forbid VPNs outright.",
                style = MaterialTheme.typography.bodyMedium,
                color = SafetyPalette.Slate,
            )
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
