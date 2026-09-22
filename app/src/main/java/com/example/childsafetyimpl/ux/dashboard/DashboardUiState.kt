package com.example.childsafetyimpl.ux.dashboard

import com.example.childsafetyimpl.core.protection.HealthState
import com.example.childsafetyimpl.core.protection.ProtectionHealth
import com.example.childsafetyimpl.core.protection.ProtectionStatus
import com.example.childsafetyimpl.core.protection.ProtectionStatusProvider
import com.example.childsafetyimpl.domain.model.AppUsage
import com.example.childsafetyimpl.domain.model.SafetyEvent
import com.example.childsafetyimpl.ui.illustration.ShieldState

data class DashboardUiState(
    val loading: Boolean = true,
    val childName: String = "",
    val deviceLabel: String = "",
    val protectionEnabled: Boolean = false,

    val summary: ProtectionStatusProvider.Summary =
        ProtectionStatusProvider.Summary(0, 0, 0, 0, 0),
    /** Protections the parent switched on that cannot currently run. */
    val needsAttention: List<ProtectionStatus> = emptyList(),

    /** Null when usage access has not been granted. Never zero in that case. */
    val screenTimeMillis: Long? = null,
    val screenTimeUnavailableReason: String? = null,
    val screenTimeLimitMinutes: Int = 240,
    val topApps: List<AppUsage> = emptyList(),
    val weekMillis: List<Pair<Long, Long>> = emptyList(),

    val eventsToday: Int = 0,
    val seriousEventsToday: Int = 0,
    val blockedToday: Int = 0,
    val surfacesClosedToday: Int = 0,
    val recentEvents: List<SafetyEvent> = emptyList(),

    /** Whether protection is actually working, from [ProtectionHealth]. Null until first built. */
    val health: ProtectionHealth? = null,

    /** What the parent asked for. */
    val internetPaused: Boolean = false,
    /** Whether the pause is in force; the gap between the two is a health issue. */
    val internetPauseEnforced: Boolean = false,
    /** False in the Private DNS-only posture, which has nothing in the traffic path to stop. */
    val pauseAvailable: Boolean = true,
    /** Set when a pause could not be applied, so the card can say why. */
    val pauseMessage: String? = null,
) {

    /**
     * Health issues that are not already on screen.
     *
     * A protection missing its permission has its own card below, so repeating it here would put
     * the same fact on the screen twice.
     */
    val healthIssues: List<ProtectionHealth.Issue>
        get() = health?.issues.orEmpty()
            .filter { it.kind != ProtectionHealth.IssueKind.PROTECTION_IMPAIRED }

    /** True when something other than a missing permission is wrong. */
    private val healthProblem: Boolean
        get() = protectionEnabled && healthIssues.isNotEmpty()

    val screenTimeFraction: Float
        get() {
            val used = screenTimeMillis ?: return 0f
            val limit = screenTimeLimitMinutes * 60_000L
            if (limit <= 0) return 0f
            return (used.toFloat() / limit).coerceIn(0f, 1f)
        }

    val screenTimeAvailable: Boolean get() = screenTimeMillis != null

    /**
     * The shield's state.
     *
     * Anything the parent asked for that cannot run pulls this down to partial. A shield that
     * reads as fully protecting while a protection is blocked would be the single most misleading
     * thing on the screen.
     */
    val shieldState: ShieldState
        get() = when {
            !protectionEnabled -> ShieldState.INACTIVE
            summary.blocked > 0 || summary.partial > 0 || healthProblem -> ShieldState.PARTIAL
            summary.active == 0 -> ShieldState.INACTIVE
            else -> ShieldState.ACTIVE
        }

    val coverage: Float get() = summary.fractionRunning

    val headline: String
        get() = when {
            !protectionEnabled -> "Protection is off"
            // Stopped or tampered with outranks a missing permission: it is the bigger gap.
            healthProblem && health?.state != HealthState.DEGRADED -> health!!.headline
            summary.blocked > 0 -> "${summary.blocked} " +
                (if (summary.blocked == 1) "protection needs" else "protections need") +
                " a permission"
            healthProblem -> health!!.headline
            summary.partial > 0 -> "Protecting, with room to improve"
            else -> "Everything is protecting"
        }
}

sealed interface DashboardUiEvent {
    data object OpenProtections : DashboardUiEvent
    data object OpenPermissions : DashboardUiEvent
    data object OpenEvents : DashboardUiEvent
    data object OpenApps : DashboardUiEvent
    data object OpenBrowser : DashboardUiEvent
    data object OpenPlayer : DashboardUiEvent
    data class ToggleProtection(val enabled: Boolean) : DashboardUiEvent
    data object ToggleInternetPause : DashboardUiEvent
    data object OpenControls : DashboardUiEvent
    data object Refresh : DashboardUiEvent
}
