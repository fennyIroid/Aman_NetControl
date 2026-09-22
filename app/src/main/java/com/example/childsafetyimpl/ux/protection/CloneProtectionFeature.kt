package com.example.childsafetyimpl.ux.protection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.childsafetyimpl.core.protection.ProtectionFeature
import com.example.childsafetyimpl.core.protection.ProtectionStatus
import com.example.childsafetyimpl.core.protection.ProtectionStatusProvider
import com.example.childsafetyimpl.data.local.CloneFindingEntity
import com.example.childsafetyimpl.data.prefs.SafetyPreferences
import com.example.childsafetyimpl.navigation.NavigationAction
import com.example.childsafetyimpl.navigation.ViewModelNav
import com.example.childsafetyimpl.navigation.ViewModelNavImpl
import com.example.childsafetyimpl.platform.clone.CloneDetector
import com.example.childsafetyimpl.platform.clone.CloneGuard
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

data class CloneProtectionUiState(
    val loading: Boolean = true,
    val status: ProtectionStatus? = null,
    val findings: List<CloneFindingEntity> = emptyList(),
    val lastScan: CloneDetector.CloneScanResult? = null,
    val lastScanAt: Long = 0,
    val scanning: Boolean = false,
    val blockCloneTools: Boolean = true,
)

sealed interface CloneProtectionUiEvent {
    data object Back : CloneProtectionUiEvent
    data class ToggleFeature(val enabled: Boolean) : CloneProtectionUiEvent
    data class ToggleBlocking(val enabled: Boolean) : CloneProtectionUiEvent
    data class SetFindingBlocked(val identity: String, val blocked: Boolean) :
        CloneProtectionUiEvent
    data object Rescan : CloneProtectionUiEvent
}

class GetCloneProtectionUiStateUseCase @Inject constructor(
    private val statusProvider: ProtectionStatusProvider,
    private val cloneGuard: CloneGuard,
    private val preferences: SafetyPreferences,
) {

    private val _uiState = MutableStateFlow(CloneProtectionUiState())
    val uiState: StateFlow<CloneProtectionUiState> = _uiState.asStateFlow()

    suspend fun observe() {
        combine(
            statusProvider.observe(ProtectionFeature.CLONE_CONTROL),
            cloneGuard.findings,
            cloneGuard.lastScanAt,
            cloneGuard.scanning,
            preferences.settings,
        ) { status, findings, scanAt, scanning, settings ->
            _uiState.value.copy(
                loading = false,
                status = status,
                findings = findings,
                lastScanAt = scanAt,
                scanning = scanning,
                blockCloneTools = settings.blockCloneTools,
            )
        }.collect { state -> _uiState.update { state } }
    }

    /**
     * The scan walks every launcher entry in every visible profile, which is slow enough that it
     * must never touch the main thread and slow enough to be worth showing progress for.
     */
    suspend fun rescan() {
        val result = withContext(Dispatchers.IO) { cloneGuard.scan() }
        _uiState.update { it.copy(lastScan = result) }
    }
}

@HiltViewModel
class CloneProtectionViewModel @Inject constructor(
    private val useCase: GetCloneProtectionUiStateUseCase,
    private val preferences: SafetyPreferences,
    private val cloneGuard: CloneGuard,
) : ViewModel(), ViewModelNav by ViewModelNavImpl() {

    val uiState: StateFlow<CloneProtectionUiState> = useCase.uiState

    init {
        attachScope(this)
        viewModelScope.launch { useCase.observe() }
        viewModelScope.launch { useCase.rescan() }
    }

    fun onEvent(event: CloneProtectionUiEvent) {
        when (event) {
            CloneProtectionUiEvent.Back -> navigate(NavigationAction.Back)

            is CloneProtectionUiEvent.ToggleFeature -> viewModelScope.launch {
                preferences.setFeatureEnabled(ProtectionFeature.CLONE_CONTROL, event.enabled)
            }

            is CloneProtectionUiEvent.ToggleBlocking -> viewModelScope.launch {
                preferences.setBlockCloneTools(event.enabled)
            }

            is CloneProtectionUiEvent.SetFindingBlocked -> viewModelScope.launch {
                cloneGuard.setBlocked(event.identity, event.blocked)
            }

            CloneProtectionUiEvent.Rescan -> viewModelScope.launch { useCase.rescan() }
        }
    }
}
