package com.example.childsafetyimpl.ux.protection

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.childsafetyimpl.core.common.TimeFormat
import com.example.childsafetyimpl.domain.model.RestrictedSurface
import com.example.childsafetyimpl.navigation.HandleNavigation
import com.example.childsafetyimpl.navigation.LocalNavController
import com.example.childsafetyimpl.ui.components.DetailRow
import com.example.childsafetyimpl.ui.components.FilterPill
import com.example.childsafetyimpl.ui.components.IconBadge
import com.example.childsafetyimpl.ui.components.MeterBar
import com.example.childsafetyimpl.ui.components.TonalChip
import com.example.childsafetyimpl.ui.theme.SafetyPalette
import com.example.childsafetyimpl.ux.common.BulletLine
import com.example.childsafetyimpl.ux.common.OutcomeCard
import com.example.childsafetyimpl.ux.common.OutcomeTone
import com.example.childsafetyimpl.ux.common.ProtectionScaffold
import com.example.childsafetyimpl.ux.common.ProtectionSection

/**
 * One screen, used for both Shorts and Status.
 *
 * The allowance control is the centre of it. Refusing a surface outright and permitting fifteen
 * minutes of it are genuinely different parenting decisions, and offering only the first would
 * have made this a blunter product than the brief asks for.
 */
@Composable
fun SurfaceProtectionScreen(viewModel: SurfaceProtectionViewModel) {
    HandleNavigation(viewModel, LocalNavController.current)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val status = state.status ?: return
    val usage = state.usage

    val surfaceName = state.surface.childFacingName
    val installedCount = state.watchedApps.count { it.installed }

    ProtectionScaffold(
        status = status,
        onBack = { viewModel.onEvent(SurfaceProtectionUiEvent.Back) },
        onToggle = { viewModel.onEvent(SurfaceProtectionUiEvent.ToggleFeature(it)) },
        heroStats = listOf(
            (usage?.encounters ?: 0).toString() to "Opened today",
            TimeFormat.duration(usage?.dwellMillis ?: 0) to "Time in it",
            installedCount.toString() to "Apps watched",
        ),
    ) {
        // The screen reading service is the whole mechanism. Without it, nothing else on this
        // page is doing anything, and the page has to say so before it says anything else.
        if (!state.serviceConnected) {
            OutcomeCard(
                headline = "Screen protection is not running",
                detail = "This protection reads which screen is open inside another app. Until " +
                    "the accessibility service is switched on in Android settings, $surfaceName " +
                    "cannot be recognised or closed.",
                tone = OutcomeTone.WARNING,
                footnote = "Grant it from the Permissions screen.",
            )
        } else if (usage != null && usage.exhausted && usage.encounters > 0) {
            OutcomeCard(
                headline = "$surfaceName closed ${usage.encounters} times today",
                detail = if (state.refusedOutright) {
                    "The feed is closed the moment it is recognised."
                } else {
                    "Today's ${usage.allowanceMinutes} minute allowance is used up."
                },
                tone = OutcomeTone.BLOCKED,
            )
        } else {
            OutcomeCard(
                headline = if (usage?.encounters == 0) "Not opened today" else "Watching",
                detail = if (state.refusedOutright) {
                    "$surfaceName is closed as soon as it is recognised in any watched app."
                } else {
                    "${TimeFormat.duration(usage?.dwellMillis ?: 0)} of today's " +
                        "${usage?.allowanceMinutes} minutes used."
                },
                tone = OutcomeTone.SUCCESS,
            )
        }

        ProtectionSection(title = "Daily allowance", icon = Icons.Rounded.Timer) {
            Text(
                text = "Choose whether $surfaceName is refused outright or allowed for a while " +
                    "each day.",
                style = MaterialTheme.typography.bodySmall,
                color = SafetyPalette.Slate,
            )
            Spacer(Modifier.height(13.dp))

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterPill(
                    text = "Never",
                    selected = state.allowanceMinutes == null,
                    onClick = { viewModel.onEvent(SurfaceProtectionUiEvent.SetAllowance(null)) },
                )
                ALLOWANCE_CHOICES.forEach { minutes ->
                    FilterPill(
                        text = "$minutes min",
                        selected = state.allowanceMinutes == minutes,
                        onClick = {
                            viewModel.onEvent(SurfaceProtectionUiEvent.SetAllowance(minutes))
                        },
                    )
                }
            }

            if (usage != null && !state.refusedOutright) {
                Spacer(Modifier.height(16.dp))
                MeterBar(
                    progress = usage.fraction,
                    color = if (usage.exhausted) SafetyPalette.Danger else SafetyPalette.Indigo,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "${TimeFormat.duration(usage.dwellMillis)} used of " +
                        TimeFormat.durationFromMinutes(usage.allowanceMinutes ?: 0),
                    style = MaterialTheme.typography.labelMedium,
                    color = SafetyPalette.Slate,
                )
            }
        }

        ProtectionSection(
            title = "Apps this watches",
            icon = Icons.Rounded.Apps,
            trailing = "$installedCount installed",
        ) {
            state.watchedApps.forEach { app ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = app.label,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (app.installed) SafetyPalette.Ink else SafetyPalette.Mist,
                        modifier = Modifier.weight(1f),
                    )
                    TonalChip(
                        text = if (app.installed) "Installed" else "Not installed",
                        content = if (app.installed) SafetyPalette.Success else SafetyPalette.Mist,
                        container = if (app.installed) SafetyPalette.SuccessSoft
                        else SafetyPalette.Canvas,
                    )
                }
            }
        }

        ProtectionSection(title = "Recognition right now", icon = Icons.Rounded.Visibility) {
            val signal = state.currentSignal
            if (signal == null) {
                Text(
                    text = "Nothing read yet. Open one of the apps above and this shows what the " +
                        "protection sees.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SafetyPalette.Slate,
                )
            } else {
                DetailRow("App in front", signal.appLabel)
                DetailRow(
                    "Screen recognised as",
                    signal.surface?.label ?: "Nothing restricted",
                )
                DetailRow("Recognised by", signal.matchedOn ?: "No signature matched")
                DetailRow("Read in", "${signal.walkDurationMillis} ms")
            }

            Spacer(Modifier.height(10.dp))
            DetailRow("Signatures watched", state.signalCount.toString())
            DetailRow("Screens closed", state.stats.surfacesClosed.toString())
            DetailRow("Average read cost", "${state.stats.averageWalkMillis} ms")
        }

        ProtectionSection(title = "Recent", icon = Icons.Rounded.History) {
            if (state.recent.isEmpty()) {
                Text(
                    text = "Nothing yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SafetyPalette.Slate,
                )
            } else {
                state.recent.take(12).forEach { encounter ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconBadge(
                            icon = Icons.Rounded.History,
                            tint = if (encounter.closed) SafetyPalette.Danger
                            else SafetyPalette.Warning,
                            container = if (encounter.closed) SafetyPalette.DangerSoft
                            else SafetyPalette.WarningSoft,
                            size = 32,
                        )
                        Spacer(Modifier.size(11.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = encounter.appLabel,
                                style = MaterialTheme.typography.titleSmall,
                                color = SafetyPalette.Ink,
                            )
                            Text(
                                text = if (encounter.closed) {
                                    "Closed after ${TimeFormat.duration(encounter.dwellMillis)}"
                                } else {
                                    "Open for ${TimeFormat.duration(encounter.dwellMillis)}"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = SafetyPalette.Slate,
                            )
                        }
                        Text(
                            text = TimeFormat.relative(encounter.atMillis),
                            style = MaterialTheme.typography.labelSmall,
                            color = SafetyPalette.Mist,
                        )
                    }
                }
            }
        }

        ProtectionSection(title = "What to expect", icon = Icons.Rounded.Visibility) {
            BulletLine(
                "A second or two of the feed is visible before it closes. The screen has to be " +
                    "read before it can be recognised, and that read is what takes the time.",
            )
            BulletLine(
                "Closing works by going back, the same as the child pressing back themselves. An " +
                    "app that ignores back stays open, and the history above says so when it " +
                    "happens rather than claiming a close that did not occur.",
            )
            BulletLine(
                "These apps can change their screens in any update. When that happens, " +
                    "recognition drops and the numbers on this page fall, which is the signal " +
                    "that a signature needs updating.",
            )
            if (state.surface == RestrictedSurface.SHORT_FORM_FEED) {
                BulletLine(
                    "The rest of the app keeps working. A child blocked from Reels can still " +
                        "message a friend in Instagram.",
                )
            }
        }
    }
}

/** Round numbers a parent would actually pick, rather than a slider they have to aim. */
private val ALLOWANCE_CHOICES = listOf(5, 10, 15, 30, 45, 60)
