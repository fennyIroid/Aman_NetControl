package com.example.childsafetyimpl.ux.protection

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Router
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.childsafetyimpl.core.common.TimeFormat
import com.example.childsafetyimpl.navigation.HandleNavigation
import com.example.childsafetyimpl.navigation.LocalNavController
import com.example.childsafetyimpl.platform.network.PrivateDnsGuard
import com.example.childsafetyimpl.ui.components.DetailRow
import com.example.childsafetyimpl.ui.components.SecondaryButton
import com.example.childsafetyimpl.ux.common.BulletLine
import com.example.childsafetyimpl.ux.common.OutcomeCard
import com.example.childsafetyimpl.ux.common.OutcomeTone
import com.example.childsafetyimpl.ux.common.ProtectionScaffold
import com.example.childsafetyimpl.ux.common.ProtectionSection
import com.example.childsafetyimpl.ux.common.SettingSwitchRow

/**
 * Search and site filtering.
 *
 * The screen exists because the honest answer to "why did Chrome show that" has two halves that
 * work in completely different ways, and a parent cannot judge the protection without being told
 * which half is running. Sites and SafeSearch are handled by the Private DNS resolver, and only as
 * far as that resolver goes; the search itself, and the parent's own site lists, are read on
 * screen and need the accessibility service. Either can be running without the other, so the card
 * says which.
 */
@Composable
fun SearchFilteringScreen(viewModel: SearchFilteringViewModel) {
    HandleNavigation(viewModel, LocalNavController.current)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Private DNS has no change notification, so it is re-read whenever this screen comes back.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.onEvent(SearchFilteringUiEvent.RefreshPrivateDns)
        }
    }

    val status = state.status ?: return

    ProtectionScaffold(
        status = status,
        onBack = { viewModel.onEvent(SearchFilteringUiEvent.Back) },
        onToggle = { viewModel.onEvent(SearchFilteringUiEvent.ToggleFeature(it)) },
        heroStats = listOf(
            state.searchesClosed.toString() to "Searches closed",
            (if (state.privateDnsFiltering) "On" else "Off") to "Private DNS",
            state.termCount.toString() to "Terms watched",
        ),
    ) {
        val blocked = state.lastBlocked
        when {
            blocked != null -> OutcomeCard(
                headline = "A search was closed in ${blocked.appLabel}",
                detail = blocked.host?.let { "$it is refused on this device, so the browser was closed." }
                    ?: "The search matched the ${blocked.category.lowercase()} list, so the " +
                    "browser was closed and the child was told why.",
                tone = OutcomeTone.BLOCKED,
                footnote = "At ${TimeFormat.clock(blocked.atMillis)}. What was searched for was " +
                    "judged in memory and never written down.",
            )

            !state.privateDnsFiltering -> OutcomeCard(
                headline = "Other apps are not being filtered",
                detail = "Sites and SafeSearch are filtered by the resolver Android's Private " +
                    "DNS points at, and it is not pointed at one that filters. Outside the " +
                    "built-in browser, harmful sites load and the search engine's own setting " +
                    "applies.",
                tone = OutcomeTone.WARNING,
                footnote = "Searches for restricted things, and your own blocked sites, are " +
                    "still read and closed in browsers.",
            )

            state.resolver?.forcesSafeSearch == false -> OutcomeCard(
                headline = "Sites are filtered, SafeSearch is not forced",
                detail = "${state.resolver?.label} refuses harmful sites for every app but does " +
                    "not force SafeSearch, so search results follow the search engine's own " +
                    "setting outside the built-in browser.",
                tone = OutcomeTone.WARNING,
                footnote = "CleanBrowsing Family forces it. Change the resolver on Controls.",
            )

            else -> OutcomeCard(
                headline = "Every app is filtered",
                detail = "Name lookups from every app go to " +
                    (state.resolver?.label ?: "a filtering resolver") + " through Private DNS, " +
                    "which refuses harmful sites" +
                    when {
                        state.resolver?.restrictsYouTube == true ->
                            " and forces SafeSearch on Google, Bing and YouTube, so it cannot " +
                                "be switched off from inside a browser."
                        state.resolver?.forcesSafeSearch == true ->
                            " and forces SafeSearch on Google and Bing. YouTube is not put " +
                                "in Restricted Mode by this resolver."
                        else -> "."
                    },
                tone = OutcomeTone.SUCCESS,
            )
        }

        ProtectionSection(title = "Settings", icon = Icons.Rounded.Tune) {
            SettingSwitchRow(
                title = "Force SafeSearch in the built-in browser",
                description = "Rewrites searches in this app's own browser to their SafeSearch " +
                    "form. In every other app SafeSearch is the resolver's job.",
                checked = state.safeSearchEnabled,
                onCheckedChange = { viewModel.onEvent(SearchFilteringUiEvent.ToggleSafeSearch(it)) },
            )
            Spacer(Modifier.height(6.dp))
            SettingSwitchRow(
                title = "Close restricted searches",
                description = "Reads the address bar and search box of other browsers, and leaves " +
                    "the browser when what is being searched for is restricted.",
                checked = state.termGuardEnabled,
                onCheckedChange = { viewModel.onEvent(SearchFilteringUiEvent.ToggleTermGuard(it)) },
            )
        }

        ProtectionSection(title = "What is being watched", icon = Icons.Rounded.SearchOff) {
            DetailRow("Browsers recognised", state.browserCount.toString())
            DetailRow("Restricted terms", state.termCount.toString())
            DetailRow("Searches closed", state.searchesClosed.toString())
            DetailRow(
                "Resolver",
                when (val posture = state.privateDnsPosture) {
                    is PrivateDnsGuard.Posture.Filtering ->
                        state.resolver?.label ?: posture.host
                    else -> "Not filtering"
                },
            )

            Spacer(Modifier.height(14.dp))
            if (!state.privateDnsFiltering) {
                SecondaryButton(
                    text = "Set up Private DNS",
                    onClick = { viewModel.onEvent(SearchFilteringUiEvent.OpenControls) },
                    icon = Icons.Rounded.Router,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
            }
            SecondaryButton(
                text = "Open the filtered browser",
                onClick = { viewModel.onEvent(SearchFilteringUiEvent.OpenBrowser) },
                icon = Icons.Rounded.Language,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        ProtectionSection(title = "What to expect", icon = Icons.Rounded.Insights) {
            BulletLine(
                "Sites are judged by the resolver, so what is refused is that resolver's list. " +
                    "Your own blocked and allowed sites cannot reach a public resolver; they are " +
                    "checked against the address bar of every browser instead, which closes the " +
                    "browser shortly after the page starts to load rather than before.",
            )
            BulletLine(
                "A browser with its own secure-DNS setting, or a site reached by its raw " +
                    "address, can step around Private DNS. Restricting other browsers from App " +
                    "restriction closes most of that.",
            )
            BulletLine(
                "SafeSearch is the search engine's own judgement. It removes explicit imagery " +
                    "from results and image search, and it does not pretend to be a topic " +
                    "filter: a search for a drug name still returns encyclopaedia entries.",
            )
            BulletLine(
                "Restricted searches are caught by reading the address bar, the search box and " +
                    "the title of a results page. The body of a page is never read, so an " +
                    "article that happens to mention a drug does not close the browser.",
            )
            BulletLine(
                "A restricted search closes the browser rather than going back, because back " +
                    "returns to the search box with the words still in it.",
            )
            BulletLine(
                "The term list is a floor, not a complete one. A child determined to phrase a " +
                    "search around it will manage; what this catches is the ordinary case.",
            )
            BulletLine(
                "Nothing that was searched for is stored. The event log records the browser and " +
                    "the category that matched, and the words are dropped where they were judged.",
            )
            BulletLine(
                "The strongest version of this is to restrict other browsers entirely from App " +
                    "restriction, and leave the built-in browser as the way online.",
            )
        }
    }
}
