package com.example.childsafetyimpl.ux.dashboard

import com.example.childsafetyimpl.core.common.DataResult
import com.example.childsafetyimpl.core.common.TimeFormat
import com.example.childsafetyimpl.core.permission.SafetyPermission
import com.example.childsafetyimpl.core.protection.ProtectionHealth
import com.example.childsafetyimpl.core.protection.ProtectionState
import com.example.childsafetyimpl.core.protection.ProtectionStatus
import com.example.childsafetyimpl.core.protection.ProtectionStatusProvider
import com.example.childsafetyimpl.data.prefs.SafetyPreferences
import com.example.childsafetyimpl.data.prefs.SafetySettings
import com.example.childsafetyimpl.domain.repository.SafetyEventRepository
import com.example.childsafetyimpl.platform.accessibility.SurfaceGuard
import com.example.childsafetyimpl.platform.admin.AdvancedProtectionProbe
import com.example.childsafetyimpl.platform.enforcement.MonitoringService
import com.example.childsafetyimpl.platform.network.ChildSafetyVpnService
import com.example.childsafetyimpl.platform.network.PrivateDnsGuard
import com.example.childsafetyimpl.platform.usage.UsageStatsProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Owns the dashboard's state, in the UseCase-as-state-manager pattern the project follows.
 *
 * Two sources with very different shapes are joined here. Protection status, settings and the
 * event log are all flows and update themselves. Usage measurement is a query with no change
 * notification of any kind, so it is polled, and the poll is the only reason this class is more
 * than a `combine`.
 *
 * The rule this class exists to enforce is the one about zeros. When usage access is missing, the
 * screen-time field stays null and the reason travels with it, so the dashboard can say "not
 * allowed to look" instead of showing a confident and entirely false `0m`.
 */
class GetDashboardUiStateUseCase @Inject constructor(
    private val preferences: SafetyPreferences,
    private val statusProvider: ProtectionStatusProvider,
    private val usageStatsProvider: UsageStatsProvider,
    private val eventRepository: SafetyEventRepository,
    private val surfaceGuard: SurfaceGuard,
    private val privateDnsGuard: PrivateDnsGuard,
    private val advancedProtectionProbe: AdvancedProtectionProbe,
) {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    /** The inputs the health model was last built from, so the usage poll can rebuild it too. */
    private var lastSettings: SafetySettings? = null
    private var lastStatuses: List<ProtectionStatus> = emptyList()

    suspend fun observe() {
        val startOfToday = TimeFormat.startOfTodayMillis()

        // The live state of the two services is observed so the health card changes the moment
        // they do, rather than on the next poll.
        val serviceSignals = combine(
            MonitoringService.State.running,
            MonitoringService.State.vpnActive,
            MonitoringService.State.recentlyRevoked,
            ChildSafetyVpnService.State.running,
            ChildSafetyVpnService.State.paused,
        ) { _, _, _, _, _ -> Unit }

        combine(
            preferences.settings,
            statusProvider.observeAll(),
            statusProvider.observeSummary(),
            eventRepository.observeRecent(limit = 12),
            combine(
                eventRepository.observeCountSince(startOfToday),
                eventRepository.observeSeriousCountSince(startOfToday),
                surfaceGuard.observeClosedToday(),
                serviceSignals,
            ) { total, serious, closed, _ -> Triple(total, serious, closed) },
        ) { settings, statuses, summary, events, counts ->
            lastSettings = settings
            lastStatuses = statuses
            _uiState.value.copy(
                loading = false,
                childName = settings.childName,
                deviceLabel = settings.deviceLabel,
                protectionEnabled = settings.protectionEnabled,
                screenTimeLimitMinutes = settings.dailyScreenTimeLimitMinutes,
                summary = summary,
                needsAttention = statuses.filter {
                    it.enabled && it.state == ProtectionState.NEEDS_PERMISSION
                },
                recentEvents = events,
                eventsToday = counts.first,
                seriousEventsToday = counts.second,
                surfacesClosedToday = counts.third,
                blockedToday = events.count {
                    it.occurredAt >= startOfToday &&
                        it.action == com.example.childsafetyimpl.domain.model.EventAction.BLOCKED
                },
                internetPaused = settings.internetPaused,
                internetPauseEnforced = pauseEnforced(),
                pauseAvailable = settings.filteringMode.canPauseInternet,
                health = buildHealth(settings, statuses),
            )
        }
            // Building the health picture reads system settings; kept off the main thread.
            .flowOn(Dispatchers.IO)
            .collect { state -> _uiState.update { state } }
    }

    fun setPauseMessage(message: String?) = _uiState.update { it.copy(pauseMessage = message) }

    private fun pauseEnforced(): Boolean =
        ChildSafetyVpnService.State.running.value && ChildSafetyVpnService.State.paused.value

    /**
     * Builds the health picture from what is true right now.
     *
     * Parts of it have no change notification — the Private DNS setting, Advanced Protection,
     * the heartbeat going stale — so it is also rebuilt on the usage poll.
     */
    private fun buildHealth(
        settings: SafetySettings,
        statuses: List<ProtectionStatus>,
    ): ProtectionHealth {
        val filtering = settings.filteringMode
        val posture = privateDnsGuard.currentPosture()

        // What counts as a problem depends on the posture. With the tunnel, any named resolver
        // is a way around it. With Private DNS, the setting is the filter, so it being unset or
        // pointed somewhere that does not filter is the problem, and a filtering resolver is the
        // correct state rather than a bypass.
        val (bypassing, host) = when (posture) {
            is PrivateDnsGuard.Posture.ForeignResolver -> true to posture.host
            is PrivateDnsGuard.Posture.Filtering -> !filtering.usesPrivateDns to posture.host
            PrivateDnsGuard.Posture.NotConfigured -> filtering.usesPrivateDns to null
            is PrivateDnsGuard.Posture.Unknown -> false to null
        }

        return ProtectionHealth.from(
            protectionEnabled = settings.protectionEnabled,
            statuses = statuses,
            monitoringRunning = MonitoringService.State.running.value,
            lastHeartbeatMillis = settings.lastMonitorHeartbeat,
            nowMillis = System.currentTimeMillis(),
            foreignVpnActive = MonitoringService.State.vpnActive.value,
            privateDnsBypassing = bypassing,
            privateDnsHost = host,
            recentlyRevokedGrants = MonitoringService.State.recentlyRevoked.value
                .mapNotNull { name -> SafetyPermission.entries.firstOrNull { it.name == name } }
                .toSet(),
            internetPauseRequested = settings.internetPaused,
            internetPauseEnforced = pauseEnforced(),
            advancedProtectionOn =
                advancedProtectionProbe.current() == AdvancedProtectionProbe.Mode.ON,
            privateDnsIsTheFilter = filtering.usesPrivateDns,
        )
    }

    /**
     * Usage has no change notification, so it is re-queried on a timer while the screen is open.
     *
     * The interval is generous on purpose: rebuilding today's totals walks the whole event stream,
     * and a dashboard that costs more battery than the apps it reports on would be self-defeating.
     */
    suspend fun pollUsage() {
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            refreshUsage()
            lastSettings?.let { settings ->
                val health = withContext(Dispatchers.IO) { buildHealth(settings, lastStatuses) }
                _uiState.update { it.copy(health = health) }
            }
            delay(REFRESH_INTERVAL_MILLIS)
        }
    }

    suspend fun refreshUsage() {
        val today = withContext(Dispatchers.IO) { usageStatsProvider.usageToday() }
        val week = withContext(Dispatchers.IO) { usageStatsProvider.dailyTotals(7) }

        _uiState.update { current ->
            when (today) {
                is DataResult.Success -> current.copy(
                    screenTimeMillis = today.data.sumOf { it.foregroundMillis },
                    screenTimeUnavailableReason = null,
                    topApps = today.data.take(5),
                    weekMillis = (week as? DataResult.Success)?.data.orEmpty(),
                )

                is DataResult.Unavailable -> current.copy(
                    // Deliberately null rather than zero. See the class comment.
                    screenTimeMillis = null,
                    screenTimeUnavailableReason = today.reason,
                    topApps = emptyList(),
                    weekMillis = emptyList(),
                )

                is DataResult.Error -> current.copy(
                    screenTimeMillis = null,
                    screenTimeUnavailableReason = today.message,
                )

                DataResult.Loading -> current
            }
        }
    }

    private companion object {
        const val REFRESH_INTERVAL_MILLIS = 15_000L
    }
}
