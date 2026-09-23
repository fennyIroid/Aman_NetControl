package com.example.childsafetyimpl.platform.browserimages

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.example.childsafetyimpl.BuildConfig
import com.example.childsafetyimpl.core.protection.ProtectionFeature
import com.example.childsafetyimpl.data.prefs.SafetyPreferences
import com.example.childsafetyimpl.domain.model.EventAction
import com.example.childsafetyimpl.domain.model.SafetyEventType
import com.example.childsafetyimpl.domain.repository.SafetyEventRepository
import com.example.childsafetyimpl.platform.accessibility.ScreenSignalStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Covers the photos on a browser's image-search results, and uncovers each one only once it has
 * been judged clean.
 *
 * Ported from SafeScreen, and the opposite way round from video blurring. The capture loop shows
 * the screen and covers what a frame proves unsafe; this covers every photo on the grid the moment
 * the page is recognised and reveals what the models clear. It can afford to because it does not
 * have to guess at anything the capture loop guesses at:
 *
 * - **Where the photos are** comes from the page itself, through the accessibility tree
 *   ([ImageRegions]): exact bounds, including ones scrolled just off screen, and an identity per
 *   photo that a verdict stays attached to.
 * - **Where they went** comes from re-reading one photo's bounds per scroll container every frame
 *   while the page moves ([AnchorTracker]), not from aligning pixels.
 * - **What they are** is judged on a capture of the browser's window alone
 *   (`takeScreenshotOfWindow`, Android 14), which none of this app's covers are in, by two models
 *   built for the job: an NSFW classifier and a drug check. Rule 17 cannot arise here.
 *
 * The scope is deliberately narrow: an image-search results page ([ImageSearchSurface]) in a
 * Chromium browser, because that is where the tree was measured to say which nodes are photos.
 * Everywhere else — video, feeds, ordinary web pages, other browsers — the capture loop does what
 * it did before. The two never cover the same screen: while this holds it,
 * [ScreenSignalStore.browserImages] tells the capture loop to stand down.
 *
 * Every way of not getting a verdict — not loaded, off screen, a model failing, the page moving —
 * leaves a photo covered (rule 12). Nothing captured is kept: crops are scored and recycled, and
 * the event log gets counts (rule 3).
 *
 * Threads: accessibility events and every piece of state here are on the main thread. Tree walks
 * run on [walkExecutor], captures and both models on [frostExecutor] (the interpreters are not
 * thread-safe), anchor reads on their own thread.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class BrowserImageCover(
    private val service: AccessibilityService,
    private val signalStore: ScreenSignalStore,
    private val preferences: SafetyPreferences,
    private val eventRepository: SafetyEventRepository,
    private val scope: CoroutineScope,
) {

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var scrim: ScrimOverlay? = null
    private var touchSentinel: TouchSentinel? = null

    /**
     * The parent's settings as this path reads them; null while it has nothing to do. Cached from
     * a flow rather than read per event: rule 6.
     */
    private var policy: BrowserImagePolicy? = null
    private var settingsJob: Job? = null

    /** The NSFW and drug models. Loaded on [frostExecutor] and only ever used there. */
    private var classifier: NsfwClassifier? = null
    private var drugClassifier: DrugClassifier? = null
    private var screener: PhotoScreener? = null
    private var modelsRequested = false

    /** The surface the scrim is currently up for, so repeat events do not re-raise it. */
    private var coveredSurfaceKey: String? = null

    /**
     * The last page covered in [lastCoveredPackage], kept when the scrim is lowered only because
     * another window came in front. Chrome hides its toolbar while a page scrolls, and with the
     * address bar gone there is nothing to read the page from.
     */
    private var lastCoveredKey: String? = null
    private var lastCoveredPackage: String? = null
    private var coveredPackage: String? = null

    /** What the next capture should frost: the browser window and the covered area. */
    private class BackdropTarget(
        val windowId: Int,
        val windowBounds: Rect,
        val area: Rect,
        val root: AccessibilityNodeInfo,
    )

    /** What one capture produced: frosts, and a score for each photo it could see. */
    private class CaptureOutput(
        val frosted: Bitmap?,
        val frostArea: Rect,
        val photoFrosts: Map<Long, PhotoFrost>,
        val screened: List<PhotoScreener.Result>,
    )

    /** Verdicts for the current page's photos, by photo id. Cleared for every new page. */
    private val verdicts = PhotoVerdicts()

    /** Captures in a row that scored no photo; see [FRUITLESS_CAPTURES]. */
    private var fruitlessCaptures = 0

    private val mainHandler = Handler(Looper.getMainLooper())
    private val frostExecutor = Executors.newSingleThreadExecutor()
    private var backdropTarget: BackdropTarget? = null
    private var backdropInFlight = false
    private var backdropDirty = false
    private var lastBackdropAt = 0L
    private var idleRefreshesLeft = 0
    private val captureBackdrop = Runnable { startBackdropCapture() }

    /** Until this uptime a new page is covered whole; see [SETTLE_MILLIS]. */
    private var pageSettlesAt = 0L

    /** Until this uptime the page counts as moving: patches travel with it, nothing is screened. */
    private var movingUntil = 0L
    private var coveredArea: Rect? = null
    private val settle = Runnable { onSettled() }

    /** Photos near the covered area from the last walk, at their true bounds; null covers all. */
    private var knownPhotos: List<Photo>? = null

    /** How far each scroll container has moved since [knownPhotos] were placed, by scroller. */
    private var tracks: Map<Long, Track> = emptyMap()

    /** Where the page's content sits on the screen, as last read; null until read. */
    private var pageBounds: Rect? = null

    /** Bumped for every placement; anchor reads made against an older one are dropped. */
    private var placement = 0

    /** The last second of [tracks], to find where the photos were when a capture was taken. */
    private val trackHistory = TrackHistory()

    /** The part of the page the last walk looked through; see [uncheckedBands]. */
    private var checked: Rect? = null

    // Anchor reads run on their own thread, so a slow answer never holds up the main thread or a
    // walk. Everything from here to the poller is touched on that thread only.
    private val anchorThread = HandlerThread("browser-image-anchors").apply { start() }
    private val anchorHandler = Handler(anchorThread.looper)
    private val tracker = AnchorTracker()
    private var trackerPlacement = 0
    private var pageNode: AccessibilityNodeInfo? = null
    private val pollAnchors = Runnable { readAnchors() }
    private var anchorReads = 0

    /** Until this uptime the anchors are read; set from the main thread. */
    @Volatile private var pollUntil = 0L
    private val polling = AtomicBoolean(false)

    private var lastWalkAt = 0L
    private var lastEvaluateAt = 0L
    private val walkLater = Runnable { frontRoot()?.let(::requestWalk) }

    /** Each covered photo's frost, by photo id, taken while the page was still. */
    private val photoFrosts = mutableMapOf<Long, PhotoFrost>()

    // Walking the tree is 50-100 ms of IPC, 400+ at worst; on the main thread that delayed the
    // touch sentinel, the one thing that has to be instant.
    private val walkExecutor = Executors.newSingleThreadExecutor()
    private var walkInFlight = false
    private var walkDirty = false

    /**
     * Bumped when a new page arrives or the page starts to move. A screening started in an older
     * generation is discarded: its crops may not be of the photos it names.
     */
    private var screenGeneration = 0

    private var released = false

    // --- lifecycle ---------------------------------------------------------------------------

    /** Starts following the parent's settings. Call once, on the main thread. */
    fun start() {
        settingsJob = scope.launch {
            preferences.settings
                .map { BrowserImagePolicy.from(it) }
                .distinctUntilChanged()
                .collect { next -> mainHandler.post { applyPolicy(next) } }
        }
    }

    private fun applyPolicy(next: BrowserImagePolicy?) {
        if (released) return
        policy = next
        if (next == null) {
            lower(forgetPage = true)
            touchSentinel?.detach()
            touchSentinel = null
            scrim?.detach()
            scrim = null
            diag("off")
            return
        }
        if (scrim == null) {
            // Attached once and held invisible: raising it later is a visibility toggle rather
            // than an addView, which is what keeps it ahead of the browser's paint.
            scrim = ScrimOverlay(windowManager).also { it.attach(service) }
            touchSentinel = TouchSentinel(windowManager, ::onTouchDown).also { it.attach(service) }
        }
        loadModels()
        // New thresholds re-judge the page on screen.
        verdicts.reset()
        frontRoot()?.let(::reevaluate)
    }

    private fun loadModels() {
        if (modelsRequested) return
        modelsRequested = true
        frostExecutor.execute {
            val nsfw = TfliteNsfwClassifier.load(service)
            val drugs = TfliteDrugClassifier.load(service)
            classifier = nsfw
            drugClassifier = drugs
            // Both or neither: drug imagery is part of what the parent asked to have covered, and
            // a path that could only clear photos for nudity would uncover drug photos. Without
            // both the capture loop keeps the browser, as it did before this path existed.
            val loaded = if (nsfw != null && drugs != null) PhotoScreener(nsfw, drugs) else null
            mainHandler.post {
                screener = loaded
                diag("models nsfw=${nsfw != null} drugs=${drugs != null}")
                if (loaded != null) frontRoot()?.let(::reevaluate)
            }
        }
    }

    /** Whether this path can take a browser page right now. */
    private val isRunning: Boolean get() = !released && policy != null && screener != null && scrim != null

    /** Tears everything down. Call from the service's onDestroy, on the main thread. */
    fun release() {
        if (released) return
        lower(forgetPage = true)
        released = true
        settingsJob?.cancel()
        mainHandler.removeCallbacks(captureBackdrop)
        mainHandler.removeCallbacks(settle)
        mainHandler.removeCallbacks(walkLater)
        screener = null
        // Closed on the thread that uses them, after any capture in flight.
        frostExecutor.execute {
            classifier?.close()
            drugClassifier?.close()
        }
        frostExecutor.shutdown()
        walkExecutor.shutdownNow()
        anchorThread.quitSafely()
        touchSentinel?.detach()
        touchSentinel = null
        scrim?.detach()
        scrim = null
    }

    // --- events ------------------------------------------------------------------------------

    /**
     * Every accessibility event, before the service's throttle: the scrim has to follow the page
     * at the rate the page changes. Cheap for everything that is not a watched browser while
     * nothing is covered — that returns before any request to another app.
     */
    fun onEvent(event: AccessibilityEvent, eventPackage: String) {
        if (!isRunning) return
        if (eventPackage !in CHROMIUM_BROWSERS && coveredSurfaceKey == null) return

        // The event's own package is the wrong thing to test. Events arrive constantly from
        // SystemUI and others while the browser is still in front; what matters is which window
        // is actually in front.
        val root = frontRoot() ?: return // Fail closed: leave the scrim as it is.
        val packageName = root.packageName?.toString() ?: return

        // The front window is an app window, never an overlay (see FrontWindow), so this app's
        // own covers cannot land here and a cover cannot report itself as the child leaving —
        // rule 25. This app in front is its own screen, opened over the browser.
        if (packageName !in CHROMIUM_BROWSERS) {
            if (scrim?.isShowing == true) diag("left the browser; lowering")
            lower()
            return
        }

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED && coveredSurfaceKey != null) {
            markMoving(SETTLE_MILLIS)
        }

        // While the page moves the anchors keep the patches in place, and every read of the
        // address bar is a request the browser's UI thread answers instead of drawing the scroll.
        val now = SystemClock.uptimeMillis()
        if (isMoving() && now - lastEvaluateAt < MOVING_EVALUATE_INTERVAL_MILLIS) return
        lastEvaluateAt = now

        try {
            evaluate(packageName, root)
        } catch (e: Exception) {
            // A malformed tree must not take the service down. Whatever was covered stays covered.
            diag("evaluate threw: ${e.javaClass.simpleName}")
        }
    }

    /**
     * The root of the window the child is looking at — usually the active one, but not a small
     * system window such as a screenshot toolbar; see [FrontWindow].
     */
    private fun frontRoot(): AccessibilityNodeInfo? {
        val all = runCatching { service.windows }.getOrNull().orEmpty()
        if (all.isEmpty()) return service.rootInActiveWindow
        val summaries = all.map { window ->
            FrontWindow.Summary(
                type = window.type,
                layer = window.layer,
                isActive = window.isActive,
                bounds = Rect().also(window::getBoundsInScreen),
            )
        }
        val front = FrontWindow.pick(summaries, screenBounds()) ?: return service.rootInActiveWindow
        return all[front].root ?: service.rootInActiveWindow
    }

    private fun screenBounds(): Rect = Rect(windowManager.currentWindowMetrics.bounds)

    private fun evaluate(packageName: String, root: AccessibilityNodeInfo) {
        val addressBarNodes = ADDRESS_BAR_IDS.flatMap { id ->
            root.findAccessibilityNodeInfosByViewId("$packageName:id/$id").orEmpty()
        }
        // Read, parsed and dropped here. Only a hash of the query outlives this call.
        val candidates = addressBarNodes.flatMap {
            listOf(it.text?.toString(), it.contentDescription?.toString())
        }

        if (candidates.all { it.isNullOrBlank() }) {
            // Nothing was read, which is not the same as reading a safe page. Chrome slides its
            // toolbar away while the page scrolls, and lowering here uncovered every photo the
            // moment the child scrolled. A covered page stays covered, now edge to edge.
            val key = coveredSurfaceKey ?: lastCoveredKey?.takeIf { lastCoveredPackage == packageName }
            key?.let { raise(it, toolbar = null, root = root) }
            return
        }

        val page = candidates.asSequence()
            .filterNotNull()
            .mapNotNull { ImageSearchSurface.parse(it) }
            .firstOrNull()
        if (page == null) {
            lower(forgetPage = true)
            return
        }
        diag("image search engine=${page.engine}")

        raise(
            key = "$packageName:${page.key}",
            toolbar = addressBarNodes.firstNotNullOfOrNull { node ->
                Rect().also { node.getBoundsInScreen(it) }.takeIf { !it.isEmpty }
            },
            root = root,
        )
    }

    private fun raise(key: String, toolbar: Rect?, root: AccessibilityNodeInfo) {
        if (scrim == null) return
        val area = ScrimGeometry.contentArea(screenBounds(), toolbar)

        // A new page's photos are not in the tree yet, or not all of them: cover everything until
        // it has had a moment to settle. The same page coming back after another window was in
        // front keeps what was learnt of it — verdicts, frosts.
        var samePageBack = false
        if (key != coveredSurfaceKey) {
            samePageBack = coveredSurfaceKey == null && key == lastCoveredKey
            pageSettlesAt = SystemClock.uptimeMillis() + SETTLE_MILLIS
            knownPhotos = null
            forgetPlacement()
            if (!samePageBack) {
                verdicts.reset()
                forgetPhotoFrosts()
            }
            screenGeneration++
            scheduleSettle()
        }
        coveredSurfaceKey = key
        coveredArea = area
        coveredPackage = root.packageName?.toString()
        lastCoveredKey = key
        lastCoveredPackage = coveredPackage
        signalStore.setBrowserImagesActive(true, SystemClock.uptimeMillis())

        // Cover at once with what is known; the walk refines it when it comes back.
        redraw()
        if (samePageBack) scrim?.showPhotoFrosts(photoFrosts.toMap())
        requestWalk(root)

        val windowBounds = Rect().also { bounds ->
            root.window?.getBoundsInScreen(bounds) ?: root.getBoundsInScreen(bounds)
        }
        backdropTarget = BackdropTarget(root.windowId, windowBounds, area, root)
        requestBackdrop()
    }

    /**
     * [forgetPage] when the page itself was left or turned out not to be an image search; not
     * when another window merely came in front of it, since the same page may come back with no
     * address bar to read.
     */
    private fun lower(forgetPage: Boolean = false) {
        if (scrim?.isShowing == true) diag("lowering")
        if (forgetPage) {
            lastCoveredKey = null
            lastCoveredPackage = null
        }
        scrim?.hide()
        if (coveredSurfaceKey != null) signalStore.setBrowserImagesActive(false, SystemClock.uptimeMillis())
        coveredSurfaceKey = null
        coveredArea = null
        coveredPackage = null
        knownPhotos = null
        forgetPlacement()
        pollUntil = 0L
        if (forgetPage) {
            verdicts.reset()
            photoFrosts.clear()
        }
        walkDirty = false
        backdropTarget = null
        backdropDirty = false
        mainHandler.removeCallbacks(captureBackdrop)
        mainHandler.removeCallbacks(settle)
        mainHandler.removeCallbacks(walkLater)
    }

    // --- motion ------------------------------------------------------------------------------

    /** Nothing is placed: the next walk places the page afresh. */
    private fun forgetPlacement() {
        tracks = emptyMap()
        trackHistory.clear()
        pageBounds = null
        checked = null
        placement++
        anchorHandler.post {
            tracker.clear()
            pageNode = null
        }
    }

    private fun isSettling(): Boolean = SystemClock.uptimeMillis() < pageSettlesAt

    private fun isMoving(): Boolean = SystemClock.uptimeMillis() < movingUntil

    /** The page is moving, or about to: patches travel with it until it has been still a while. */
    private fun markMoving(millis: Long) {
        if (coveredSurfaceKey == null) return
        // Screening in flight was cropped from a page about to shift under it.
        if (!isMoving()) screenGeneration++
        movingUntil = maxOf(movingUntil, SystemClock.uptimeMillis() + millis)
        startPolling()
        scheduleSettle()
        redraw()
    }

    /** Read the anchors until the page has stopped. */
    private fun startPolling() {
        pollUntil = movingUntil
        if (polling.compareAndSet(false, true)) anchorHandler.post(pollAnchors)
    }

    /**
     * On the anchor thread: read every anchor and the page's bounds straight from the browser,
     * hand them to the main thread, and go again next frame while the page moves.
     */
    private fun readAnchors() {
        val now = SystemClock.uptimeMillis()
        val sampled = tracker.sample(now) { node ->
            if (runCatching { node.refresh() }.getOrDefault(false)) ImageRegions.trueBounds(node) else null
        }
        val page = pageNode?.takeIf { anchorReads++ % PAGE_READ_EVERY == 0 }?.let { node ->
            if (runCatching { node.refresh() }.getOrDefault(false)) Rect().also(node::getBoundsInScreen) else null
        }
        val generation = trackerPlacement
        val goingOn = now < pollUntil
        // The last read carries no speed: nothing reads the page again to pull back a patch
        // carried on past where the page stopped.
        val result = if (goingOn) sampled else sampled.mapValues { (_, track) -> track.copy(velocityX = 0f, velocityY = 0f) }
        mainHandler.post { onAnchorsRead(generation, result, page) }
        if (goingOn) {
            anchorHandler.postDelayed(pollAnchors, ANCHOR_POLL_MILLIS)
        } else {
            polling.set(false)
            // The main thread may have asked for more between the check and the flag.
            if (SystemClock.uptimeMillis() < pollUntil && polling.compareAndSet(false, true)) anchorHandler.post(pollAnchors)
        }
    }

    private fun onAnchorsRead(generation: Int, sampled: Map<Long, Track>, page: Rect?) {
        if (generation != placement || coveredSurfaceKey == null) return
        val moved = sampled.any { (scroller, track) ->
            val before = tracks[scroller]
            track.shiftX != (before?.shiftX ?: 0) || track.shiftY != (before?.shiftY ?: 0)
        } || (page != null && page != pageBounds)
        tracks = sampled
        trackHistory.record(sampled)
        page?.let { pageBounds = it }
        // The anchors see a page move before the browser reports it, and after its last report
        // while a fling runs out: they keep the page counted as moving for as long as it does.
        if (moved) markMoving(SETTLE_MILLIS) else redraw()
    }

    private fun scheduleSettle() {
        mainHandler.removeCallbacks(settle)
        mainHandler.postAtTime(settle, maxOf(movingUntil, pageSettlesAt))
    }

    /** The page has been still long enough: patches stop, and it can be walked and screened. */
    private fun onSettled() {
        if (isMoving() || isSettling()) {
            scheduleSettle()
            return
        }
        frontRoot()?.let(::reevaluate)
    }

    /** A finger landed. Grow the patches now, before the page under it can start to move. */
    private fun onTouchDown() {
        markMoving(TOUCH_HOLD_MILLIS)
    }

    /** Re-run the decision against the current window. */
    private fun reevaluate(root: AccessibilityNodeInfo) {
        if (!isRunning) return
        val packageName = root.packageName?.toString() ?: return
        if (packageName !in CHROMIUM_BROWSERS) return
        try {
            evaluate(packageName, root)
        } catch (e: Exception) {
            diag("settle threw: ${e.javaClass.simpleName}")
        }
    }

    // --- walking the page --------------------------------------------------------------------

    /** Find the photos off the main thread. Coalesced: one walk at a time, the latest wins. */
    private fun requestWalk(root: AccessibilityNodeInfo) {
        if (walkInFlight) {
            walkDirty = true
            return
        }
        val key = coveredSurfaceKey ?: return
        val area = coveredArea ?: return
        // Not while the page moves. A scroll clears the node cache, so a walk then fetches every
        // node afresh from the browser's UI thread — the thread that also carries the scroll. The
        // anchors keep placed photos covered, the unchecked bands cover what no walk has looked
        // through, and the settle that ends the motion walks again.
        if (isMoving()) return
        val now = SystemClock.uptimeMillis()
        mainHandler.removeCallbacks(walkLater)
        if (now - lastWalkAt < STILL_WALK_INTERVAL_MILLIS) {
            mainHandler.postAtTime(walkLater, lastWalkAt + STILL_WALK_INTERVAL_MILLIS)
            return
        }
        val generation = screenGeneration
        walkInFlight = true
        lastWalkAt = now
        val minSide = dp(MIN_PHOTO_SIDE_DP)
        walkExecutor.execute {
            val startedAt = SystemClock.uptimeMillis()
            val nodes = HashMap<Long, AccessibilityNodeInfo>()
            val pages = ArrayList<AccessibilityNodeInfo>()
            val found = runCatching {
                ImageRegions.find(
                    root,
                    near = area,
                    reachScreens = STILL_REACH_SCREENS,
                    minSide = minSide,
                    nodes = nodes,
                    pages = pages,
                )?.let { freshBounds(it, nodes) }
            }.getOrNull()
            diag("walk photos=${found?.size ?: "unknown"} in ${SystemClock.uptimeMillis() - startedAt}ms")
            val reach = ImageRegions.reachOf(area, STILL_REACH_SCREENS)
            // The page itself, not a frame inside it: an embedded frame is a page node too.
            val page = pages.maxByOrNull { node -> Rect().also(node::getBoundsInScreen).let { it.width().toLong() * it.height() } }
            mainHandler.post { finishWalk(key, generation, startedAt, reach, found, nodes, page) }
        }
    }

    /**
     * A still page's positions become what the anchors measure from, so they must be fresh: the
     * node cache keeps whatever it read last, including positions read mid-fling.
     */
    private fun freshBounds(photos: List<Photo>, nodes: Map<Long, AccessibilityNodeInfo>): List<Photo> =
        photos.map { photo ->
            nodes[photo.id]?.takeIf { it.refresh() }?.let { photo.copy(bounds = ImageRegions.trueBounds(it)) } ?: photo
        }

    private fun finishWalk(
        key: String,
        generation: Int,
        startedAt: Long,
        reach: Rect,
        found: List<Photo>?,
        nodes: Map<Long, AccessibilityNodeInfo>,
        page: AccessibilityNodeInfo?,
    ) {
        walkInFlight = false
        if (key == coveredSurfaceKey) {
            // Read with the page still from start to finish: the positions can be the new base.
            val still = generation == screenGeneration && !isMoving()
            when {
                found == null -> knownPhotos = null
                still || knownPhotos == null -> place(found, nodes, page, startedAt)
                else -> addNewPhotos(found, startedAt)
            }
            if (found != null) {
                // Everything walked since the placement has been looked through — except below the
                // lowest photo, where the next results are added as the page nears its end.
                val lowest = found.filter { Rect.intersects(it.bounds, reach) }.maxOfOrNull { it.bounds.bottom }
                val looked = Rect(reach).apply { if (lowest != null && lowest < bottom) bottom = lowest }
                val now = checkedBy(looked, startedAt)
                checked = checked?.takeUnless { still }?.apply { union(now) } ?: now
            }
            redraw()
            fruitlessCaptures = 0
            // New photos on a still page need a capture: to be screened, and for their frost.
            if (placedStill() && (screenable(found).isNotEmpty() || unfrosted(found))) requestBackdrop()
        }
        if (walkDirty) {
            walkDirty = false
            frontRoot()?.let { root -> if (coveredSurfaceKey != null) requestWalk(root) }
        }
    }

    /** [found] becomes what the anchors measure from; see [AnchorTracker]. */
    private fun place(found: List<Photo>, nodes: Map<Long, AccessibilityNodeInfo>, page: AccessibilityNodeInfo?, at: Long) {
        knownPhotos = found
        tracks = emptyMap()
        trackHistory.clear()
        page?.let { pageBounds = Rect().also(it::getBoundsInScreen) }
        val generation = ++placement
        val focus = Rect(coveredArea ?: Rect())
        anchorHandler.post {
            tracker.place(found, nodes, focus, at)
            trackerPlacement = generation
            pageNode = page
        }
    }

    /**
     * Photos a walk found while the page moved and not placed yet — ones that scrolled near —
     * put back by however far their container had moved, into the positions the anchors measure
     * from. Already placed photos keep their places: the anchors know better where they are now.
     */
    private fun addNewPhotos(found: List<Photo>, at: Long) {
        val known = knownPhotos ?: return
        val placed = known.mapTo(HashSet()) { it.id }
        val offset = Point()
        val added = found.filter { it.id !in placed }.map { photo ->
            val track = tracks[photo.scroller] ?: return@map photo
            track.offsetAt(at, offset)
            photo.copy(bounds = Rect(photo.bounds).apply { offset(-offset.x, -offset.y) })
        }
        if (added.isNotEmpty()) knownPhotos = known + added
    }

    /** The part of the page a walk looked through, in the positions the anchors measure from. */
    private fun checkedBy(reach: Rect, at: Long): Rect {
        val track = tracks[mainScroller()] ?: return Rect(reach)
        val offset = Point()
        track.offsetAt(at, offset)
        return Rect(reach).apply { offset(-offset.x, -offset.y) }
    }

    /** The scroll container holding most of the known photos: the one the page scrolls by. */
    private fun mainScroller(): Long =
        knownPhotos.orEmpty().groupingBy { it.scroller }.eachCount().maxByOrNull { it.value }?.key
            ?: ImageRegions.NO_SCROLLER

    /**
     * While the page moves, the parts of the screen no walk has looked through yet, as patches,
     * so a photo arriving there is covered before a walk finds it.
     */
    private fun uncheckedBands(area: Rect): List<Photo> {
        val base = checked ?: return emptyList()
        val scroller = mainScroller()
        val offset = Point()
        tracks[scroller]?.offsetAt(SystemClock.uptimeMillis(), offset)
        val onScreen = Rect(base).apply { offset(offset.x, offset.y) }
        val bands = ArrayList<Photo>(2)
        if (onScreen.top > area.top) {
            bands += Photo(UNCHECKED_ABOVE, Rect(area.left, area.top, area.right, onScreen.top), scroller)
        }
        if (onScreen.bottom < area.bottom) {
            bands += Photo(UNCHECKED_BELOW, Rect(area.left, onScreen.bottom, area.right, area.bottom), scroller)
        }
        return bands.map { it.copy(bounds = Rect(it.bounds).apply { offset(-offset.x, -offset.y) }) }
    }

    /** Whether the known positions are the screen's: placed on a page that has not moved since. */
    private fun placedStill(): Boolean = !isMoving() && !isSettling() && tracks.isEmpty()

    private fun photoRegions(root: AccessibilityNodeInfo, area: Rect, minSide: Int): List<Photo>? =
        ImageRegions.find(root, near = area, reachScreens = STILL_REACH_SCREENS, minSide = minSide)

    /** Unscored photos wholly inside the covered area, nearest the middle of the screen first. */
    private fun screenable(photos: List<Photo>?): List<Photo> {
        val area = coveredArea ?: return emptyList()
        return photos.orEmpty().let(verdicts::unscreened)
            .filter { area.contains(it.bounds) }
            .sortedBy { abs(it.bounds.centerY() - area.centerY()) }
    }

    private fun dp(value: Int): Int = (value * service.resources.displayMetrics.density).toInt()

    /** The patches for the photos that stay covered, in screen coordinates. */
    private fun patches(known: List<Photo>): List<Photo> =
        ImageRegions.padded(verdicts.toCover(known), dp(PHOTO_PADDING_DP))

    /** Whether a covered photo on screen has no frost yet. */
    private fun unfrosted(photos: List<Photo>?): Boolean {
        val area = coveredArea ?: return false
        return patches(photos.orEmpty()).any { it.id !in photoFrosts && Rect.intersects(it.bounds, area) }
    }

    private fun forgetPhotoFrosts() {
        photoFrosts.clear()
        scrim?.showPhotoFrosts(emptyMap())
    }

    // --- drawing -----------------------------------------------------------------------------

    /**
     * Put the scrim in step with what is known: the whole area while a new page settles, its
     * photos are unknown, or it flies past too fast to follow; otherwise a patch over every photo
     * not judged clean — moved as far as its container has, and carried along while it moves.
     */
    private fun redraw() {
        val overlay = scrim ?: return
        val area = currentArea() ?: return
        val known = knownPhotos
        if (isSettling() || known == null) {
            overlay.cover(area)
            return
        }
        val current = tracks
        if (current.values.any { hypot(it.velocityX, it.velocityY) > FAST_FLING_PX_PER_MS }) {
            overlay.cover(area)
            return
        }
        val moving = isMoving()
        val pageMoved = current.values.any { it.shiftX != 0 || it.shiftY != 0 || it.velocityX != 0f || it.velocityY != 0f }
        val motion = if (!moving && current.isEmpty()) {
            null
        } else {
            PatchMotion(
                tracks = current,
                margin = if (moving) dp(MOVING_MARGIN_DP) else 0,
                // Until a container's speed is known a finger may already be dragging it.
                unmeasuredMargin = if (moving && pageMoved) dp(TOUCH_MARGIN_DP) else 0,
            )
        }
        val patches = if (moving) patches(known) + uncheckedBands(area) else patches(known)
        overlay.cover(area, patches, motion)
    }

    /**
     * The part of the screen the scrim may paint: from the top of the page's content to the
     * bottom of the screen. The top is the higher of the page's own top and the toolbar's bottom,
     * as last read; each lags the other at times, and the higher one never leaves a photo out.
     */
    private fun currentArea(): Rect? {
        val area = coveredArea ?: return null
        val screen = screenBounds()
        val top = minOf(pageBounds?.top ?: area.top, area.top)
        return Rect(screen.left, top.coerceIn(screen.top, screen.bottom), screen.right, screen.bottom)
    }

    // --- capturing and judging ---------------------------------------------------------------

    /**
     * Ask for a fresh capture of the browser behind the raised scrim. Coalesced: at most one in
     * flight, and never closer together than [BACKDROP_INTERVAL_MILLIS].
     */
    private fun requestBackdrop() {
        backdropDirty = true
        idleRefreshesLeft = BACKDROP_IDLE_REFRESHES
        if (backdropInFlight) return
        // The target time is fixed by the last capture, so a stream of events cannot starve it.
        mainHandler.removeCallbacks(captureBackdrop)
        mainHandler.postAtTime(captureBackdrop, lastBackdropAt + BACKDROP_INTERVAL_MILLIS)
    }

    private fun startBackdropCapture() {
        val target = backdropTarget ?: return
        if (scrim?.isShowing != true) return
        // A capture is a full-screen copy, and taken mid-scroll it costs the scroll frames. The
        // settle that ends the motion asks again.
        if (isMoving()) {
            backdropDirty = true
            return
        }

        backdropDirty = false
        backdropInFlight = true
        lastBackdropAt = SystemClock.uptimeMillis()

        // Only photos the walk placed while the page was still: a moving page's photos are not
        // where the walk said.
        val key = coveredSurfaceKey
        val generation = screenGeneration
        val activeScreener = screener
        val toScreen = if (activeScreener != null && placedStill()) {
            screenable(knownPhotos).take(PHOTOS_PER_CAPTURE)
        } else {
            emptyList()
        }
        val toFrost = patches(knownPhotos.orEmpty())
        val history = trackHistory.snapshot()
        val minSide = dp(MIN_PHOTO_SIDE_DP)

        val callback = object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                // On the frost executor, off the main thread.
                val startedAt = SystemClock.uptimeMillis()
                val output = runCatching {
                    BackdropBlur.readScreenshot(screenshot) { capture ->
                        CaptureOutput(
                            frosted = BackdropBlur.frost(capture, target.area, target.windowBounds),
                            frostArea = target.area,
                            photoFrosts = frostEach(capture, toFrost, history, capturedAt(screenshot), target),
                            screened = activeScreener?.screen(capture, toScreen, target.windowBounds).orEmpty(),
                        )
                    }
                }.getOrNull()
                val confirmed = output?.let { confirmStillInPlace(it, toScreen, target, minSide) }
                diag("capture screened=${toScreen.size} in ${SystemClock.uptimeMillis() - startedAt}ms")
                mainHandler.post { finishBackdrop(confirmed, key, generation, retry = false) }
            }

            override fun onFailure(errorCode: Int) {
                // A secure window (Incognito) or a busy platform. The scrim stays as it is.
                diag("capture failed code=$errorCode")
                mainHandler.post {
                    finishBackdrop(
                        null,
                        key,
                        generation,
                        retry = errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT,
                    )
                }
            }
        }

        try {
            service.takeScreenshotOfWindow(target.windowId, frostExecutor, callback)
        } catch (e: Exception) {
            diag("capture threw: ${e.javaClass.simpleName}")
            finishBackdrop(null, key, generation, retry = false)
        }
    }

    /** Frost each patch from the pixels it covered when the capture was taken. Frost thread. */
    private fun frostEach(
        capture: Bitmap,
        patches: List<Photo>,
        history: TrackHistory,
        uptime: Long,
        target: BackdropTarget,
    ): Map<Long, PhotoFrost> {
        val offset = Point()
        return patches.mapNotNull { patch ->
            history.offsetAt(patch.scroller, uptime, offset)
            val onScreen = Rect(patch.bounds).apply { offset(offset.x, offset.y) }
            if (!Rect.intersects(onScreen, target.area)) return@mapNotNull null
            BackdropBlur.frostPhoto(capture, onScreen, target.windowBounds)?.let { patch.id to it }
        }.toMap()
    }

    /** When [screenshot] was taken, in uptime milliseconds; some versions report nanoseconds. */
    private fun capturedAt(screenshot: AccessibilityService.ScreenshotResult): Long {
        val stamp = screenshot.timestamp
        return if (stamp > 100_000_000_000L) stamp / 1_000_000L else stamp
    }

    /**
     * Keep only scores for photos that are where the walk said they were, checked by walking the
     * tree again now the capture is taken. A photo that moved between the two walks may have been
     * cropped from the wrong pixels, and its score could belong to a different photo — the one
     * mistake that would uncover something explicit. Frost thread.
     */
    private fun confirmStillInPlace(
        output: CaptureOutput,
        screened: List<Photo>,
        target: BackdropTarget,
        minSide: Int,
    ): CaptureOutput {
        if (output.screened.isEmpty()) return output
        val now = runCatching { photoRegions(target.root, target.area, minSide) }.getOrNull()
            ?.associate { it.id to it.bounds }
        val before = screened.associate { it.id to it.bounds }
        val confirmed = if (now == null) {
            emptyList()
        } else {
            output.screened.filter { before[it.id] != null && now[it.id] == before[it.id] }
        }
        return CaptureOutput(output.frosted, output.frostArea, output.photoFrosts, confirmed)
    }

    private fun finishBackdrop(output: CaptureOutput?, key: String?, generation: Int, retry: Boolean) {
        backdropInFlight = false
        if (released) return
        // showBackdrop ignores a scrim that was lowered while this capture was in flight.
        output?.frosted?.let { scrim?.showBackdrop(it, output.frostArea) }
        if (output != null && key == coveredSurfaceKey) {
            // Frosts hold however the page has moved since; scores only count for the layout
            // they were taken from.
            showPhotoFrosts(output.photoFrosts)
            if (generation == screenGeneration) recordVerdicts(output.screened)
        }
        if (retry) backdropDirty = true
        // Photos past one capture's share are scored by the next, for as long as captures keep
        // scoring something.
        val scoredAny = output?.screened.orEmpty().any { it is PhotoScreener.Result.Scored }
        fruitlessCaptures = if (scoredAny) 0 else fruitlessCaptures + 1
        if (placedStill() && fruitlessCaptures < FRUITLESS_CAPTURES && screenable(knownPhotos).isNotEmpty()) {
            backdropDirty = true
        }
        when {
            backdropTarget == null -> Unit
            backdropDirty -> requestBackdrop()
            idleRefreshesLeft > 0 -> {
                idleRefreshesLeft--
                mainHandler.postDelayed(captureBackdrop, BACKDROP_IDLE_REFRESH_MILLIS)
            }
        }
    }

    private fun showPhotoFrosts(fresh: Map<Long, PhotoFrost>) {
        if (fresh.isEmpty()) return
        photoFrosts.putAll(fresh)
        // Keep only photos still on the page; a long scroll would otherwise pile them up.
        knownPhotos?.let { known -> photoFrosts.keys.retainAll(known.mapTo(HashSet()) { it.id }) }
        scrim?.showPhotoFrosts(photoFrosts.toMap())
    }

    private fun recordVerdicts(results: List<PhotoScreener.Result>) {
        val rules = policy ?: return
        val packageName = coveredPackage
        var scored = 0
        var forNudity = 0
        var forDrugs = 0
        var covered = 0
        for (result in results) {
            // Failed and not loaded stay covered and unscored, so a later capture tries again.
            if (result !is PhotoScreener.Result.Scored) continue
            scored++
            val nudity = rules.coversForNudity(result.scores)
            val drugs = rules.coversForDrugs(result.drugScore)
            if (nudity) forNudity++
            if (drugs) forDrugs++
            if (nudity || drugs) covered++
            verdicts.record(result.id, if (nudity || drugs) PhotoVerdicts.Verdict.BLOCKED else PhotoVerdicts.Verdict.CLEAN)
        }
        if (results.isNotEmpty()) diag("screened ${results.size}: scored=$scored nudity=$forNudity drugs=$forDrugs")
        redraw()

        if (covered > 0) {
            // Counts only: no query, no address, no picture (rule 3).
            scope.launch {
                eventRepository.record(
                    type = SafetyEventType.UNSAFE_SCREEN,
                    title = if (covered == 1) "An image search result was covered"
                    else "$covered image search results were covered",
                    detail = "$covered of $scored photos judged on an image search page stayed covered " +
                        "($forNudity for nudity, $forDrugs for drugs); the rest were uncovered. " +
                        "Judged on this device by an explicit-content model and a drug model built " +
                        "for the job. No picture, address or search is stored.",
                    packageName = packageName,
                    action = EventAction.BLURRED,
                    capabilityId = ProtectionFeature.VIDEO_BLURRING.id,
                    dedupeWindowMillis = 60_000L,
                )
            }
        }
    }

    private fun diag(message: String) {
        // Structural only: never a URL, a query or any page content.
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    companion object {
        private const val TAG = "BrowserImages"

        /**
         * The browsers whose tree says which nodes are photos. Measured on Chrome; the others
         * share its accessibility code. Firefox, Samsung Internet, Opera and DuckDuckGo stay with
         * the capture loop until they are driven on a device.
         */
        val CHROMIUM_BROWSERS: Set<String> = setOf(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.chrome.canary",
            "com.brave.browser",
            "com.microsoft.emmx",
            "com.kiwibrowser.browser",
        )

        /** Node ids that carry the current URL, across the Chromium browsers. */
        private val ADDRESS_BAR_IDS = listOf("url_bar", "search_box_text", "location_bar_status", "search_box_text_small")

        /**
         * Minimum gap between captures. The platform refuses screenshots closer together than
         * about 333 ms with `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT`.
         */
        private const val BACKDROP_INTERVAL_MILLIS = 400L

        /**
         * After the page goes quiet it is still captured this often, this many times: photos
         * finish decoding after the tree has gone quiet. Bounded, because each capture is a
         * full-screen copy.
         */
        private const val BACKDROP_IDLE_REFRESH_MILLIS = 1_000L
        private const val BACKDROP_IDLE_REFRESHES = 2

        /** A new page is covered whole this long; the same quiet after a scroll event ends it. */
        private const val SETTLE_MILLIS = 300L

        /** A finger landing counts the page as moving for this long. */
        private const val TOUCH_HOLD_MILLIS = 500L

        /** Room around a patch whose container has started to move at a speed not known yet. */
        private const val TOUCH_MARGIN_DP = 40

        /** Above this speed (px/ms) patches cannot keep up, and the whole area is covered. */
        private const val FAST_FLING_PX_PER_MS = 8f

        /** Anchors are read once a frame while the page moves. */
        private const val ANCHOR_POLL_MILLIS = 16L

        /** How far a walk looks past the screen, in screens. Walks only run on a still page. */
        private const val STILL_REACH_SCREENS = 3f

        /** A still page's walks at most this often. */
        private const val STILL_WALK_INTERVAL_MILLIS = 150L

        /** Photos scored per capture, nearest the middle first, so the first are revealed sooner. */
        private const val PHOTOS_PER_CAPTURE = 6

        /** Captures in a row that may score nothing before the page stops asking for more. */
        private const val FRUITLESS_CAPTURES = 3

        /** While the page moves the address bar is read at most this often. */
        private const val MOVING_EVALUATE_INTERVAL_MILLIS = 500L

        /** The page's own bounds are read on every this-many anchor reads. */
        private const val PAGE_READ_EVERY = 2

        /** Ids of the patches over the parts of a moving page no walk has looked through. */
        private const val UNCHECKED_ABOVE = Long.MIN_VALUE
        private const val UNCHECKED_BELOW = Long.MIN_VALUE + 1

        /** Margin around each photo, so the patch still covers it after a pixel or two. */
        private const val PHOTO_PADDING_DP = 8

        /** Further room around every patch while the page moves. */
        private const val MOVING_MARGIN_DP = 6

        /**
         * The smallest photo, on either side. Below it Chrome's image flag marks an icon — the
         * viewer's close, Lens and Visit buttons measured 37-42 dp.
         */
        private const val MIN_PHOTO_SIDE_DP = 48
    }
}
