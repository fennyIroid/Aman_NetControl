package com.example.childsafetyimpl.ux.protection

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.childsafetyimpl.core.common.TimeFormat
import com.example.childsafetyimpl.navigation.HandleNavigation
import com.example.childsafetyimpl.navigation.LocalNavController
import com.example.childsafetyimpl.ui.components.FilterPill
import com.example.childsafetyimpl.ui.components.IconBadge
import com.example.childsafetyimpl.ui.components.MeterBar
import com.example.childsafetyimpl.ui.theme.SafetyPalette
import com.example.childsafetyimpl.ui.theme.SafetyRadius
import com.example.childsafetyimpl.ux.common.BulletLine
import com.example.childsafetyimpl.ux.common.OutcomeCard
import com.example.childsafetyimpl.ux.common.OutcomeTone
import com.example.childsafetyimpl.ux.common.ProtectionScaffold
import com.example.childsafetyimpl.ux.common.ProtectionSection
import com.example.childsafetyimpl.ux.common.SettingSwitchRow

/**
 * The app list, and the screen that answers the YouTube question.
 *
 * Switching an app off here means the child cannot open it: the enforcement loop closes it every
 * time it comes to the front. That is what "block YouTube" means in this product, and it is
 * stronger than any setting that could be applied inside YouTube itself.
 */
@Composable
fun AppRestrictionScreen(viewModel: AppRestrictionViewModel) {
    HandleNavigation(viewModel, LocalNavController.current)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val status = state.status ?: return

    ProtectionScaffold(
        status = status,
        onBack = { viewModel.onEvent(AppRestrictionUiEvent.Back) },
        onToggle = { viewModel.onEvent(AppRestrictionUiEvent.ToggleFeature(it)) },
        heroStats = listOf(
            state.blockedCount.toString() to "Switched off",
            state.limitedCount.toString() to "Time limited",
            state.apps.size.toString() to "Apps",
        ),
    ) {
        if (state.usageUnavailable != null) {
            OutcomeCard(
                headline = "Time limits cannot run",
                detail = state.usageUnavailable!!,
                tone = OutcomeTone.WARNING,
                footnote = "Switching an app off still works. Only the time-based limits need this.",
            )
        }

        ProtectionSection(title = "Find an app", icon = Icons.Rounded.Search) {
            OutlinedTextField(
                value = state.query,
                onValueChange = { viewModel.onEvent(AppRestrictionUiEvent.Search(it)) },
                placeholder = { Text("Search installed apps") },
                singleLine = true,
                shape = SafetyRadius.field,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = SafetyPalette.Canvas,
                    unfocusedContainerColor = SafetyPalette.Canvas,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterPill(
                    text = "All",
                    selected = state.categoryFilter == null,
                    onClick = { viewModel.onEvent(AppRestrictionUiEvent.FilterCategory(null)) },
                    count = state.apps.size,
                )
                state.categories.forEach { category ->
                    FilterPill(
                        text = category.label,
                        selected = state.categoryFilter == category,
                        onClick = {
                            viewModel.onEvent(AppRestrictionUiEvent.FilterCategory(category))
                        },
                        count = state.apps.count { it.app.category == category },
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            SettingSwitchRow(
                title = "Include system apps",
                description = "Off by default. Switching off a system app can leave the device " +
                    "hard to use.",
                checked = state.showSystemApps,
                onCheckedChange = {
                    viewModel.onEvent(AppRestrictionUiEvent.ShowSystemApps(it))
                },
            )
        }

        ProtectionSection(
            title = "Apps",
            icon = Icons.Rounded.Apps,
            trailing = "${state.visible.size} shown",
        ) {
            if (state.visible.isEmpty()) {
                Text(
                    text = if (state.loading) "Reading the installed app list."
                    else "Nothing matches that search.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SafetyPalette.Slate,
                )
            } else {
                state.visible.forEach { managed ->
                    ManagedAppRow(
                        managed = managed,
                        onBlockedChange = { blocked ->
                            viewModel.onEvent(AppRestrictionUiEvent.SetBlocked(managed, blocked))
                        },
                        onLimitChange = { minutes ->
                            viewModel.onEvent(AppRestrictionUiEvent.SetLimit(managed, minutes))
                        },
                    )
                }
            }
        }

        ProtectionSection(title = "What to expect", icon = Icons.Rounded.Block) {
            BulletLine(
                "A switched-off app closes about a second or two after it opens. Android gives " +
                    "no instant signal that an app came to the front, so a brief flash of it is " +
                    "unavoidable.",
            )
            BulletLine(
                "The child sees a screen saying which rule applied and can close it. Trapping " +
                    "them behind a screen with no way out is not something this app does.",
            )
            BulletLine(
                "A time limit needs usage access. Switching an app off does not, and keeps " +
                    "working either way.",
            )
        }
    }
}

@Composable
private fun ManagedAppRow(
    managed: ManagedApp,
    onBlockedChange: (Boolean) -> Unit,
    onLimitChange: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(
                icon = Icons.Rounded.Apps,
                tint = if (managed.blocked) SafetyPalette.Danger else SafetyPalette.Indigo,
                container = if (managed.blocked) SafetyPalette.DangerSoft
                else SafetyPalette.IndigoSoft,
                size = 38,
            )
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = managed.app.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = SafetyPalette.Ink,
                )
                Text(
                    text = buildString {
                        append(managed.app.category.label)
                        if (managed.usedMillis > 0) {
                            append(" · ${TimeFormat.duration(managed.usedMillis)} today")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = SafetyPalette.Mist,
                )
            }
            Switch(
                checked = managed.blocked,
                onCheckedChange = onBlockedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = SafetyPalette.Danger,
                    checkedBorderColor = SafetyPalette.Danger,
                ),
            )
        }

        // A time limit on an app that is already switched off is meaningless, so the control is
        // removed rather than shown doing nothing.
        AnimatedVisibility(visible = !managed.blocked) {
            Column {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    FilterPill(
                        text = "No limit",
                        selected = managed.limitMinutes == null,
                        onClick = { onLimitChange(null) },
                    )
                    LIMIT_CHOICES.forEach { minutes ->
                        FilterPill(
                            text = "$minutes min",
                            selected = managed.limitMinutes == minutes,
                            onClick = { onLimitChange(minutes) },
                        )
                    }
                }
                if (managed.limitMinutes != null) {
                    Spacer(Modifier.height(9.dp))
                    MeterBar(
                        progress = managed.limitFraction,
                        color = if (managed.limitFraction >= 1f) SafetyPalette.Danger
                        else SafetyPalette.Indigo,
                    )
                }
            }
        }
    }
}

private val LIMIT_CHOICES = listOf(15, 30, 45, 60, 90, 120)
