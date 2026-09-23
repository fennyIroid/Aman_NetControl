package com.example.childsafetyimpl.platform.accessibility

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

/**
 * Which pipeline covers pictures in the browser. [releasedAtUptime] is when the browser path last
 * handed the screen back, on the uptime clock; zero if it never held it.
 */
data class BrowserImagesHandoff(
    val active: Boolean = false,
    val releasedAtUptime: Long = 0L,
) {
    /** Whether the capture loop must keep out of the way at [nowUptime]. */
    fun holdsScreen(nowUptime: Long, settleMillis: Long): Boolean =
        active || (releasedAtUptime > 0L && nowUptime - releasedAtUptime < settleMillis)
}

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

    private val _browserImages = MutableStateFlow(BrowserImagesHandoff())

    /**
     * Whether the browser image-search path owns the screen, and when it last let go of it.
     *
     * Two pipelines can cover pictures in a browser and only one may. The capture loop stands down
     * while this is active and for a moment after, because its capture shows the browser path's
     * covers and rule 17 applies to another pipeline's paint as much as to its own. See
     * `BrowserImageCover` and `ScreenAnalysisService.consider`.
     */
    val browserImages: StateFlow<BrowserImagesHandoff> = _browserImages.asStateFlow()

    fun setBrowserImagesActive(active: Boolean, atUptime: Long) = _browserImages.update { current ->
        when {
            current.active == active -> current
            active -> current.copy(active = true)
            else -> BrowserImagesHandoff(active = false, releasedAtUptime = atUptime)
        }
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
