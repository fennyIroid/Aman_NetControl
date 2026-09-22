package com.example.childsafetyimpl.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.childsafetyimpl.di.ApplicationScope
import com.example.childsafetyimpl.domain.model.RestrictedSurface
import com.example.childsafetyimpl.platform.ai.AiActivityMonitor
import com.example.childsafetyimpl.platform.enforcement.BlockOverlayController
import com.example.childsafetyimpl.platform.enforcement.SurfaceBlockDecision
import com.example.childsafetyimpl.platform.usage.AppInventoryProvider
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Screen-level detection and enforcement inside other apps.
 *
 * This service is what makes the Shorts, Status and AI protections possible at all. Nothing else
 * Android offers a normal app can tell the difference between a child messaging a friend in
 * Instagram and a child three hundred Reels deep, because both are the same package in the
 * foreground.
 *
 * It does three things and no more:
 *
 *  1. Walks the active window and describes it: which app, which surface, which signal matched.
 *  2. Asks [SurfaceGuard] what should happen, and where the answer is "close", presses back on the
 *     child's behalf and shows the reason.
 *  3. On an AI conversation, hands the prompt text to [AiActivityMonitor], which scores it in
 *     memory and keeps only a verdict.
 *
 * The service never persists what it reads. Screen text is scored and dropped inside the call
 * stack it arrived on; what survives is a surface name, a duration and a risk verdict.
 *
 * Two limits are structural rather than incidental, and the protection screens say so. The
 * signatures describe other apps' private implementations and can stop matching after any update
 * to those apps. And back is a request, not a command: an app that consumes it stays open, which
 * is why the guard records whether the close actually worked instead of assuming it did.
 */
@AndroidEntryPoint
class SafetyAccessibilityService : AccessibilityService() {

    @Inject lateinit var signalStore: ScreenSignalStore
    @Inject lateinit var surfaceGuard: SurfaceGuard
    @Inject lateinit var searchGuard: SearchTermGuard
    @Inject lateinit var aiMonitor: AiActivityMonitor
    @Inject lateinit var inventory: AppInventoryProvider
    @Inject lateinit var overlayController: BlockOverlayController

    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    /** Guards against re-walking the tree for every scroll event inside a feed. */
    private var lastWalkAt = 0L

    /** Stops a single stubborn screen producing a stream of back presses. */
    private var lastCloseAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = this
        signalStore.setConnected(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString() ?: return
        if (packageName == this.packageName) return

        // How far the page moved, as the app doing the moving reports it, for the covers to follow.
        // Numbers only — a delta, a position, a time — and nothing else about the event or its
        // source is read: rule 3. Handled before everything below and never walks the tree, so
        // it is off the throttle; a scroll report dropped is a cover left behind by that much.
        // See ScrollLedger.
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            val delta = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                event.scrollDeltaY.takeIf { it != UNDEFINED_SCROLL }
            } else {
                null
            }
            val position = event.scrollY.takeIf { it >= 0 && event.maxScrollY > 0 }
            signalStore.scroll.record(
                at = event.eventTime,
                packageName = packageName,
                source = "${event.windowId}/${event.className}",
                delta = delta,
                position = position,
            )
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            -> Unit
            else -> return
        }

        // Published before the throttle and before any tree walk, because it costs nothing and
        // because the thing that needs it cannot wait. A window change is the moment every cover
        // the blurring pipeline is holding stopped describing what is on screen, and that pipeline
        // is otherwise left to infer it from pixels — which it does badly, because an app switch
        // and a scene change in a video look far more alike than either does to a person. See
        // ScreenSignalStore.noteForegroundWindow. Nothing about the window is read here beyond
        // which app owns it.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            signalStore.noteForegroundWindow(packageName)
        }

        // Content-changed events arrive continuously while a feed scrolls. Throttling here is not
        // an optimisation: it is what keeps this service from becoming the battery cost that gets
        // it switched off.
        val now = SystemClock.elapsedRealtime()
        if (now - lastWalkAt < THROTTLE_MILLIS) return
        lastWalkAt = now

        val root = rootInActiveWindow ?: return
        val startedAt = SystemClock.uptimeMillis()

        val walk = try {
            walkTree(
                root = root,
                collectText = SurfaceSignatures.surfacesIn(packageName)
                    .contains(RestrictedSurface.AI_CONVERSATION),
                addressBarIds = BrowserSurfaces.addressBarIdsFor(packageName),
            )
        } catch (_: Exception) {
            // A node tree can be recycled underneath the walk at any moment. Losing one sample is
            // acceptable; crashing the accessibility service is not.
            return
        }

        val duration = SystemClock.uptimeMillis() - startedAt
        val match = matchSurface(packageName, event.className?.toString(), walk)
        val appLabel = inventory.labelFor(packageName)

        signalStore.publish(
            ScreenSignal(
                packageName = packageName,
                appLabel = appLabel,
                className = event.className?.toString(),
                surface = match?.first,
                matchedOn = match?.second,
                nodeCount = walk.nodeCount,
                textNodeCount = walk.textNodeCount,
                walkDurationMillis = duration,
                atMillis = System.currentTimeMillis(),
            )
        )

        if (match?.first == RestrictedSurface.AI_CONVERSATION) {
            // Passed by value and scored in memory. The strings die with this call.
            val conversation = walk.texts.toList()
            scope.launch { aiMonitor.onConversation(packageName, appLabel, conversation) }
        }

        if (BrowserSurfaces.isBrowser(packageName)) {
            // Passed by value and judged in memory, the same as the AI conversation above. What
            // was searched for never reaches storage.
            val candidates = walk.searchCandidates()
            scope.launch {
                when (val verdict = searchGuard.onBrowserScreen(candidates)) {
                    SearchTermGuard.Verdict.Allow -> Unit
                    is SearchTermGuard.Verdict.Close ->
                        leaveBrowser(verdict, packageName, appLabel)
                }
            }
        }

        scope.launch {
            when (val verdict = surfaceGuard.onScreen(match?.first, packageName, appLabel, match?.second)) {
                SurfaceGuard.Verdict.Allow -> Unit
                is SurfaceGuard.Verdict.Close -> enforce(verdict, packageName, appLabel)
            }
        }
    }

    /**
     * Leaves a restricted surface.
     *
     * Back rather than launching a screen of our own, because from Android 10 a background process
     * is largely prevented from starting an activity, and the child ends up sitting in the feed
     * behind a block screen that never appeared. Back returns them to where they were, which is
     * both more reliable and less punitive.
     */
    private suspend fun enforce(
        verdict: SurfaceGuard.Verdict.Close,
        packageName: String,
        appLabel: String,
    ) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastCloseAt < CLOSE_COOLDOWN_MILLIS) return
        lastCloseAt = now

        val closed = try {
            performGlobalAction(GLOBAL_ACTION_BACK)
        } catch (_: Exception) {
            false
        }

        signalStore.recordClosure(verdict.surface)
        surfaceGuard.onClosed(verdict, closed)

        overlayController.showSurfaceBlock(
            SurfaceBlockDecision(
                surface = verdict.surface,
                packageName = packageName,
                appLabel = appLabel,
                message = verdict.childMessage,
                closed = closed,
            )
        )
    }

    /**
     * Leaves a browser that is showing a restricted search.
     *
     * Home rather than back, which is the opposite of what the surface guard does, and for a
     * reason. Backing out of a results page lands on the search box with the query still in it,
     * which matches again on the very next screen; the child ends up in a loop and the log fills
     * with the same event. Home ends it in one action.
     */
    private suspend fun leaveBrowser(
        verdict: SearchTermGuard.Verdict.Close,
        packageName: String,
        appLabel: String,
    ) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastCloseAt < CLOSE_COOLDOWN_MILLIS) return
        lastCloseAt = now

        val left = try {
            performGlobalAction(GLOBAL_ACTION_HOME)
        } catch (_: Exception) {
            false
        }

        searchGuard.onClosed(verdict, packageName, appLabel, left)

        overlayController.showSurfaceBlock(
            SurfaceBlockDecision(
                surface = RestrictedSurface.WEB_SEARCH,
                packageName = packageName,
                appLabel = appLabel,
                message = verdict.childMessage,
                closed = left,
            )
        )
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (connected === this) connected = null
        signalStore.setConnected(false)
        scope.launch { surfaceGuard.release() }
        super.onDestroy()
    }

    // --- tree walking -------------------------------------------------------------------------

    private class WalkResult {
        var nodeCount = 0
        var textNodeCount = 0
        val viewIds = mutableSetOf<String>()
        val labels = mutableSetOf<String>()

        /** Only populated for AI conversation surfaces, and never stored. */
        val texts = mutableListOf<String>()

        /** The address bar and any editable field, only inside a browser. */
        val editableTexts = mutableListOf<String>()

        /**
         * Everything worth judging on a browser screen, and nothing else.
         *
         * The address bar and text fields carry what the child typed. Page titles are included
         * only when they carry a search engine's own results suffix, which is what makes a search
         * that has already run visible without reading the page it produced.
         */
        fun searchCandidates(): List<String> = buildList {
            addAll(editableTexts)
            labels.forEach { label ->
                BrowserSurfaces.searchQueryFromTitle(label)?.let { add(it) }
            }
        }.filter { it.isNotBlank() }.distinct().take(MAX_SEARCH_CANDIDATES)
    }

    /**
     * Depth-limited breadth walk of the active window.
     *
     * The caps matter. A social feed's hierarchy is extremely deep, and an unbounded walk on the
     * main thread is exactly how an accessibility service ends up being blamed for a device
     * feeling slow.
     */
    private fun walkTree(
        root: AccessibilityNodeInfo,
        collectText: Boolean,
        addressBarIds: Set<String>,
    ): WalkResult {
        val result = WalkResult()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)

        while (queue.isNotEmpty() && result.nodeCount < MAX_NODES) {
            val (node, depth) = queue.removeFirst()
            result.nodeCount++

            val viewId = node.viewIdResourceName
            viewId?.let { result.viewIds.add(it) }
            node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let {
                result.labels.add(it.trim())
            }

            val text = node.text?.toString()
            if (!text.isNullOrBlank()) {
                result.textNodeCount++
                if (text.length <= MAX_LABEL_LENGTH) result.labels.add(text.trim())
                if (collectText && result.texts.size < MAX_TEXT_NODES) {
                    result.texts.add(text.trim())
                }
                // The address bar, or any field the child can type into. Never the page body:
                // matching a restricted word anywhere on screen would close a news article.
                val editable = (viewId != null && viewId in addressBarIds) || node.isEditable
                if (editable &&
                    text.length <= MAX_SEARCH_LENGTH &&
                    result.editableTexts.size < MAX_SEARCH_CANDIDATES
                ) {
                    result.editableTexts.add(text.trim())
                }
            }

            if (depth < MAX_DEPTH) {
                for (index in 0 until node.childCount) {
                    val child = node.getChild(index) ?: continue
                    queue.add(child to depth + 1)
                }
            }
        }
        return result
    }

    /**
     * Returns the matched surface and the signal that produced it, or null for no match.
     *
     * View ids are tried first because they are the least ambiguous, then labels, then class
     * names. Exclusions are checked before anything else: a signature that both matches and is
     * excluded must not fire, and checking exclusions last would let ordering decide it.
     */
    private fun matchSurface(
        packageName: String,
        className: String?,
        walk: WalkResult,
    ): Pair<RestrictedSurface, String>? {
        for (signature in SurfaceSignatures.forPackage(packageName)) {
            val excluded = signature.excludeLabels.any { excluded ->
                walk.labels.any { it.equals(excluded, ignoreCase = true) }
            }
            if (excluded) continue

            signature.viewIds.firstOrNull { it in walk.viewIds }?.let {
                return signature.surface to "view id $it"
            }
            signature.labels.firstOrNull { label ->
                walk.labels.any { it.equals(label, ignoreCase = true) }
            }?.let {
                return signature.surface to "label \"$it\""
            }
            if (className != null) {
                signature.classFragments
                    .firstOrNull { className.contains(it, ignoreCase = true) }
                    ?.let { return signature.surface to "screen name containing \"$it\"" }
            }
        }
        return null
    }

    companion object {

        /**
         * The connected instance, or null.
         *
         * An accessibility service is constructed by the system and cannot be injected into, so
         * this is the only way for code elsewhere to ask it to act. Cleared on destroy, because a
         * held reference to a torn-down service performs nothing and reports success.
         */
        @Volatile
        private var connected: SafetyAccessibilityService? = null

        /**
         * Sends the child to the launcher, leaving whatever app they were in.
         *
         * Returns false when the service is not connected, which is a real state rather than an
         * error: the parent may not have granted it. The caller falls back to a home intent, and
         * the difference matters because that fallback needs its own permission to work.
         */
        fun goHome(): Boolean {
            val service = connected ?: return false
            return runCatching { service.performGlobalAction(GLOBAL_ACTION_HOME) }
                .getOrDefault(false)
        }

        /**
         * A window manager whose windows the system trusts, or null when this service is off.
         *
         * This is the way out of the trade described on `BlockOverlayController.coverFlags`. From
         * Android 12 the system caps the opacity of any pass-through overlay at
         * `maximum_obscuring_opacity_for_touch`, so an ordinary app has to choose between a cover
         * that is opaque and a cover that lets the child scroll. The cap exists to stop an app
         * hiding what a person is about to touch, and it is applied only to *untrusted* windows.
         * A window added through an accessibility service is trusted, because the system already
         * relies on such services to draw over other apps for the people who need them.
         *
         * So a cover added here is allowed to be both opaque and invisible to touch, which is the
         * only combination that is actually a cover and still leaves the device usable. Returning
         * null is an ordinary outcome, not an error: the parent may not have granted the service,
         * and the caller falls back to an app overlay that works at reduced strength.
         */
        fun trustedWindowManager(): WindowManager? {
            val service = connected ?: return null
            return runCatching {
                service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            }.getOrNull()
        }

        /** Whether the trusted route is available, for anything that has to say so on screen. */
        fun isConnected(): Boolean = connected != null

        private const val THROTTLE_MILLIS = 700L

        /** What `scrollDeltaY` holds when the app did not say. */
        private const val UNDEFINED_SCROLL = -1

        /** A stubborn screen must not turn into a stream of back presses. */
        private const val CLOSE_COOLDOWN_MILLIS = 2500L

        private const val MAX_NODES = 900
        private const val MAX_DEPTH = 22
        private const val MAX_LABEL_LENGTH = 120
        private const val MAX_TEXT_NODES = 40

        /** A search is a handful of words. Anything longer is a page, not a query. */
        private const val MAX_SEARCH_LENGTH = 300
        private const val MAX_SEARCH_CANDIDATES = 8
    }
}
