package com.example.childsafetyimpl.ux.events

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.childsafetyimpl.core.common.TimeFormat
import com.example.childsafetyimpl.core.protection.ProtectionFeature
import com.example.childsafetyimpl.domain.model.EventSeverity
import com.example.childsafetyimpl.domain.model.SafetyEvent
import com.example.childsafetyimpl.domain.repository.SafetyEventRepository
import com.example.childsafetyimpl.navigation.NavigationAction
import com.example.childsafetyimpl.navigation.Routes
import com.example.childsafetyimpl.navigation.ViewModelNav
import com.example.childsafetyimpl.navigation.ViewModelNavImpl
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EventsUiState(
    val events: List<SafetyEvent> = emptyList(),
    val severityFilter: EventSeverity? = null,
    val todayCount: Int = 0,
    val seriousCount: Int = 0,
) {
    val filtered: List<SafetyEvent>
        get() = severityFilter?.let { filter -> events.filter { it.severity == filter } } ?: events

    val severityCounts: Map<EventSeverity, Int>
        get() = events.groupingBy { it.severity }.eachCount()

    /** Events the app saw but could not act on. The most important number on the screen. */
    val notEnforceableCount: Int
        get() = events.count {
            it.action == com.example.childsafetyimpl.domain.model.EventAction.NOT_ENFORCEABLE
        }
}

@HiltViewModel
class EventsViewModel @Inject constructor(
    private val repository: SafetyEventRepository,
) : ViewModel(), ViewModelNav by ViewModelNavImpl() {

    private val _uiState = MutableStateFlow(EventsUiState())
    val uiState: StateFlow<EventsUiState> = _uiState.asStateFlow()

    init {
        attachScope(this)

        repository.observeRecent(limit = 300)
            .onEach { events -> _uiState.update { it.copy(events = events) } }
            .launchIn(viewModelScope)

        repository.observeCountSince(TimeFormat.startOfTodayMillis())
            .onEach { count -> _uiState.update { it.copy(todayCount = count) } }
            .launchIn(viewModelScope)

        repository.observeSeriousCountSince(TimeFormat.startOfTodayMillis())
            .onEach { count -> _uiState.update { it.copy(seriousCount = count) } }
            .launchIn(viewModelScope)
    }

    fun setFilter(severity: EventSeverity?) {
        _uiState.update { it.copy(severityFilter = severity) }
    }

    fun clear() {
        viewModelScope.launch { repository.clear() }
    }

    /**
     * Events carry the id of the protection that produced them, so tapping one goes to the screen
     * that can explain or change it rather than to a dead end.
     */
    fun openProtection(featureId: String) {
        val feature = ProtectionFeature.find(featureId) ?: return
        navigate(NavigationAction.To(Routes.screenFor(feature)))
    }
}
