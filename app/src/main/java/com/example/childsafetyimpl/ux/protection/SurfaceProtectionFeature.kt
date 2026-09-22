package com.example.childsafetyimpl.ux.protection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.childsafetyimpl.core.protection.ProtectionFeature
import com.example.childsafetyimpl.core.protection.ProtectionStatus
import com.example.childsafetyimpl.core.protection.ProtectionStatusProvider
import com.example.childsafetyimpl.data.local.SurfaceEncounterEntity
import com.example.childsafetyimpl.data.prefs.SafetyPreferences
import com.example.childsafetyimpl.data.source.KnownPackages
import com.example.childsafetyimpl.domain.model.RestrictedSurface
import com.example.childsafetyimpl.domain.model.SurfaceUsage
import com.example.childsafetyimpl.navigation.NavigationAction
import com.example.childsafetyimpl.navigation.ViewModelNav
import com.example.childsafetyimpl.navigation.ViewModelNavImpl
import com.example.childsafetyimpl.platform.accessibility.ScreenSignal
import com.example.childsafetyimpl.platform.accessibility.ScreenSignalStore
import com.example.childsafetyimpl.platform.accessibility.SurfaceGuard
import com.example.childsafetyimpl.platform.accessibility.SurfaceSignatures
import com.example.childsafetyimpl.platform.usage.AppInventoryProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SurfaceProtectionUiState(
    val loading: Boolean = true,
    val surface: RestrictedSurface = RestrictedSurface.SHORT_FORM_FEED,
    val status: ProtectionStatus? = null,
    val usage: SurfaceUsage? = null,
    val recent: List<SurfaceEncounterEntity> = emptyList(),
    val serviceConnected: Boolean = false,
    val currentSignal: ScreenSignal? = null,
    val stats: ScreenSignalStore.SignalStats = ScreenSignalStore.SignalStats(),
    /** Apps this surface is watched for, and whether each is installed. */
    val watchedApps: List<WatchedApp> = emptyList(),
    val signalCount: Int = 0,
) {
    data class WatchedApp(val packageName: String, val label: String, val installed: Boolean)

    val allowanceMinutes: Int? get() = usage?.allowanceMinutes
    val refusedOutright: Boolean get() = usage?.allowanceMinutes == null
}

sealed interface SurfaceProtectionUiEvent {
    data object Back : SurfaceProtectionUiEvent
    data class ToggleFeature(val enabled: Boolean) : SurfaceProtectionUiEvent
    /** Null means refuse the surface outright rather than allowance it. */
    data class SetAllowance(val minutes: Int?) : SurfaceProtectionUiEvent
}

/**
 * State for one restricted surface.
 *
 * Written once and used by both the Shorts and the Status screens, because the two protections are
 * genuinely the same shape: a set of signatures, an allowance, a live match, and a history. Where
 * they differ is in the words on the screen, and words are what the surface enum carries.
 */
class GetSurfaceProtectionUiStateUseCase @Inject constructor(
    private val statusProvider: ProtectionStatusProvider,
    private val surfaceGuard: SurfaceGuard,
    private val signalStore: ScreenSignalStore,
    private val inventory: AppInventoryProvider,
) {

    private val _uiState = MutableStateFlow(SurfaceProtectionUiState())
    val uiState: StateFlow<SurfaceProtectionUiState> = _uiState.asStateFlow()

    suspend fun observe(surface: RestrictedSurface, feature: ProtectionFeature) {
        val watched = SurfaceSignatures.packagesFor(surface)
            .map { packageName ->
                val app = inventory.describe(packageName)
                SurfaceProtectionUiState.WatchedApp(
                    packageName = packageName,
                    label = app?.label ?: KnownPackages.displayNameFor(packageName),
                    installed = app != null,
                )
            }
            // Installed apps first: a parent cares about what is actually on the device, and the
            // rest is only there to show the protection is not limited to what they can see.
            .sortedWith(compareByDescending<SurfaceProtectionUiState.WatchedApp> { it.installed }
                .thenBy { it.label.lowercase() })

        combine(
            statusProvider.observe(feature),
            surfaceGuard.observeUsageToday(surface),
            surfaceGuard.observeRecent(surface),
            signalStore.connected,
            combine(signalStore.current, signalStore.stats) { signal, stats -> signal to stats },
        ) { status, usage, recent, connected, live ->
            SurfaceProtectionUiState(
                loading = false,
                surface = surface,
                status = status,
                usage = usage,
                recent = recent,
                serviceConnected = connected,
                currentSignal = live.first,
                stats = live.second,
                watchedApps = watched,
                signalCount = SurfaceSignatures.signalCountFor(surface),
            )
        }.collect { state -> _uiState.update { state } }
    }
}

abstract class SurfaceProtectionViewModel(
    private val surface: RestrictedSurface,
    private val feature: ProtectionFeature,
    private val useCase: GetSurfaceProtectionUiStateUseCase,
    private val preferences: SafetyPreferences,
) : ViewModel(), ViewModelNav by ViewModelNavImpl() {

    val uiState: StateFlow<SurfaceProtectionUiState> = useCase.uiState

    init {
        attachScope(this)
        viewModelScope.launch { useCase.observe(surface, feature) }
    }

    fun onEvent(event: SurfaceProtectionUiEvent) {
        when (event) {
            SurfaceProtectionUiEvent.Back -> navigate(NavigationAction.Back)

            is SurfaceProtectionUiEvent.ToggleFeature -> viewModelScope.launch {
                preferences.setFeatureEnabled(feature, event.enabled)
            }

            is SurfaceProtectionUiEvent.SetAllowance -> viewModelScope.launch {
                preferences.setSurfaceAllowance(surface, event.minutes)
            }
        }
    }
}

@HiltViewModel
class ShortFormProtectionViewModel @Inject constructor(
    useCase: GetSurfaceProtectionUiStateUseCase,
    preferences: SafetyPreferences,
) : SurfaceProtectionViewModel(
    surface = RestrictedSurface.SHORT_FORM_FEED,
    feature = ProtectionFeature.SHORT_FORM_CONTROL,
    useCase = useCase,
    preferences = preferences,
)

@HiltViewModel
class StatusProtectionViewModel @Inject constructor(
    useCase: GetSurfaceProtectionUiStateUseCase,
    preferences: SafetyPreferences,
) : SurfaceProtectionViewModel(
    surface = RestrictedSurface.STATUS_STORY,
    feature = ProtectionFeature.STATUS_CONTROL,
    useCase = useCase,
    preferences = preferences,
)
