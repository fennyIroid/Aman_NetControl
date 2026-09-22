package com.example.childsafetyimpl.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Navigation intents a ViewModel can raise without holding a NavController.
 *
 * Follows the pattern described in CLAUDE.md: actions flow through a SharedFlow that the Screen
 * layer consumes, which keeps ViewModels free of navigation types and testable without Compose.
 */
sealed interface NavigationAction {
    data class To(val route: String, val popCurrent: Boolean = false) : NavigationAction
    data object Back : NavigationAction
    data class ToTab(val route: String) : NavigationAction
    data class PopTo(val route: String, val inclusive: Boolean = false) : NavigationAction
}

interface ViewModelNav {
    val navigationActionFlow: SharedFlow<NavigationAction>
    fun navigate(action: NavigationAction)
    fun attachScope(viewModel: ViewModel)
}

class ViewModelNavImpl : ViewModelNav {

    private val _navigationActionFlow = MutableSharedFlow<NavigationAction>(extraBufferCapacity = 4)
    override val navigationActionFlow: SharedFlow<NavigationAction> =
        _navigationActionFlow.asSharedFlow()

    private var owner: ViewModel? = null

    override fun attachScope(viewModel: ViewModel) {
        owner = viewModel
    }

    override fun navigate(action: NavigationAction) {
        val viewModel = owner ?: run {
            // Emitting without a scope would silently drop the action, which is a far more
            // confusing failure than an immediate one during development.
            _navigationActionFlow.tryEmit(action)
            return
        }
        viewModel.viewModelScope.launch { _navigationActionFlow.emit(action) }
    }
}

/** Convenience helpers so call sites read as intent rather than as plumbing. */
fun ViewModelNav.navigateTo(route: String) = navigate(NavigationAction.To(route))
fun ViewModelNav.navigateBack() = navigate(NavigationAction.Back)
