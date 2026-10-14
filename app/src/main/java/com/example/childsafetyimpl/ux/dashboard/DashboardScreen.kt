package com.example.childsafetyimpl.ux.dashboard

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WifiOff
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.childsafetyimpl.core.common.TimeFormat
import com.example.childsafetyimpl.core.protection.HealthState
import com.example.childsafetyimpl.navigation.HandleNavigation
import com.example.childsafetyimpl.navigation.LocalNavController
import com.example.childsafetyimpl.ui.components.DayBars
import com.example.childsafetyimpl.ui.components.HeroHeader
import com.example.childsafetyimpl.ui.components.IconBadge
import com.example.childsafetyimpl.ui.components.MeterBar
import com.example.childsafetyimpl.ui.components.PrimaryButton
import com.example.childsafetyimpl.ui.components.SecondaryButton
import com.example.childsafetyimpl.ui.components.SafetyCard
import com.example.childsafetyimpl.ui.components.ScreenTimeRing
import com.example.childsafetyimpl.ui.components.SectionHeader
import com.example.childsafetyimpl.ui.components.StaggeredEntrance
import com.example.childsafetyimpl.ui.components.StatTile
import com.example.childsafetyimpl.ui.illustration.ShieldPulse
import com.example.childsafetyimpl.ui.theme.SafetyPalette
import com.example.childsafetyimpl.ui.theme.tone
import com.example.childsafetyimpl.ux.common.BottomBarSpacer
import com.example.childsafetyimpl.ux.common.EventRow
import com.example.childsafetyimpl.ux.protection.icon

/**
 * The parent's home screen.
 *
 * The order answers the questions a parent actually opens the app with, in the order they ask
 * them: is protection on, is anything broken, how much has the child been on the phone, and what
 * happened today. Anything the parent asked for that cannot run appears above the numbers, because
 * a number produced by a protection that is not running is worse than no number.
 */
@Composable
fun DashboardScreen(viewModel: DashboardViewModel) {
    HandleNavigation(viewModel, LocalNavController.current)
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // A pause needs VPN consent, and only an Activity can ask for it. Once given, the pause the
    // parent tapped for is sent, so it stays one tap from their point of view.
    val pauseConsentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != 0) viewModel.onEvent(DashboardUiEvent.ToggleInternetPause)
    }

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
                            text = if (state.childName.isBlank()) "Protection"
                            else state.childName,
                            style = MaterialTheme.typography.displaySmall,
                            color = Color.White,
                        )
                        Text(
                            text = state.headline,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xCCFFFFFF),
                        )
                        if (state.deviceLabel.isNotBlank()) {
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = state.deviceLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0x99FFFFFF),
                            )
                        }
                    }
                    ShieldPulse(
                        state = state.shieldState,
                        coverage = state.coverage,
                        size = 84.dp,
                    )
                }

                Spacer(Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Protection",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${state.summary.running} of ${state.summary.total}",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xCCFFFFFF),
                    )
                    Spacer(Modifier.size(10.dp))
                    Switch(
                        checked = state.protectionEnabled,
                        onCheckedChange = {
                            viewModel.onEvent(DashboardUiEvent.ToggleProtection(it))
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
                Spacer(Modifier.height(4.dp))
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // --- anything broken, first --------------------------------------------------
                if (state.protectionEnabled && state.healthIssues.isNotEmpty()) {
                    HealthCard(state = state)
                }

                // --- the control reached for in a hurry -------------------------------------------
                InternetPauseCard(
                    state = state,
                    onToggle = {
                        val consent = viewModel.pauseConsentIntent()
                        if (consent != null) {
                            pauseConsentLauncher.launch(consent)
                        } else {
                            viewModel.onEvent(DashboardUiEvent.ToggleInternetPause)
                        }
                    },
                )

                if (state.needsAttention.isNotEmpty()) {
                    SafetyCard(
                        background = SafetyPalette.WarningSoft,
                        borderColor = Color.Transparent,
                        onClick = { viewModel.onEvent(DashboardUiEvent.OpenPermissions) },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(
                                icon = Icons.Rounded.VisibilityOff,
                                tint = SafetyPalette.Warning,
                                container = Color(0x33FFFFFF),
                            )
                            Spacer(Modifier.size(13.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${state.needsAttention.size} " +
                                        (if (state.needsAttention.size == 1) "protection is"
                                        else "protections are") + " not running",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = SafetyPalette.Ink,
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = state.needsAttention.joinToString { it.feature.title },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SafetyPalette.Slate,
                                )
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                contentDescription = null,
                                tint = SafetyPalette.Warning,
                            )
                        }
                    }
                }

                // --- screen time --------------------------------------------------------------
                SafetyCard {
                    if (state.screenTimeAvailable) {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            ScreenTimeRing(
                                progress = state.screenTimeFraction,
                                headline = TimeFormat.duration(state.screenTimeMillis ?: 0),
                                caption = "of ${
                                    TimeFormat.durationFromMinutes(state.screenTimeLimitMinutes)
                                } today",
                            )
                        }

                        if (state.topApps.isNotEmpty()) {
                            Spacer(Modifier.height(18.dp))
                            state.topApps.forEach { app ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = app.label,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = SafetyPalette.Ink,
                                        )
                                        Spacer(Modifier.height(5.dp))
                                        MeterBar(
                                            progress = (
                                                app.foregroundMillis.toFloat() /
                                                    (state.screenTimeMillis ?: 1L).toFloat()
                                                ).coerceIn(0f, 1f),
                                            height = 6.dp,
                                        )
                                    }
                                    Spacer(Modifier.size(14.dp))
                                    Text(
                                        text = TimeFormat.duration(app.foregroundMillis),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = SafetyPalette.Slate,
                                    )
                                }
                            }
                        }
                    } else {
                        // The honest empty state. A zero here would read as "your child used
                        // nothing today", which is the most dangerous thing this card could say.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(
                                icon = Icons.Rounded.VisibilityOff,
                                tint = SafetyPalette.Slate,
                                container = SafetyPalette.Canvas,
                            )
                            Spacer(Modifier.size(13.dp))
                            Column {
                                Text(
                                    text = "Screen time is unavailable",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = SafetyPalette.Ink,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = state.screenTimeUnavailableReason
                                        ?: "Usage access has not been granted.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SafetyPalette.Slate,
                                )
                            }
                        }
                    }
                }

                if (state.weekMillis.isNotEmpty()) {
                    SafetyCard {
                        SectionHeader(title = "This week", subtitle = "Total time each day")
                        Spacer(Modifier.height(16.dp))
                        DayBars(
                            values = state.weekMillis.map { it.second.toFloat() },
                            labels = state.weekMillis.map { TimeFormat.weekday(it.first) },
                        )
                    }
                }

                // --- today's numbers ------------------------------------------------------------
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile(
                        value = state.blockedToday.toString(),
                        label = "Blocked today",
                        accent = SafetyPalette.Danger,
                        container = SafetyPalette.DangerSoft,
                        icon = Icons.Rounded.Block,
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.onEvent(DashboardUiEvent.OpenEvents) },
                    )
                    StatTile(
                        value = state.surfacesClosedToday.toString(),
                        label = "Screens closed",
                        accent = SafetyPalette.Indigo,
                        container = SafetyPalette.IndigoSoft,
                        icon = Icons.Rounded.Shield,
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.onEvent(DashboardUiEvent.OpenProtections) },
                    )
                    StatTile(
                        value = state.seriousEventsToday.toString(),
                        label = "Need a look",
                        accent = SafetyPalette.Warning,
                        container = SafetyPalette.WarningSoft,
                        icon = Icons.Rounded.NotificationsActive,
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.onEvent(DashboardUiEvent.OpenEvents) },
                    )
                }

                // --- quick ways in ----------------------------------------------------------------
                SectionHeader(title = "Open", subtitle = "The surfaces this app controls itself")

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile(
                        value = "Player",
                        label = "Restricted, no ads",
                        accent = SafetyPalette.Coral,
                        container = SafetyPalette.CoralSoft,
                        icon = Icons.Rounded.PlayCircleOutline,
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.onEvent(DashboardUiEvent.OpenPlayer) },
                    )
                    StatTile(
                        value = "Web",
                        label = "Filtered and blurred",
                        accent = SafetyPalette.Sky,
                        container = SafetyPalette.SkySoft,
                        icon = Icons.Rounded.Language,
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.onEvent(DashboardUiEvent.OpenBrowser) },
                    )
                    StatTile(
                        value = "Apps",
                        label = "Block and limit",
                        accent = SafetyPalette.Lavender,
                        container = SafetyPalette.LavenderSoft,
                        icon = Icons.Rounded.Apps,
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.onEvent(DashboardUiEvent.OpenApps) },
                    )
                }

                // --- what happened ------------------------------------------------------------------
                SectionHeader(
                    title = "Today",
                    subtitle = if (state.eventsToday == 0) "Nothing to report"
                    else "${state.eventsToday} things happened",
                )

                if (state.recentEvents.isEmpty()) {
                    SafetyCard {
                        Text(
                            text = if (state.protectionEnabled) {
                                "Nothing has needed attention. Anything protection does appears here."
                            } else {
                                "Protection is switched off, so nothing is being watched."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = SafetyPalette.Slate,
                        )
                    }
                } else {
                    SafetyCard {
                        state.recentEvents.forEachIndexed { index, event ->
                            StaggeredEntrance(index) {
                                EventRow(event = event)
                            }
                        }
                    }
                }

                // --- protections at a glance -----------------------------------------------------------
                if (state.needsAttention.isNotEmpty()) {
                    SectionHeader(title = "Waiting on you")
                    state.needsAttention.forEach { status ->
                        SafetyCard(
                            onClick = { viewModel.onEvent(DashboardUiEvent.OpenPermissions) },
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconBadge(
                                    icon = status.feature.icon,
                                    tint = status.state.tone.content,
                                    container = status.state.tone.container,
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
                                        style = MaterialTheme.typography.bodySmall,
                                        color = status.state.tone.content,
                                    )
                                }
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = SafetyPalette.Mist,
                                )
                            }
                        }
                    }
                }
            }

            BottomBarSpacer()
        }
    }
}

/**
 * What is wrong beyond a missing permission: a stopped service, a revoked grant, a pause that is
 * not in force, filtering being bypassed, an Android security mode that switched something off.
 *
 * Each issue carries the three things the health model promises — what is wrong, what it means
 * for the child, and what to do — because a warning without the third leaves a parent anxious and
 * stuck.
 */
@Composable
private fun HealthCard(state: DashboardUiState) {
    val serious = state.health?.state == HealthState.TAMPERED ||
        state.health?.state == HealthState.DISABLED
    val accent = if (serious) SafetyPalette.Danger else SafetyPalette.Warning
    SafetyCard(
        background = if (serious) SafetyPalette.DangerSoft else SafetyPalette.WarningSoft,
        borderColor = Color.Transparent,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(
                icon = Icons.Rounded.GppMaybe,
                tint = accent,
                container = Color(0x33FFFFFF),
            )
            Spacer(Modifier.size(13.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.health?.headline.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    color = SafetyPalette.Ink,
                )
                state.health?.state?.let { health ->
                    Text(
                        text = health.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = accent,
                    )
                }
            }
        }
        state.healthIssues.forEach { issue ->
            Spacer(Modifier.height(14.dp))
            Text(
                text = issue.title,
                style = MaterialTheme.typography.titleSmall,
                color = SafetyPalette.Ink,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = issue.why,
                style = MaterialTheme.typography.bodySmall,
                color = SafetyPalette.Slate,
            )
            issue.resolution?.let { resolution ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = resolution,
                    style = MaterialTheme.typography.bodySmall,
                    color = accent,
                )
            }
        }
    }
}

/**
 * Internet pause, one tap from the home screen.
 *
 * It lives on the Controls tab too, beside the rest of the filtering settings, but a parent
 * reaching for it is usually in a hurry, and a control you reach for in a hurry belongs on the
 * first screen. The text tells the truth about the three states that matter: paused and in force,
 * paused but not in force, and not paused.
 */
@Composable
private fun InternetPauseCard(
    state: DashboardUiState,
    onToggle: () -> Unit,
) {
    val mismatch = state.internetPaused && !state.internetPauseEnforced
    SafetyCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(
                icon = Icons.Rounded.WifiOff,
                tint = if (state.internetPaused) SafetyPalette.Danger else SafetyPalette.Slate,
                container = if (state.internetPaused) SafetyPalette.DangerSoft
                else SafetyPalette.Canvas,
            )
            Spacer(Modifier.size(13.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when {
                        mismatch -> "Paused, but not in force"
                        state.internetPaused -> "The internet is paused"
                        else -> "Internet"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = SafetyPalette.Ink,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = when {
                        mismatch -> "This device is online. Protection is trying to put the " +
                            "pause back; if it cannot, allow the connection request again."
                        state.internetPaused -> "Wi-Fi and mobile data are both stopped. Apps " +
                            "that work offline still work."
                        else -> "Stop all internet on this device at once."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (mismatch) SafetyPalette.Danger else SafetyPalette.Slate,
                )
            }
        }

        state.pauseMessage?.let { message ->
            Spacer(Modifier.height(10.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = SafetyPalette.Danger,
            )
        }

        Spacer(Modifier.height(14.dp))
        when {
            state.internetPaused -> PrimaryButton(
                text = "Resume the internet",
                onClick = onToggle,
                modifier = Modifier.fillMaxWidth(),
            )

            else -> SecondaryButton(
                text = "Pause the internet",
                onClick = onToggle,
                contentColor = SafetyPalette.Danger,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
