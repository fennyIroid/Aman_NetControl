package com.example.childsafetyimpl.platform.enforcement

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.childsafetyimpl.MainActivity
import com.example.childsafetyimpl.navigation.Routes
import com.example.childsafetyimpl.platform.accessibility.SafetyAccessibilityService
import com.example.childsafetyimpl.ui.block.BlockOverlayContent
import com.example.childsafetyimpl.ui.block.SurfaceBlockNotice
import com.example.childsafetyimpl.ui.block.CoveredPatch
import com.example.childsafetyimpl.ui.block.CoverNotice
import com.example.childsafetyimpl.ui.block.PatchCover
import com.example.childsafetyimpl.ui.block.VideoBlurContent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Every window this app draws on top of another app.
 *
 * There are three, and they are deliberately different in weight:
 *
 *  - **The block screen** fills the display when an app the child is not allowed to use is opened.
 *  - **The surface notice** is a small card that appears for a few seconds when a restricted
 *    screen inside a permitted app was closed. It has to be brief, or Instagram becomes unusable
 *    for the messaging a parent chose to allow.
 *  - **The blur layer** covers the picture while unsafe video is playing, and lifts by itself the
 *    moment the frames come back clean.
 *
 * A window added through the window manager is used rather than a launched activity, and that is
 * not a stylistic choice: from Android 10 a background process is largely prevented from starting
 * an activity, so a blocking activity started from the monitoring service simply would not appear
 * on most builds. A window still does.
 *
 * The block screen is dismissible on purpose. A window with no way out would be treated as
 * disruptive by Google Play, and trapping a child behind a screen they cannot leave is a safety
 * problem of its own making.
 */
@Singleton
class BlockOverlayController @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val mainHandler = Handler(Looper.getMainLooper())

    private var blockView: View? = null
    private var blockHost: OverlayLifecycleHost? = null

    private var noticeView: View? = null
    private var noticeHost: OverlayLifecycleHost? = null
    private var noticeDismiss: Runnable? = null

    private var blurView: View? = null
    private var blurHost: OverlayLifecycleHost? = null

    /**
     * Held separately from the whole-screen cover because the two can be up at once and are
     * torn down on different schedules. Sharing one field made a region update remove the
     * full-screen cover, which uncovered the video it was hiding.
     */
    /**
     * One window per covered picture, and the marker that says so.
     *
     * Reused across frames by position in the list rather than removed and re-added, because a
     * window removed and added again is a frame with the picture on screen and a visible blink.
     * What a cover update actually does is move a window and write new state into it.
     */
    private val patchWindows = mutableListOf<PatchWindow>()
    private var noticeChipView: View? = null
    private var noticeChipHost: OverlayLifecycleHost? = null

    /**
     * What one patch window is drawing, held as observable state rather than passed to setContent.
     *
     * The difference is not a refactor. `setContent` throws the whole composition away and builds
     * a new one, so every `remember` in it starts again from nothing and every animation restarts
     * from its first frame. Doing that on each update turned a slow drift into a visible stutter,
     * and it did the work of laying out the window again to draw a rectangle in a slightly
     * different place. Writing to state instead keeps the composition alive and redraws only what
     * actually changed.
     */
    private class PatchWindow(
        val view: ComposeView,
        val host: OverlayLifecycleHost,
        val params: WindowManager.LayoutParams,
        val patch: androidx.compose.runtime.MutableState<CoveredPatch?>,
        val strength: androidx.compose.runtime.MutableFloatState,
    )

    /**
     * Which of the two window routes the covers currently sit on, or null when none are up.
     *
     * The accessibility service can be switched on or off while covers are on screen, and the two
     * routes produce windows of different types with different opacity rules. Mixing them would
     * leave some covers opaque and some washed out with no pattern a parent could make sense of,
     * so a change of route tears the covers down and rebuilds them on the new one.
     */
    private var patchRouteTrusted: Boolean? = null

    private val _visible = MutableStateFlow<BlockDecision?>(null)
    val visible: StateFlow<BlockDecision?> = _visible.asStateFlow()

    /** What was last handed to the cover, so an unchanged frame does nothing. Main thread only. */
    private var lastPatches: List<CoveredPatch>? = emptyList()
    private var lastStrength: Float = -1f

    private val _blurring = MutableStateFlow(false)
    val blurring: StateFlow<Boolean> = _blurring.asStateFlow()

    fun canShow(): Boolean = Settings.canDrawOverlays(context)

    // --- the full block screen ----------------------------------------------------------------

    /**
     * [leaveAppOnClose] is false only for a notice rather than a refusal — the internet pause —
     * where the app itself is allowed and closing should hand the child back to it, offline.
     */
    fun show(
        decision: BlockDecision,
        leaveAppOnClose: Boolean = true,
        onDismiss: () -> Unit,
    ) {
        if (!canShow()) return
        if (_visible.value?.packageName == decision.packageName) return
        hide()

        onMain {
            val host = OverlayLifecycleHost().apply { start() }
            val dismiss = {
                hide()
                onDismiss()
                if (leaveAppOnClose) leaveBlockedApp()
            }
            val askParent = {
                hide()
                onDismiss()
                openPermissions()
            }
            val vpnSettings = {
                hide()
                onDismiss()
                openVpnSettings()
            }
            val composeView = ComposeView(context).apply {
                attachTo(host)
                setContent {
                    BlockOverlayContent(
                        decision = decision,
                        onAskParent = askParent,
                        onClose = dismiss,
                        onOpenVpnSettings = vpnSettings,
                    )
                }

                // A window added through the window manager has no back dispatcher, so the system
                // back gesture would otherwise arrive here and be swallowed with nothing to
                // handle it. Handling it explicitly is what keeps the promise that the child is
                // never trapped behind this screen.
                isFocusableInTouchMode = true
                requestFocus()
                setOnKeyListener { _, keyCode, event ->
                    if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                        dismiss()
                        true
                    } else {
                        false
                    }
                }
            }

            val params = layoutParams(
                width = WindowManager.LayoutParams.MATCH_PARENT,
                height = WindowManager.LayoutParams.MATCH_PARENT,
                flags = WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            )

            try {
                windowManager.addView(composeView, params)
                blockView = composeView
                blockHost = host
                _visible.value = decision
            } catch (_: Exception) {
                // Overlay permission can be revoked between the check and the add, and some OEM
                // builds refuse the window outright. Failing quietly beats crashing the monitor.
                host.stop()
            }
        }
    }

    /**
     * Takes the child out of the app the rule applies to.
     *
     * Taking the block screen away on its own would hand them straight back to the app it just
     * refused, and the monitor would raise the screen again on its next poll: a flicker the child
     * can tap through until the timing goes their way. Leaving for the launcher ends it once.
     *
     * Two routes, because either permission may be missing. The accessibility service performs a
     * real home action; without it, a home intent works only because holding the overlay
     * permission is itself what lets this app start an activity from the background, and that is
     * the same permission the block screen needed to exist.
     */
    private fun leaveBlockedApp() {
        if (SafetyAccessibilityService.goHome()) return
        val home = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(home) }
    }

    /**
     * Opens this app on the permissions screen, which is what "ask a parent" has to mean here.
     *
     * There is no message to send and nobody to send it to: the parent is the person holding the
     * phone or the person who set it up. What answers the child is the screen where the rules and
     * the access behind them live, so the button goes there rather than dismissing itself.
     */
    private fun openPermissions() {
        runCatching {
            context.startActivity(MainActivity.intent(context, Routes.permissions()))
        }.onFailure { leaveBlockedApp() }
    }

    /**
     * Opens Android's VPN screen, which is where any VPN — an app's or one set up by hand — is
     * switched off. Settings stays usable while a VPN is up (see `VpnLockout`), and the surface
     * guard leaves the VPN screen open for exactly as long as one is connected.
     */
    private fun openVpnSettings() {
        val opened = runCatching {
            context.startActivity(
                Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
        if (!opened) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { leaveBlockedApp() }
        }
    }

    fun hide() = onMain {
        blockView?.let { view -> runCatching { windowManager.removeView(view) } }
        blockView = null
        blockHost?.stop()
        blockHost = null
        _visible.value = null
    }

    // --- the brief surface notice --------------------------------------------------------------

    /**
     * Shown when a restricted screen inside a permitted app was closed.
     *
     * Self-dismissing, and not focusable: the child has just been returned to the app they were
     * in, and a window that steals focus at that moment would feel like a second interruption.
     */
    fun showSurfaceBlock(decision: SurfaceBlockDecision) {
        if (!canShow()) return

        onMain {
            hideSurfaceBlock()

            val host = OverlayLifecycleHost().apply { start() }
            val composeView = ComposeView(context).apply {
                attachTo(host)
                setContent { SurfaceBlockNotice(decision = decision) }
            }

            val params = layoutParams(
                width = WindowManager.LayoutParams.MATCH_PARENT,
                height = WindowManager.LayoutParams.WRAP_CONTENT,
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            )

            try {
                windowManager.addView(composeView, params)
                noticeView = composeView
                noticeHost = host
                val dismiss = Runnable { hideSurfaceBlock() }
                noticeDismiss = dismiss
                mainHandler.postDelayed(dismiss, NOTICE_DURATION_MILLIS)
            } catch (_: Exception) {
                host.stop()
            }
        }
    }

    fun hideSurfaceBlock() = onMain {
        noticeDismiss?.let(mainHandler::removeCallbacks)
        noticeDismiss = null
        noticeView?.let { view -> runCatching { windowManager.removeView(view) } }
        noticeView = null
        noticeHost?.stop()
        noticeHost = null
    }

    // --- the video blur layer -------------------------------------------------------------------

    /**
     * The one way the cover changes.
     *
     * Everything about which windows exist happens here, on the main thread, in one pass. That is
     * not tidiness. The previous version exposed content: `showVideoBlur` read the "am I covering"
     * flag on the caller's thread while `hideRegionBlur` cleared it inside a post to the main
     * thread, so switching from a region cover to a whole-screen one read a flag that was still
     * set, returned early, and then let the queued removal take the region cover down. The result
     * was an unsafe frame with nothing over it, arrived at by code that had just decided to cover
     * it. One entry point on one thread is what makes that unrepresentable.
     *
     * @param patches null covers the whole screen; an empty list covers nothing; otherwise these
     *   pictures, positioned as fractions of the screen rather than in pixels
     */
    fun applyCover(patches: List<CoveredPatch>?, strength: Float) = onMain {
        if (!canShow()) return@onMain

        // The capture loop calls this on every sampled frame, and most frames change nothing.
        // Re-running setContent twice a second re-draws the covers over a scrolling page for no
        // reason, which is visible as stutter on exactly the screens this is most likely to be up.
        val unchanged = patches == lastPatches && strength == lastStrength
        lastPatches = patches
        lastStrength = strength
        if (unchanged) return@onMain

        when {
            patches == null -> {
                removeRegionCover()
                addFullScreenCover(strength)
            }

            patches.isEmpty() -> {
                removeRegionCover()
                removeFullScreenCover()
            }

            else -> {
                removeFullScreenCover()
                addRegionCover(patches, strength)
            }
        }

        _blurring.value = blurView != null || patchWindows.isNotEmpty()
    }

    /** Takes every cover down. Used when the session ends, and it must never fail silently. */
    fun clearCover() = onMain {
        lastPatches = emptyList()
        lastStrength = -1f
        removeRegionCover()
        removeFullScreenCover()
        _blurring.value = false
    }

    /**
     * The whole-screen cover.
     *
     * Not focusable and not touch-modal, so a tap still reaches the pause and back controls
     * underneath. Covering the picture is the goal; taking the device away is not, and a layer that
     * swallowed touches would leave the child unable to stop the video it is complaining about.
     * The cover is heavy enough that those controls are felt for rather than seen, which is the
     * trade this makes on purpose: a cover the picture survives is not a cover.
     */
    private fun addFullScreenCover(strength: Float) {
        if (blurView != null) return

        val surface = coverSurface()
        val host = OverlayLifecycleHost().apply { start() }
        val composeView = ComposeView(context).apply {
            attachTo(host)
            setContent { VideoBlurContent(strength = strength) }
        }

        val params = layoutParams(
            width = WindowManager.LayoutParams.MATCH_PARENT,
            height = WindowManager.LayoutParams.MATCH_PARENT,
            flags = coverFlags(),
            type = surface.type,
        ).apply {
            coverWholeDisplay()
            blurBehind(strength)
        }

        try {
            surface.manager.addView(composeView, params)
            blurView = composeView
            blurHost = host
        } catch (_: Exception) {
            host.stop()
        }
    }

    /**
     * Covers only the pictures given, leaving the rest of the screen visible and usable.
     *
     * One window per picture, all of them passing touches through, so the page underneath scrolls
     * and taps exactly as it did before anything was covered. See [coverSurface] for what makes
     * that compatible with an opaque cover.
     *
     * Windows are reused in order and only moved, never removed and re-added for a position
     * change. Adding and removing a window twice a second produces a visible flicker, and during
     * each gap the thing being covered is on screen unobstructed.
     */
    @Suppress("LongMethod")
    private fun addRegionCover(patches: List<CoveredPatch>, strength: Float) {
        val (screenWidth, screenHeight) = displaySize()
        if (screenWidth <= 0 || screenHeight <= 0) return

        val surface = coverSurface()
        if (patchRouteTrusted != null && patchRouteTrusted != surface.trusted) removeRegionCover()
        patchRouteTrusted = surface.trusted

        var addedWindow = false

        patches.forEachIndexed { index, patch ->
            val left = (patch.area.left * screenWidth).roundToInt().coerceIn(0, screenWidth)
            val top = (patch.area.top * screenHeight).roundToInt().coerceIn(0, screenHeight)
            val width = (patch.area.width * screenWidth).roundToInt()
                .coerceIn(1, screenWidth - left)
            val height = (patch.area.height * screenHeight).roundToInt()
                .coerceIn(1, screenHeight - top)

            val existing = patchWindows.getOrNull(index)
            if (existing != null) {
                existing.patch.value = patch
                existing.strength.floatValue = strength
                val moved = existing.params.x != left || existing.params.y != top ||
                    existing.params.width != width || existing.params.height != height
                if (moved) {
                    existing.params.x = left
                    existing.params.y = top
                    existing.params.width = width
                    existing.params.height = height
                    runCatching { windowManager.updateViewLayout(existing.view, existing.params) }
                }
                return@forEachIndexed
            }

            val state = mutableStateOf<CoveredPatch?>(patch)
            val level = mutableFloatStateOf(strength)
            val host = OverlayLifecycleHost().apply { start() }
            val composeView = ComposeView(context).apply {
                attachTo(host)
                setContent {
                    PatchCover(
                        patch = state.value,
                        strength = level.floatValue,
                        capped = !surface.trusted,
                    )
                }
            }
            val params = layoutParams(
                width = width,
                height = height,
                flags = coverFlags(),
                gravity = Gravity.TOP or Gravity.START,
                type = surface.type,
            ).apply {
                x = left
                y = top
                coverWholeDisplay()
            }

            try {
                surface.manager.addView(composeView, params)
                patchWindows += PatchWindow(composeView, host, params, state, level)
                addedWindow = true
            } catch (_: Exception) {
                host.stop()
            }
        }

        while (patchWindows.size > patches.size) {
            val extra = patchWindows.removeAt(patchWindows.size - 1)
            runCatching { windowManager.removeView(extra.view) }
            extra.host.stop()
        }

        // Overlays of the same type stack in the order they were added, so a patch window created
        // after the marker is drawn over it — and a marker hidden under the very thing it is
        // explaining is worse than none. Re-adding it last is what keeps it on top.
        if (addedWindow) removeCoverNotice()
        showCoverNotice()
    }

    /**
     * The marker that says something on this screen is covered.
     *
     * Its own window, because the patches no longer share one to draw it in. It passes touches
     * through and is deliberately small: it is a label, not a cover, so the opacity cap that
     * applies to pass-through overlays costs it nothing.
     */
    private fun showCoverNotice() {
        if (noticeChipView != null) return

        val surface = coverSurface()
        val host = OverlayLifecycleHost().apply { start() }
        val composeView = ComposeView(context).apply {
            attachTo(host)
            setContent { CoverNotice() }
        }
        val params = layoutParams(
            width = WindowManager.LayoutParams.WRAP_CONTENT,
            height = WindowManager.LayoutParams.WRAP_CONTENT,
            flags = coverFlags(),
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            type = surface.type,
        ).apply { y = NOTICE_BOTTOM_MARGIN_PX }

        try {
            surface.manager.addView(composeView, params)
            noticeChipView = composeView
            noticeChipHost = host
        } catch (_: Exception) {
            host.stop()
        }
    }

    /**
     * The size of the display, which is the coordinate space the patches are measured in.
     *
     * The capture is of the whole display, so a fraction of a frame is a fraction of the display
     * and of nothing else. Measuring against a window instead is how a cover ends up a system bar's
     * height away from the picture it belongs to.
     */
    private fun displaySize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            return bounds.width() to bounds.height()
        }
        @Suppress("DEPRECATION")
        val metrics = android.util.DisplayMetrics().also {
            windowManager.defaultDisplay.getRealMetrics(it)
        }
        return metrics.widthPixels to metrics.heightPixels
    }

    /**
     * Lays a window out against the display rather than against what is left of it.
     *
     * Without this a window is fitted inside the system bars, so it is shorter than the display and
     * its origin is below the status bar. Every fraction then resolves a few percent too high and
     * a few percent too short, which on a phone is tens of pixels: enough for a cover to sit beside
     * the picture it was measured on instead of over it.
     */
    private fun WindowManager.LayoutParams.coverWholeDisplay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fitInsetsTypes = 0
    }

    /**
     * The flags a cover window must have, and the reason it is a function rather than four lines
     * repeated twice.
     *
     * [WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE] is the one that matters and it was missing.
     * A cover is full-screen — the region cover too, because the patches are painted inside one
     * transparent window — and a full-screen window with only FLAG_NOT_TOUCH_MODAL consumes every
     * touch on the display. That flag only passes through what lands *outside* the window, and a
     * window the size of the screen has no outside. Nothing in the cover's view tree handles a
     * touch, so taps, scrolls and swipes were delivered to it, ignored, and never reached the app
     * underneath: the phone appeared to freeze for as long as anything was covered, and the child
     * could not scroll the page or leave the browser.
     *
     * NOT_TOUCHABLE also delivers the behaviour the whole-screen cover always claimed, which is
     * that the video's own pause and back controls stay reachable through it. Covering the picture
     * is the goal; taking the device away is not, and a cover that traps a child behind it is a
     * safety problem of its own making.
     *
     * Every cover carries it, the picture-sized ones included. They briefly did not: a window that
     * takes its own touches is exempt from the opacity cap, so making each patch touchable was the
     * only way an app overlay could be opaque. It cost more than it bought. One covered picture
     * that does not respond is a curiosity; a grid of them is a screen the child cannot scroll,
     * and that is precisely the screen this feature is for. [coverSurface] is the way out — a
     * trusted window is not capped and does not have to take touches to be opaque — and where it
     * is unavailable the cover gives up strength rather than giving up the scroll.
     *
     * The two layout flags are what make a patch land on the picture it was measured on. A cover
     * is handed rectangles as fractions of a screen capture, and a capture is of the whole display.
     * An overlay window by default is laid out inside the system bars, so without these its origin
     * is below the status bar and it is shorter than the display by both bars — every fraction then
     * resolves a few percent too high and a few percent too short, and each cover sits beside its
     * picture instead of over it. The bars stay usable regardless, because the window does not take
     * touches.
     */
    private fun coverFlags(): Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

    private fun removeRegionCover() {
        patchWindows.forEach { window ->
            runCatching { windowManager.removeView(window.view) }
            window.host.stop()
        }
        patchWindows.clear()
        patchRouteTrusted = null

        removeCoverNotice()
    }

    private fun removeCoverNotice() {
        noticeChipView?.let { view -> runCatching { windowManager.removeView(view) } }
        noticeChipView = null
        noticeChipHost?.stop()
        noticeChipHost = null
    }

    private fun removeFullScreenCover() {
        blurView?.let { view -> runCatching { windowManager.removeView(view) } }
        blurView = null
        blurHost?.stop()
        blurHost = null
    }

    fun hideAll() {
        hide()
        hideSurfaceBlock()
        clearCover()
    }

    // --- plumbing --------------------------------------------------------------------------------

    private fun layoutParams(
        width: Int,
        height: Int,
        flags: Int,
        gravity: Int = Gravity.CENTER,
        type: Int = appOverlayType(),
    ): WindowManager.LayoutParams =
        WindowManager.LayoutParams(width, height, type, flags, PixelFormat.TRANSLUCENT)
            .apply { this.gravity = gravity }

    private fun appOverlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    /**
     * Where a cover window is added, and what that costs when the better route is unavailable.
     *
     * Two routes, and the difference is the whole behaviour of the feature:
     *
     *  - **Through the accessibility service.** Its windows are trusted, so the untrusted-touch
     *    opacity cap does not apply to them. A cover can be fully opaque *and* invisible to touch
     *    at the same time: a real cover over a page that still scrolls normally underneath it.
     *  - **Through this app's own overlay permission.** Trusted by nothing, so a pass-through
     *    cover is capped at 80% opacity and a fifth of the picture shows through it.
     *
     * The fallback deliberately keeps the touches passing rather than keeping the opacity. A cover
     * that absorbs touches is fine over one picture and unusable over a screenful of them — which
     * is exactly the screen this feature exists for — and a device the child cannot scroll is a
     * product nobody keeps installed. So when the trusted route is missing the cover gets weaker,
     * never heavier, and the video blurring screen says the accessibility grant is what makes it
     * a full cover.
     */
    private class CoverSurface(val manager: WindowManager, val type: Int, val trusted: Boolean)

    private fun coverSurface(): CoverSurface {
        val trusted = SafetyAccessibilityService.trustedWindowManager()
        if (trusted != null) {
            return CoverSurface(
                manager = trusted,
                type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                trusted = true,
            )
        }
        return CoverSurface(windowManager, appOverlayType(), trusted = false)
    }

    /**
     * Asks the window manager to blur the live picture behind this window.
     *
     * Compose can only blur what it draws itself, so for years the only cover available to an
     * overlay was a wash, and a wash leaves every silhouette intact. From Android 12 the window
     * manager will blur what is behind a window, which is the one mechanism here that genuinely
     * destroys detail rather than dimming it.
     *
     * It is a bonus and never the plan. The device may be older, and even on a new one the system
     * switches cross-window blur off under battery saver or on hardware that cannot afford it, with
     * no notice to the app. So the drawn cover has to be sufficient on its own, and this only makes
     * a sufficient cover better.
     */
    private fun WindowManager.LayoutParams.blurBehind(strength: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val enabled = runCatching { windowManager.isCrossWindowBlurEnabled }.getOrDefault(false)
        if (!enabled) return
        flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        blurBehindRadius =
            (MIN_BLUR_RADIUS_PX + strength.coerceIn(0f, 1f) * BLUR_RADIUS_RANGE_PX).toInt()
    }

    private fun ComposeView.attachTo(host: OverlayLifecycleHost) {
        setViewTreeLifecycleOwner(host)
        setViewTreeViewModelStoreOwner(host)
        setViewTreeSavedStateRegistryOwner(host)
    }

    /**
     * Window manager calls must happen on the main thread, and every caller here is a service
     * loop or an accessibility callback that may not be on it.
     */
    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post { block() }
    }

    /**
     * A minimal lifecycle owner for an overlay.
     *
     * A ComposeView added outside an Activity has no lifecycle, view-model store or saved-state
     * registry attached, and Compose requires all three. This supplies them for as long as the
     * window exists.
     */
    private class OverlayLifecycleHost :
        LifecycleOwner,
        ViewModelStoreOwner,
        SavedStateRegistryOwner {

        private val lifecycleRegistry = LifecycleRegistry(this)
        private val savedStateController = SavedStateRegistryController.create(this)
        private val store = ViewModelStore()

        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val viewModelStore: ViewModelStore get() = store
        override val savedStateRegistry: SavedStateRegistry
            get() = savedStateController.savedStateRegistry

        fun start() {
            savedStateController.performRestore(null)
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }

        fun stop() {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
            store.clear()
        }
    }

    private companion object {
        const val NOTICE_DURATION_MILLIS = 3500L

        /** Pixels, not dp: the window manager reads this radius in the display's own units. */
        const val MIN_BLUR_RADIUS_PX = 40f
        const val BLUR_RADIUS_RANGE_PX = 80f

        /** Clear of the gesture bar, so the marker never sits where a swipe home starts. */
        const val NOTICE_BOTTOM_MARGIN_PX = 160
    }
}
