package com.example.childsafetyimpl.platform.accessibility

import android.os.SystemClock
import com.example.childsafetyimpl.core.common.TimeFormat
import com.example.childsafetyimpl.core.protection.ProtectionFeature
import com.example.childsafetyimpl.data.local.SurfaceEncounterDao
import com.example.childsafetyimpl.data.local.SurfaceEncounterEntity
import com.example.childsafetyimpl.data.prefs.SafetyPreferences
import com.example.childsafetyimpl.data.prefs.SafetySettings
import com.example.childsafetyimpl.domain.model.EventAction
import com.example.childsafetyimpl.domain.model.RestrictedSurface
import com.example.childsafetyimpl.domain.model.SafetyEventType
import com.example.childsafetyimpl.domain.model.SurfaceUsage
import com.example.childsafetyimpl.domain.repository.SafetyEventRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides what happens when a restricted screen is recognised inside another app.
 *
 * The interesting behaviour is the allowance. A parent can refuse Shorts outright, or permit
 * fifteen minutes of it a day, and those are genuinely different products: the second one needs
 * the time in the feed to be measured, not merely the entry to be counted. So the guard keeps an
 * open session per surface, closes it when the child leaves, and writes the elapsed time to the
 * database, where the next allowance check reads it back.
 *
 * The decision is deliberately kept out of the accessibility service. The service runs on the main
 * thread on every window change and should do one thing: describe what it sees. Everything about
 * policy lives here, where it can be reasoned about and tested without a device.
 */
@Singleton
class SurfaceGuard @Inject constructor(
    private val encounterDao: SurfaceEncounterDao,
    private val preferences: SafetyPreferences,
    private val eventRepository: SafetyEventRepository,
) {

    /** What the guard wants the service to do about the screen it just described. */
    sealed interface Verdict {
        /** Not restricted, or allowance remaining. Nothing happens. */
        data object Allow : Verdict

        /** Leave the surface and tell the child why. */
        data class Close(
            val surface: RestrictedSurface,
            val reason: String,
            val childMessage: String,
        ) : Verdict
    }

    private data class Session(
        val surface: RestrictedSurface,
        val packageName: String,
        val appLabel: String,
        val matchedOn: String,
        val startedAtUptime: Long,
        val startedAtWallClock: Long,
    )

    private var session: Session? = null

    private val _lastClosure = MutableStateFlow<Verdict.Close?>(null)
    val lastClosure: StateFlow<Verdict.Close?> = _lastClosure.asStateFlow()

    /**
     * Called for every window the service describes, matched or not.
     *
     * Passing unmatched windows in is what closes an open session: leaving Reels produces a window
     * with no signature, and without that signal the time in the feed would never stop counting.
     */
    suspend fun onScreen(
        surface: RestrictedSurface?,
        packageName: String,
        appLabel: String,
        matchedOn: String?,
    ): Verdict {
        val settings = preferences.settings.first()

        val open = session
        if (open != null && (open.surface != surface || open.packageName != packageName)) {
            closeSession(open, closedByUs = false)
        }

        if (surface == null) return Verdict.Allow
        if (!isGuarded(surface, settings)) return Verdict.Allow

        if (session?.surface != surface || session?.packageName != packageName) {
            session = Session(
                surface = surface,
                packageName = packageName,
                appLabel = appLabel,
                matchedOn = matchedOn.orEmpty(),
                startedAtUptime = SystemClock.elapsedRealtime(),
                startedAtWallClock = System.currentTimeMillis(),
            )
        }

        return decide(surface, settings)
    }

    /** Whether the feature that owns this surface is switched on. */
    private fun isGuarded(surface: RestrictedSurface, settings: SafetySettings): Boolean =
        when (surface) {
            RestrictedSurface.SHORT_FORM_FEED ->
                settings.isEnabled(ProtectionFeature.SHORT_FORM_CONTROL)

            RestrictedSurface.STATUS_STORY ->
                settings.isEnabled(ProtectionFeature.STATUS_CONTROL)

            RestrictedSurface.VPN_SETTINGS ->
                settings.isEnabled(ProtectionFeature.VPN_RESTRICTION) && settings.blockVpnSettings

            // The AI surface is observed rather than refused. Its limit is a time budget, which
            // the monitoring service enforces at the app level.
            // Judged by SearchTermGuard, which reads the search rather than the screen.
            RestrictedSurface.WEB_SEARCH,
            RestrictedSurface.AI_CONVERSATION,
            RestrictedSurface.CLONE_WORKSPACE,
            -> false
        }

    private suspend fun decide(
        surface: RestrictedSurface,
        settings: SafetySettings,
    ): Verdict {
        if (surface == RestrictedSurface.VPN_SETTINGS) {
            return Verdict.Close(
                surface = surface,
                reason = "VPN settings are restricted while protection is on.",
                childMessage = "VPN settings are turned off on this device.",
            )
        }

        val allowance = settings.allowanceFor(surface)
        if (allowance == null) {
            return Verdict.Close(
                surface = surface,
                reason = "${surface.label} is switched off by a parent.",
                childMessage = "${surface.childFacingName} are switched off on this device.",
            )
        }

        val usedToday = encounterDao.dwellOnDay(surface, TimeFormat.todayEpochDay()) +
            currentSessionDwell()

        if (usedToday >= allowance * 60_000L) {
            val used = TimeFormat.duration(usedToday)
            return Verdict.Close(
                surface = surface,
                reason = "Today's ${allowance} minute allowance for ${surface.label.lowercase()} " +
                    "is used up ($used so far).",
                childMessage = "You have used today's ${allowance} minutes of " +
                    "${surface.childFacingName.lowercase()}.",
            )
        }

        return Verdict.Allow
    }

    private fun currentSessionDwell(): Long {
        val open = session ?: return 0
        return (SystemClock.elapsedRealtime() - open.startedAtUptime).coerceAtLeast(0)
    }

    /**
     * Called by the service once it has actually left the surface.
     *
     * Split from [onScreen] on purpose: the encounter is only worth recording as closed if the
     * global back action succeeded, and only the service knows whether it did.
     */
    suspend fun onClosed(verdict: Verdict.Close, succeeded: Boolean) {
        val open = session ?: return
        _lastClosure.value = verdict
        closeSession(open, closedByUs = succeeded)

        eventRepository.record(
            type = when (verdict.surface) {
                RestrictedSurface.SHORT_FORM_FEED -> SafetyEventType.SHORT_FORM_DETECTED
                RestrictedSurface.STATUS_STORY -> SafetyEventType.RESTRICTED_CONTENT_ATTEMPT
                RestrictedSurface.VPN_SETTINGS -> SafetyEventType.BYPASS_ATTEMPT
                else -> SafetyEventType.RESTRICTED_CONTENT_ATTEMPT
            },
            title = if (succeeded) {
                "${verdict.surface.label} was closed in ${open.appLabel}"
            } else {
                "${verdict.surface.label} opened in ${open.appLabel}"
            },
            detail = buildString {
                append(verdict.reason)
                append(" Recognised by ${open.matchedOn.ifBlank { "a screen signature" }}.")
                if (!succeeded) {
                    append(" The screen could not be closed automatically this time.")
                }
            },
            packageName = open.packageName,
            appLabel = open.appLabel,
            action = if (succeeded) EventAction.BLOCKED else EventAction.NOT_ENFORCEABLE,
            capabilityId = when (verdict.surface) {
                RestrictedSurface.SHORT_FORM_FEED -> ProtectionFeature.SHORT_FORM_CONTROL.id
                RestrictedSurface.STATUS_STORY -> ProtectionFeature.STATUS_CONTROL.id
                else -> ProtectionFeature.VPN_RESTRICTION.id
            },
            dedupeWindowMillis = 60_000L,
        )
    }

    private suspend fun closeSession(open: Session, closedByUs: Boolean) {
        val dwell = (SystemClock.elapsedRealtime() - open.startedAtUptime).coerceAtLeast(0)
        session = null

        // A sub-second match is a window in transition, not time spent in a feed. Recording those
        // would inflate the allowance count with screens the child never actually saw.
        if (dwell < MINIMUM_DWELL_MILLIS && !closedByUs) return

        encounterDao.insert(
            SurfaceEncounterEntity(
                surface = open.surface,
                packageName = open.packageName,
                appLabel = open.appLabel,
                atMillis = open.startedAtWallClock,
                epochDay = TimeFormat.todayEpochDay(),
                dwellMillis = dwell,
                closed = closedByUs,
                matchedOn = open.matchedOn,
            )
        )
    }

    /** Ends any open session, for when the service disconnects or the screen goes off. */
    suspend fun release() {
        session?.let { closeSession(it, closedByUs = false) }
    }

    fun clearLastClosure() {
        _lastClosure.value = null
    }

    // --- reporting ----------------------------------------------------------------------------

    fun observeUsageToday(surface: RestrictedSurface): Flow<SurfaceUsage> {
        val day = TimeFormat.todayEpochDay()
        return combine(
            encounterDao.observeDwellOnDay(surface, day),
            encounterDao.observeCountOnDay(surface, day),
            preferences.settings,
        ) { dwell, count, settings ->
            SurfaceUsage(
                surface = surface,
                encounters = count,
                dwellMillis = dwell,
                allowanceMinutes = settings.allowanceFor(surface),
            )
        }
    }

    fun observeClosedToday(): Flow<Int> =
        encounterDao.observeClosedCountOnDay(TimeFormat.todayEpochDay())

    fun observeRecent(surface: RestrictedSurface): Flow<List<SurfaceEncounterEntity>> =
        encounterDao.observeForSurface(surface)

    /** True while a surface is being watched but its allowance still has room. */
    fun observeAllowanceRemaining(surface: RestrictedSurface): Flow<Boolean> =
        observeUsageToday(surface).map { !it.exhausted }

    private companion object {
        const val MINIMUM_DWELL_MILLIS = 700L
    }
}
