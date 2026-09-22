package com.example.childsafetyimpl.ux.protection

import android.content.Intent
import android.net.VpnService
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.childsafetyimpl.core.protection.ProtectionFeature
import com.example.childsafetyimpl.core.protection.ProtectionStatus
import com.example.childsafetyimpl.core.protection.ProtectionStatusProvider
import com.example.childsafetyimpl.data.prefs.SafetyPreferences
import com.example.childsafetyimpl.di.ApplicationScope
import com.example.childsafetyimpl.navigation.NavigationAction
import com.example.childsafetyimpl.navigation.Routes
import com.example.childsafetyimpl.navigation.ViewModelNav
import com.example.childsafetyimpl.navigation.ViewModelNavImpl
import com.example.childsafetyimpl.platform.enforcement.MonitoringService
import com.example.childsafetyimpl.platform.network.ChildSafetyVpnService
import com.example.childsafetyimpl.platform.network.TunnelMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProtectionsUiState(
    val loading: Boolean = true,
    val protectionEnabled: Boolean = false,
    val primary: List<ProtectionStatus> = emptyList(),
    val secondary: List<ProtectionStatus> = emptyList(),
    val summary: ProtectionStatusProvider.Summary = ProtectionStatusProvider.Summary(0, 0, 0, 0, 0),
)

sealed interface ProtectionsUiEvent {
    data class ToggleMaster(val enabled: Boolean) : ProtectionsUiEvent
    data class ToggleFeature(val feature: ProtectionFeature, val enabled: Boolean) :
        ProtectionsUiEvent
    data class Open(val feature: ProtectionFeature) : ProtectionsUiEvent
    data object OpenPermissions : ProtectionsUiEvent
}

/**
 * Owns the protection list's state, in the UseCase-as-state-manager pattern the project follows.
 *
 * Splitting the list into the two groups the brief asks for is done here rather than in the screen,
 * so the ordering is a property of the product rather than of a layout.
 */
class GetProtectionsUiStateUseCase @Inject constructor(
    private val statusProvider: ProtectionStatusProvider,
    private val preferences: SafetyPreferences,
) {

    private val _uiState = MutableStateFlow(ProtectionsUiState())
    val uiState: StateFlow<ProtectionsUiState> = _uiState.asStateFlow()

    suspend fun observe() {
        combine(
            statusProvider.observeAll(),
            statusProvider.observeSummary(),
            preferences.settings,
        ) { statuses, summary, settings ->
            ProtectionsUiState(
                loading = false,
                protectionEnabled = settings.protectionEnabled,
                primary = statuses.filter { it.feature in ProtectionFeature.primary },
                secondary = statuses.filter { it.feature in ProtectionFeature.secondary },
                summary = summary,
            )
        }.collect { state -> _uiState.update { state } }
    }
}

@HiltViewModel
class ProtectionsViewModel @Inject constructor(
    private val useCase: GetProtectionsUiStateUseCase,
    private val preferences: SafetyPreferences,
    private val protectionRunner: ProtectionRunner,
) : ViewModel(), ViewModelNav by ViewModelNavImpl() {

    val uiState: StateFlow<ProtectionsUiState> = useCase.uiState

    init {
        attachScope(this)
        viewModelScope.launch { useCase.observe() }
    }

    fun onEvent(event: ProtectionsUiEvent) {
        when (event) {
            is ProtectionsUiEvent.ToggleMaster -> viewModelScope.launch {
                preferences.setProtectionEnabled(event.enabled)
                // Starting and stopping the services here rather than leaving it to a screen is
                // what makes the master switch mean something the moment it is flipped.
                protectionRunner.apply(event.enabled)
            }

            is ProtectionsUiEvent.ToggleFeature -> viewModelScope.launch {
                preferences.setFeatureEnabled(event.feature, event.enabled)
            }

            is ProtectionsUiEvent.Open ->
                navigate(NavigationAction.To(Routes.screenFor(event.feature)))

            ProtectionsUiEvent.OpenPermissions ->
                navigate(NavigationAction.To(Routes.permissions()))
        }
    }
}

/**
 * Starts and stops the services the protections run on.
 *
 * Kept as its own class because several screens, the app shell and the boot receiver can all turn
 * protection on, and each of them getting the service lifecycle subtly different is exactly how a
 * parental control ends up appearing to run while nothing is watching.
 */
@javax.inject.Singleton
class ProtectionRunner @Inject constructor(
    @param:dagger.hilt.android.qualifiers.ApplicationContext
    private val context: android.content.Context,
    preferences: SafetyPreferences,
    @ApplicationScope scope: CoroutineScope,
) {

    /**
     * Whether the filtering posture wants a tunnel running right now, or null before the first
     * read. Cached because [apply] is called from places that cannot suspend.
     *
     * In the Private DNS postures the tunnel exists only for a pause. Before the first read the
     * answer is unknown and the start goes ahead: the tunnel service reads the same settings
     * itself and stands down if it is not wanted, so the worst case is a notification that
     * appears for a moment, never a filter in front of the resolver.
     */
    @Volatile private var tunnelWanted: Boolean? = null

    init {
        scope.launch {
            preferences.settings.collect { settings ->
                tunnelWanted = TunnelMode.restingFor(
                    settings.filteringMode,
                    settings.internetPaused,
                ) != null
            }
        }
    }

    fun apply(enabled: Boolean) {
        if (enabled) {
            MonitoringService.start(context)
            startTunnelIfAllowed()
        } else {
            MonitoringService.stop(context)
            stopTunnel()
        }
    }

    /**
     * Starts the filtering tunnel, but only where consent already exists.
     *
     * VpnService.prepare returning an intent means the user has never allowed the tunnel, and that
     * dialog can only be raised by an Activity. Trying anyway from here would throw; asking is the
     * Controls screen's job, and this quietly does nothing until it has been asked.
     */
    private fun startTunnelIfAllowed() {
        if (tunnelWanted == false) return
        val consentNeeded = runCatching { VpnService.prepare(context) != null }.getOrDefault(true)
        if (consentNeeded) return
        runCatching {
            val intent = Intent(context, ChildSafetyVpnService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private fun stopTunnel() {
        runCatching {
            context.startService(
                Intent(context, ChildSafetyVpnService::class.java)
                    .setAction(ChildSafetyVpnService.ACTION_STOP),
            )
        }
    }
}
