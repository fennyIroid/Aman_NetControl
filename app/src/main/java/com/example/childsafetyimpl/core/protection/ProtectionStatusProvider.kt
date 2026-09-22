package com.example.childsafetyimpl.core.protection

import com.example.childsafetyimpl.core.common.TimeFormat
import com.example.childsafetyimpl.core.permission.SafetyPermission
import com.example.childsafetyimpl.data.prefs.SafetyPreferences
import com.example.childsafetyimpl.data.prefs.SafetySettings
import com.example.childsafetyimpl.domain.model.RestrictedSurface
import com.example.childsafetyimpl.platform.accessibility.SearchTermGuard
import com.example.childsafetyimpl.platform.accessibility.SurfaceGuard
import com.example.childsafetyimpl.platform.ai.AiActivityMonitor
import com.example.childsafetyimpl.platform.browser.SafeBrowserEngine
import com.example.childsafetyimpl.platform.clone.CloneGuard
import com.example.childsafetyimpl.platform.network.ChildSafetyVpnService
import com.example.childsafetyimpl.platform.network.VpnGuard
import com.example.childsafetyimpl.platform.permission.PermissionInspector
import com.example.childsafetyimpl.platform.projection.ScreenAnalysisService
import com.example.childsafetyimpl.platform.youtube.YouTubeSurface
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Assembles the live state of every protection.
 *
 * One class owns this question because the answer has to be consistent everywhere it is asked. A
 * protection card, the dashboard summary and the enforcement engine must never disagree about
 * whether something is running, and the surest way to guarantee that is for all three to read the
 * same computation.
 *
 * The rule that matters most is the one that produces [ProtectionState.NEEDS_PERMISSION]: a
 * protection the parent switched on, that cannot run because a grant is missing, must never look
 * the same as one they chose to leave off. That is the difference between a product that is honest
 * about its gaps and one a parent trusts for something it is not doing.
 */
@Singleton
class ProtectionStatusProvider @Inject constructor(
    private val preferences: SafetyPreferences,
    private val permissionInspector: PermissionInspector,
    private val vpnGuard: VpnGuard,
    private val cloneGuard: CloneGuard,
    private val surfaceGuard: SurfaceGuard,
    private val searchGuard: SearchTermGuard,
    private val aiMonitor: AiActivityMonitor,
    private val browserEngine: SafeBrowserEngine,
    private val youTubeSurface: YouTubeSurface,
) {

    /** Live status for every protection, recomputed whenever settings or live counters change. */
    fun observeAll(): Flow<List<ProtectionStatus>> = combine(
        preferences.settings,
        surfaceGuard.observeUsageToday(RestrictedSurface.SHORT_FORM_FEED),
        surfaceGuard.observeUsageToday(RestrictedSurface.STATUS_STORY),
        cloneGuard.findingCount,
        aiMonitor.observeConcerningCountToday(),
    ) { settings, shortForm, status, cloneCount, concerningAi ->
        ProtectionFeature.ordered.map { feature ->
            statusOf(
                feature = feature,
                settings = settings,
                headline = headlineFor(feature, shortForm.encounters, status.encounters, cloneCount, concerningAi),
                detail = detailFor(feature, settings),
            )
        }
    }

    fun observe(feature: ProtectionFeature): Flow<ProtectionStatus> =
        observeAll().map { list -> list.first { it.feature == feature } }

    /** A one-line summary of how much of the protection a parent switched on is actually running. */
    fun observeSummary(): Flow<Summary> = observeAll().map { statuses ->
        Summary(
            total = statuses.size,
            active = statuses.count { it.state == ProtectionState.ACTIVE },
            partial = statuses.count { it.state == ProtectionState.PARTIAL },
            blocked = statuses.count { it.state == ProtectionState.NEEDS_PERMISSION },
            off = statuses.count { it.state == ProtectionState.OFF },
        )
    }

    data class Summary(
        val total: Int,
        val active: Int,
        val partial: Int,
        val blocked: Int,
        val off: Int,
    ) {
        val running: Int get() = active + partial

        /** The number a parent should act on: protections asked for that cannot run. */
        val needsAttention: Int get() = blocked

        val fractionRunning: Float
            get() = if (total == 0) 0f else running.toFloat() / total
    }

    private fun statusOf(
        feature: ProtectionFeature,
        settings: SafetySettings,
        headline: String,
        detail: String,
    ): ProtectionStatus {
        val missingRequired = feature.requires.filterNot { permissionInspector.stateOf(it).isGranted }
        val missingOptional =
            feature.strengthenedBy.filterNot { permissionInspector.stateOf(it).isGranted }

        val enabled = settings.isEnabled(feature)

        val state = when {
            !enabled -> ProtectionState.OFF
            missingRequired.isNotEmpty() -> ProtectionState.NEEDS_PERMISSION
            missingOptional.isNotEmpty() -> ProtectionState.PARTIAL
            else -> ProtectionState.ACTIVE
        }

        return ProtectionStatus(
            feature = feature,
            enabled = enabled,
            state = state,
            missingRequired = missingRequired,
            missingOptional = missingOptional,
            headline = when (state) {
                ProtectionState.OFF -> "Switched off"
                ProtectionState.NEEDS_PERMISSION ->
                    "${missingRequired.first().title} is needed"
                else -> headline
            },
            detail = when (state) {
                ProtectionState.NEEDS_PERMISSION -> missingRequired.first().withoutIt
                ProtectionState.PARTIAL ->
                    "Running. ${missingOptional.first().title} would make it stronger."
                ProtectionState.OFF -> feature.summary
                ProtectionState.ACTIVE -> detail
            },
        )
    }

    /** The live number worth putting on a card, once the protection is actually running. */
    private fun headlineFor(
        feature: ProtectionFeature,
        shortFormEncounters: Int,
        statusEncounters: Int,
        cloneCount: Int,
        concerningAi: Int,
    ): String = when (feature) {
        ProtectionFeature.VPN_RESTRICTION -> {
            val posture = vpnGuard.posture.value
            when {
                posture.foreignTunnelActive -> "A VPN is running now"
                posture.installedVpnApps.isEmpty() -> "No VPN apps installed"
                else -> "${posture.installedVpnApps.size} VPN apps watched"
            }
        }

        ProtectionFeature.CLONE_CONTROL ->
            when (cloneCount) {
                0 -> "Nothing cloned"
                1 -> "1 finding"
                else -> "$cloneCount findings"
            }

        ProtectionFeature.SHORT_FORM_CONTROL ->
            if (shortFormEncounters == 0) "Not opened today"
            else "$shortFormEncounters today"


        ProtectionFeature.STATUS_CONTROL ->
            if (statusEncounters == 0) "Not opened today" else "$statusEncounters today"

        ProtectionFeature.AI_GAME_MONITORING ->
            if (concerningAi == 0) "Nothing concerning" else "$concerningAi to review"

        ProtectionFeature.APP_RESTRICTION -> "Watching every app"

        ProtectionFeature.SEARCH_FILTERING -> {
            val forced = ChildSafetyVpnService.State.counters.value.safeSearchForced
            val closed = searchGuard.blockedToday.value
            when {
                closed == 1 -> "1 search closed"
                closed > 1 -> "$closed searches closed"
                forced > 0 -> "SafeSearch forced $forced times"
                else -> "SafeSearch enforced"
            }
        }

        ProtectionFeature.AD_REMOVAL -> {
            val tunnel = ChildSafetyVpnService.State.counters.value.adsBlocked
            val browser = browserEngine.stats.value.adsBlocked
            val player = youTubeSurface.stats.value.adsBlocked
            val total = tunnel + browser + player
            if (total == 0) "Ready" else "$total ads removed"
        }

        ProtectionFeature.IMAGE_BLURRING -> {
            val covered = browserEngine.stats.value.imagesCovered
            if (covered == 0) "Nothing blurred yet" else "$covered images blurred"
        }

        ProtectionFeature.VIDEO_BLURRING -> {
            val metrics = ScreenAnalysisService.State.metrics.value
            when {
                !ScreenAnalysisService.State.running.value -> "Waiting for permission each session"
                metrics.blurEvents == 0 -> "Watching"
                else -> "${metrics.blurEvents} covered"
            }
        }
    }

    private fun detailFor(feature: ProtectionFeature, settings: SafetySettings): String =
        when (feature) {
            ProtectionFeature.VPN_RESTRICTION -> buildString {
                append("VPN apps are closed on open")
                if (settings.reclaimTunnel) append(" and the tunnel is taken back")
                append(".")
            }

            ProtectionFeature.CLONE_CONTROL ->
                if (settings.blockCloneTools) {
                    "Cloning tools are closed on open, and new clones raise an alert."
                } else {
                    "New clones raise an alert. Closing them is switched off."
                }

            ProtectionFeature.SHORT_FORM_CONTROL ->
                allowanceText(settings.shortFormAllowanceMinutes, "Shorts and Reels")

            ProtectionFeature.STATUS_CONTROL ->
                allowanceText(settings.statusAllowanceMinutes, "Status and Stories")

            ProtectionFeature.AI_GAME_MONITORING -> buildString {
                append(budgetText("AI", settings.aiDailyBudgetMinutes))
                append(" ")
                append(budgetText("Games", settings.gameDailyBudgetMinutes))
                if (settings.aiConversationScanEnabled) {
                    append(" Conversations are checked for risk.")
                }
            }

            ProtectionFeature.SEARCH_FILTERING -> buildString {
                if (settings.safeSearchEnabled) {
                    append("SafeSearch is forced for every browser on the device")
                } else {
                    append("SafeSearch forcing is switched off")
                }
                append(if (settings.searchTermGuardEnabled) {
                    ", and a restricted search closes the browser."
                } else {
                    ". Watching what is searched for is switched off."
                })
            }

            ProtectionFeature.APP_RESTRICTION ->
                "A blocked app is closed each time it is opened, usually within two seconds."

            ProtectionFeature.AD_REMOVAL ->
                "Ads are removed in the built-in player and browser" +
                    if (ChildSafetyVpnService.State.running.value) ", and device-wide." else "."

            ProtectionFeature.IMAGE_BLURRING ->
                "Images are scored before the page receives them, at " +
                    "${(settings.blurStrength * 100).toInt()}% blur."

            ProtectionFeature.VIDEO_BLURRING ->
                "Frames are checked on this device and the picture is covered while unsafe."
        }

    private fun allowanceText(minutes: Int?, what: String): String = when {
        minutes == null -> "$what are closed the moment they open."
        else -> "$what are allowed ${TimeFormat.durationFromMinutes(minutes)} a day."
    }

    private fun budgetText(what: String, minutes: Int?): String = when {
        minutes == null -> "$what: no limit."
        else -> "$what: ${TimeFormat.durationFromMinutes(minutes)} a day."
    }

    /** Every grant any switched-on protection is still waiting for. */
    fun observeMissingPermissions(): Flow<List<SafetyPermission>> = observeAll().map { statuses ->
        statuses
            .filter { it.enabled }
            .flatMap { it.missingRequired }
            .distinct()
    }
}
