package com.example.childsafetyimpl.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.childsafetyimpl.BuildConfig
import com.example.childsafetyimpl.core.protection.ProtectionFeature
import com.example.childsafetyimpl.data.prefs.SafetyPreferences
import com.example.childsafetyimpl.di.ApplicationScope
import com.example.childsafetyimpl.domain.model.EventAction
import com.example.childsafetyimpl.domain.model.RestrictedSurface
import com.example.childsafetyimpl.domain.model.SafetyEventType
import com.example.childsafetyimpl.domain.repository.SafetyEventRepository
import com.example.childsafetyimpl.platform.content.Box
import com.example.childsafetyimpl.platform.content.PrivateCoverGeometry
import com.example.childsafetyimpl.platform.content.PrivateTabAction
import com.example.childsafetyimpl.platform.enforcement.BlockOverlayController
import com.example.childsafetyimpl.platform.enforcement.SurfaceBlockDecision
import com.example.childsafetyimpl.platform.usage.AppInventoryProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Acts on a browser's private tab, which screen capture cannot see.
 *
 * A private tab is FLAG_SECURE, so it captures as black and the covering loop has nothing to
 * judge; that loop only says *that* one is in front ([ScreenSignalStore.privateTab]). What a
 * secure window hides is its pixels, not its accessibility tree, so this finds the pictures there
 * and does what the parent chose ([PrivateTabAction]):
 *
 *  - **Cover every picture.** The whole page is covered the moment the tab is private, and a walk
 *    of the tree then narrows that to one opaque patch per photo ([ImageRegions]). Nothing is
 *    judged, so nothing is uncovered, and a walk that cannot account for every photo leaves the
 *    whole page covered (rule 12). There is no blurred copy to draw — nothing could be captured to
 *    make one — so a patch is a plain panel, and rule 10's wash is not in play: it is opaque.
 *  - **Don't allow private tabs.** Home, and a notice, the same as a restricted search.
 *
 * The patches are one full-display window added through this service, so they are opaque and let
 * every touch through at once (rules 14 and 15), laid out over the whole display so screen
 * coordinates from the tree land where they say (rule 16). Between walks they follow the page by
 * the scroll the browser reports (rule 27). Walks run off the main thread: on Chrome one is 50-100
 * ms of IPC and 400+ at worst, measured on the `blurtest2` branch.
 *
 * Nothing about the page is kept: not its address, its title or its text. The event log gets the
 * browser and how many pictures were covered (rule 3).
 */
@Singleton
class PrivateTabGuard @Inject constructor(
    private val signalStore: ScreenSignalStore,
    private val preferences: SafetyPreferences,
    private val eventRepository: SafetyEventRepository,
    private val overlayController: BlockOverlayController,
    private val inventory: AppInventoryProvider,
    @param:ApplicationScope private val scope: CoroutineScope,
) {

    /**
     * Everything below runs on this thread, not the main one. The accessibility service walks the
     * tree on its main thread for its own reasons, hundreds of milliseconds at a time while an
     * app is opening — measured on a realme RMX3870, a cover waited 370 ms behind that for its
     * turn. The patch window is added from here, so it is drawn from here too.
     */
    private val thread = HandlerThread("private-tab-covers").apply { start() }
    private val guard = Handler(thread.looper)
    @Volatile private var service: AccessibilityService? = null
    private var watchJob: Job? = null
    @Volatile private var walkExecutor: ExecutorService? = null

    // The guard thread only, from here down.
    private var coveringPackage: String? = null
    private var overlay: PrivateCoverView? = null
    private var windowManager: WindowManager? = null

    /** Photos from the last walk at their true bounds; null until a walk has accounted for all. */
    private var photos: List<Box>? = null

    /** The web page's visible area from the last walk; null covers the screen. */
    private var page: Box? = null
    private var scrollAtWalk = 0L
    private var walkInFlight = false
    private var walkDirty = false
    private var lastWalkStartedAt = 0L
    private var mostCovered = 0
    private var lastLeaveAt = 0L

    private val walkNow = Runnable { walk() }

    /** Starts following the private-tab signal. Call from the service's onServiceConnected. */
    fun attach(service: AccessibilityService) {
        // A service can be connected again without being destroyed first — Android suspends and
        // restores accessibility services around a UiAutomation session, for one — and a second
        // watcher alongside the first would raise every cover twice.
        if (this.service != null) detach()
        this.service = service
        walkExecutor = Executors.newSingleThreadExecutor()
        watchJob = scope.launch(guard.asCoroutineDispatcher()) {
            combine(signalStore.privateTab, preferences.settings) { tab, settings ->
                val on = settings.isEnabled(ProtectionFeature.VIDEO_BLURRING) &&
                    settings.videoBlurEnabled
                tab to (if (on) settings.privateTabAction else null)
            }.distinctUntilChanged().collect { (tab, action) -> apply(tab, action) }
        }
    }

    /** Tears everything down. Call from the service's onDestroy. */
    fun detach() {
        watchJob?.cancel()
        watchJob = null
        val executor = walkExecutor
        walkExecutor = null
        guard.post {
            lower()
            executor?.shutdownNow()
        }
        service = null
    }

    /**
     * Every event from the app in front, before the service's own throttle: a scroll moves the
     * patches at once, and a change to the page is a reason to look for pictures again.
     */
    fun onEvent(event: AccessibilityEvent, packageName: String) {
        // The event is recycled once the service returns, so only its type crosses threads.
        val type = event.eventType
        guard.post { onEvent(type, packageName) }
    }

    private fun onEvent(type: Int, packageName: String) {
        if (packageName != coveringPackage) return
        when (type) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                redraw()
                requestWalk(CONTENT_WALK_DELAY_MILLIS)
            }
            // A new page or tab: what was placed describes the old one. Cover the whole page
            // until the walk says where the new one's pictures are.
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                photos = null
                redraw()
                requestWalk(0)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> requestWalk(CONTENT_WALK_DELAY_MILLIS)
        }
    }

    private fun apply(tab: String?, action: PrivateTabAction?) {
        if (BuildConfig.DEBUG) Log.d(TAG, "private tab=$tab action=$action")
        when {
            tab == null || action == null -> lower()
            action == PrivateTabAction.LEAVE_TAB -> {
                lower()
                leave(tab)
            }
            tab != coveringPackage -> raise(tab)
        }
    }

    private fun raise(packageName: String) {
        lower()
        val manager = SafetyAccessibilityService.trustedWindowManager() ?: return
        val context = service ?: return
        val view = PrivateCoverView(context)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            title = "PrivateTabCover"
        }
        val added = runCatching { manager.addView(view, params) }.isSuccess
        if (!added) return

        overlay = view
        windowManager = manager
        coveringPackage = packageName
        photos = null
        page = null
        mostCovered = 0
        redraw()
        requestWalk(0)
    }

    private fun lower() {
        guard.removeCallbacks(walkNow)
        val previous = coveringPackage
        overlay?.let { view -> runCatching { windowManager?.removeView(view) } }
        overlay = null
        windowManager = null
        coveringPackage = null
        photos = null
        page = null
        walkDirty = false
        signalStore.setPrivateCovers(emptyList())
        if (previous != null) recordCovered(previous, mostCovered)
        mostCovered = 0
    }

    private fun leave(packageName: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLeaveAt < LEAVE_COOLDOWN_MILLIS) return
        lastLeaveAt = now

        val left = runCatching {
            service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) == true
        }.getOrDefault(false)
        val appLabel = inventory.labelFor(packageName)

        overlayController.showSurfaceBlock(
            SurfaceBlockDecision(
                surface = RestrictedSurface.PRIVATE_BROWSING,
                packageName = packageName,
                appLabel = appLabel,
                message = "Private browsing isn't available on this device.",
                closed = left,
            )
        )
        scope.launch {
            eventRepository.record(
                type = SafetyEventType.RESTRICTED_CONTENT_ATTEMPT,
                title = if (left) "A private tab was closed in $appLabel"
                else "A private tab was opened in $appLabel",
                detail = "Private and incognito tabs cannot be checked for pictures, and they " +
                    "are set not to be allowed. " +
                    (if (left) "The child was sent to the home screen. "
                    else "The browser could not be left automatically this time. ") +
                    "Nothing about the tab is recorded.",
                packageName = packageName,
                appLabel = appLabel,
                action = if (left) EventAction.BLOCKED else EventAction.NOT_ENFORCEABLE,
                capabilityId = ProtectionFeature.VIDEO_BLURRING.id,
                dedupeWindowMillis = 60_000L,
            )
        }
    }

    private fun recordCovered(packageName: String, pictures: Int) {
        val appLabel = inventory.labelFor(packageName)
        scope.launch {
            eventRepository.record(
                type = SafetyEventType.UNSAFE_SCREEN,
                title = "Pictures were covered in a private tab",
                detail = "A private tab in $appLabel cannot be captured, so its pictures cannot " +
                    "be checked, and every one was covered. " +
                    (if (pictures > 0) "Up to $pictures were on screen at once. " else "") +
                    "Nothing about the page is recorded.",
                packageName = packageName,
                appLabel = appLabel,
                action = EventAction.BLURRED,
                capabilityId = ProtectionFeature.VIDEO_BLURRING.id,
                dedupeWindowMillis = 10 * 60_000L,
            )
        }
    }

    // --- walking ---------------------------------------------------------------------------

    /**
     * Walks soon, but never lets a stream of events put it off for good: a page that keeps
     * changing is walked at least every [MAX_WALK_GAP_MILLIS].
     */
    private fun requestWalk(delayMillis: Long) {
        if (coveringPackage == null) return
        guard.removeCallbacks(walkNow)
        val sinceLast = SystemClock.uptimeMillis() - lastWalkStartedAt
        guard.postDelayed(walkNow, if (sinceLast >= MAX_WALK_GAP_MILLIS) 0 else delayMillis)
    }

    private fun walk() {
        val packageName = coveringPackage ?: return
        if (walkInFlight) {
            walkDirty = true
            return
        }
        val root = runCatching { service?.rootInActiveWindow }.getOrNull() ?: return
        if (root.packageName?.toString() != packageName) return
        val executor = walkExecutor ?: return

        walkInFlight = true
        lastWalkStartedAt = SystemClock.uptimeMillis()
        val screen = screenRect()
        val minSide = (MIN_PHOTO_DP * (service?.resources?.displayMetrics?.density ?: 1f)).toInt()

        executor.execute {
            val scrollBefore = signalStore.scroll.total()
            val pages = mutableListOf<AccessibilityNodeInfo>()
            val found = runCatching {
                ImageRegions.find(root, near = screen, minSide = minSide, pages = pages)
            }.getOrNull()
            val pageBox = runCatching { visibleUnion(pages) }.getOrNull()
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "walk photos=${found?.size ?: "all"} pages=${pages.size} page=$pageBox " +
                    "in ${SystemClock.uptimeMillis() - lastWalkStartedAt}ms")
            }
            guard.post {
                walkInFlight = false
                if (coveringPackage == packageName) {
                    photos = found?.map { it.bounds.toBox() }
                    page = pageBox
                    scrollAtWalk = scrollBefore
                    mostCovered = maxOf(mostCovered, found?.size ?: 0)
                    redraw()
                }
                if (walkDirty) {
                    walkDirty = false
                    requestWalk(0)
                }
            }
        }
    }

    private fun redraw() {
        val view = overlay ?: return
        val scrolled = (signalStore.scroll.total() - scrollAtWalk).toInt()
        val area = page ?: screenRect().toBox()
        val density = view.resources.displayMetrics.density
        val placed = PrivateCoverGeometry.place(
            photos = photos,
            scrolledSinceWalk = scrolled,
            page = area,
            padding = (PATCH_PADDING_DP * density).toInt(),
        )
        view.show(placed)
        signalStore.setPrivateCovers(placed)
    }

    private fun screenRect(): Rect {
        val manager = windowManager ?: SafetyAccessibilityService.trustedWindowManager()
        if (manager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Rect(manager.currentWindowMetrics.bounds)
        }
        val metrics = service?.resources?.displayMetrics
        return Rect(0, 0, metrics?.widthPixels ?: 0, metrics?.heightPixels ?: 0)
    }

    /** The union of the web pages' on-screen bounds, or null when there is no page in the tree. */
    private fun visibleUnion(pages: List<AccessibilityNodeInfo>): Box? {
        var union: Rect? = null
        val bounds = Rect()
        for (page in pages) {
            page.getBoundsInScreen(bounds)
            if (bounds.isEmpty) continue
            union = union?.apply { union(bounds) } ?: Rect(bounds)
        }
        return union?.toBox()
    }

    private fun Rect.toBox() = Box(left, top, right, bottom)

    /**
     * The patches, drawn in screen coordinates. The window covers the whole display, but where it
     * actually starts is asked rather than assumed, so a system that insets it anyway still gets
     * every patch where the tree said the picture is (rule 16).
     */
    private class PrivateCoverView(context: Context) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PATCH_COLOR }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = PATCH_EDGE_COLOR
            style = Paint.Style.STROKE
            strokeWidth = context.resources.displayMetrics.density
        }
        private val radius = PATCH_RADIUS_DP * context.resources.displayMetrics.density
        private val origin = IntArray(2)
        private val shape = RectF()
        private var boxes: List<Box> = emptyList()

        fun show(next: List<Box>) {
            if (next == boxes) return
            boxes = next
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            getLocationOnScreen(origin)
            for (box in boxes) {
                shape.set(
                    (box.left - origin[0]).toFloat(),
                    (box.top - origin[1]).toFloat(),
                    (box.right - origin[0]).toFloat(),
                    (box.bottom - origin[1]).toFloat(),
                )
                canvas.drawRoundRect(shape, radius, radius, fill)
                canvas.drawRoundRect(shape, radius, radius, edge)
            }
        }
    }

    private companion object {
        const val TAG = "PrivateTab"

        /** Settles a burst of content changes into one walk. */
        const val CONTENT_WALK_DELAY_MILLIS = 80L

        /** However busy the page, it is walked at least this often. */
        const val MAX_WALK_GAP_MILLIS = 250L

        /** Anything pictorial smaller than this on both sides is an icon, not a photo. */
        const val MIN_PHOTO_DP = 48f

        const val PATCH_PADDING_DP = 2f
        const val PATCH_RADIUS_DP = 6f

        /** Opaque, and plain: this is a picture nobody could look at, not a judged one. */
        const val PATCH_COLOR = 0xFFD5D9DE.toInt()
        const val PATCH_EDGE_COLOR = 0xFFB8BEC6.toInt()

        const val LEAVE_COOLDOWN_MILLIS = 2_500L
    }
}
