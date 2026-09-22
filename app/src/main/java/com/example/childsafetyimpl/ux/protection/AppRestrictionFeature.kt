package com.example.childsafetyimpl.ux.protection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.childsafetyimpl.core.common.DataResult
import com.example.childsafetyimpl.core.protection.ProtectionFeature
import com.example.childsafetyimpl.core.protection.ProtectionStatus
import com.example.childsafetyimpl.core.protection.ProtectionStatusProvider
import com.example.childsafetyimpl.data.prefs.SafetyPreferences
import com.example.childsafetyimpl.domain.model.AppCategory
import com.example.childsafetyimpl.domain.model.AppRule
import com.example.childsafetyimpl.domain.model.InstalledApp
import com.example.childsafetyimpl.domain.repository.SafetyRulesRepository
import com.example.childsafetyimpl.navigation.NavigationAction
import com.example.childsafetyimpl.navigation.ViewModelNav
import com.example.childsafetyimpl.navigation.ViewModelNavImpl
import com.example.childsafetyimpl.platform.usage.AppInventoryProvider
import com.example.childsafetyimpl.platform.usage.UsageStatsProvider
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

/** One installed app, its rule, and how long it has been used today. */
data class ManagedApp(
    val app: InstalledApp,
    val rule: AppRule?,
    val usedMillis: Long,
) {
    val blocked: Boolean get() = rule?.blocked == true
    val limitMinutes: Int? get() = rule?.dailyLimitMinutes

    val limitFraction: Float
        get() {
            val limit = limitMinutes ?: return 0f
            if (limit <= 0) return 0f
            return (usedMillis.toFloat() / (limit * 60_000f)).coerceIn(0f, 1f)
        }
}

data class AppRestrictionUiState(
    val loading: Boolean = true,
    val status: ProtectionStatus? = null,
    val apps: List<ManagedApp> = emptyList(),
    val usageUnavailable: String? = null,
    val query: String = "",
    val categoryFilter: AppCategory? = null,
    val showSystemApps: Boolean = false,
) {
    val visible: List<ManagedApp>
        get() = apps
            .asSequence()
            .filter { showSystemApps || !it.app.isSystemApp }
            .filter { categoryFilter == null || it.app.category == categoryFilter }
            .filter { query.isBlank() || it.app.label.contains(query, ignoreCase = true) }
            // Restricted apps first: a parent scrolling this list is usually checking what they
            // already set, not browsing every app on the phone.
            .sortedWith(
                compareByDescending<ManagedApp> { it.blocked }
                    .thenByDescending { it.limitMinutes != null }
                    .thenByDescending { it.usedMillis }
                    .thenBy { it.app.label.lowercase() },
            )
            .toList()

    val blockedCount: Int get() = apps.count { it.blocked }
    val limitedCount: Int get() = apps.count { it.limitMinutes != null }

    val categories: List<AppCategory>
        get() = apps.map { it.app.category }.distinct().sortedBy { it.label }
}

sealed interface AppRestrictionUiEvent {
    data object Back : AppRestrictionUiEvent
    data class ToggleFeature(val enabled: Boolean) : AppRestrictionUiEvent
    data class SetBlocked(val app: ManagedApp, val blocked: Boolean) : AppRestrictionUiEvent
    data class SetLimit(val app: ManagedApp, val minutes: Int?) : AppRestrictionUiEvent
    data class Search(val query: String) : AppRestrictionUiEvent
    data class FilterCategory(val category: AppCategory?) : AppRestrictionUiEvent
    data class ShowSystemApps(val show: Boolean) : AppRestrictionUiEvent
}

/**
 * Builds the managed-app list.
 *
 * Reading the installed-app list and today's usage are both slow enough to matter, so they happen
 * once off the main thread and are then joined against the rule flow, which is the only part that
 * changes while the screen is open.
 */
class GetAppRestrictionUiStateUseCase @Inject constructor(
    private val statusProvider: ProtectionStatusProvider,
    private val inventory: AppInventoryProvider,
    private val usageStatsProvider: UsageStatsProvider,
    private val rulesRepository: SafetyRulesRepository,
) {

    private val _uiState = MutableStateFlow(AppRestrictionUiState())
    val uiState: StateFlow<AppRestrictionUiState> = _uiState.asStateFlow()

    suspend fun observe() {
        val installed = withContext(Dispatchers.IO) { inventory.launchableApps() }
        val usageResult = withContext(Dispatchers.IO) { usageStatsProvider.usageToday() }

        val usageByPackage = (usageResult as? DataResult.Success)?.data
            ?.associate { it.packageName to it.foregroundMillis }
            .orEmpty()

        combine(
            statusProvider.observe(ProtectionFeature.APP_RESTRICTION),
            rulesRepository.observeAppRules(),
        ) { status, rules ->
            val byPackage = rules.associateBy { it.packageName }
            _uiState.value.copy(
                loading = false,
                status = status,
                apps = installed.map { app ->
                    ManagedApp(
                        app = app,
                        rule = byPackage[app.packageName],
                        usedMillis = usageByPackage[app.packageName] ?: 0L,
                    )
                },
                usageUnavailable = (usageResult as? DataResult.Unavailable)?.reason,
            )
        }.collect { state -> _uiState.update { state } }
    }

    fun setQuery(query: String) = _uiState.update { it.copy(query = query) }

    fun setCategory(category: AppCategory?) = _uiState.update { it.copy(categoryFilter = category) }

    fun setShowSystemApps(show: Boolean) = _uiState.update { it.copy(showSystemApps = show) }
}

@HiltViewModel
class AppRestrictionViewModel @Inject constructor(
    private val useCase: GetAppRestrictionUiStateUseCase,
    private val rulesRepository: SafetyRulesRepository,
    private val preferences: SafetyPreferences,
) : ViewModel(), ViewModelNav by ViewModelNavImpl() {

    val uiState: StateFlow<AppRestrictionUiState> = useCase.uiState

    init {
        attachScope(this)
        viewModelScope.launch { useCase.observe() }
    }

    fun onEvent(event: AppRestrictionUiEvent) {
        when (event) {
            AppRestrictionUiEvent.Back -> navigate(NavigationAction.Back)

            is AppRestrictionUiEvent.ToggleFeature -> viewModelScope.launch {
                preferences.setFeatureEnabled(ProtectionFeature.APP_RESTRICTION, event.enabled)
            }

            is AppRestrictionUiEvent.SetBlocked -> viewModelScope.launch {
                upsert(event.app) { it.copy(blocked = event.blocked) }
            }

            is AppRestrictionUiEvent.SetLimit -> viewModelScope.launch {
                upsert(event.app) { it.copy(dailyLimitMinutes = event.minutes) }
            }

            is AppRestrictionUiEvent.Search -> useCase.setQuery(event.query)
            is AppRestrictionUiEvent.FilterCategory -> useCase.setCategory(event.category)
            is AppRestrictionUiEvent.ShowSystemApps -> useCase.setShowSystemApps(event.show)
        }
    }

    /**
     * A rule row is created on first use rather than for every installed app, so the rules table
     * stays a list of decisions a parent made rather than a mirror of the app drawer.
     */
    private suspend fun upsert(managed: ManagedApp, change: (AppRule) -> AppRule) {
        val existing = managed.rule ?: AppRule(
            packageName = managed.app.packageName,
            appLabel = managed.app.label,
            dailyLimitMinutes = null,
            blocked = false,
            blockFromMinuteOfDay = null,
            blockToMinuteOfDay = null,
        )
        val updated = change(existing)

        if (!updated.blocked &&
            updated.dailyLimitMinutes == null &&
            updated.blockFromMinuteOfDay == null
        ) {
            rulesRepository.deleteAppRule(updated.packageName)
        } else {
            rulesRepository.upsertAppRule(updated)
        }
    }
}
