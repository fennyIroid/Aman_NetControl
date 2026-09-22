package com.example.childsafetyimpl.ux.controls

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.childsafetyimpl.core.common.TimeFormat
import com.example.childsafetyimpl.domain.model.AppCategory
import com.example.childsafetyimpl.domain.model.WebCategory
import com.example.childsafetyimpl.navigation.HandleNavigation
import com.example.childsafetyimpl.navigation.LocalNavController
import com.example.childsafetyimpl.platform.network.FilteringMode
import com.example.childsafetyimpl.platform.network.NetworkCapability
import com.example.childsafetyimpl.platform.network.PrivateDnsGuard
import com.example.childsafetyimpl.ui.components.FilterPill
import com.example.childsafetyimpl.ui.components.HeroHeader
import com.example.childsafetyimpl.ui.components.PrimaryButton
import com.example.childsafetyimpl.ui.components.SecondaryButton
import com.example.childsafetyimpl.ui.theme.SafetyPalette
import com.example.childsafetyimpl.ui.theme.SafetyRadius
import com.example.childsafetyimpl.ux.common.BottomBarSpacer
import com.example.childsafetyimpl.ux.common.ProtectionSection
import com.example.childsafetyimpl.ux.common.SettingSwitchRow

/**
 * The settings a parent chooses once: when the day ends, how long it lasts, and what the web
 * filter refuses.
 *
 * Per-protection strictness is not here. It lives on each protection's own screen, beside the
 * evidence of what that protection is currently doing, which is the only place a parent can judge
 * whether a setting is right.
 */
@Composable
fun ControlsScreen(viewModel: ControlsViewModel) {
    HandleNavigation(viewModel, LocalNavController.current)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = state.settings

    val vpnConsentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != 0) viewModel.setTunnel(true)
    }

    // Consent asked for by the pause button itself, so pausing is still one tap from the
    // parent's side the first time: allow, and the pause they asked for goes through.
    val pauseConsentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != 0) viewModel.onEvent(ControlsUiEvent.ToggleInternetPause)
    }
    val onTogglePause = {
        val consent = viewModel.pauseConsentIntent()
        if (consent != null) {
            pauseConsentLauncher.launch(consent)
        } else {
            viewModel.onEvent(ControlsUiEvent.ToggleInternetPause)
        }
    }

    // Android says nothing when Private DNS changes, so it is re-read whenever this screen comes
    // back to the front, which is the moment a parent returns from entering it in Settings.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.onEvent(ControlsUiEvent.RefreshPrivateDns)
        }
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
                Text(
                    text = "Controls",
                    style = MaterialTheme.typography.displaySmall,
                    color = Color.White,
                )
                Text(
                    text = "The day, the limits, and what the web filter refuses",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xCCFFFFFF),
                )
                Spacer(Modifier.height(4.dp))
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ProtectionSection(title = "Bedtime", icon = Icons.Rounded.NightsStay) {
                    Text(
                        text = "Social, messaging, video, games and AI apps close during these " +
                            "hours. A clock or a torch does not.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SafetyPalette.Slate,
                    )
                    Spacer(Modifier.height(14.dp))

                    HourPicker(
                        label = "Starts",
                        minuteOfDay = settings.bedtimeStartMinuteOfDay,
                        onSelect = { start ->
                            viewModel.onEvent(
                                ControlsUiEvent.SetBedtime(start, settings.bedtimeEndMinuteOfDay),
                            )
                        },
                    )
                    Spacer(Modifier.height(14.dp))
                    HourPicker(
                        label = "Ends",
                        minuteOfDay = settings.bedtimeEndMinuteOfDay,
                        onSelect = { end ->
                            viewModel.onEvent(
                                ControlsUiEvent.SetBedtime(settings.bedtimeStartMinuteOfDay, end),
                            )
                        },
                    )
                }

                ProtectionSection(title = "Screen time today", icon = Icons.Rounded.Timer) {
                    Text(
                        text = "The figure the dashboard ring is measured against.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SafetyPalette.Slate,
                    )
                    Spacer(Modifier.height(13.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DAILY_LIMIT_CHOICES.forEach { minutes ->
                            FilterPill(
                                text = TimeFormat.durationFromMinutes(minutes),
                                selected = settings.dailyScreenTimeLimitMinutes == minutes,
                                onClick = {
                                    viewModel.onEvent(ControlsUiEvent.SetDailyLimit(minutes))
                                },
                            )
                        }
                    }
                }

                ProtectionSection(title = "Category budgets", icon = Icons.Rounded.Apps) {
                    Text(
                        text = "A shared allowance for every app in a category. Blocking one app " +
                            "at a time is on the apps screen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SafetyPalette.Slate,
                    )
                    Spacer(Modifier.height(6.dp))

                    BUDGET_CATEGORIES.forEach { category ->
                        val budget = state.categoryBudgets
                            .firstOrNull { it.category == category && it.enabled }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = category.label,
                            style = MaterialTheme.typography.titleSmall,
                            color = SafetyPalette.Ink,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterPill(
                                text = "No limit",
                                selected = budget?.dailyLimitMinutes == null,
                                onClick = {
                                    viewModel.onEvent(
                                        ControlsUiEvent.SetCategoryBudget(category, null),
                                    )
                                },
                            )
                            CATEGORY_BUDGET_CHOICES.forEach { minutes ->
                                FilterPill(
                                    text = "$minutes min",
                                    selected = budget?.dailyLimitMinutes == minutes,
                                    onClick = {
                                        viewModel.onEvent(
                                            ControlsUiEvent.SetCategoryBudget(category, minutes),
                                        )
                                    },
                                )
                            }
                        }
                    }
                }

                ProtectionSection(title = "Web filtering", icon = Icons.Rounded.Language) {
                    SettingSwitchRow(
                        title = "Force safe search",
                        description = "Answers search-engine lookups with the filtered address " +
                            "each engine publishes, so this applies in Chrome too, not only in " +
                            "the built-in browser.",
                        checked = settings.safeSearchEnabled,
                        onCheckedChange = {
                            viewModel.onEvent(ControlsUiEvent.SetSafeSearch(it))
                        },
                    )

                    SettingSwitchRow(
                        title = "Only allow sites you have listed",
                        description = "The strictest setting. Nothing loads in the built-in " +
                            "browser unless a parent added it by name below.",
                        checked = settings.allowListOnlyBrowsing,
                        onCheckedChange = {
                            viewModel.onEvent(ControlsUiEvent.SetAllowListOnly(it))
                        },
                    )

                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Categories to refuse",
                        style = MaterialTheme.typography.titleSmall,
                        color = SafetyPalette.Ink,
                    )
                    Spacer(Modifier.height(8.dp))
                    WebCategory.entries
                        .filter { it != WebCategory.UNCATEGORISED }
                        .forEach { category ->
                            SettingSwitchRow(
                                title = category.label,
                                description = category.description,
                                checked = state.webCategories[category] == true,
                                onCheckedChange = { blocked ->
                                    viewModel.onEvent(
                                        ControlsUiEvent.SetWebCategoryBlocked(category, blocked),
                                    )
                                },
                            )
                        }
                }

                ProtectionSection(
                    title = if (settings.allowListOnlyBrowsing) "Sites you allow"
                    else "Sites you have blocked",
                    icon = Icons.Rounded.Dns,
                    trailing = "${state.parentDomainRules.size}",
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = state.newDomain,
                            onValueChange = {
                                viewModel.onEvent(ControlsUiEvent.NewDomainChanged(it))
                            },
                            placeholder = { Text("example.com") },
                            singleLine = true,
                            shape = SafetyRadius.field,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(
                                onDone = { viewModel.onEvent(ControlsUiEvent.AddDomain) },
                            ),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = SafetyPalette.Canvas,
                                unfocusedContainerColor = SafetyPalette.Canvas,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.size(10.dp))
                        PrimaryButton(
                            text = "Add",
                            onClick = { viewModel.onEvent(ControlsUiEvent.AddDomain) },
                            icon = Icons.Rounded.Add,
                        )
                    }

                    if (state.parentDomainRules.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        state.parentDomainRules.forEach { rule ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = rule.pattern,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = SafetyPalette.Ink,
                                    modifier = Modifier.weight(1f),
                                )
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Remove ${rule.pattern}",
                                    tint = SafetyPalette.Mist,
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clickableRemove {
                                            viewModel.onEvent(
                                                ControlsUiEvent.RemoveDomain(rule.id),
                                            )
                                        },
                                )
                            }
                        }
                    }
                }

                // Placed above device-wide filtering deliberately. Pause is the control a
                // parent reaches for in a hurry, and a control you reach for in a hurry does not
                // belong three sections down a settings list.
                ProtectionSection(title = "Internet pause", icon = Icons.Rounded.WifiOff) {
                    Text(
                        text = when {
                            state.internetPaused && state.internetPauseEnforced ->
                                "The internet is paused. Wi-Fi and mobile data are both covered, " +
                                    "and the child is seeing a notice that says a parent paused " +
                                    "it rather than a connection error. Apps that work offline " +
                                    "still work."

                            // The mismatch. Silent in every other product and the reason this
                            // branch exists at all.
                            state.internetPaused ->
                                "You paused the internet, but it is not being applied: the " +
                                    "filtering tunnel is not running, so this device is online. " +
                                    "Connection permission was most likely withdrawn."

                            else ->
                                "Stops all internet on this device at once, on Wi-Fi and mobile " +
                                    "data. It survives a restart, and it lifts the moment you " +
                                    "turn it back on."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state.internetPaused && !state.internetPauseEnforced) {
                            SafetyPalette.Danger
                        } else {
                            SafetyPalette.Slate
                        },
                    )

                    state.pauseMessage?.let { message ->
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = SafetyPalette.Danger,
                        )
                    }

                    Spacer(Modifier.height(14.dp))
                    if (state.internetPaused) {
                        PrimaryButton(
                            text = "Resume the internet",
                            onClick = onTogglePause,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else if (state.filteringMode.canPauseInternet) {
                        SecondaryButton(
                            text = "Pause the internet",
                            onClick = onTogglePause,
                            contentColor = SafetyPalette.Danger,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        // Said here rather than left as a button that fails: the posture the
                        // parent chose has nothing in the traffic path to stop.
                        Text(
                            text = "Private DNS on its own cannot pause the internet, because a " +
                                "resolver cannot stop a connection that is already open. Choose " +
                                "Hybrid or Tunnel below to use pause.",
                            style = MaterialTheme.typography.bodySmall,
                            color = SafetyPalette.Warning,
                        )
                    }
                }

                FilteringModeSection(
                    state = state,
                    onSelectMode = { viewModel.onEvent(ControlsUiEvent.SetFilteringMode(it)) },
                    onSelectResolver = {
                        viewModel.onEvent(ControlsUiEvent.SetPrivateDnsHostname(it))
                    },
                    onRefresh = { viewModel.onEvent(ControlsUiEvent.RefreshPrivateDns) },
                )

                ProtectionSection(title = "Device-wide filtering", icon = Icons.Rounded.Shield) {
                    if (!state.filteringMode.keepsTunnelUp) {
                        // In the Private DNS postures the tunnel is not something a parent keeps
                        // running, so the start button would contradict the choice just made.
                        Text(
                            text = if (state.tunnelRunning) {
                                "The tunnel is up for now — for a pause, or briefly to end " +
                                    "another VPN — and goes away again afterwards."
                            } else {
                                "Filtering runs through Private DNS, so the tunnel is not kept " +
                                    "running. It is raised only while it is needed" +
                                    (if (state.filteringMode.canPauseInternet) ", such as for " +
                                        "a pause." else ".")
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = SafetyPalette.Slate,
                        )
                        return@ProtectionSection
                    }
                    Text(
                        text = if (state.tunnelRunning) {
                            "The tunnel is running. Blocked sites and ad hosts are refused for " +
                                "every app on this device, not only the built-in browser."
                        } else {
                            "Without the tunnel, filtering only applies inside the built-in " +
                                "browser and player. Other apps are unaffected."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = SafetyPalette.Slate,
                    )
                    Spacer(Modifier.height(14.dp))
                    if (state.tunnelRunning) {
                        SecondaryButton(
                            text = "Stop the tunnel",
                            onClick = { viewModel.onEvent(ControlsUiEvent.SetTunnel(false)) },
                            contentColor = SafetyPalette.Danger,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        PrimaryButton(
                            text = "Start the tunnel",
                            onClick = {
                                val consent = viewModel.tunnelConsentIntent()
                                if (consent != null) {
                                    vpnConsentLauncher.launch(consent)
                                } else {
                                    viewModel.setTunnel(true)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                ProtectionSection(title = "Message signals", icon = Icons.Rounded.Tune) {
                    SettingSwitchRow(
                        title = "Watch messaging notifications",
                        description = "Scores incoming message previews for concerning language. " +
                            "The message itself is never stored, only what it matched.",
                        checked = settings.notificationSignalsEnabled,
                        onCheckedChange = {
                            viewModel.onEvent(ControlsUiEvent.SetNotificationSignals(it))
                        },
                    )
                }

                ProtectionSection(title = "Elsewhere", icon = Icons.Rounded.Apps) {
                    SecondaryButton(
                        text = "Block and limit individual apps",
                        onClick = { viewModel.onEvent(ControlsUiEvent.OpenApps) },
                        icon = Icons.Rounded.Apps,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(9.dp))
                    SecondaryButton(
                        text = "Permissions",
                        onClick = { viewModel.onEvent(ControlsUiEvent.OpenPermissions) },
                        icon = Icons.Rounded.Shield,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            BottomBarSpacer()
        }
    }
}

@Composable
private fun HourPicker(
    label: String,
    minuteOfDay: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = SafetyPalette.Ink,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = TimeFormat.formatMinuteOfDay(minuteOfDay),
                style = MaterialTheme.typography.labelLarge,
                color = SafetyPalette.Indigo,
            )
        }
        Spacer(Modifier.height(9.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            // Half-hour granularity. A parent setting bedtime does not want a minute picker, and
            // an exact time here would be false precision on a rule that already has a lag.
            (0 until 48).forEach { half ->
                val value = half * 30
                FilterPill(
                    text = TimeFormat.formatMinuteOfDay(value),
                    selected = minuteOfDay == value,
                    onClick = { onSelect(value) },
                )
            }
        }
    }
}

/** Small clickable wrapper so a bare icon stays a touch target without a button's chrome. */
private fun Modifier.clickableRemove(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)

private val DAILY_LIMIT_CHOICES = listOf(60, 90, 120, 180, 240, 300, 360)
private val CATEGORY_BUDGET_CHOICES = listOf(15, 30, 45, 60, 90, 120)
private val BUDGET_CATEGORIES = listOf(
    AppCategory.SOCIAL,
    AppCategory.COMMUNICATION,
    AppCategory.VIDEO,
    AppCategory.GAME,
    AppCategory.AI,
)

private fun FilteringMode.shortLabel(): String = when (this) {
    FilteringMode.VPN_TUNNEL -> "Tunnel"
    FilteringMode.PRIVATE_DNS -> "Private DNS"
    FilteringMode.HYBRID -> "Hybrid"
}

/**
 * How device-wide filtering is delivered, and the setup the Private DNS postures need.
 *
 * The capability list under the choice is generated from [FilteringMode.capabilities], the same
 * data the architecture note's comparison table is built from, so what this screen promises and
 * what the Sprint 0 results claim cannot drift apart.
 */
@Composable
private fun FilteringModeSection(
    state: ControlsUiState,
    onSelectMode: (FilteringMode) -> Unit,
    onSelectResolver: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    val mode = state.filteringMode
    ProtectionSection(title = "How filtering works", icon = Icons.Rounded.Router) {
        Text(
            text = "The tunnel filters inside this app and can pause the internet, but shows a " +
                "VPN key and is visible to banking apps. Private DNS is quieter and invisible to " +
                "them, but cannot pause. Hybrid uses Private DNS day to day and raises the " +
                "tunnel only for a pause.",
            style = MaterialTheme.typography.bodySmall,
            color = SafetyPalette.Slate,
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilteringMode.entries.forEach { option ->
                FilterPill(
                    text = option.shortLabel(),
                    selected = option == mode,
                    onClick = { onSelectMode(option) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = mode.label,
            style = MaterialTheme.typography.titleSmall,
            color = SafetyPalette.Ink,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = mode.summary,
            style = MaterialTheme.typography.bodySmall,
            color = SafetyPalette.Slate,
        )
        Spacer(Modifier.height(10.dp))
        NetworkCapability.entries.forEach { capability ->
            val supported = mode.supports(capability)
            Row(
                modifier = Modifier.padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (supported) Icons.Rounded.Check else Icons.Rounded.Close,
                    contentDescription = if (supported) "Supported" else "Not supported",
                    tint = if (supported) SafetyPalette.Mint else SafetyPalette.Mist,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = capability.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (supported) SafetyPalette.Ink else SafetyPalette.Slate,
                )
            }
        }

        if (mode.usesPrivateDns) {
            Spacer(Modifier.height(16.dp))
            PrivateDnsSetup(
                state = state,
                onSelectResolver = onSelectResolver,
                onRefresh = onRefresh,
            )
        }
    }
}

/**
 * The guided Private DNS step.
 *
 * Android does not let an app write this setting — only a device owner can — so the best this
 * screen can do is make the parent's part unambiguous and then check it. It is the one place the
 * "simpler" DNS approach is harder to set up than a VPN consent dialog, and the steps are written
 * for that.
 */
@Composable
private fun PrivateDnsSetup(
    state: ControlsUiState,
    onSelectResolver: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    val context = LocalContext.current
    val hostname = state.settings.privateDnsHostname
    val advice = state.privateDnsAdvice

    Text(
        text = "Set up Private DNS",
        style = MaterialTheme.typography.titleSmall,
        color = SafetyPalette.Ink,
    )
    Spacer(Modifier.height(6.dp))

    advice?.let {
        val tone = when (state.privateDnsPosture) {
            is PrivateDnsGuard.Posture.Filtering -> SafetyPalette.Mint
            is PrivateDnsGuard.Posture.ForeignResolver -> SafetyPalette.Danger
            else -> SafetyPalette.Warning
        }
        Text(
            text = it.headline,
            style = MaterialTheme.typography.labelLarge,
            color = tone,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = it.whatItMeans,
            style = MaterialTheme.typography.bodySmall,
            color = SafetyPalette.Slate,
        )
        Spacer(Modifier.height(12.dp))
    }

    Text(
        text = "Filtering resolver",
        style = MaterialTheme.typography.labelLarge,
        color = SafetyPalette.Ink,
    )
    Spacer(Modifier.height(6.dp))
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilteringMode.demoResolvers.forEach { resolver ->
            FilterPill(
                text = resolver.label,
                selected = resolver.hostname == hostname,
                onClick = { onSelectResolver(resolver.hostname) },
            )
        }
    }
    FilteringMode.demoResolvers.firstOrNull { it.hostname == hostname }?.let { resolver ->
        Spacer(Modifier.height(4.dp))
        Text(
            text = resolver.covers,
            style = MaterialTheme.typography.bodySmall,
            color = SafetyPalette.Slate,
        )
    }

    Spacer(Modifier.height(10.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SafetyPalette.Canvas, SafetyRadius.field)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = hostname,
            style = MaterialTheme.typography.titleSmall,
            color = SafetyPalette.Ink,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Rounded.ContentCopy,
            contentDescription = "Copy the hostname",
            tint = SafetyPalette.Indigo,
            modifier = Modifier
                .size(20.dp)
                .clickable {
                    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(
                        android.content.ClipData.newPlainText("Private DNS hostname", hostname),
                    )
                },
        )
    }

    Spacer(Modifier.height(10.dp))
    Text(
        text = "1. Copy the hostname above.\n" +
            "2. Open Settings, then Network and internet (on some phones, Connections, then " +
            "More connection settings), then Private DNS.\n" +
            "3. Choose \"Private DNS provider hostname\", paste it, and save.\n" +
            "4. Come back here. The status above updates by itself.",
        style = MaterialTheme.typography.bodySmall,
        color = SafetyPalette.Slate,
    )

    Spacer(Modifier.height(12.dp))
    SecondaryButton(
        text = "Open Settings",
        onClick = {
            val opened = runCatching {
                context.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS),
                )
            }.isSuccess
            if (!opened) {
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_SETTINGS),
                    )
                }
            }
        },
        icon = Icons.AutoMirrored.Rounded.OpenInNew,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    SecondaryButton(
        text = "Check again",
        onClick = onRefresh,
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(12.dp))
    // What to expect, stated on the screen that offers the capability (rule 4).
    Text(
        text = "What to expect: Android does not let any app lock this setting, so it can be " +
            "changed back in Settings in a few taps. This app checks it every few seconds and " +
            "alerts you when it changes. Your own blocked and allowed sites are applied on this " +
            "device and do not reach a public resolver; per-family lists through Private DNS " +
            "need a resolver of our own, which is Phase 1 work.",
        style = MaterialTheme.typography.bodySmall,
        color = SafetyPalette.Slate,
    )
}
