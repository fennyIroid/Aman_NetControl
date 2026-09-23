package com.example.childsafetyimpl.platform.browserimages

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * Reports the moment a finger lands anywhere on the screen, before the page under it can move.
 *
 * While only the photos are covered, a scroll slides them out from under their patches, and the
 * accessibility event that says the page scrolled arrives after the page has already moved. A
 * finger landing is the earliest warning there is: it comes before any drag passes touch slop.
 *
 * One transparent pixel in the corner, touchable so the platform will send it
 * [MotionEvent.ACTION_OUTSIDE] for touches everywhere else, and not touch-modal so those touches
 * still go where they were aimed. For another app's window the event carries no coordinates, only
 * the fact of the touch, which is all this needs.
 */
class TouchSentinel(
    private val windowManager: WindowManager,
    private val onTouchDown: () -> Unit,
) {

    private var view: View? = null

    @SuppressLint("ClickableViewAccessibility")
    fun attach(context: Context) {
        if (view != null) return

        val sentinel = View(context).apply {
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) onTouchDown()
                false
            }
        }
        val layoutParams = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            gravity = Gravity.TOP or Gravity.START
            width = 1
            height = 1
        }

        windowManager.addView(sentinel, layoutParams)
        view = sentinel
    }

    fun detach() {
        val sentinel = view ?: return
        runCatching { windowManager.removeView(sentinel) }
        view = null
    }
}
