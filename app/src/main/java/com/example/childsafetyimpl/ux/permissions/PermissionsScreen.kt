package com.example.childsafetyimpl.ux.permissions

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Lock
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.childsafetyimpl.platform.network.VpnGuard
import com.example.childsafetyimpl.core.permission.SafetyPermission
import com.example.childsafetyimpl.ui.components.DetailHero
import com.example.childsafetyimpl.ui.components.GrantChip
import com.example.childsafetyimpl.ui.components.HairLine
import com.example.childsafetyimpl.ui.components.IconBadge
import com.example.childsafetyimpl.ui.components.MeterBar
import com.example.childsafetyimpl.ui.components.SafetyCard
import com.example.childsafetyimpl.ui.components.SecondaryButton
import com.example.childsafetyimpl.ui.components.StaggeredEntrance
import com.example.childsafetyimpl.ui.components.TonalChip
import com.example.childsafetyimpl.ui.illustration.BlindSpot
import com.example.childsafetyimpl.ui.theme.SafetyPalette

/**
 * Where the parent grants, or declines, everything the protections depend on.
 *
 * Most of these cannot be requested with a dialog: the user has to find a toggle in Settings, and
 * that hand-off is where real products lose people. The screen therefore re-checks on every
 * resume, so returning from Settings updates the row immediately rather than leaving the parent
 * wondering whether it worked.
 */
@Composable
fun PermissionsScreen(
    state: PermissionsUiState,
    onEvent: (PermissionsUiEvent) -> Unit,
    intentFor: (SafetyPermission) -> Intent?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var pendingVpn by remember { mutableStateOf(false) }

    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { onEvent(PermissionsUiEvent.Refresh) }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { onEvent(PermissionsUiEvent.Refresh) }

    val vpnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        pendingVpn = false
        onEvent(PermissionsUiEvent.Refresh)
    }

    LaunchedEffect(Unit) {
        // Grants change outside the app, so the only correct time to trust a value is right after
        // the screen becomes visible again.
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            onEvent(PermissionsUiEvent.Refresh)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SafetyPalette.Canvas)
            .verticalScroll(rememberScrollState()),
    ) {
        DetailHero(
            title = "Permissions",
            subtitle = "What this app can see, and what it cannot",
            onBack = { onEvent(PermissionsUiEvent.Back) },
            belowTitle = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x24FFFFFF), MaterialTheme.shapes.large)
                        .padding(15.dp),
                ) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = "${state.grantedCount}",
                            style = MaterialTheme.typography.displaySmall,
                            color = Color.White,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "of ${state.rows.size - state.unobtainableCount} granted",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xCCFFFFFF),
                            modifier = Modifier.padding(bottom = 5.dp),
                        )
                    }
                    Spacer(Modifier.height(11.dp))
                    MeterBar(
                        progress = state.coverage,
                        color = Color.White,
                        trackColor = Color(0x40FFFFFF),
                    )
                    if (state.unobtainableCount > 0) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "${state.unobtainableCount} cannot be granted on a normal " +
                                "install, no matter what the parent does.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xB8FFFFFF),
                        )
                    }
                }
            },
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.rows.forEachIndexed { index, row ->
                StaggeredEntrance(index) {
                    PermissionCard(
                        row = row,
                        onGrant = {
                            when (row.state.permission) {
                                SafetyPermission.POST_NOTIFICATIONS -> {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                }

                                SafetyPermission.VPN_CONSENT -> {
                                    // The one grant that comes from a system consent dialog rather
                                    // than a Settings screen. Raised through the VPN guard, which
                                    // otherwise cancels every connection request it sees.
                                    val prepare = VpnGuard.ownConsentIntent(context)
                                    if (prepare != null) {
                                        pendingVpn = true
                                        vpnLauncher.launch(prepare)
                                    } else {
                                        onEvent(PermissionsUiEvent.Refresh)
                                    }
                                }

                                else -> intentFor(row.state.permission)?.let {
                                    settingsLauncher.launch(it)
                                }
                            }
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun PermissionCard(row: PermissionRow, onGrant: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val permission = row.state.permission
    val unobtainable = row.state.unavailableReason != null

    SafetyCard(onClick = { expanded = !expanded }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(
                icon = if (row.state.isGranted) Icons.Rounded.CheckCircle else Icons.Rounded.Lock,
                tint = when {
                    row.state.isGranted -> Color(0xFF0E8F5E)
                    unobtainable -> SafetyPalette.Slate
                    else -> Color(0xFFB26A00)
                },
                container = when {
                    row.state.isGranted -> SafetyPalette.MintSoft
                    unobtainable -> Color(0xFFEFF1F7)
                    else -> SafetyPalette.WarningSoft
                },
            )
            Spacer(Modifier.width(13.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = permission.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = SafetyPalette.Ink,
                )
                Text(
                    text = permission.grantMechanism.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = SafetyPalette.Mist,
                )
            }
            GrantChip(
                text = when {
                    row.state.isGranted -> "On"
                    // Not "Blocked": that reads as though this app refused it, when the truth is
                    // Android will not hand it to any app installed the ordinary way.
                    unobtainable -> "Out of reach"
                    else -> "Off"
                },
                granted = row.state.isGranted,
                unavailable = unobtainable,
            )
        }

        Spacer(Modifier.height(12.dp))

        Text(
            text = if (row.state.isGranted) permission.enables else permission.withoutIt,
            style = MaterialTheme.typography.bodySmall,
            color = SafetyPalette.Slate,
        )

        Spacer(Modifier.height(11.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            if (row.blockedProtections.isNotEmpty()) {
                TonalChip(
                    text = "${row.blockedProtections.size} " +
                        if (row.blockedProtections.size == 1) "protection" else "protections",
                    content = if (row.state.isGranted) SafetyPalette.Success
                    else SafetyPalette.Danger,
                    container = if (row.state.isGranted) SafetyPalette.SuccessSoft
                    else SafetyPalette.DangerSoft,
                )
            }
            if (row.weakenedProtections.isNotEmpty()) {
                TonalChip(
                    text = "${row.weakenedProtections.size} stronger",
                    content = SafetyPalette.IndigoDeep,
                    container = SafetyPalette.IndigoSoft,
                )
            }
            if (!row.intentResolves && !unobtainable && permission != SafetyPermission.VPN_CONSENT &&
                permission != SafetyPermission.POST_NOTIFICATIONS
            ) {
                TonalChip(
                    text = "No direct settings screen",
                    content = Color(0xFFB26A00),
                    container = SafetyPalette.WarningSoft,
                )
            }
        }

        AnimatedVisibility(visible = expanded, enter = fadeIn() + expandVertically()) {
            Column {
                Spacer(Modifier.height(14.dp))
                HairLine()
                Spacer(Modifier.height(14.dp))
                if (row.blockedProtections.isNotEmpty()) {
                    Text(
                        text = if (row.state.isGranted) "This is running" else "Waiting on this",
                        style = MaterialTheme.typography.titleSmall,
                        color = SafetyPalette.Ink,
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = row.blockedProtections.joinToString { it.title },
                        style = MaterialTheme.typography.bodySmall,
                        color = SafetyPalette.Slate,
                    )
                    Spacer(Modifier.height(14.dp))
                }
                Text(
                    text = "What Android hands over",
                    style = MaterialTheme.typography.labelMedium,
                    color = SafetyPalette.Slate,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    text = permission.grants,
                    style = MaterialTheme.typography.bodySmall,
                    color = SafetyPalette.Ink.copy(alpha = 0.82f),
                )

                if (unobtainable) {
                    Spacer(Modifier.height(15.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BlindSpot(size = 54.dp)
                        Spacer(Modifier.width(13.dp))
                        Text(
                            text = row.state.unavailableReason.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = SafetyPalette.Slate,
                        )
                    }
                }
            }
        }

        if (!row.state.isGranted && !unobtainable) {
            Spacer(Modifier.height(15.dp))
            SecondaryButton(
                text = "Grant ${permission.title}",
                onClick = onGrant,
                icon = Icons.AutoMirrored.Rounded.OpenInNew,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
