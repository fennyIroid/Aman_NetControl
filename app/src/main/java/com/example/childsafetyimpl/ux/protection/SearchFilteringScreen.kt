package com.example.childsafetyimpl.ux.protection

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.childsafetyimpl.core.common.TimeFormat
import com.example.childsafetyimpl.navigation.HandleNavigation
import com.example.childsafetyimpl.navigation.LocalNavController
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
 * which half is running. SafeSearch is forced at the name-lookup layer and needs the tunnel; the
 * search itself is read on screen and needs the accessibility service. Either can be running
 * without the other, so the card says which.
 */
@Composable
fun SearchFilteringScreen(viewModel: SearchFilteringViewModel) {
    HandleNavigation(viewModel, LocalNavController.current)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val status = state.status ?: return

    ProtectionScaffold(
        status = status,
        onBack = { viewModel.onEvent(SearchFilteringUiEvent.Back) },
        onToggle = { viewModel.onEvent(SearchFilteringUiEvent.ToggleFeature(it)) },
        heroStats = listOf(
            state.searchesClosed.toString() to "Searches closed",
            state.safeSearchForced.toString() to "SafeSearch forced",
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

            !state.tunnelRunning -> OutcomeCard(
                headline = "SafeSearch is forced only inside this app",
                detail = "Forcing SafeSearch in Chrome and every other browser happens at the " +
                    "name-lookup layer, which needs the filtering tunnel. It is not running, so " +
                    "outside this app the search engine's own setting applies.",
                tone = OutcomeTone.WARNING,
                footnote = "Searches for restricted things are still read and closed.",
            )

            else -> OutcomeCard(
                headline = "SafeSearch is forced device-wide",
                detail = "Google, Bing, DuckDuckGo and YouTube are answered with the filtered " +
                    "address each one publishes, so SafeSearch cannot be switched off from " +
                    "inside any browser on this device.",
                tone = OutcomeTone.SUCCESS,
            )
        }

        ProtectionSection(title = "Settings", icon = Icons.Rounded.Tune) {
            SettingSwitchRow(
                title = "Force SafeSearch everywhere",
                description = "Answers search-engine lookups with the filtered address they " +
                    "publish. Applies to every app, not only this one.",
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
            DetailRow("SafeSearch answers given", state.safeSearchForced.toString())
            DetailRow("Searches closed", state.searchesClosed.toString())
            DetailRow(
                "Encrypted DNS",
                if (state.tunnelRunning) "Refused, so lookups stay filtered" else "Not filtered",
            )

            Spacer(Modifier.height(14.dp))
            if (!state.tunnelRunning) {
                SecondaryButton(
                    text = "Start the filtering tunnel",
                    onClick = { viewModel.onEvent(SearchFilteringUiEvent.OpenVpn) },
                    icon = Icons.Rounded.VpnKey,
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
