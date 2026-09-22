package com.example.childsafetyimpl.ux.protection

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.childsafetyimpl.core.common.DataResult
import com.example.childsafetyimpl.core.common.TimeFormat
import com.example.childsafetyimpl.data.local.AiInteractionEntity
import com.example.childsafetyimpl.domain.model.AppUsage
import com.example.childsafetyimpl.navigation.HandleNavigation
import com.example.childsafetyimpl.navigation.LocalNavController
import com.example.childsafetyimpl.platform.ai.AiPromptRiskScorer
import com.example.childsafetyimpl.ui.components.FilterPill
import com.example.childsafetyimpl.ui.components.IconBadge
import com.example.childsafetyimpl.ui.components.MeterBar
import com.example.childsafetyimpl.ui.components.SecondaryButton
import com.example.childsafetyimpl.ui.components.TonalChip
import com.example.childsafetyimpl.ui.theme.SafetyPalette
import com.example.childsafetyimpl.ux.common.BulletLine
import com.example.childsafetyimpl.ux.common.OutcomeCard
import com.example.childsafetyimpl.ux.common.OutcomeTone
import com.example.childsafetyimpl.ux.common.ProtectionScaffold
import com.example.childsafetyimpl.ux.common.ProtectionSection
import com.example.childsafetyimpl.ux.common.SettingSwitchRow

@Composable
fun AiProtectionScreen(viewModel: AiProtectionViewModel) {
    HandleNavigation(viewModel, LocalNavController.current)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val status = state.status ?: return

    val breakdown = (state.breakdown as? DataResult.Success)?.data
    val unavailable = state.breakdown as? DataResult.Unavailable

    ProtectionScaffold(
        status = status,
        onBack = { viewModel.onEvent(AiProtectionUiEvent.Back) },
        onToggle = { viewModel.onEvent(AiProtectionUiEvent.ToggleFeature(it)) },
        heroStats = listOf(
            TimeFormat.duration(breakdown?.aiMillis ?: 0) to "AI today",
            TimeFormat.duration(breakdown?.gameMillis ?: 0) to "Games today",
            state.concerningToday.toString() to "To review",
        ),
    ) {
        when {
            unavailable != null -> OutcomeCard(
                headline = "Time cannot be measured",
                detail = unavailable.reason,
                tone = OutcomeTone.WARNING,
                footnote = "Without it, no budget can be enforced and no time can be shown. " +
                    "This screen shows nothing rather than showing zero.",
            )

            state.concerningToday > 0 -> OutcomeCard(
                headline = "${state.concerningToday} conversations to review",
                detail = "Questions asked of an AI today matched one of the risk categories below.",
                tone = OutcomeTone.BLOCKED,
                footnote = "What was typed is not stored. Only the categories it matched.",
            )

            else -> OutcomeCard(
                headline = "Nothing concerning today",
                detail = "AI conversations are being checked and nothing has matched a risk " +
                    "category.",
                tone = OutcomeTone.SUCCESS,
            )
        }

        ProtectionSection(title = "Daily budgets", icon = Icons.Rounded.Timer) {
            Text(
                text = "A budget covers the whole category rather than one app, so three AI apps " +
                    "share one allowance instead of having three.",
                style = MaterialTheme.typography.bodySmall,
                color = SafetyPalette.Slate,
            )

            Spacer(Modifier.height(15.dp))
            BudgetPicker(
                label = "AI assistants",
                minutes = state.aiBudgetMinutes,
                usedMillis = breakdown?.aiMillis ?: 0,
                onSelect = { viewModel.onEvent(AiProtectionUiEvent.SetAiBudget(it)) },
            )

            Spacer(Modifier.height(18.dp))
            BudgetPicker(
                label = "Games",
                minutes = state.gameBudgetMinutes,
                usedMillis = breakdown?.gameMillis ?: 0,
                onSelect = { viewModel.onEvent(AiProtectionUiEvent.SetGameBudget(it)) },
            )
        }

        ProtectionSection(title = "Conversation checks", icon = Icons.Rounded.Psychology) {
            SettingSwitchRow(
                title = "Check what is asked",
                description = "Reads the conversation screen of an AI app and scores it. The " +
                    "question is never saved, only what it matched.",
                checked = state.conversationScanEnabled,
                onCheckedChange = {
                    viewModel.onEvent(AiProtectionUiEvent.ToggleConversationScan(it))
                },
                enabled = state.serviceConnected,
            )

            if (!state.serviceConnected) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Screen protection is not switched on, so conversations cannot be " +
                        "read. Time in AI apps is still measured and budgets still apply.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SafetyPalette.Warning,
                )
            }

            Spacer(Modifier.height(14.dp))
            Text(
                text = "Watching ${state.watchedPhraseCount} phrases across " +
                    "${state.watchedCategories.size} categories",
                style = MaterialTheme.typography.labelMedium,
                color = SafetyPalette.Slate,
            )
            Spacer(Modifier.height(9.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                state.watchedCategories.forEach { category ->
                    TonalChip(
                        text = category,
                        content = SafetyPalette.IndigoDeep,
                        container = SafetyPalette.IndigoSoft,
                    )
                }
            }
        }

        if (breakdown != null && breakdown.aiApps.isNotEmpty()) {
            ProtectionSection(
                title = "AI apps used today",
                icon = Icons.Rounded.Psychology,
                trailing = TimeFormat.duration(breakdown.aiMillis),
            ) {
                breakdown.aiApps.forEach { UsageRow(it, SafetyPalette.Lavender) }
            }
        }

        if (breakdown != null && breakdown.gameApps.isNotEmpty()) {
            ProtectionSection(
                title = "Games played today",
                icon = Icons.Rounded.SportsEsports,
                trailing = TimeFormat.duration(breakdown.gameMillis),
            ) {
                breakdown.gameApps.forEach { UsageRow(it, SafetyPalette.Sky) }
            }
        }

        ProtectionSection(
            title = "What was asked",
            icon = Icons.Rounded.WarningAmber,
            trailing = "${state.interactions.size} recorded",
        ) {
            if (state.interactions.isEmpty()) {
                Text(
                    text = "Nothing recorded yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SafetyPalette.Slate,
                )
            } else {
                state.interactions.take(15).forEach { InteractionRow(it) }
                Spacer(Modifier.height(12.dp))
                SecondaryButton(
                    text = "Clear this history",
                    onClick = { viewModel.onEvent(AiProtectionUiEvent.ClearHistory) },
                    icon = Icons.Rounded.DeleteOutline,
                    contentColor = SafetyPalette.Danger,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        ProtectionSection(title = "What is kept", icon = Icons.Rounded.Lock) {
            BulletLine(
                "The question a child types is scored in memory and then dropped. It is never " +
                    "written to storage and never leaves the device.",
            )
            BulletLine(
                "What is kept is how long the question was, which app it was asked in, and which " +
                    "risk categories it matched.",
            )
            BulletLine(
                "The scorer matches phrases, so it catches direct questions and will miss ones " +
                    "phrased carefully or asked in another language.",
            )
            BulletLine(
                "An app is treated as an AI app if it is on this app's list or declares itself " +
                    "as one. A new AI app nobody has heard of yet is not recognised until the " +
                    "list is updated.",
            )
        }
    }
}

@Composable
private fun BudgetPicker(
    label: String,
    minutes: Int?,
    usedMillis: Long,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = SafetyPalette.Ink,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (minutes == null) {
                    "${TimeFormat.duration(usedMillis)} used"
                } else {
                    "${TimeFormat.duration(usedMillis)} of ${TimeFormat.durationFromMinutes(minutes)}"
                },
                style = MaterialTheme.typography.labelMedium,
                color = SafetyPalette.Slate,
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterPill(
                text = "No limit",
                selected = minutes == null,
                onClick = { onSelect(null) },
            )
            BUDGET_CHOICES.forEach { choice ->
                FilterPill(
                    text = "$choice min",
                    selected = minutes == choice,
                    onClick = { onSelect(choice) },
                )
            }
        }

        if (minutes != null) {
            Spacer(Modifier.height(11.dp))
            val fraction = (usedMillis.toFloat() / (minutes * 60_000f)).coerceIn(0f, 1f)
            MeterBar(
                progress = fraction,
                color = if (fraction >= 1f) SafetyPalette.Danger else SafetyPalette.Indigo,
            )
        }
    }
}

@Composable
private fun UsageRow(usage: AppUsage, accent: androidx.compose.ui.graphics.Color) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(
            icon = Icons.Rounded.Psychology,
            tint = accent,
            container = accent.copy(alpha = 0.12f),
            size = 32,
        )
        Spacer(Modifier.size(11.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = usage.label,
                style = MaterialTheme.typography.titleSmall,
                color = SafetyPalette.Ink,
            )
            Text(
                text = "${usage.sessionCount} " +
                    if (usage.sessionCount == 1) "session" else "sessions",
                style = MaterialTheme.typography.labelSmall,
                color = SafetyPalette.Mist,
            )
        }
        Text(
            text = TimeFormat.duration(usage.foregroundMillis),
            style = MaterialTheme.typography.titleSmall,
            color = SafetyPalette.Ink,
        )
    }
}

@Composable
private fun InteractionRow(interaction: AiInteractionEntity) {
    val concerning = interaction.riskScore >= AiPromptRiskScorer.ALERT_THRESHOLD
    val tags = interaction.riskTags.split(',').filter { it.isNotBlank() }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        IconBadge(
            icon = if (concerning) Icons.Rounded.WarningAmber else Icons.Rounded.Psychology,
            tint = if (concerning) SafetyPalette.Danger else SafetyPalette.Slate,
            container = if (concerning) SafetyPalette.DangerSoft else SafetyPalette.Canvas,
            size = 32,
        )
        Spacer(Modifier.size(11.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = interaction.appLabel,
                style = MaterialTheme.typography.titleSmall,
                color = SafetyPalette.Ink,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = if (tags.isEmpty()) {
                    "Nothing matched. ${interaction.promptLength} characters."
                } else {
                    "Matched ${tags.joinToString(", ")}."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (concerning) SafetyPalette.Danger else SafetyPalette.Slate,
            )
        }
        Text(
            text = TimeFormat.relative(interaction.atMillis),
            style = MaterialTheme.typography.labelSmall,
            color = SafetyPalette.Mist,
        )
    }
}

private val BUDGET_CHOICES = listOf(15, 30, 45, 60, 90, 120)
