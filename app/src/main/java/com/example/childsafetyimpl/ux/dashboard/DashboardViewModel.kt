package com.example.childsafetyimpl.ux.dashboard

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.childsafetyimpl.data.prefs.SafetyPreferences
import com.example.childsafetyimpl.navigation.NavigationAction
import com.example.childsafetyimpl.navigation.Routes
import com.example.childsafetyimpl.navigation.ViewModelNav
import com.example.childsafetyimpl.navigation.ViewModelNavImpl
import com.example.childsafetyimpl.platform.network.InternetPauseController
import com.example.childsafetyimpl.platform.network.VpnGuard
import com.example.childsafetyimpl.ux.protection.ProtectionRunner
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * A thin delegate over the use case, per the project's ViewModel convention: state belongs to the
 * use case, navigation belongs to the delegate, and this class does neither job itself.
 */
@HiltViewModel
class DashboardViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val useCase: GetDashboardUiStateUseCase,
    private val preferences: SafetyPreferences,
    private val protectionRunner: ProtectionRunner,
    private val internetPauseController: InternetPauseController,
) : ViewModel(), ViewModelNav by ViewModelNavImpl() {

    val uiState: StateFlow<DashboardUiState> = useCase.uiState

    init {
        attachScope(this)
        viewModelScope.launch { useCase.observe() }
        viewModelScope.launch { useCase.pollUsage() }
    }

    fun onEvent(event: DashboardUiEvent) {
        when (event) {
            DashboardUiEvent.OpenProtections ->
                navigate(NavigationAction.ToTab(Routes.protections()))

            DashboardUiEvent.OpenEvents ->
                navigate(NavigationAction.ToTab(Routes.events()))

            DashboardUiEvent.OpenPermissions -> go(Routes.permissions())
            DashboardUiEvent.OpenApps -> go(Routes.appRestriction())
            DashboardUiEvent.OpenBrowser -> go(Routes.safeBrowser())
            DashboardUiEvent.OpenPlayer -> go(Routes.youtubePlayer())

            is DashboardUiEvent.ToggleProtection -> viewModelScope.launch {
                preferences.setProtectionEnabled(event.enabled)
                protectionRunner.apply(event.enabled)
            }

            DashboardUiEvent.Refresh -> viewModelScope.launch { useCase.refreshUsage() }

            DashboardUiEvent.ToggleInternetPause -> viewModelScope.launch {
                val outcome = internetPauseController.toggle()
                useCase.setPauseMessage(
                    when (outcome) {
                        InternetPauseController.PauseOutcome.NeedsConsent ->
                            "Pausing needs the connection permission. Tap pause again to allow it."
                        is InternetPauseController.PauseOutcome.Failed -> outcome.reason
                        else -> null
                    },
                )
            }

            DashboardUiEvent.OpenControls -> navigate(NavigationAction.ToTab(Routes.controls()))
        }
    }

    /**
     * The VPN consent dialog a pause needs, or null when it has already been given. Only an
     * Activity can raise it, so the screen launches it and sends the pause once it returns.
     * Never asked for when lifting a pause, which needs no permission.
     */
    fun pauseConsentIntent(): Intent? =
        if (uiState.value.internetPaused) null else VpnGuard.ownConsentIntent(context)

    private fun go(route: String) = navigate(NavigationAction.To(route))
}
