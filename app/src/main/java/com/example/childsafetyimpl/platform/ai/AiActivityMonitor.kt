package com.example.childsafetyimpl.platform.ai

import com.example.childsafetyimpl.core.common.DataResult
import com.example.childsafetyimpl.core.common.TimeFormat
import com.example.childsafetyimpl.data.local.AiInteractionDao
import com.example.childsafetyimpl.data.local.AiInteractionEntity
import com.example.childsafetyimpl.data.prefs.SafetyPreferences
import com.example.childsafetyimpl.data.source.KnownPackages
import com.example.childsafetyimpl.domain.model.AppCategory
import com.example.childsafetyimpl.domain.model.AppUsage
import com.example.childsafetyimpl.domain.model.EventAction
import com.example.childsafetyimpl.domain.model.EventSeverity
import com.example.childsafetyimpl.domain.model.SafetyEventType
import com.example.childsafetyimpl.domain.repository.SafetyEventRepository
import com.example.childsafetyimpl.platform.usage.AppInventoryProvider
import com.example.childsafetyimpl.platform.usage.UsageStatsProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI chat and game monitoring (first-priority protection).
 *
 * Two questions, answered by two different mechanisms because Android exposes them differently.
 *
 * **How long.** AI assistants and games are identified from the curated list and the platform's
 * own app category, and their foreground time comes from the usage event stream. This half works
 * with Usage Access alone and is the part that can enforce a budget.
 *
 * **What about.** When the accessibility service is running, the conversation surface of an AI app
 * is read and scored here. This is the half that can notice a child asking an AI about self-harm,
 * and it is the reason the protection exists rather than being a second screen-time counter.
 *
 * The privacy rule is absolute and is enforced by this class rather than by convention: the
 * conversation is scored inside [onConversation] and the strings are never written anywhere. What
 * reaches the database is a length, a score and the categories that matched. A parent is told that
 * their child asked an AI something in the self-harm category; they are not handed the sentence.
 */
@Singleton
class AiActivityMonitor @Inject constructor(
    private val interactionDao: AiInteractionDao,
    private val usageStatsProvider: UsageStatsProvider,
    private val inventory: AppInventoryProvider,
    private val eventRepository: SafetyEventRepository,
    private val preferences: SafetyPreferences,
    private val riskScorer: AiPromptRiskScorer,
) {

    private val _lastSeen = MutableStateFlow<Snapshot?>(null)
    val lastSeen: StateFlow<Snapshot?> = _lastSeen.asStateFlow()

    data class Snapshot(
        val packageName: String,
        val appLabel: String,
        val atMillis: Long,
        val promptLength: Int,
        val riskScore: Int,
        val tags: List<String>,
    )

    /**
     * The text nodes of an AI conversation screen, in reading order.
     *
     * Only the tail is scored. An AI app's screen carries the whole scrollback, and re-scoring
     * every visible message on every window change would raise the same alert once per scroll and
     * would count old messages as new ones.
     */
    suspend fun onConversation(packageName: String, appLabel: String, texts: List<String>) {
        if (!preferences.settings.first().aiConversationScanEnabled) return
        if (texts.isEmpty()) return

        val candidate = texts
            .asSequence()
            .filter { it.length in MIN_PROMPT_LENGTH..MAX_PROMPT_LENGTH }
            .lastOrNull()
            ?: return

        // Deduplication by content hash rather than by text: the hash is a number, so nothing
        // that could reconstruct the message survives this check either.
        val fingerprint = candidate.hashCode()
        if (fingerprint == lastFingerprint) return
        lastFingerprint = fingerprint

        val verdict = riskScorer.score(candidate)
        val now = System.currentTimeMillis()

        interactionDao.insert(
            AiInteractionEntity(
                packageName = packageName,
                appLabel = appLabel,
                atMillis = now,
                promptLength = candidate.length,
                riskScore = verdict.score,
                riskTags = verdict.tags.joinToString(","),
            )
        )

        _lastSeen.value = Snapshot(
            packageName = packageName,
            appLabel = appLabel,
            atMillis = now,
            promptLength = candidate.length,
            riskScore = verdict.score,
            tags = verdict.tags,
        )

        if (verdict.score >= AiPromptRiskScorer.ALERT_THRESHOLD) {
            eventRepository.record(
                type = SafetyEventType.COMMUNICATION_SIGNAL,
                title = "A concerning question was asked in $appLabel",
                // The question itself is deliberately absent. Only what it matched.
                detail = "The conversation matched: ${verdict.tags.joinToString(", ")}. " +
                    "What was typed is not stored anywhere, only this verdict.",
                packageName = packageName,
                appLabel = appLabel,
                severity = if (verdict.score >= AiPromptRiskScorer.URGENT_THRESHOLD) {
                    EventSeverity.CRITICAL
                } else {
                    EventSeverity.HIGH
                },
                action = EventAction.LOGGED,
                capabilityId = "ai_game_monitoring",
                dedupeWindowMillis = 3 * 60_000L,
            )
        }
    }

    private var lastFingerprint: Int = 0

    // --- time and budgets ---------------------------------------------------------------------

    /** Foreground time today across every AI app, and every game, separately. */
    fun todayBreakdown(): DataResult<Breakdown> {
        return when (val usage = usageStatsProvider.usageToday()) {
            is DataResult.Success -> DataResult.Success(breakdownOf(usage.data))
            is DataResult.Unavailable -> usage
            is DataResult.Error -> usage
            DataResult.Loading -> DataResult.Loading
        }
    }

    private fun breakdownOf(usage: List<AppUsage>): Breakdown {
        val ai = usage.filter { it.category == AppCategory.AI || KnownPackages.isAi(it.packageName) }
        val games = usage.filter {
            it.category == AppCategory.GAME || KnownPackages.isGame(it.packageName)
        }
        return Breakdown(
            aiApps = ai.sortedByDescending { it.foregroundMillis },
            gameApps = games.sortedByDescending { it.foregroundMillis },
            aiMillis = ai.sumOf { it.foregroundMillis },
            gameMillis = games.sumOf { it.foregroundMillis },
        )
    }

    data class Breakdown(
        val aiApps: List<AppUsage>,
        val gameApps: List<AppUsage>,
        val aiMillis: Long,
        val gameMillis: Long,
    ) {
        val anyActivity: Boolean get() = aiMillis > 0 || gameMillis > 0
    }

    /** Every AI app installed on the device, whether used today or not. */
    fun installedAiApps() = KnownPackages.ai.mapNotNull { inventory.describe(it) }

    fun installedGames() = KnownPackages.games.mapNotNull { inventory.describe(it) }

    fun observeRecent(limit: Int = 100): Flow<List<AiInteractionEntity>> =
        interactionDao.observeRecent(limit)

    fun observeCountToday(): Flow<Int> =
        interactionDao.observeCountSince(TimeFormat.startOfTodayMillis())

    fun observeConcerningCountToday(): Flow<Int> = interactionDao.observeConcerningCountSince(
        TimeFormat.startOfTodayMillis(),
        AiPromptRiskScorer.ALERT_THRESHOLD,
    )

    /** True when the category's budget for today is spent, which is what triggers a block. */
    fun budgetExhausted(category: AppCategory, budgetMinutes: Int?): Boolean {
        if (budgetMinutes == null) return true
        if (budgetMinutes <= 0) return true
        val used = usageStatsProvider.todayMillisForCategory(category)
        return used >= budgetMinutes * 60_000L
    }

    suspend fun clearHistory() = interactionDao.clear()

    private companion object {
        /** Below this a string is a button label, not a question. */
        const val MIN_PROMPT_LENGTH = 12

        /** Above this it is the model's answer being read back, not the child's prompt. */
        const val MAX_PROMPT_LENGTH = 600
    }
}
