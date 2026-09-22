package com.example.childsafetyimpl.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.example.childsafetyimpl.ui.theme.SafetyMotion
import com.example.childsafetyimpl.ux.browser.SafeBrowserScreen
import com.example.childsafetyimpl.ux.browser.SafeBrowserViewModel
import com.example.childsafetyimpl.ux.controls.ControlsScreen
import com.example.childsafetyimpl.ux.controls.ControlsViewModel
import com.example.childsafetyimpl.ux.dashboard.DashboardScreen
import com.example.childsafetyimpl.ux.dashboard.DashboardViewModel
import com.example.childsafetyimpl.ux.events.EventsScreen
import com.example.childsafetyimpl.ux.events.EventsViewModel
import com.example.childsafetyimpl.ux.onboarding.OnboardingScreen
import com.example.childsafetyimpl.ux.onboarding.OnboardingViewModel
import com.example.childsafetyimpl.ux.permissions.PermissionsScreen
import com.example.childsafetyimpl.ux.permissions.PermissionsViewModel
import com.example.childsafetyimpl.ux.protection.AdRemovalScreen
import com.example.childsafetyimpl.ux.protection.AdRemovalViewModel
import com.example.childsafetyimpl.ux.protection.AiProtectionScreen
import com.example.childsafetyimpl.ux.protection.AiProtectionViewModel
import com.example.childsafetyimpl.ux.protection.AppRestrictionScreen
import com.example.childsafetyimpl.ux.protection.AppRestrictionViewModel
import com.example.childsafetyimpl.ux.protection.CloneProtectionScreen
import com.example.childsafetyimpl.ux.protection.CloneProtectionViewModel
import com.example.childsafetyimpl.ux.protection.ImageBlurringScreen
import com.example.childsafetyimpl.ux.protection.ImageBlurringViewModel
import com.example.childsafetyimpl.ux.protection.ProtectionsScreen
import com.example.childsafetyimpl.ux.protection.ProtectionsViewModel
import com.example.childsafetyimpl.ux.protection.SearchFilteringScreen
import com.example.childsafetyimpl.ux.protection.SearchFilteringViewModel
import com.example.childsafetyimpl.ux.protection.ShortFormProtectionViewModel
import com.example.childsafetyimpl.ux.protection.StatusProtectionViewModel
import com.example.childsafetyimpl.ux.protection.SurfaceProtectionScreen
import com.example.childsafetyimpl.ux.protection.VideoBlurringScreen
import com.example.childsafetyimpl.ux.protection.VideoBlurringViewModel
import com.example.childsafetyimpl.ux.protection.VpnProtectionScreen
import com.example.childsafetyimpl.ux.protection.VpnProtectionViewModel
import com.example.childsafetyimpl.ux.youtube.YouTubePlayerScreen
import com.example.childsafetyimpl.ux.youtube.YouTubePlayerViewModel

/**
 * The whole graph.
 *
 * Transitions carry meaning rather than decoration: the four tabs cross-fade because they are
 * siblings, while a push into a protection slides, because it is a place you came from somewhere
 * and will come back from.
 */
@Composable
fun SafetyNavGraph(
    navController: NavHostController,
    startDestination: String,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
        enterTransition = { pushEnter() },
        exitTransition = { pushExit() },
        popEnterTransition = { popEnter() },
        popExitTransition = { popExit() },
    ) {
        composable(
            route = Routes.onboarding.pattern,
            enterTransition = { fadeIn(SafetyMotion.screenEnter()) },
            exitTransition = { fadeOut(SafetyMotion.screenExit()) },
        ) {
            val viewModel: OnboardingViewModel = hiltViewModel()
            HandleNavigation(viewModel, LocalNavController.current)
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            OnboardingScreen(
                state = state,
                onSetName = viewModel::setName,
                onSetAge = viewModel::setAge,
                onSetAvatar = viewModel::setAvatar,
                onSetDevice = viewModel::setDeviceLabel,
                onNext = viewModel::next,
                onBack = viewModel::back,
            )
        }

        // --- the four tabs -------------------------------------------------------------------

        tab(Routes.dashboard.pattern) {
            DashboardScreen(viewModel = hiltViewModel<DashboardViewModel>())
        }

        tab(Routes.protections.pattern) {
            ProtectionsScreen(viewModel = hiltViewModel<ProtectionsViewModel>())
        }

        tab(Routes.controls.pattern) {
            ControlsScreen(viewModel = hiltViewModel<ControlsViewModel>())
        }

        tab(Routes.events.pattern) {
            val viewModel: EventsViewModel = hiltViewModel()
            HandleNavigation(viewModel, LocalNavController.current)
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            EventsScreen(
                state = state,
                onFilter = viewModel::setFilter,
                onClear = viewModel::clear,
                onOpenCapability = viewModel::openProtection,
            )
        }

        // --- one screen per protection ---------------------------------------------------------

        composable(Routes.vpnProtection.pattern) {
            VpnProtectionScreen(viewModel = hiltViewModel<VpnProtectionViewModel>())
        }

        composable(Routes.cloneProtection.pattern) {
            CloneProtectionScreen(viewModel = hiltViewModel<CloneProtectionViewModel>())
        }

        composable(Routes.shortFormProtection.pattern) {
            SurfaceProtectionScreen(viewModel = hiltViewModel<ShortFormProtectionViewModel>())
        }

        composable(Routes.statusProtection.pattern) {
            SurfaceProtectionScreen(viewModel = hiltViewModel<StatusProtectionViewModel>())
        }

        composable(Routes.aiProtection.pattern) {
            AiProtectionScreen(viewModel = hiltViewModel<AiProtectionViewModel>())
        }

        composable(Routes.appRestriction.pattern) {
            AppRestrictionScreen(viewModel = hiltViewModel<AppRestrictionViewModel>())
        }

        composable(Routes.searchFiltering.pattern) {
            SearchFilteringScreen(viewModel = hiltViewModel<SearchFilteringViewModel>())
        }

        composable(Routes.adRemoval.pattern) {
            AdRemovalScreen(viewModel = hiltViewModel<AdRemovalViewModel>())
        }

        composable(Routes.imageBlurring.pattern) {
            ImageBlurringScreen(viewModel = hiltViewModel<ImageBlurringViewModel>())
        }

        composable(Routes.videoBlurring.pattern) {
            VideoBlurringScreen(viewModel = hiltViewModel<VideoBlurringViewModel>())
        }

        // --- the surfaces the child uses ---------------------------------------------------------

        composable(Routes.safeBrowser.pattern) {
            SafeBrowserScreen(viewModel = hiltViewModel<SafeBrowserViewModel>())
        }

        composable(Routes.youtubePlayer.pattern) {
            YouTubePlayerScreen(viewModel = hiltViewModel<YouTubePlayerViewModel>())
        }

        composable(Routes.permissions.pattern) {
            val viewModel: PermissionsViewModel = hiltViewModel()
            HandleNavigation(viewModel, LocalNavController.current)
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            PermissionsScreen(
                state = state,
                onEvent = viewModel::onEvent,
                intentFor = viewModel::intentFor,
            )
        }
    }
}

/**
 * A bottom-tab destination.
 *
 * Cross-fades rather than slides. The four tabs are siblings with no spatial relationship, and a
 * horizontal slide between them would imply an order that does not exist.
 */
private fun NavGraphBuilder.tab(route: String, content: @Composable () -> Unit) {
    composable(
        route = route,
        enterTransition = { fadeIn(SafetyMotion.contentFade()) },
        exitTransition = { fadeOut(SafetyMotion.contentFade()) },
        popEnterTransition = { fadeIn(SafetyMotion.contentFade()) },
        popExitTransition = { fadeOut(SafetyMotion.contentFade()) },
    ) {
        content()
    }
}

private fun AnimatedContentTransitionScope<*>.pushEnter(): EnterTransition =
    slideInHorizontally(SafetyMotion.screenEnter()) { it / 4 } + fadeIn(SafetyMotion.screenEnter())

private fun AnimatedContentTransitionScope<*>.pushExit(): ExitTransition =
    slideOutHorizontally(SafetyMotion.screenExit()) { -it / 6 } + fadeOut(SafetyMotion.screenExit())

private fun AnimatedContentTransitionScope<*>.popEnter(): EnterTransition =
    slideInHorizontally(SafetyMotion.screenEnter()) { -it / 6 } + fadeIn(SafetyMotion.screenEnter())

private fun AnimatedContentTransitionScope<*>.popExit(): ExitTransition =
    slideOutHorizontally(SafetyMotion.screenExit()) { it / 4 } + fadeOut(SafetyMotion.screenExit())

/**
 * The nav controller, provided once by the shell.
 *
 * Screens take it from here rather than through a parameter, so a ViewModel's navigation actions
 * can be wired up in one line at the top of a screen instead of being threaded through every
 * composable between the shell and the destination.
 */
val LocalNavController = staticCompositionLocalOf<NavHostController> {
    error("No NavController provided")
}
