package com.example.childsafetyimpl.ux.controls

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.childsafetyimpl.data.prefs.SafetyPreferences
import com.example.childsafetyimpl.data.prefs.SafetySettings
import com.example.childsafetyimpl.domain.model.AppCategory
import com.example.childsafetyimpl.domain.model.CategoryBudget
import com.example.childsafetyimpl.domain.model.DomainRule
import com.example.childsafetyimpl.domain.model.WebCategory
import com.example.childsafetyimpl.domain.repository.SafetyRulesRepository
import com.example.childsafetyimpl.navigation.NavigationAction
import com.example.childsafetyimpl.navigation.Routes
import com.example.childsafetyimpl.navigation.ViewModelNav
import com.example.childsafetyimpl.navigation.ViewModelNavImpl
import com.example.childsafetyimpl.platform.admin.NetworkLockdown
import com.example.childsafetyimpl.platform.enforcement.MonitoringService
import com.example.childsafetyimpl.platform.network.InternetPauseController
import com.example.childsafetyimpl.platform.network.PauseTunnelService
import com.example.childsafetyimpl.platform.network.PrivateDnsGuard
import com.example.childsafetyimpl.platform.network.VpnGuard
import com.example.childsafetyimpl.ux.protection.ProtectionRunner
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ControlsUiState(
    val loading: Boolean = true,
    val settings: SafetySettings = SafetySettings(),
    val monitoringRunning: Boolean = false,
    val categoryBudgets: List<CategoryBudget> = emptyList(),
    val webCategories: Map<WebCategory, Boolean> = emptyMap(),
    val parentDomainRules: List<DomainRule> = emptyList(),
    val newDomain: String = "",

    /** What the parent asked for. Survives reboot and a killed process. */
    val internetPaused: Boolean = false,

    /**
     * Whether the pause is actually applying right now.
     *
     * Held separately from [internetPaused] because the gap between the two is the state a parent
     * most needs to see and would otherwise never learn about: they paused, the screen says
     * paused, and the child is online because the connection permission was withdrawn.
     */
    val internetPauseEnforced: Boolean = false,

    /** Set when a pause could not be applied, so the screen can say why instead of doing nothing. */
    val pauseMessage: String? = null,

    /** Where Android's Private DNS setting currently points. Null until first read. */
    val privateDnsPosture: PrivateDnsGuard.Posture? = null,
    /** What that means, in the parent's words. */
    val privateDnsAdvice: PrivateDnsGuard.Advice? = null,

    /** What device policy is enforcing, which is nothing unless the device is managed. */
    val lockdown: NetworkLockdown.Applied = NetworkLockdown.Applied(),
) {
    /** Private DNS is set up and pointing at a resolver that filters. */
    val privateDnsFiltering: Boolean
        get() = privateDnsPosture is PrivateDnsGuard.Posture.Filtering
}

sealed interface ControlsUiEvent {
    data class SetBedtime(val startMinuteOfDay: Int, val endMinuteOfDay: Int) : ControlsUiEvent
    data class SetDailyLimit(val minutes: Int) : ControlsUiEvent
    data class SetCategoryBudget(val category: AppCategory, val minutes: Int?) : ControlsUiEvent
    data class SetSafeSearch(val enabled: Boolean) : ControlsUiEvent
    data class SetAllowListOnly(val enabled: Boolean) : ControlsUiEvent
    data class SetNotificationSignals(val enabled: Boolean) : ControlsUiEvent
    data class SetWebCategoryBlocked(val category: WebCategory, val blocked: Boolean) :
        ControlsUiEvent
    data class NewDomainChanged(val value: String) : ControlsUiEvent
    data object AddDomain : ControlsUiEvent
    data class RemoveDomain(val id: Long) : ControlsUiEvent
    data object ToggleInternetPause : ControlsUiEvent
    data object DismissPauseMessage : ControlsUiEvent
    data class SetPrivateDnsHostname(val hostname: String) : ControlsUiEvent
    data object RefreshPrivateDns : ControlsUiEvent
    data object OpenApps : ControlsUiEvent
    data object OpenPermissions : ControlsUiEvent
    data object OpenProtections : ControlsUiEvent
}

/**
 * The controls a parent sets once and rarely revisits: bedtime, the day's overall limit, category
 * budgets, and what the web filter refuses.
 *
 * Per-protection strictness deliberately does not live here. It belongs on the protection's own
 * screen, next to the live evidence of what that protection is doing, where a parent can see the
 * effect of the choice rather than guessing at it from a settings list.
 */
class GetControlsUiStateUseCase @Inject constructor(
    private val preferences: SafetyPreferences,
    private val rulesRepository: SafetyRulesRepository,
    private val privateDnsGuard: PrivateDnsGuard,
    private val networkLockdown: NetworkLockdown,
) {

    private val _uiState = MutableStateFlow(ControlsUiState())
    val uiState: StateFlow<ControlsUiState> = _uiState.asStateFlow()

    suspend fun observe() {
        combine(
            preferences.settings,
            rulesRepository.observeCategoryBudgets(),
            rulesRepository.observeWebCategories(),
            rulesRepository.observeParentDomainRules(),
            combine(
                MonitoringService.State.running,
                PauseTunnelService.State.running,
                networkLockdown.applied,
            ) { monitoring, pauseRunning, lockdown -> Triple(monitoring, pauseRunning, lockdown) },
        ) { settings, budgets, webCategories, domainRules, live ->
            _uiState.value.copy(
                loading = false,
                settings = settings,
                categoryBudgets = budgets,
                webCategories = webCategories,
                parentDomainRules = domainRules,
                monitoringRunning = live.first,
                internetPaused = settings.internetPaused,
                // The pause tunnel only ever runs to hold a pause, so it being up is the pause
                // being in force. Asked for and not up is the mismatch worth showing.
                internetPauseEnforced = live.second,
                lockdown = live.third,
            )
        }.collect { state -> _uiState.update { state } }
    }

    fun setNewDomain(value: String) = _uiState.update { it.copy(newDomain = value) }

    fun setPauseMessage(message: String?) = _uiState.update { it.copy(pauseMessage = message) }

    /**
     * Re-reads the Private DNS setting.
     *
     * Android sends no notification when it changes, so the screen asks each time it comes back
     * to the front — which is exactly when a parent returns from typing the hostname in Settings.
     */
    fun refreshPrivateDns() {
        val posture = privateDnsGuard.currentPosture()
        _uiState.update {
            it.copy(
                privateDnsPosture = posture,
                privateDnsAdvice = privateDnsGuard.explain(posture),
            )
        }
    }

    fun clearNewDomain() = _uiState.update { it.copy(newDomain = "") }
}

@HiltViewModel
class ControlsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val useCase: GetControlsUiStateUseCase,
    private val preferences: SafetyPreferences,
    private val rulesRepository: SafetyRulesRepository,
    private val protectionRunner: ProtectionRunner,
    private val internetPauseController: InternetPauseController,
) : ViewModel(), ViewModelNav by ViewModelNavImpl() {

    val uiState: StateFlow<ControlsUiState> = useCase.uiState

    init {
        attachScope(this)
        viewModelScope.launch { useCase.observe() }
    }

    fun onEvent(event: ControlsUiEvent) {
        when (event) {
            is ControlsUiEvent.SetBedtime -> viewModelScope.launch {
                preferences.setBedtime(event.startMinuteOfDay, event.endMinuteOfDay)
            }

            is ControlsUiEvent.SetDailyLimit -> viewModelScope.launch {
                preferences.setDailyLimit(event.minutes)
            }

            is ControlsUiEvent.SetCategoryBudget -> viewModelScope.launch {
                rulesRepository.setCategoryBudget(
                    category = event.category,
                    minutes = event.minutes,
                    enabled = event.minutes != null,
                )
            }

            is ControlsUiEvent.SetSafeSearch -> viewModelScope.launch {
                preferences.setSafeSearch(event.enabled)
            }

            is ControlsUiEvent.SetAllowListOnly -> viewModelScope.launch {
                preferences.setAllowListOnly(event.enabled)
                rulesRepository.refreshFilterCache()
            }

            is ControlsUiEvent.SetNotificationSignals -> viewModelScope.launch {
                preferences.setNotificationSignals(event.enabled)
            }

            is ControlsUiEvent.SetWebCategoryBlocked -> viewModelScope.launch {
                rulesRepository.setWebCategoryBlocked(event.category, event.blocked)
                // The filter engines evaluate against a cached snapshot, so a rule change that
                // does not refresh it is a setting that appears to save and does nothing.
                rulesRepository.refreshFilterCache()
            }

            is ControlsUiEvent.NewDomainChanged -> useCase.setNewDomain(event.value)

            ControlsUiEvent.AddDomain -> viewModelScope.launch {
                val pattern = uiState.value.newDomain.trim().lowercase()
                    .removePrefix("https://")
                    .removePrefix("http://")
                    .removePrefix("www.")
                    .substringBefore('/')
                if (pattern.isBlank()) return@launch
                // In allow-list mode this list is what the child may reach, so a site added here
                // has to be an allow rule. Adding a block rule would make the control do the
                // opposite of what its own heading says.
                val allowListOnly = uiState.value.settings.allowListOnlyBrowsing
                rulesRepository.addParentDomainRule(pattern, block = !allowListOnly)
                rulesRepository.refreshFilterCache()
                useCase.clearNewDomain()
            }

            is ControlsUiEvent.RemoveDomain -> viewModelScope.launch {
                rulesRepository.deleteDomainRule(event.id)
                rulesRepository.refreshFilterCache()
            }

            ControlsUiEvent.ToggleInternetPause -> viewModelScope.launch {
                when (val outcome = internetPauseController.toggle()) {
                    InternetPauseController.PauseOutcome.NeedsConsent ->
                        useCase.setPauseMessage(
                            "Pausing the internet needs the connection permission. Tap pause " +
                                "again to allow it.",
                        )

                    is InternetPauseController.PauseOutcome.Failed ->
                        useCase.setPauseMessage(outcome.reason)

                    else -> useCase.setPauseMessage(null)
                }
            }

            ControlsUiEvent.DismissPauseMessage -> useCase.setPauseMessage(null)

            is ControlsUiEvent.SetPrivateDnsHostname -> viewModelScope.launch {
                preferences.setPrivateDnsHostname(event.hostname)
            }

            ControlsUiEvent.RefreshPrivateDns -> useCase.refreshPrivateDns()

            ControlsUiEvent.OpenApps -> navigate(NavigationAction.To(Routes.appRestriction()))
            ControlsUiEvent.OpenPermissions ->
                navigate(NavigationAction.To(Routes.permissions()))
            ControlsUiEvent.OpenProtections ->
                navigate(NavigationAction.ToTab(Routes.protections()))
        }
    }

    /**
     * The consent dialog a pause needs, or null when it is already given. Lifting a pause never
     * needs it. Raised through the VPN guard, which otherwise cancels every connection request.
     */
    fun pauseConsentIntent(): Intent? =
        if (uiState.value.internetPaused) null else VpnGuard.ownConsentIntent(context)

    fun applyProtectionRunning(enabled: Boolean) = protectionRunner.apply(enabled)
}
