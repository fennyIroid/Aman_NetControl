package com.example.childsafetyimpl.ux.protection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.childsafetyimpl.core.common.DataResult
import com.example.childsafetyimpl.core.protection.ProtectionFeature
import com.example.childsafetyimpl.core.protection.ProtectionStatus
import com.example.childsafetyimpl.core.protection.ProtectionStatusProvider
import com.example.childsafetyimpl.data.local.AiInteractionEntity
import com.example.childsafetyimpl.data.prefs.SafetyPreferences
import com.example.childsafetyimpl.domain.model.InstalledApp
import com.example.childsafetyimpl.navigation.NavigationAction
import com.example.childsafetyimpl.navigation.ViewModelNav
import com.example.childsafetyimpl.navigation.ViewModelNavImpl
import com.example.childsafetyimpl.platform.accessibility.ScreenSignalStore
import com.example.childsafetyimpl.platform.ai.AiActivityMonitor
import com.example.childsafetyimpl.platform.ai.AiPromptRiskScorer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class AiProtectionUiState(
    val loading: Boolean = true,
    val status: ProtectionStatus? = null,
    val breakdown: DataResult<AiActivityMonitor.Breakdown> = DataResult.Loading,
    val interactions: List<AiInteractionEntity> = emptyList(),
    val concerningToday: Int = 0,
    val aiBudgetMinutes: Int? = 30,
    val gameBudgetMinutes: Int? = 60,
    val conversationScanEnabled: Boolean = true,
    val serviceConnected: Boolean = false,
    val installedAiApps: List<InstalledApp> = emptyList(),
    val installedGames: List<InstalledApp> = emptyList(),
    val watchedCategories: List<String> = emptyList(),
    val watchedPhraseCount: Int = 0,
)

sealed interface AiProtectionUiEvent {
    data object Back : AiProtectionUiEvent
    data class ToggleFeature(val enabled: Boolean) : AiProtectionUiEvent
    data class ToggleConversationScan(val enabled: Boolean) : AiProtectionUiEvent
    data class SetAiBudget(val minutes: Int?) : AiProtectionUiEvent
    data class SetGameBudget(val minutes: Int?) : AiProtectionUiEvent
    data object ClearHistory : AiProtectionUiEvent
}

class GetAiProtectionUiStateUseCase @Inject constructor(
    private val statusProvider: ProtectionStatusProvider,
    private val aiMonitor: AiActivityMonitor,
    private val signalStore: ScreenSignalStore,
    private val preferences: SafetyPreferences,
    private val riskScorer: AiPromptRiskScorer,
) {

    private val _uiState = MutableStateFlow(AiProtectionUiState())
    val uiState: StateFlow<AiProtectionUiState> = _uiState.asStateFlow()

    suspend fun observe() {
        val installedAi = withContext(Dispatchers.IO) { aiMonitor.installedAiApps() }
        val installedGames = withContext(Dispatchers.IO) { aiMonitor.installedGames() }

        combine(
            statusProvider.observe(ProtectionFeature.AI_GAME_MONITORING),
            aiMonitor.observeRecent(),
            aiMonitor.observeConcerningCountToday(),
            signalStore.connected,
            preferences.settings,
        ) { status, interactions, concerning, connected, settings ->
            _uiState.value.copy(
                loading = false,
                status = status,
                interactions = interactions,
                concerningToday = concerning,
                aiBudgetMinutes = settings.aiDailyBudgetMinutes,
                gameBudgetMinutes = settings.gameDailyBudgetMinutes,
                conversationScanEnabled = settings.aiConversationScanEnabled,
                serviceConnected = connected,
                installedAiApps = installedAi,
                installedGames = installedGames,
                watchedCategories = riskScorer.categoryNames,
                watchedPhraseCount = riskScorer.watchedPhraseCount,
            )
        }.collect { state -> _uiState.update { state } }
    }

    /**
     * Usage totals are polled rather than observed.
     *
     * UsageStatsManager offers no change notification, so a periodic re-query is the only way the
     * numbers on this screen stay live while a parent is looking at them.
     */
    suspend fun pollUsage() {
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val breakdown = withContext(Dispatchers.IO) { aiMonitor.todayBreakdown() }
            _uiState.update { it.copy(breakdown = breakdown) }
            delay(USAGE_REFRESH_MILLIS)
        }
    }

    private companion object {
        const val USAGE_REFRESH_MILLIS = 8000L
    }
}

@HiltViewModel
class AiProtectionViewModel @Inject constructor(
    private val useCase: GetAiProtectionUiStateUseCase,
    private val preferences: SafetyPreferences,
    private val aiMonitor: AiActivityMonitor,
) : ViewModel(), ViewModelNav by ViewModelNavImpl() {

    val uiState: StateFlow<AiProtectionUiState> = useCase.uiState

    init {
        attachScope(this)
        viewModelScope.launch { useCase.observe() }
        viewModelScope.launch { useCase.pollUsage() }
    }

    fun onEvent(event: AiProtectionUiEvent) {
        when (event) {
            AiProtectionUiEvent.Back -> navigate(NavigationAction.Back)

            is AiProtectionUiEvent.ToggleFeature -> viewModelScope.launch {
                preferences.setFeatureEnabled(
                    ProtectionFeature.AI_GAME_MONITORING,
                    event.enabled,
                )
            }

            is AiProtectionUiEvent.ToggleConversationScan -> viewModelScope.launch {
                preferences.setAiConversationScan(event.enabled)
            }

            is AiProtectionUiEvent.SetAiBudget -> viewModelScope.launch {
                preferences.setAiBudget(event.minutes)
            }

            is AiProtectionUiEvent.SetGameBudget -> viewModelScope.launch {
                preferences.setGameBudget(event.minutes)
            }

            AiProtectionUiEvent.ClearHistory -> viewModelScope.launch {
                aiMonitor.clearHistory()
            }
        }
    }
}
