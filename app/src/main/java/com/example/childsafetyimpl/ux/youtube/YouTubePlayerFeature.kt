package com.example.childsafetyimpl.ux.youtube

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.childsafetyimpl.data.prefs.SafetyPreferences
import com.example.childsafetyimpl.navigation.NavigationAction
import com.example.childsafetyimpl.navigation.ViewModelNav
import com.example.childsafetyimpl.navigation.ViewModelNavImpl
import com.example.childsafetyimpl.platform.youtube.YouTubeSurface
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class YouTubePlayerUiState(
    val query: String = "",
    val pendingUrl: String? = null,
    val loadingProgress: Int = 0,
    val canGoBack: Boolean = false,
    val stats: YouTubeSurface.Stats = YouTubeSurface.Stats(),
    val adBlockEnabled: Boolean = true,
    /** Set when a navigation was refused, so the screen can say why rather than doing nothing. */
    val refusal: String? = null,
)

sealed interface YouTubePlayerUiEvent {
    data object Back : YouTubePlayerUiEvent
    data class QueryChanged(val value: String) : YouTubePlayerUiEvent
    data object Search : YouTubePlayerUiEvent
    data object GoHome : YouTubePlayerUiEvent
    data object UrlConsumed : YouTubePlayerUiEvent
    data class Progress(val percent: Int) : YouTubePlayerUiEvent
    data class PageChanged(val canGoBack: Boolean) : YouTubePlayerUiEvent
    data class Refused(val message: String) : YouTubePlayerUiEvent
    data object DismissRefusal : YouTubePlayerUiEvent
}

class GetYouTubePlayerUiStateUseCase @Inject constructor(
    private val surface: YouTubeSurface,
    private val preferences: SafetyPreferences,
) {

    private val _uiState = MutableStateFlow(YouTubePlayerUiState())
    val uiState: StateFlow<YouTubePlayerUiState> = _uiState.asStateFlow()

    suspend fun observe() {
        combine(surface.stats, preferences.settings) { stats, settings ->
            _uiState.value.copy(stats = stats, adBlockEnabled = settings.adBlockEnabled)
        }.collect { state -> _uiState.update { state } }
    }

    fun home() = _uiState.update { it.copy(pendingUrl = surface.homeUrl, refusal = null) }

    fun search() {
        val query = _uiState.value.query.trim()
        if (query.isEmpty()) return
        _uiState.update { it.copy(pendingUrl = surface.searchUrl(query), refusal = null) }
    }

    fun setQuery(value: String) = _uiState.update { it.copy(query = value) }
    fun consumeUrl() = _uiState.update { it.copy(pendingUrl = null) }
    fun setProgress(percent: Int) = _uiState.update { it.copy(loadingProgress = percent) }
    fun setCanGoBack(value: Boolean) = _uiState.update { it.copy(canGoBack = value) }
    fun refuse(message: String) = _uiState.update { it.copy(refusal = message) }
    fun dismissRefusal() = _uiState.update { it.copy(refusal = null) }
}

@HiltViewModel
class YouTubePlayerViewModel @Inject constructor(
    private val useCase: GetYouTubePlayerUiStateUseCase,
    val surface: YouTubeSurface,
) : ViewModel(), ViewModelNav by ViewModelNavImpl() {

    val uiState: StateFlow<YouTubePlayerUiState> = useCase.uiState

    init {
        attachScope(this)
        viewModelScope.launch { useCase.observe() }
        useCase.home()
    }

    fun onEvent(event: YouTubePlayerUiEvent) {
        when (event) {
            YouTubePlayerUiEvent.Back -> navigate(NavigationAction.Back)
            is YouTubePlayerUiEvent.QueryChanged -> useCase.setQuery(event.value)
            YouTubePlayerUiEvent.Search -> useCase.search()
            YouTubePlayerUiEvent.GoHome -> useCase.home()
            YouTubePlayerUiEvent.UrlConsumed -> useCase.consumeUrl()
            is YouTubePlayerUiEvent.Progress -> useCase.setProgress(event.percent)
            is YouTubePlayerUiEvent.PageChanged -> useCase.setCanGoBack(event.canGoBack)
            is YouTubePlayerUiEvent.Refused -> useCase.refuse(event.message)
            YouTubePlayerUiEvent.DismissRefusal -> useCase.dismissRefusal()
        }
    }
}
