package com.example.childsafetyimpl.ux.protection

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.childsafetyimpl.core.common.TimeFormat
import com.example.childsafetyimpl.data.local.CloneFindingEntity
import com.example.childsafetyimpl.domain.model.CloneKind
import com.example.childsafetyimpl.navigation.HandleNavigation
import com.example.childsafetyimpl.navigation.LocalNavController
import com.example.childsafetyimpl.ui.components.DetailRow
import com.example.childsafetyimpl.ui.components.IconBadge
import com.example.childsafetyimpl.ui.components.SecondaryButton
import com.example.childsafetyimpl.ui.illustration.BlindSpot
import com.example.childsafetyimpl.ui.illustration.RadarSweep
import com.example.childsafetyimpl.ui.theme.SafetyPalette
import com.example.childsafetyimpl.ux.common.BulletLine
import com.example.childsafetyimpl.ux.common.OutcomeCard
import com.example.childsafetyimpl.ux.common.OutcomeTone
import com.example.childsafetyimpl.ux.common.ProtectionScaffold
import com.example.childsafetyimpl.ux.common.ProtectionSection
import com.example.childsafetyimpl.ux.common.SettingSwitchRow

@Composable
fun CloneProtectionScreen(viewModel: CloneProtectionViewModel) {
    HandleNavigation(viewModel, LocalNavController.current)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val status = state.status ?: return

    val scan = state.lastScan

    ProtectionScaffold(
        status = status,
        onBack = { viewModel.onEvent(CloneProtectionUiEvent.Back) },
        onToggle = { viewModel.onEvent(CloneProtectionUiEvent.ToggleFeature(it)) },
        heroStats = listOf(
            state.findings.size.toString() to "Findings",
            (scan?.visibleProfileCount ?: 1).toString() to "Profiles",
            state.findings.count { it.blocked }.toString() to "Closed on open",
        ),
    ) {
        if (state.findings.isEmpty()) {
            OutcomeCard(
                headline = "Nothing cloned found",
                detail = "No cloning tool is installed and no app appears twice across the " +
                    "profiles this app can see.",
                tone = OutcomeTone.SUCCESS,
                footnote = "Checked ${
                    if (state.lastScanAt == 0L) "just now" else TimeFormat.relative(state.lastScanAt)
                }.",
            )
        } else {
            OutcomeCard(
                headline = "${state.findings.size} " +
                    if (state.findings.size == 1) "finding" else "findings",
                detail = "Each one is a way an app could run outside the copy protection watches.",
                tone = OutcomeTone.WARNING,
                footnote = "Checked ${TimeFormat.relative(state.lastScanAt)}.",
            )
        }

        ProtectionSection(title = "How strict", icon = Icons.Rounded.Shield) {
            SettingSwitchRow(
                title = "Close cloning tools on open",
                description = "A cloning tool is closed each time the child opens it.",
                checked = state.blockCloneTools,
                onCheckedChange = { viewModel.onEvent(CloneProtectionUiEvent.ToggleBlocking(it)) },
            )
        }

        ProtectionSection(
            title = "What was found",
            icon = Icons.Rounded.ContentCopy,
            trailing = if (state.scanning) "Scanning" else null,
        ) {
            if (state.findings.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    RadarSweep(running = state.scanning)
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Nothing to show. New clones are found as they appear, without waiting " +
                        "for a scan.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SafetyPalette.Slate,
                )
            } else {
                state.findings.forEach { finding ->
                    CloneFindingRow(
                        finding = finding,
                        onToggleBlocked = { blocked ->
                            viewModel.onEvent(
                                CloneProtectionUiEvent.SetFindingBlocked(finding.identity, blocked),
                            )
                        },
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            SecondaryButton(
                text = if (state.scanning) "Scanning" else "Scan again",
                onClick = { viewModel.onEvent(CloneProtectionUiEvent.Rescan) },
                icon = Icons.Rounded.Refresh,
                enabled = !state.scanning,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (scan != null) {
            ProtectionSection(title = "How the check was made", icon = Icons.Rounded.Shield) {
                DetailRow("Cloning tools checked", scan.cloneToolsChecked.toString())
                DetailRow("Profiles visible", scan.visibleProfileCount.toString())
                DetailRow(
                    "Duplicate launcher entries",
                    scan.duplicateLauncherEntries.size.toString(),
                )
                DetailRow(
                    "Reading across profiles",
                    if (scan.launcherAppsAccessible) "Allowed" else "Refused by this device",
                )
            }
        }

        // The blind spot is stated on the screen rather than in a footnote, because a parent who
        // believes this covers Secure Folder is worse off than one who knows it does not.
        ProtectionSection(title = "What this cannot see", icon = Icons.Rounded.VisibilityOff) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                BlindSpot()
            }
            Spacer(Modifier.height(12.dp))
            BulletLine(
                "A manufacturer's own clone space, such as Samsung Secure Folder or Xiaomi Dual " +
                    "Apps, is sealed off by Android itself. Nothing installed inside one is " +
                    "visible from here.",
            )
            BulletLine(
                "A cloning tool that renames itself is not matched by name. It is still found if " +
                    "it produces a duplicate launcher entry.",
            )
            scan?.notes?.forEach { note -> BulletLine(note) }
        }
    }
}

@Composable
private fun CloneFindingRow(
    finding: CloneFindingEntity,
    onToggleBlocked: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(
                icon = Icons.Rounded.ContentCopy,
                tint = if (finding.blocked) SafetyPalette.Danger else SafetyPalette.Warning,
                container = if (finding.blocked) SafetyPalette.DangerSoft
                else SafetyPalette.WarningSoft,
                size = 36,
            )
            Spacer(Modifier.size(11.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = finding.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = SafetyPalette.Ink,
                )
                Text(
                    text = finding.kind.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = SafetyPalette.Slate,
                )
            }
            // Only a real app can be closed. A profile count is a fact about the device, and
            // offering a switch that could do nothing would be a lie in control form.
            if (finding.kind == CloneKind.CLONING_TOOL ||
                finding.kind == CloneKind.DUPLICATE_LAUNCHER_ENTRY
            ) {
                Switch(
                    checked = finding.blocked,
                    onCheckedChange = onToggleBlocked,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = SafetyPalette.Indigo,
                        checkedBorderColor = SafetyPalette.Indigo,
                    ),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "First seen ${TimeFormat.relative(finding.firstSeenAt)}" +
                if (finding.instanceCount > 1) ", ${finding.instanceCount} instances" else "",
            style = MaterialTheme.typography.labelSmall,
            color = SafetyPalette.Mist,
        )
    }
}
