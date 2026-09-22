package com.example.childsafetyimpl.ux.permissions

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.childsafetyimpl.core.protection.ProtectionFeature
import com.example.childsafetyimpl.core.permission.PermissionState
import com.example.childsafetyimpl.core.permission.SafetyPermission
import com.example.childsafetyimpl.data.prefs.SafetyPreferences
import com.example.childsafetyimpl.navigation.NavigationAction
import com.example.childsafetyimpl.navigation.ViewModelNav
import com.example.childsafetyimpl.navigation.ViewModelNavImpl
import com.example.childsafetyimpl.platform.permission.PermissionInspector
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PermissionRow(
    val state: PermissionState,
    /** Protections that cannot run at all without this grant. */
    val blockedProtections: List<ProtectionFeature>,
    /** Protections that run without it but are weaker. */
    val weakenedProtections: List<ProtectionFeature>,
    val settingsIntent: Intent?,
    val intentResolves: Boolean,
)

data class PermissionsUiState(
    val rows: List<PermissionRow> = emptyList(),
    val grantedCount: Int = 0,
    val requestableCount: Int = 0,
    val unobtainableCount: Int = 0,
) {
    val coverage: Float
        get() {
            val obtainable = rows.count { it.state.unavailableReason == null }
            return if (obtainable == 0) 0f else grantedCount.toFloat() / obtainable
        }
}

sealed interface PermissionsUiEvent {
    data object Refresh : PermissionsUiEvent
    data class Grant(val permission: SafetyPermission) : PermissionsUiEvent
    data object Back : PermissionsUiEvent
}

/**
 * The permission centre.
 *
 * Each row states what Android hands over, which protections it unlocks, and what stays broken
 * without it. Google Play requires that disclosure for every one of these, and a parent granting
 * accessibility or notification access deserves it regardless.
 *
 * Which protections a permission gates is derived from the protection catalog rather than written
 * into copy, so a permission's importance can never drift out of step with what actually depends
 * on it.
 */
class GetPermissionsUiStateUseCase @Inject constructor(
    private val inspector: PermissionInspector,
    private val preferences: SafetyPreferences,
) {

    private val _uiState = MutableStateFlow(PermissionsUiState())
    val uiState: StateFlow<PermissionsUiState> = _uiState.asStateFlow()

    fun refresh() {
        val states = inspector.allStates()
        val rows = states.map { state ->
            val intent = inspector.settingsIntentFor(state.permission)
            PermissionRow(
                state = state,
                blockedProtections = ProtectionFeature.entries
                    .filter { state.permission in it.requires },
                weakenedProtections = ProtectionFeature.entries
                    .filter { state.permission in it.strengthenedBy },
                settingsIntent = intent,
                intentResolves = intent != null && inspector.resolves(intent),
            )
            // Ungranted first, then whatever blocks the most protection: the top of this list is
            // the shortest path to a device that is actually protected.
        }.sortedWith(
            compareBy(
                { it.state.isGranted },
                { it.state.unavailableReason != null },
                { -it.blockedProtections.size },
            )
        )

        _uiState.update {
            it.copy(
                rows = rows,
                grantedCount = rows.count { row -> row.state.isGranted },
                requestableCount = rows.count { row -> row.state.isRequestable },
                unobtainableCount = rows.count { row -> row.state.unavailableReason != null },
            )
        }
    }

    /** Records the current grant set so the bypass detector has a baseline to compare against. */
    suspend fun snapshotGrants() {
        preferences.recordGrants(inspector.grantedSet())
    }

    fun intentFor(permission: SafetyPermission): Intent? =
        inspector.settingsIntentFor(permission)?.takeIf { inspector.resolves(it) }
            ?: inspector.appSettingsIntent()
}

@HiltViewModel
class PermissionsViewModel @Inject constructor(
    private val useCase: GetPermissionsUiStateUseCase,
) : ViewModel(), ViewModelNav by ViewModelNavImpl() {

    val uiState: StateFlow<PermissionsUiState> = useCase.uiState

    init {
        attachScope(this)
        useCase.refresh()
    }

    fun onEvent(event: PermissionsUiEvent) {
        when (event) {
            PermissionsUiEvent.Refresh -> {
                useCase.refresh()
                viewModelScope.launch { useCase.snapshotGrants() }
            }
            PermissionsUiEvent.Back -> navigate(NavigationAction.Back)
            is PermissionsUiEvent.Grant -> Unit // handled by the screen, which owns the launcher
        }
    }

    fun intentFor(permission: SafetyPermission): Intent? = useCase.intentFor(permission)
}
