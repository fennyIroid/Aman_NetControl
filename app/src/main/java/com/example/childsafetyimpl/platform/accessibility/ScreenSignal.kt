package com.example.childsafetyimpl.platform.accessibility

import com.example.childsafetyimpl.platform.content.Box
import com.example.childsafetyimpl.platform.content.ScrollLedger
import com.example.childsafetyimpl.domain.model.RestrictedSurface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which app is in front, and how many times that has changed since the app started.
 *
 * The count is what callers compare: a reader that keeps the last one it saw learns that the
 * window changed without having to know or store which app it was.
 */
data class ForegroundWindow(
    val packageName: String = "",
    val changes: Long = 0,
)

/** What the accessibility service believes is on screen right now. */
data class ScreenSignal(
    val packageName: String,
    val appLabel: String,
    val className: String?,
    val surface: RestrictedSurface?,
    /** The view id or label that produced the match, so a stale signature is visible in the UI. */
    val matchedOn: String?,
    val nodeCount: Int,
    val textNodeCount: Int,
    val walkDurationMillis: Long,
    val atMillis: Long,
) {
    val recognised: Boolean get() = surface != null
}

/**
 * Shared state between the accessibility service and the rest of the app.
 *
 * The service is created by the system rather than by Hilt's normal construction path, so a
 * singleton holder is how observations reach the UI. Everything here is transient by design:
 * nothing the accessibility service reads is written to disk, only counted and, where a surface
 * was closed, summarised into an encounter row that carries no screen content.
 */
@Singleton
class ScreenSignalStore @Inject constructor() {

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _current = MutableStateFlow<ScreenSignal?>(null)
    val current: StateFlow<ScreenSignal?> = _current.asStateFlow()

    private val _recent = MutableStateFlow<List<ScreenSignal>>(emptyList())
    val recent: StateFlow<List<ScreenSignal>> = _recent.asStateFlow()

    private val _stats = MutableStateFlow(SignalStats())
    val stats: StateFlow<SignalStats> = _stats.asStateFlow()

    private val _foreground = MutableStateFlow(ForegroundWindow())
    val foreground: StateFlow<ForegroundWindow> = _foreground.asStateFlow()

    /**
     * How far the app in front has scrolled, as it reports it. Read by the covering pipeline,
     * which cannot measure a scroll through its own covers; see [ScrollLedger].
     */
    val scroll = ScrollLedger()

    private val _privateTab = MutableStateFlow<String?>(null)

    /**
     * The browser showing a private tab right now, or null.
     *
     * Decided by the covering loop, which is the one that sees the window go black, from the
     * foreground package the accessibility service reports (see PrivateTabTracker); acted on by
     * the accessibility service, which is the one that can find the pictures and cover them or
     * send the child home. A package name and nothing else: no URL, no title, no content.
     */
    val privateTab: StateFlow<String?> = _privateTab.asStateFlow()

    private val coverHistory = ArrayDeque<Pair<Long, List<Box>>>()

    /**
     * Everywhere a private-tab cover has been drawn from shortly before [frameTime] until now, in
     * screen pixels. The covering loop leaves all of it out when it decides whether the window is
     * still black, because it is looking at its own output there (rule 17). The span matters as
     * much as the place: the capture shows covers a frame or two late, and a frame the loop was
     * too busy for is held and looked at later (rule 28), so a frame can show covers that have
     * since moved. [frameTime] is the frame's own capture time, on the uptime clock.
     */
    @Synchronized
    fun recentPrivateCovers(frameTime: Long): List<Box> {
        val now = android.os.SystemClock.uptimeMillis()
        // Nothing still on screen at or after this can be in any frame that is yet to be judged.
        while (coverHistory.size > 1 && now - coverHistory[1].first > MAX_FRAME_AGE_MILLIS) {
            coverHistory.removeFirst()
        }
        val since = frameTime - PRIVATE_COVER_SETTLE_MILLIS
        return coverHistory.filterIndexed { index, _ ->
            // An entry was on screen until the next one replaced it.
            val replacedAt = coverHistory.getOrNull(index + 1)?.first ?: Long.MAX_VALUE
            replacedAt >= since
        }.flatMap { it.second }.distinct()
    }

    /**
     * Set by the covering loop from what it sees. A tab it has seen turn normal in the browser
     * still in use is forgotten entirely, so coming back to that browser is not treated as coming
     * back to a private tab.
     */
    fun setPrivateTab(packageName: String?) {
        val was = _privateTab.value
        // Forgotten only when the browser was still the app in use: then its tab really turned
        // normal. Ended any other way, the browser was left, and is remembered for its return.
        if (packageName == null && was != null && was == _activeApp.value) leftWhilePrivate = null
        _privateTab.value = packageName
    }

    /**
     * A browser the child left while its tab was private. The tab is almost certainly still
     * private when the browser comes back, and the capture takes up to half a second to see that
     * again, so the covers go up on arrival instead (rule 12); the capture then confirms the tab
     * or lets it go on its usual evidence.
     */
    @Volatile private var leftWhilePrivate: String? = null

    @Synchronized
    fun setPrivateCovers(covers: List<Box>, now: Long = android.os.SystemClock.uptimeMillis()) {
        if (covers.isEmpty()) coverHistory.clear()
        else coverHistory.addLast(now to covers)
        if (coverHistory.size > MAX_COVER_HISTORY) coverHistory.removeFirst()
    }


    /**
     * Records which app's window is in front, if it is not the one already recorded.
     *
     * Deliberately the thinnest thing the accessibility service publishes: a package name and a
     * count of how many times it has changed. No window title, no class, no content — rule 3 — and
     * no tree walk, so this is not on the throttle that the rest of the service's work is on.
     *
     * It exists for the covering pipeline, which otherwise has to infer a window change from
     * pixels. Screen capture has no idea what an app is; it sees a rectangle of colour, and two
     * different apps can produce rectangles that resemble each other more than two frames of the
     * same video do. The accessibility service is told directly, and a cover judged in a window
     * that is no longer in front is a cover over content nobody looked at.
     *
     * Only a genuine change of package counts. Events repeat constantly within one app, and a
     * count that ticked on every one of them would tear the covers down several times a second.
     * This app's own package never reaches here at all — the caller drops it before the throttle —
     * which is rule 17 in another form: the covers this app draws are windows, and a cover raising
     * itself must never be able to report a window change that takes it back down.
     */
    fun noteForegroundWindow(packageName: String) = _foreground.update { current ->
        if (current.packageName == packageName) current
        else ForegroundWindow(packageName, current.changes + 1)
    }

    private val _activeApp = MutableStateFlow("")

    /**
     * The app that owns the active window, which is not always the one [foreground] names:
     * the keyboard and the notification shade raise window events of their own without ever
     * becoming the window the child is using. The private-tab handling keys off this one, because
     * the keyboard coming up over an incognito address bar must not end the private tab.
     */
    val activeApp: StateFlow<String> = _activeApp.asStateFlow()

    fun noteActiveApp(packageName: String) {
        _activeApp.value = packageName
        val private = _privateTab.value
        // A private tab belongs to the browser it was seen in. Once another app is in use it is
        // over, and waiting for the next captured frame to say so would leave the covers standing
        // over whatever came next.
        if (private != null && private != packageName) {
            leftWhilePrivate = private
            _privateTab.value = null
        } else if (private == null && packageName == leftWhilePrivate) {
            _privateTab.value = packageName
        }
    }

    fun setConnected(connected: Boolean) {
        _connected.value = connected
        if (!connected) _current.value = null
    }

    fun publish(signal: ScreenSignal) {
        _current.value = signal
        _recent.update { existing -> (listOf(signal) + existing).take(40) }
        _stats.update { current ->
            current.copy(
                windowChanges = current.windowChanges + 1,
                surfaceMatches = current.surfaceMatches + if (signal.recognised) 1 else 0,
                totalWalkMillis = current.totalWalkMillis + signal.walkDurationMillis,
                deepestNodeCount = maxOf(current.deepestNodeCount, signal.nodeCount),
            )
        }
    }

    fun recordClosure(surface: RestrictedSurface) = _stats.update {
        it.copy(
            surfacesClosed = it.surfacesClosed + 1,
            closedBySurface = it.closedBySurface + (surface to (it.closedBySurface[surface] ?: 0) + 1),
        )
    }

    private companion object {
        /** How long the capture can go on showing a private-tab cover where it no longer is. */
        const val PRIVATE_COVER_SETTLE_MILLIS = 600L

        /** Older than this, a frame is not held for judging; history beyond it is never needed. */
        const val MAX_FRAME_AGE_MILLIS = 5_000L
        const val MAX_COVER_HISTORY = 64
    }

    fun reset() {
        _recent.value = emptyList()
        _stats.value = SignalStats()
    }

    /**
     * Running cost and effectiveness of the screen-reading approach.
     *
     * The walk time is here because it is the number that decides whether this protection is
     * usable: reading the view tree on every window change is what an accessibility service gets
     * blamed for when a device feels slow, and a protection nobody leaves switched on protects
     * nobody.
     */
    data class SignalStats(
        val windowChanges: Int = 0,
        val surfaceMatches: Int = 0,
        val surfacesClosed: Int = 0,
        val closedBySurface: Map<RestrictedSurface, Int> = emptyMap(),
        val totalWalkMillis: Long = 0,
        val deepestNodeCount: Int = 0,
    ) {
        val averageWalkMillis: Long
            get() = if (windowChanges == 0) 0 else totalWalkMillis / windowChanges

        val matchRate: Float
            get() = if (windowChanges == 0) 0f else surfaceMatches.toFloat() / windowChanges
    }
}
