package com.example.childsafetyimpl.ux.protection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.childsafetyimpl.core.protection.ProtectionFeature
import com.example.childsafetyimpl.core.protection.ProtectionStatus
import com.example.childsafetyimpl.core.protection.ProtectionStatusProvider
import com.example.childsafetyimpl.data.prefs.SafetyPreferences
import com.example.childsafetyimpl.data.source.BlockedSearchTerms
import com.example.childsafetyimpl.navigation.NavigationAction
import com.example.childsafetyimpl.navigation.Routes
import com.example.childsafetyimpl.navigation.ViewModelNav
import com.example.childsafetyimpl.navigation.ViewModelNavImpl
import com.example.childsafetyimpl.platform.accessibility.BrowserSurfaces
import com.example.childsafetyimpl.platform.accessibility.SearchTermGuard
import com.example.childsafetyimpl.platform.network.ChildSafetyVpnService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchFilteringUiState(
    val loading: Boolean = true,
    val status: ProtectionStatus? = null,
    val safeSearchEnabled: Boolean = true,
    val termGuardEnabled: Boolean = true,
    val tunnelRunning: Boolean = false,
    val safeSearchForced: Int = 0,
    val searchesClosed: Int = 0,
    val lastBlocked: SearchTermGuard.Blocked? = null,
    val termCount: Int = BlockedSearchTerms.count,
    val browserCount: Int = BrowserSurfaces.watchedBrowserCount,
)

sealed interface SearchFilteringUiEvent {
    data object Back : SearchFilteringUiEvent
    data class ToggleFeature(val enabled: Boolean) : SearchFilteringUiEvent
    data class ToggleSafeSearch(val enabled: Boolean) : SearchFilteringUiEvent
    data class ToggleTermGuard(val enabled: Boolean) : SearchFilteringUiEvent
    data object OpenBrowser : SearchFilteringUiEvent
    data object OpenVpn : SearchFilteringUiEvent
    data object DismissLast : SearchFilteringUiEvent
}

class GetSearchFilteringUiStateUseCase @Inject constructor(
    private val statusProvider: ProtectionStatusProvider,
    private val searchGuard: SearchTermGuard,
    private val preferences: SafetyPreferences,
) {

    private val _uiState = MutableStateFlow(SearchFilteringUiState())
    val uiState: StateFlow<SearchFilteringUiState> = _uiState.asStateFlow()

    suspend fun observe() {
        combine(
            statusProvider.observe(ProtectionFeature.SEARCH_FILTERING),
            preferences.settings,
            ChildSafetyVpnService.State.running,
            ChildSafetyVpnService.State.counters,
            combine(searchGuard.blockedToday, searchGuard.lastBlocked) { count, last ->
                count to last
            },
        ) { status, settings, running, counters, closed ->
            SearchFilteringUiState(
                loading = false,
                status = status,
                safeSearchEnabled = settings.safeSearchEnabled,
                termGuardEnabled = settings.searchTermGuardEnabled,
                tunnelRunning = running,
                safeSearchForced = counters.safeSearchForced,
                searchesClosed = closed.first,
                lastBlocked = closed.second,
            )
        }.collect { state -> _uiState.update { state } }
    }
}

@HiltViewModel
class SearchFilteringViewModel @Inject constructor(
    private val useCase: GetSearchFilteringUiStateUseCase,
    private val preferences: SafetyPreferences,
    private val searchGuard: SearchTermGuard,
) : ViewModel(), ViewModelNav by ViewModelNavImpl() {

    val uiState: StateFlow<SearchFilteringUiState> = useCase.uiState

    init {
        attachScope(this)
        viewModelScope.launch { useCase.observe() }
    }

    fun onEvent(event: SearchFilteringUiEvent) {
        when (event) {
            SearchFilteringUiEvent.Back -> navigate(NavigationAction.Back)

            is SearchFilteringUiEvent.ToggleFeature -> viewModelScope.launch {
                preferences.setFeatureEnabled(ProtectionFeature.SEARCH_FILTERING, event.enabled)
            }

            is SearchFilteringUiEvent.ToggleSafeSearch -> viewModelScope.launch {
                preferences.setSafeSearch(event.enabled)
            }

            is SearchFilteringUiEvent.ToggleTermGuard -> viewModelScope.launch {
                preferences.setSearchTermGuard(event.enabled)
            }

            SearchFilteringUiEvent.OpenBrowser ->
                navigate(NavigationAction.To(Routes.safeBrowser()))

            SearchFilteringUiEvent.OpenVpn ->
                navigate(NavigationAction.To(Routes.vpnProtection()))

            SearchFilteringUiEvent.DismissLast -> searchGuard.clearLastBlocked()
        }
    }
}
