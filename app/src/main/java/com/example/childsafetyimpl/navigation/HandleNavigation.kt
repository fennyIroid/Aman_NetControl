package com.example.childsafetyimpl.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavHostController

/**
 * Consumes a ViewModel's navigation actions in the Screen layer.
 *
 * Collection is tied to the lifecycle so an action raised while the screen is in the background
 * is not applied to a controller that has already moved on.
 */
@Composable
fun HandleNavigation(
    viewModelNav: ViewModelNav,
    navController: NavHostController,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModelNav, navController) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModelNav.navigationActionFlow.collect { action ->
                when (action) {
                    is NavigationAction.To -> navController.navigate(action.route) {
                        if (action.popCurrent) {
                            navController.currentDestination?.route?.let {
                                popUpTo(it) { inclusive = true }
                            }
                        }
                        launchSingleTop = true
                    }

                    is NavigationAction.ToTab -> navController.navigate(action.route) {
                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }

                    is NavigationAction.PopTo -> navController.popBackStack(
                        route = action.route,
                        inclusive = action.inclusive,
                    )

                    NavigationAction.Back -> navController.popBackStack()
                }
            }
        }
    }
}
