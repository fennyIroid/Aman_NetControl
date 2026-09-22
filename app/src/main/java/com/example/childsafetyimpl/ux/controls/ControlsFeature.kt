package com.example.childsafetyimpl.ux.controls

import android.content.Context
import android.content.Intent
import android.os.Build
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
import com.example.childsafetyimpl.platform.enforcement.MonitoringService
import com.example.childsafetyimpl.platform.network.ChildSafetyVpnService
import com.example.childsafetyimpl.platform.network.FilteringMode
import com.example.childsafetyimpl.platform.network.InternetPauseController
import com.example.childsafetyimpl.platform.network.PrivateDnsGuard
import com.example.childsafetyimpl.platform.network.TunnelMode
import com.example.childsafetyimpl.ux.protection.ProtectionRunner
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ControlsUiState(
    val loading: Boolean = true,
    val settings: SafetySettings = SafetySettings(),
    val monitoringRunning: Boolean = false,
    val tunnelRunning: Boolean = false,
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
     * paused, and the child is online because VPN consent was withdrawn.
     */
    val internetPauseEnforced: Boolean = false,

    /** Set when a pause could not be applied, so the screen can say why instead of doing nothing. */
    val pauseMessage: String? = null,

    /** Where Android's Private DNS setting currently points. Null until first read. */
    val privateDnsPosture: PrivateDnsGuard.Posture? = null,
    /** What that means in the chosen filtering posture, in the parent's words. */
    val privateDnsAdvice: PrivateDnsGuard.Advice? = null,
) {
    val filteringMode: FilteringMode get() = settings.filteringMode

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
    data class SetTunnel(val running: Boolean) : ControlsUiEvent
    data object ToggleInternetPause : ControlsUiEvent
    data object DismissPauseMessage : ControlsUiEvent
    data class SetFilteringMode(val mode: FilteringMode) : ControlsUiEvent
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
                ChildSafetyVpnService.State.running,
                ChildSafetyVpnService.State.paused,
            ) { monitoring, tunnel, paused -> Triple(monitoring, tunnel, paused) },
        ) { settings, budgets, webCategories, domainRules, running ->
            _uiState.value.copy(
                loading = false,
                settings = settings,
                categoryBudgets = budgets,
                webCategories = webCategories,
                parentDomainRules = domainRules,
                monitoringRunning = running.first,
                tunnelRunning = running.second,
                internetPaused = settings.internetPaused,
                // Enforced only when the tunnel is up AND in the paused posture. Either alone is
                // the mismatch worth showing rather than hiding.
                internetPauseEnforced = running.second && running.third,
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
    suspend fun refreshPrivateDns() {
        val filtering = preferences.settings.first().filteringMode
        val posture = privateDnsGuard.currentPosture()
        _uiState.update {
            it.copy(
                privateDnsPosture = posture,
                privateDnsAdvice = privateDnsGuard.explain(posture, filtering),
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

            is ControlsUiEvent.SetTunnel -> setTunnel(event.running)

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

            is ControlsUiEvent.SetFilteringMode -> viewModelScope.launch {
                preferences.setFilteringMode(event.mode)
                applyFilteringMode(event.mode)
                useCase.refreshPrivateDns()
            }

            is ControlsUiEvent.SetPrivateDnsHostname -> viewModelScope.launch {
                preferences.setPrivateDnsHostname(event.hostname)
            }

            ControlsUiEvent.RefreshPrivateDns -> viewModelScope.launch {
                useCase.refreshPrivateDns()
            }

            ControlsUiEvent.OpenApps -> navigate(NavigationAction.To(Routes.appRestriction()))
            ControlsUiEvent.OpenPermissions ->
                navigate(NavigationAction.To(Routes.permissions()))
            ControlsUiEvent.OpenProtections ->
                navigate(NavigationAction.ToTab(Routes.protections()))
        }
    }

    /**
     * Starting the tunnel needs VPN consent, which only an Activity can ask for. The screen owns
     * that launcher; this only handles the case where consent already exists, and stopping.
     */
    fun tunnelConsentIntent(): Intent? = android.net.VpnService.prepare(context)

    /**
     * The consent dialog a pause needs, or null when it is already given or not needed. Lifting a
     * pause never needs it, and neither does a posture that cannot pause at all.
     */
    fun pauseConsentIntent(): Intent? {
        val state = uiState.value
        if (state.internetPaused || !state.filteringMode.canPauseInternet) return null
        return runCatching { tunnelConsentIntent() }.getOrNull()
    }

    /**
     * Brings the tunnel into line with a newly chosen filtering posture.
     *
     * In the tunnel posture it is started, where consent already exists; otherwise the Start
     * button on this screen asks for it. In the Private DNS postures it is stopped, unless a
     * pause is in force, which a change of posture must never quietly lift.
     */
    private suspend fun applyFilteringMode(mode: FilteringMode) {
        val settings = preferences.settings.first()
        val resting = TunnelMode.restingFor(mode, settings.internetPaused)
        when {
            resting == null -> if (uiState.value.tunnelRunning) setTunnel(false)
            settings.protectionEnabled &&
                runCatching { tunnelConsentIntent() == null }.getOrDefault(false) ->
                setTunnel(true)
        }
    }

    fun setTunnel(running: Boolean) {
        val intent = Intent(context, ChildSafetyVpnService::class.java)
        if (running) {
            // startForegroundService only exists from Android 8. Below that a plain start is the
            // correct call, and the version gate is what keeps this off a crash path on Android 7.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } else {
            context.startService(intent.setAction(ChildSafetyVpnService.ACTION_STOP))
        }
    }

    fun applyProtectionRunning(enabled: Boolean) = protectionRunner.apply(enabled)
}
