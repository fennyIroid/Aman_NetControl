package com.example.childsafetyimpl.ux.protection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.childsafetyimpl.core.protection.ProtectionFeature
import com.example.childsafetyimpl.core.protection.ProtectionStatus
import com.example.childsafetyimpl.core.protection.ProtectionStatusProvider
import com.example.childsafetyimpl.data.prefs.SafetyPreferences
import com.example.childsafetyimpl.domain.model.InstalledApp
import com.example.childsafetyimpl.domain.model.VpnPosture
import com.example.childsafetyimpl.navigation.NavigationAction
import com.example.childsafetyimpl.navigation.ViewModelNav
import com.example.childsafetyimpl.navigation.ViewModelNavImpl
import com.example.childsafetyimpl.platform.accessibility.ScreenSignalStore
import com.example.childsafetyimpl.platform.admin.NetworkLockdown
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
    val blockVpnApps: Boolean = true,
    val vpnLockout: Boolean = true,
    val blockVpnSettings: Boolean = true,
    /** Connection requests cancelled before a VPN could start. */
    val refusedRequests: Int = 0,
    val blockedLaunches: Int = 0,
    /** Apps refused because a VPN was up. */
    val lockouts: Int = 0,
    /** Whether the accessibility service is connected, which refusing a request needs. */
    val canRefuseRequests: Boolean = false,
    /** True when device policy forbids VPN setup outright, on a managed device. */
    val vpnForbiddenByPolicy: Boolean = false,
    val scanning: Boolean = false,
) {
    val vpnApps: List<InstalledApp> get() = posture?.installedVpnApps.orEmpty()
}

sealed interface VpnProtectionUiEvent {
    data object Back : VpnProtectionUiEvent
    data class ToggleFeature(val enabled: Boolean) : VpnProtectionUiEvent
    data class ToggleBlockApps(val enabled: Boolean) : VpnProtectionUiEvent
    data class ToggleLockout(val enabled: Boolean) : VpnProtectionUiEvent
    data class ToggleBlockSettings(val enabled: Boolean) : VpnProtectionUiEvent
    data object Rescan : VpnProtectionUiEvent
}

class GetVpnProtectionUiStateUseCase @Inject constructor(
    private val statusProvider: ProtectionStatusProvider,
    private val vpnGuard: VpnGuard,
    private val preferences: SafetyPreferences,
    private val signalStore: ScreenSignalStore,
    private val networkLockdown: NetworkLockdown,
) {

    private val _uiState = MutableStateFlow(VpnProtectionUiState())
    val uiState: StateFlow<VpnProtectionUiState> = _uiState.asStateFlow()

    suspend fun observe() {
        val counts = combine(
            vpnGuard.refusedRequests,
            vpnGuard.blockedLaunches,
            vpnGuard.lockouts,
        ) { refused, launches, lockouts -> Triple(refused, launches, lockouts) }

        combine(
            statusProvider.observe(ProtectionFeature.VPN_RESTRICTION),
            vpnGuard.posture,
            preferences.settings,
            counts,
            combine(signalStore.connected, networkLockdown.applied) { connected, lockdown ->
                connected to lockdown.vpnConfigBlocked
            },
        ) { status, posture, settings, count, access ->
            VpnProtectionUiState(
                loading = false,
                status = status,
                posture = posture,
                blockVpnApps = settings.blockVpnApps,
                vpnLockout = settings.vpnLockout,
                blockVpnSettings = settings.blockVpnSettings,
                refusedRequests = count.first,
                blockedLaunches = count.second,
                lockouts = count.third,
                canRefuseRequests = access.first,
                vpnForbiddenByPolicy = access.second,
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

            is VpnProtectionUiEvent.ToggleLockout -> viewModelScope.launch {
                preferences.setVpnLockout(event.enabled)
            }

            is VpnProtectionUiEvent.ToggleBlockSettings -> viewModelScope.launch {
                preferences.setBlockVpnSettings(event.enabled)
            }

            VpnProtectionUiEvent.Rescan -> viewModelScope.launch { useCase.rescan() }
        }
    }
}
