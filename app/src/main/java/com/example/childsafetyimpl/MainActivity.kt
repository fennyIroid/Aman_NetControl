 package com.example.childsafetyimpl

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.childsafetyimpl.navigation.LocalNavController
import com.example.childsafetyimpl.navigation.Routes
import com.example.childsafetyimpl.navigation.SafetyNavGraph
import com.example.childsafetyimpl.ui.theme.ChildSafetyTheme
import com.example.childsafetyimpl.ui.theme.SafetyMotion
import com.example.childsafetyimpl.ui.theme.SafetyPalette
import com.example.childsafetyimpl.ux.common.SafetyBottomBar
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: AppShellViewModel by viewModels()

    /**
     * A destination asked for from outside the app.
     *
     * Held as state rather than read from the intent inside composition, so that an intent
     * arriving while the app is already open is honoured too. Cleared once navigated, or the same
     * destination would be pushed again on every recomposition.
     */
    private var requestedRoute by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Hold the splash until the stored profile has been read, so the app never flashes the
        // onboarding flow at a parent who completed it weeks ago.
        splash.setKeepOnScreenCondition { viewModel.uiState.value.loading }

        requestedRoute = intent?.getStringExtra(EXTRA_ROUTE)

        setContent {
            ChildSafetyTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                if (!state.loading) {
                    ChildSafetyApp(
                        startDestination = if (state.onboardingComplete) {
                            Routes.dashboard()
                        } else {
                            Routes.onboarding()
                        },
                        // Never mid-onboarding: a parent who has not finished setting the app up
                        // has nothing to grant yet, and dropping them into a permissions list
                        // loses the profile they were halfway through creating.
                        requestedRoute = requestedRoute.takeIf { state.onboardingComplete },
                        onRouteHandled = { requestedRoute = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedRoute = intent.getStringExtra(EXTRA_ROUTE)
    }

    companion object {
        private const val EXTRA_ROUTE = "com.example.childsafetyimpl.route"

        /**
         * Opens the app on one screen.
         *
         * Used where something outside the app has to hand the parent the place that answers it:
         * the block screen's "ask a parent", which is useless if it lands on the dashboard.
         */
        fun intent(context: Context, route: String): Intent =
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_ROUTE, route)
    }
}

/**
 * The app shell.
 *
 * The bottom bar belongs here rather than inside each screen, so a tab switch never rebuilds it
 * and the selected-pill animation carries continuously across the change.
 */
@Composable
private fun ChildSafetyApp(
    startDestination: String,
    requestedRoute: String? = null,
    onRouteHandled: () -> Unit = {},
) {
      val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    LaunchedEffect(requestedRoute) {
        val route = requestedRoute ?: return@LaunchedEffect
        if (route != currentRoute) {
            navController.navigate(route) { launchSingleTop = true }
        }
        onRouteHandled()
    }

    CompositionLocalProvider(LocalNavController provides navController) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(SafetyPalette.Canvas),
        ) {
            SafetyNavGraph(
                navController = navController,
                startDestination = startDestination,
                modifier = Modifier.fillMaxSize(),
            )

            AnimatedVisibility(
                visible = currentRoute in Routes.bottomTabs,
                enter = slideInVertically(SafetyMotion.screenEnter()) { it } +
                    fadeIn(SafetyMotion.contentFade()),
                exit = slideOutVertically(SafetyMotion.screenExit()) { it } +
                    fadeOut(SafetyMotion.contentFade()),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                SafetyBottomBar(
                    currentRoute = currentRoute,
                    onSelect = { route ->
                        if (route != currentRoute) {
                            navController.navigate(route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                )
            }
        }
    }
}
