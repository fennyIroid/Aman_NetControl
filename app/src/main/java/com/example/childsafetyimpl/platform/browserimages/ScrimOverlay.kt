package com.example.childsafetyimpl.platform.browserimages

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.annotation.RequiresApi

/**
 * The cover that sits above the browser.
 *
 * Attached once, for the whole life of the accessibility service, and held at [View.INVISIBLE].
 * Raising it is a visibility toggle — one frame. Calling `WindowManager.addView()` at trigger
 * time costs tens of milliseconds and loses the race to Chrome's paint, which is the failure
 * this whole design exists to prevent, so [cover] never adds anything.
 *
 * It rises over the whole content area. Once the photos on the page are known it can shrink to
 * a patch over each one ([cover] with photos), leaving the text readable. Where the platform
 * allows a capture of the window underneath, each photo's own frost is laid into its patch with
 * [showPhotoFrosts], and a frost of the whole page into the whole area with [showBackdrop], so a
 * photo reads as blurred rather than as a blank block.
 *
 * The window fills the screen from the moment it is attached and is never laid out again. The
 * content area is a clip inside it. Moving and resizing the window to the area — as it once
 * did — takes effect a frame or more after the patches are redrawn, and in between every patch
 * sat off its photo by however far the area had moved.
 *
 * Uses `TYPE_ACCESSIBILITY_OVERLAY` through the accessibility service's window manager, so the
 * window is trusted: opaque and invisible to touch at once (rules 14 and 15).
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class ScrimOverlay(
    private val windowManager: WindowManager,
    private val viewFactory: (Context) -> ScrimView = ::ScrimView,
) {

    private var view: ScrimView? = null

    val isAttached: Boolean get() = view != null
    val isShowing: Boolean get() = view?.visibility == View.VISIBLE

    fun attach(context: Context) {
        if (view != null) return

        val scrim = viewFactory(context).apply { visibility = View.INVISIBLE }
        val layoutParams = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            // Translucent so the page shows between covered photos. The patches themselves are
            // painted fully opaque; see ScrimView.GROUND.
            format = android.graphics.PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            gravity = Gravity.TOP or Gravity.START
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            x = 0
            y = 0
            // Neither the system bars nor a display cutout may push the window off the screen's
            // corner; patches are drawn in screen coordinates. Rule 16.
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        windowManager.addView(scrim, layoutParams)
        view = scrim
    }

    /**
     * Cover [area], in screen coordinates, and show the scrim. Never adds a view, and never lays
     * the window out again.
     *
     * With [photos] null the whole area is covered. Otherwise only those photos' patches are, in
     * screen coordinates, and the page shows between them; nothing outside [area] is painted.
     * With [motion], the patches travel with a scrolling page.
     */
    fun cover(area: Rect, photos: List<Photo>? = null, motion: PatchMotion? = null) {
        val scrim = view ?: return
        scrim.setArea(area)
        if (photos == null) {
            scrim.coverAll()
        } else {
            scrim.coverPhotos(photos, motion)
        }
        scrim.visibility = View.VISIBLE
    }

    /**
     * Lay a frosted capture of [frostArea] of the page into the raised scrim, for when it covers
     * the whole area. Ignored while the scrim is down: captures are asynchronous, and one that
     * lands late must not wait in the scrim for the next, unrelated raise.
     */
    fun showBackdrop(frosted: Bitmap, frostArea: Rect) {
        val scrim = view ?: return
        if (scrim.visibility != View.VISIBLE) return
        scrim.setBackdrop(frosted, frostArea)
    }

    /** Lay each photo's own frost into its patch, by [Photo.id]. Ignored while the scrim is down. */
    fun showPhotoFrosts(frosts: Map<Long, PhotoFrost>) {
        val scrim = view ?: return
        if (scrim.visibility != View.VISIBLE) return
        scrim.setPhotoFrosts(frosts)
    }

    /** Lower the scrim. The view stays attached, ready for the next raise, which starts opaque. */
    fun hide() {
        val scrim = view ?: return
        scrim.visibility = View.INVISIBLE
        scrim.clearBackdrop()
        // The next raise is a new page whose photos are not known yet.
        scrim.coverAll()
    }

    fun detach() {
        val scrim = view ?: return
        runCatching { windowManager.removeView(scrim) }
        view = null
    }
}
