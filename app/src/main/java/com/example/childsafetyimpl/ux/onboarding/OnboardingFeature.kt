package com.example.childsafetyimpl.ux.onboarding

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.childsafetyimpl.core.protection.ProtectionFeature
import com.example.childsafetyimpl.data.prefs.SafetyPreferences
import com.example.childsafetyimpl.navigation.NavigationAction
import com.example.childsafetyimpl.navigation.Routes
import com.example.childsafetyimpl.navigation.ViewModelNav
import com.example.childsafetyimpl.navigation.ViewModelNavImpl
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OnboardingUiState(
    val step: Int = 0,
    val childName: String = "",
    val childAge: Int = 10,
    val avatarIndex: Int = 0,
    val deviceLabel: String = "${Build.MANUFACTURER} ${Build.MODEL}",
    val protectionCount: Int = ProtectionFeature.entries.size,
    val primaryCount: Int = ProtectionFeature.primary.size,
    /** Distinct special accesses the parent will be asked to grant. */
    val grantCount: Int = ProtectionFeature.entries
        .flatMap { it.requires + it.strengthenedBy }
        .distinct()
        .size,
) {
    val canContinue: Boolean
        get() = when (step) {
            1 -> childName.isNotBlank()
            else -> true
        }

    val isLastStep: Boolean get() = step >= LAST_STEP

    companion object {
        const val LAST_STEP = 2
    }
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val preferences: SafetyPreferences,
) : ViewModel(), ViewModelNav by ViewModelNavImpl() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    init {
        attachScope(this)
    }

    fun setName(name: String) = _uiState.update { it.copy(childName = name) }
    fun setAge(age: Int) = _uiState.update { it.copy(childAge = age) }
    fun setAvatar(index: Int) = _uiState.update { it.copy(avatarIndex = index) }
    fun setDeviceLabel(label: String) = _uiState.update { it.copy(deviceLabel = label) }

    fun next() {
        val state = _uiState.value
        if (!state.canContinue) return
        if (state.isLastStep) {
            finish()
        } else {
            _uiState.update { it.copy(step = it.step + 1) }
        }
    }

    fun back() {
        val state = _uiState.value
        if (state.step == 0) return
        _uiState.update { it.copy(step = it.step - 1) }
    }

    private fun finish() {
        val state = _uiState.value
        viewModelScope.launch {
            preferences.completeOnboarding(
                name = state.childName.trim(),
                age = state.childAge,
                avatarIndex = state.avatarIndex,
                deviceLabel = state.deviceLabel.trim(),
            )
            navigate(NavigationAction.To(Routes.dashboard(), popCurrent = true))
        }
    }
}
