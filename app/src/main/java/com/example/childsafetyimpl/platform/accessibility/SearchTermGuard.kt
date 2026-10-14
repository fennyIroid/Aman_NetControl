package com.example.childsafetyimpl.platform.accessibility

import com.example.childsafetyimpl.core.protection.ProtectionFeature
import com.example.childsafetyimpl.data.prefs.SafetyPreferences
import com.example.childsafetyimpl.data.source.BlockedSearchTerms
import com.example.childsafetyimpl.domain.model.EventAction
import com.example.childsafetyimpl.domain.model.EventSeverity
import com.example.childsafetyimpl.domain.model.SafetyEventType
import com.example.childsafetyimpl.domain.repository.SafetyEventRepository
import com.example.childsafetyimpl.domain.repository.SafetyRulesRepository
import com.example.childsafetyimpl.platform.network.DirectAddress
import com.example.childsafetyimpl.platform.network.ProxyOrigin
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
 * The Private DNS resolver forces SafeSearch, which strips explicit imagery out of results in every
 * browser on the device. It stops there by design: a search engine does not treat "how to buy
 * cocaine" as adult content and is right not to. This guard covers what SafeSearch leaves, by
 * reading the words the child actually searched for.
 *
 * It is also where the parent's own site rules reach browsers this app does not own. A public
 * resolver cannot hold one family's block and allow lists, so a typed address is judged on the
 * device against the same rules the built-in browser uses, and the browser is left when it is
 * refused.
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

    // There is no repeat window here. One used to return Allow for the same match inside 20
    // seconds, to keep a results page from writing a stream of records, and on a device that
    // meant a refused site opened a second time within 20 seconds simply loaded. A stream of
    // identical records is already prevented twice over: the service's close cooldown, and the
    // event repository's dedupe window in onClosed. Every match closes.

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
                return Verdict.Close(
                    category = term.category.label,
                    severe = term.category.severe,
                    reason = "A search matching the ${term.category.label.lowercase()} list was " +
                        "made in a browser outside this app.",
                    childMessage = "That search is not allowed on this device.",
                    host = null,
                )
            }

            // A typed address is judged by the same rules the built-in browser uses, so the
            // parent's own list applies where the public resolver cannot carry it. An address
            // written as a number, or a site carried inside a relay's address, never reaches the
            // resolver under its own name, so both are read back here and judged as the site.
            val host = BrowserSurfaces.hostFrom(candidate)
                ?: DirectAddress.fromAddressBar(candidate)?.address
                ?: continue
            for (target in listOfNotNull(host, ProxyOrigin.fromUrl(candidate)).distinct()) {
                val verdict = rulesRepository.evaluateHost(target)
                if (verdict.allowed) continue
                return Verdict.Close(
                    category = verdict.category?.label
                        ?: if (DirectAddress.fromHost(target) != null) "Site opened by its address"
                        else "Restricted site",
                    severe = false,
                    reason = (if (target == host) "$host is" else "$host carries $target, which is") +
                        " refused by " +
                        (verdict.matchedPattern?.let { "the rule $it" } ?: "a filtering rule") +
                        ", and it was opened in a browser outside this app. ${verdict.reason}",
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
            title = when {
                verdict.host != null && succeeded -> "A restricted site was closed in $appLabel"
                verdict.host != null -> "A restricted site was opened in $appLabel"
                succeeded -> "A restricted search was closed in $appLabel"
                else -> "A restricted search was made in $appLabel"
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
}
