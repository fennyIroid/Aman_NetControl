package com.example.childsafetyimpl.platform.accessibility

import com.example.childsafetyimpl.core.protection.ProtectionFeature
import com.example.childsafetyimpl.data.prefs.SafetyPreferences
import com.example.childsafetyimpl.data.source.BlockedSearchTerms
import com.example.childsafetyimpl.domain.model.EventAction
import com.example.childsafetyimpl.domain.model.EventSeverity
import com.example.childsafetyimpl.domain.model.SafetyEventType
import com.example.childsafetyimpl.domain.repository.SafetyEventRepository
import com.example.childsafetyimpl.domain.repository.SafetyRulesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What happens when a child searches for something restricted in a browser this app does not own.
 *
 * The tunnel forces SafeSearch, which strips explicit imagery out of results in every browser on
 * the device. It stops there by design: a search engine does not treat "how to buy cocaine" as
 * adult content and is right not to. This guard covers what SafeSearch leaves, by reading the
 * words the child actually searched for.
 *
 * Leaving is done by going home rather than by going back. Back returns to the search box with
 * the query still sitting in it, which fires again on the next screen and leaves the child in a
 * loop; home ends it once. The event log records which browser and which category matched, never
 * the words themselves, because a parent needs to know a child searched about self-harm and does
 * not need a transcript of a frightened eleven-year-old's exact phrasing stored on a device.
 */
@Singleton
class SearchTermGuard @Inject constructor(
    private val preferences: SafetyPreferences,
    private val rulesRepository: SafetyRulesRepository,
    private val eventRepository: SafetyEventRepository,
) {

    sealed interface Verdict {
        data object Allow : Verdict

        data class Close(
            /** What matched, in words a parent reads: "Drugs and alcohol", "Gambling". */
            val category: String,
            /** Self-harm and weapons are raised as critical; nothing else is. */
            val severe: Boolean,
            val reason: String,
            val childMessage: String,
            /** Set when a site rule matched rather than a term, so the log can name the host. */
            val host: String?,
        ) : Verdict
    }

    data class Blocked(
        val appLabel: String,
        val category: String,
        val host: String?,
        val atMillis: Long,
    )

    private val _blockedToday = MutableStateFlow(0)
    val blockedToday: StateFlow<Int> = _blockedToday.asStateFlow()

    private val _lastBlocked = MutableStateFlow<Blocked?>(null)
    val lastBlocked: StateFlow<Blocked?> = _lastBlocked.asStateFlow()

    /**
     * The last thing that matched, and when.
     *
     * A results page fires a content-changed event several times a second, and without this the
     * same search produces a stream of identical records. Only the fingerprint is kept, so
     * nothing about the search survives the comparison.
     */
    private var lastMatchFingerprint: Int = 0
    private var lastMatchAt: Long = 0

    /**
     * Judges everything readable on a browser screen.
     *
     * Candidates are the address bar, any text field, and the title of a results page. Nothing
     * from the body of the page reaches here.
     */
    suspend fun onBrowserScreen(candidates: List<String>): Verdict {
        if (candidates.isEmpty()) return Verdict.Allow

        val settings = preferences.settings.first()
        if (!settings.isEnabled(ProtectionFeature.SEARCH_FILTERING)) return Verdict.Allow
        if (!settings.searchTermGuardEnabled) return Verdict.Allow

        for (candidate in candidates) {
            // The search first, then the site. An address bar showing
            // "google.com/search?q=bikini" is both a host and a search, and reading it only as a
            // host is how a restricted search sails through an allowed search engine.
            val query = BrowserSurfaces.searchQueryFromUrl(candidate)
                ?: BrowserSurfaces.searchQueryFromTitle(candidate)
            val words = query ?: candidate.takeIf { BrowserSurfaces.hostFrom(it) == null }

            val term = words?.let { BlockedSearchTerms.match(it) }
            if (term != null) {
                if (!isFresh(term.text.hashCode())) return Verdict.Allow
                return Verdict.Close(
                    category = term.category.label,
                    severe = term.category.severe,
                    reason = "A search matching the ${term.category.label.lowercase()} list was " +
                        "made in a browser outside this app.",
                    childMessage = "That search is not allowed on this device.",
                    host = null,
                )
            }

            // A typed address is judged by the same rules the tunnel and the browser use, so a
            // site already refused stays refused even where DNS filtering never applied.
            val host = BrowserSurfaces.hostFrom(candidate) ?: continue
            val verdict = rulesRepository.evaluateHost(host)
            if (!verdict.allowed && isFresh(host.hashCode())) {
                return Verdict.Close(
                    category = verdict.category?.label ?: "Restricted site",
                    severe = false,
                    reason = "$host is refused by " +
                        (verdict.matchedPattern?.let { "the rule $it" } ?: "a category rule") +
                        ", and it was opened in a browser outside this app.",
                    childMessage = "That site is not available on this device.",
                    host = host,
                )
            }
        }
        return Verdict.Allow
    }

    /** Records the outcome once the service has tried to leave. */
    suspend fun onClosed(
        verdict: Verdict.Close,
        packageName: String,
        appLabel: String,
        succeeded: Boolean,
    ) {
        _blockedToday.update { it + 1 }
        _lastBlocked.value = Blocked(
            appLabel = appLabel,
            category = verdict.category,
            host = verdict.host,
            atMillis = System.currentTimeMillis(),
        )

        eventRepository.record(
            type = SafetyEventType.RESTRICTED_SEARCH,
            title = if (succeeded) {
                "A restricted search was closed in $appLabel"
            } else {
                "A restricted search was made in $appLabel"
            },
            // The category and the browser, never the search. What was typed is judged in memory
            // and dropped inside the call that read it.
            detail = buildString {
                append(verdict.reason)
                append(" Category: ${verdict.category}.")
                if (!succeeded) append(" The browser could not be left automatically this time.")
            },
            packageName = packageName,
            appLabel = appLabel,
            severity = if (verdict.severe) EventSeverity.CRITICAL else EventSeverity.HIGH,
            action = if (succeeded) EventAction.BLOCKED else EventAction.NOT_ENFORCEABLE,
            capabilityId = ProtectionFeature.SEARCH_FILTERING.id,
            dedupeWindowMillis = 60_000L,
        )
    }

    fun clearLastBlocked() {
        _lastBlocked.value = null
    }

    private fun isFresh(fingerprint: Int): Boolean {
        val now = System.currentTimeMillis()
        if (fingerprint == lastMatchFingerprint && now - lastMatchAt < REPEAT_WINDOW_MILLIS) {
            return false
        }
        lastMatchFingerprint = fingerprint
        lastMatchAt = now
        return true
    }

    private companion object {
        /** Long enough that one results page produces one record, short enough to catch a retry. */
        const val REPEAT_WINDOW_MILLIS = 20_000L
    }
}
