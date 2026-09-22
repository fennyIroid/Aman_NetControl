package com.example.childsafetyimpl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.childsafetyimpl.data.prefs.SafetyPreferences
import com.example.childsafetyimpl.domain.repository.SafetyRulesRepository
import com.example.childsafetyimpl.platform.browser.SafeBrowserEngine
import com.example.childsafetyimpl.platform.youtube.YouTubeSurface
import com.example.childsafetyimpl.ux.protection.ProtectionRunner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AppShellUiState(
    val loading: Boolean = true,
    val onboardingComplete: Boolean = false,
)

/**
 * Decides where the app opens, warms the filter cache, and resumes protection.
 *
 * Resuming here matters more than it looks. The boot receiver covers a restart, but a service
 * killed by the system, by a manufacturer's power management, or by the child swiping the app away
 * has nothing else to bring it back. Opening the app is the one moment protection can always be
 * re-established, so it is done unconditionally rather than only when something looks wrong.
 */
@HiltViewModel
class AppShellViewModel @Inject constructor(
    private val preferences: SafetyPreferences,
    private val rulesRepository: SafetyRulesRepository,
    private val protectionRunner: ProtectionRunner,
    private val browserEngine: SafeBrowserEngine,
    private val youTubeSurface: YouTubeSurface,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppShellUiState())
    val uiState: StateFlow<AppShellUiState> = _uiState.asStateFlow()

    init {
        // The browser and the player read their settings from a cache, because a preference read
        // inside a request-interception callback is a disk read per subresource. These keep that
        // cache current for as long as the app is alive.
        viewModelScope.launch { browserEngine.observeSettings() }
        viewModelScope.launch { youTubeSurface.observeSettings() }

        viewModelScope.launch {
            val settings = preferences.settings.first()
            // Loaded here so the browser and the tunnel never have to wait on a database read in
            // a latency-critical callback.
            runCatching { rulesRepository.refreshFilterCache() }

            if (settings.protectionEnabled) {
                runCatching { protectionRunner.apply(true) }
            }
            _uiState.update {
                it.copy(loading = false, onboardingComplete = settings.onboardingComplete)
            }
        }
    }
}
