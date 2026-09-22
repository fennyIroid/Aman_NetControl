package com.example.childsafetyimpl.ux.protection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.childsafetyimpl.core.protection.ProtectionFeature
import com.example.childsafetyimpl.core.protection.ProtectionState
import com.example.childsafetyimpl.core.protection.ProtectionStatus
import com.example.childsafetyimpl.core.protection.ProtectionStatusProvider
import com.example.childsafetyimpl.data.prefs.SafetyPreferences
import com.example.childsafetyimpl.domain.model.InstalledApp
import com.example.childsafetyimpl.domain.model.VpnPosture
import com.example.childsafetyimpl.navigation.NavigationAction
import com.example.childsafetyimpl.navigation.ViewModelNav
import com.example.childsafetyimpl.navigation.ViewModelNavImpl
import com.example.childsafetyimpl.platform.network.ChildSafetyVpnService
import com.example.childsafetyimpl.platform.network.VpnGuard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class VpnProtectionUiState(
    val loading: Boolean = true,
    val status: ProtectionStatus? = null,
    val posture: VpnPosture? = null,
    val tunnelRunning: Boolean = false,
    val counters: ChildSafetyVpnService.State.Counters = ChildSafetyVpnService.State.Counters(),
    val blockVpnApps: Boolean = true,
    val reclaimTunnel: Boolean = true,
    val blockVpnSettings: Boolean = true,
    val scanning: Boolean = false,
) {
    val vpnApps: List<InstalledApp> get() = posture?.installedVpnApps.orEmpty()
}

sealed interface VpnProtectionUiEvent {
    data object Back : VpnProtectionUiEvent
    data class ToggleFeature(val enabled: Boolean) : VpnProtectionUiEvent
    data class ToggleBlockApps(val enabled: Boolean) : VpnProtectionUiEvent
    data class ToggleReclaim(val enabled: Boolean) : VpnProtectionUiEvent
    data class ToggleBlockSettings(val enabled: Boolean) : VpnProtectionUiEvent
    data object Rescan : VpnProtectionUiEvent
    data object ReclaimNow : VpnProtectionUiEvent
}

class GetVpnProtectionUiStateUseCase @Inject constructor(
    private val statusProvider: ProtectionStatusProvider,
    private val vpnGuard: VpnGuard,
    private val preferences: SafetyPreferences,
) {

    private val _uiState = MutableStateFlow(VpnProtectionUiState())
    val uiState: StateFlow<VpnProtectionUiState> = _uiState.asStateFlow()

    suspend fun observe() {
        combine(
            statusProvider.observe(ProtectionFeature.VPN_RESTRICTION),
            vpnGuard.posture,
            ChildSafetyVpnService.State.running,
            ChildSafetyVpnService.State.counters,
            preferences.settings,
        ) { status, posture, running, counters, settings ->
            VpnProtectionUiState(
                loading = false,
                status = status,
                posture = posture,
                tunnelRunning = running,
                counters = counters,
                blockVpnApps = settings.blockVpnApps,
                reclaimTunnel = settings.reclaimTunnel,
                blockVpnSettings = settings.blockVpnSettings,
                scanning = _uiState.value.scanning,
            )
        }.collect { state -> _uiState.update { state } }
    }

    /**
     * Enumerating installed VPN clients queries every service on the device, which is far too slow
     * for the main thread and is why the screen shows a scanning state rather than freezing.
     */
    suspend fun rescan() {
        _uiState.update { it.copy(scanning = true) }
        withContext(Dispatchers.IO) { vpnGuard.refreshPosture() }
        _uiState.update { it.copy(scanning = false) }
    }
}

@HiltViewModel
class VpnProtectionViewModel @Inject constructor(
    private val useCase: GetVpnProtectionUiStateUseCase,
    private val preferences: SafetyPreferences,
    private val vpnGuard: VpnGuard,
) : ViewModel(), ViewModelNav by ViewModelNavImpl() {

    val uiState: StateFlow<VpnProtectionUiState> = useCase.uiState

    init {
        attachScope(this)
        viewModelScope.launch { useCase.observe() }
        viewModelScope.launch { useCase.rescan() }
    }

    fun onEvent(event: VpnProtectionUiEvent) {
        when (event) {
            VpnProtectionUiEvent.Back -> navigate(NavigationAction.Back)

            is VpnProtectionUiEvent.ToggleFeature -> viewModelScope.launch {
                preferences.setFeatureEnabled(ProtectionFeature.VPN_RESTRICTION, event.enabled)
            }

            is VpnProtectionUiEvent.ToggleBlockApps -> viewModelScope.launch {
                preferences.setBlockVpnApps(event.enabled)
            }

            is VpnProtectionUiEvent.ToggleReclaim -> viewModelScope.launch {
                preferences.setReclaimTunnel(event.enabled)
            }

            is VpnProtectionUiEvent.ToggleBlockSettings -> viewModelScope.launch {
                preferences.setBlockVpnSettings(event.enabled)
            }

            VpnProtectionUiEvent.Rescan -> viewModelScope.launch { useCase.rescan() }

            VpnProtectionUiEvent.ReclaimNow -> viewModelScope.launch {
                vpnGuard.reclaimTunnel()
                useCase.rescan()
            }
        }
    }

    /** True when the protection is on but the tunnel it would reclaim was never consented to. */
    fun reclaimUnavailable(state: VpnProtectionUiState): Boolean =
        state.status?.state != ProtectionState.OFF && !state.tunnelRunning
}
