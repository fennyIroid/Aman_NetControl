package com.example.childsafetyimpl.ux.events

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.childsafetyimpl.domain.model.EventSeverity
import com.example.childsafetyimpl.ui.components.CircleIconButton
import com.example.childsafetyimpl.ui.components.FilterPill
import com.example.childsafetyimpl.ui.components.HeroHeader
import com.example.childsafetyimpl.ui.components.HeroStatRow
import com.example.childsafetyimpl.ui.components.SafetyCard
import com.example.childsafetyimpl.ui.components.StaggeredEntrance
import com.example.childsafetyimpl.ui.illustration.ShieldPulse
import com.example.childsafetyimpl.ui.illustration.ShieldState
import com.example.childsafetyimpl.ui.theme.SafetyPalette
import com.example.childsafetyimpl.ux.common.BottomBarSpacer
import com.example.childsafetyimpl.ux.common.EventRow

/**
 * The safety event log.
 *
 * The count of events the app could see but not act on is given its own place in the header,
 * because that number is the honest measure of the gap between what protection notices and what
 * it can actually stop.
 */
@Composable
fun EventsScreen(
    state: EventsUiState,
    onFilter: (EventSeverity?) -> Unit,
    onClear: () -> Unit,
    onOpenCapability: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(SafetyPalette.Canvas),
    ) {
        item {
            HeroHeader {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Activity",
                            style = MaterialTheme.typography.headlineLarge,
                            color = Color.White,
                        )
                        Text(
                            text = "Everything the safety layer detected",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xCCFFFFFF),
                        )
                    }
                    CircleIconButton(onClick = onClear, icon = Icons.Rounded.DeleteSweep)
                }

                Spacer(Modifier.height(18.dp))

                HeroStatRow(
                    stats = listOf(
                        state.todayCount.toString() to "today",
                        state.seriousCount.toString() to "need attention",
                        state.notEnforceableCount.toString() to "not enforceable",
                    ),
                )

                Spacer(Modifier.height(4.dp))
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterPill(
                    text = "All",
                    selected = state.severityFilter == null,
                    onClick = { onFilter(null) },
                    count = state.events.size,
                )
                EventSeverity.entries.reversed().forEach { severity ->
                    val count = state.severityCounts[severity] ?: return@forEach
                    FilterPill(
                        text = severity.label,
                        selected = state.severityFilter == severity,
                        onClick = {
                            onFilter(if (state.severityFilter == severity) null else severity)
                        },
                        count = count,
                    )
                }
            }
        }

        if (state.filtered.isEmpty()) {
            item { EmptyEvents() }
        } else {
            items(state.filtered, key = { it.id }) { event ->
                StaggeredEntrance(index = state.filtered.indexOf(event).coerceAtMost(8)) {
                    SafetyCard(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                        onClick = event.capabilityId?.let { { onOpenCapability(it) } },
                    ) {
                        EventRow(event = event)
                    }
                }
            }
        }

        item { BottomBarSpacer() }
    }
}

@Composable
private fun EmptyEvents() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ShieldPulse(state = ShieldState.ACTIVE, size = 108.dp, coverage = 1f)
        Spacer(Modifier.height(20.dp))
        Text(
            text = "Nothing recorded yet",
            style = MaterialTheme.typography.headlineSmall,
            color = SafetyPalette.Ink,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Events appear here as the safety layer detects them. Turn on monitoring and " +
                "web filtering, then use the device normally for a while.",
            style = MaterialTheme.typography.bodyMedium,
            color = SafetyPalette.Slate,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
