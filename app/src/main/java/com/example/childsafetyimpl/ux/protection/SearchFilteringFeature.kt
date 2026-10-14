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
import com.example.childsafetyimpl.platform.network.PrivateDnsGuard
import com.example.childsafetyimpl.platform.network.PrivateDnsResolver
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
    /** Where Private DNS points, which is the device-wide filter. */
    val privateDnsPosture: PrivateDnsGuard.Posture? = null,
    val searchesClosed: Int = 0,
    val lastBlocked: SearchTermGuard.Blocked? = null,
    val termCount: Int = BlockedSearchTerms.count,
    val browserCount: Int = BrowserSurfaces.watchedBrowserCount,
) {
    val privateDnsFiltering: Boolean
        get() = privateDnsPosture is PrivateDnsGuard.Posture.Filtering

    /** The resolver in use, when it is one this app offers and so knows what it covers. */
    val resolver: PrivateDnsResolver?
        get() = (privateDnsPosture as? PrivateDnsGuard.Posture.Filtering)
            ?.let { PrivateDnsGuard.resolverFor(it.host) }
}

sealed interface SearchFilteringUiEvent {
    data object Back : SearchFilteringUiEvent
    data class ToggleFeature(val enabled: Boolean) : SearchFilteringUiEvent
    data class ToggleSafeSearch(val enabled: Boolean) : SearchFilteringUiEvent
    data class ToggleTermGuard(val enabled: Boolean) : SearchFilteringUiEvent
    data object OpenBrowser : SearchFilteringUiEvent
    data object OpenControls : SearchFilteringUiEvent
    data object RefreshPrivateDns : SearchFilteringUiEvent
    data object DismissLast : SearchFilteringUiEvent
}

class GetSearchFilteringUiStateUseCase @Inject constructor(
    private val statusProvider: ProtectionStatusProvider,
    private val searchGuard: SearchTermGuard,
    private val preferences: SafetyPreferences,
    private val privateDnsGuard: PrivateDnsGuard,
) {

    private val _uiState = MutableStateFlow(SearchFilteringUiState())
    val uiState: StateFlow<SearchFilteringUiState> = _uiState.asStateFlow()

    suspend fun observe() {
        combine(
            statusProvider.observe(ProtectionFeature.SEARCH_FILTERING),
            preferences.settings,
            combine(searchGuard.blockedToday, searchGuard.lastBlocked) { count, last ->
                count to last
            },
        ) { status, settings, closed ->
            SearchFilteringUiState(
                loading = false,
                status = status,
                safeSearchEnabled = settings.safeSearchEnabled,
                termGuardEnabled = settings.searchTermGuardEnabled,
                privateDnsPosture = _uiState.value.privateDnsPosture,
                searchesClosed = closed.first,
                lastBlocked = closed.second,
            )
        }.collect { state -> _uiState.update { state } }
    }

    /** Android says nothing when Private DNS changes, so the screen asks each time it resumes. */
    fun refreshPrivateDns() {
        val posture = privateDnsGuard.currentPosture()
        _uiState.update { it.copy(privateDnsPosture = posture) }
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

            SearchFilteringUiEvent.OpenControls ->
                navigate(NavigationAction.ToTab(Routes.controls()))

            SearchFilteringUiEvent.RefreshPrivateDns -> useCase.refreshPrivateDns()

            SearchFilteringUiEvent.DismissLast -> searchGuard.clearLastBlocked()
        }
    }
}
