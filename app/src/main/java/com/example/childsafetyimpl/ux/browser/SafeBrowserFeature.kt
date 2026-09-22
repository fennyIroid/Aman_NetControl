package com.example.childsafetyimpl.ux.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.childsafetyimpl.data.prefs.SafetyPreferences
import com.example.childsafetyimpl.navigation.NavigationAction
import com.example.childsafetyimpl.navigation.ViewModelNav
import com.example.childsafetyimpl.navigation.ViewModelNavImpl
import com.example.childsafetyimpl.platform.browser.SafeBrowserEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SafeBrowserUiState(
    val address: String = "",
    /** Set when the screen should navigate; cleared once the web view has taken it. */
    val pendingUrl: String? = null,
    val currentUrl: String = "",
    val title: String = "",
    val loadingProgress: Int = 0,
    val canGoBack: Boolean = false,
    val blocked: SafeBrowserEngine.BlockedPage? = null,
    val stats: SafeBrowserEngine.BrowsingStats = SafeBrowserEngine.BrowsingStats(),
    val safeSearchEnabled: Boolean = true,
    val imageBlurEnabled: Boolean = true,
    val adBlockEnabled: Boolean = true,
)

sealed interface SafeBrowserUiEvent {
    data object Back : SafeBrowserUiEvent
    data class AddressChanged(val value: String) : SafeBrowserUiEvent
    data object Go : SafeBrowserUiEvent
    data class OpenUrl(val url: String) : SafeBrowserUiEvent
    data object UrlConsumed : SafeBrowserUiEvent
    data class PageChanged(val url: String, val title: String, val canGoBack: Boolean) :
        SafeBrowserUiEvent
    data class Progress(val percent: Int) : SafeBrowserUiEvent
    data object DismissBlock : SafeBrowserUiEvent
    data object GoHome : SafeBrowserUiEvent
}

class GetSafeBrowserUiStateUseCase @Inject constructor(
    private val engine: SafeBrowserEngine,
    private val preferences: SafetyPreferences,
) {

    private val _uiState = MutableStateFlow(SafeBrowserUiState())
    val uiState: StateFlow<SafeBrowserUiState> = _uiState.asStateFlow()

    suspend fun observe() {
        combine(
            engine.lastBlock,
            engine.stats,
            preferences.settings,
        ) { blocked, stats, settings ->
            _uiState.value.copy(
                blocked = blocked,
                stats = stats,
                safeSearchEnabled = settings.safeSearchEnabled,
                imageBlurEnabled = settings.imageBlurEnabled,
                adBlockEnabled = settings.adBlockEnabled,
            )
        }.collect { state -> _uiState.update { state } }
    }

    /**
     * Turns whatever was typed into something loadable, then puts it through the rule engine.
     *
     * A word becomes a search rather than a failed lookup, and a bare host gets a scheme. Guessing
     * wrong here is the difference between a child searching for "puppies" and staring at a
     * connection error.
     *
     * The rule check happens before the load rather than only inside the web view, because a
     * programmatic load never reaches shouldOverrideUrlLoading. Checking here is also what lets
     * safe-search rewriting apply to an address the child typed by hand.
     */
    fun submit(raw: String) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return

        val url = when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.contains(' ') || !trimmed.contains('.') ->
                "https://duckduckgo.com/?q=" + android.net.Uri.encode(trimmed)
            else -> "https://$trimmed"
        }
        open(url)
    }

    fun open(url: String) {
        when (val decision = engine.evaluateNavigation(url, _uiState.value.safeSearchEnabled)) {
            is SafeBrowserEngine.NavigationDecision.Allow ->
                _uiState.update { it.copy(pendingUrl = decision.url, address = decision.url) }

            is SafeBrowserEngine.NavigationDecision.Redirect ->
                _uiState.update { it.copy(pendingUrl = decision.url, address = decision.url) }

            // The engine has already published the block, which the screen renders. Nothing is
            // handed to the web view, so the page is never fetched at all.
            is SafeBrowserEngine.NavigationDecision.Block ->
                _uiState.update { it.copy(address = url) }
        }
    }

    /** Used for the home page, which is known-good and must not be re-evaluated on every open. */
    fun openDirect(url: String) = _uiState.update { it.copy(pendingUrl = url, address = url) }

    fun consumeUrl() = _uiState.update { it.copy(pendingUrl = null) }

    fun setAddress(value: String) = _uiState.update { it.copy(address = value) }

    fun onPageChanged(url: String, title: String, canGoBack: Boolean) = _uiState.update {
        it.copy(
            currentUrl = url,
            title = title,
            canGoBack = canGoBack,
            // Only overwrite what the user typed once the page has actually settled somewhere.
            address = if (it.pendingUrl == null) url else it.address,
        )
    }

    fun setProgress(percent: Int) = _uiState.update { it.copy(loadingProgress = percent) }
}

@HiltViewModel
class SafeBrowserViewModel @Inject constructor(
    private val useCase: GetSafeBrowserUiStateUseCase,
    val engine: SafeBrowserEngine,
) : ViewModel(), ViewModelNav by ViewModelNavImpl() {

    val uiState: StateFlow<SafeBrowserUiState> = useCase.uiState

    init {
        attachScope(this)
        viewModelScope.launch { useCase.observe() }
        useCase.openDirect(HOME_URL)
    }

    fun onEvent(event: SafeBrowserUiEvent) {
        when (event) {
            SafeBrowserUiEvent.Back -> navigate(NavigationAction.Back)
            is SafeBrowserUiEvent.AddressChanged -> useCase.setAddress(event.value)
            SafeBrowserUiEvent.Go -> useCase.submit(uiState.value.address)
            is SafeBrowserUiEvent.OpenUrl -> useCase.open(event.url)
            SafeBrowserUiEvent.UrlConsumed -> useCase.consumeUrl()
            is SafeBrowserUiEvent.PageChanged ->
                useCase.onPageChanged(event.url, event.title, event.canGoBack)
            is SafeBrowserUiEvent.Progress -> useCase.setProgress(event.percent)
            SafeBrowserUiEvent.DismissBlock -> engine.clearBlock()
            SafeBrowserUiEvent.GoHome -> {
                engine.clearBlock()
                useCase.openDirect(HOME_URL)
            }
        }
    }

    companion object {
        const val HOME_URL = "https://duckduckgo.com/"
    }
}
