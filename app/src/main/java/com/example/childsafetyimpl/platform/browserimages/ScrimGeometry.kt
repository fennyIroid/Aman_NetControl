package com.example.childsafetyimpl.platform.browserimages

import android.graphics.Rect

/**
 * Works out which part of the screen the scrim may cover.
 *
 * The scrim is `FLAG_NOT_TOUCHABLE` and `FLAG_NOT_FOCUSABLE` by requirement, so it cannot carry
 * a dismiss button. If it covered the browser toolbar the user would have no visible way to
 * leave the page they are being protected from, so the toolbar is always left showing.
 */
object ScrimGeometry {

    fun contentArea(screen: Rect, toolbar: Rect?): Rect {
        if (toolbar == null) return Rect(screen)

        // Chrome can put the address bar at the top or the bottom. Whichever edge the toolbar
        // is nearer to is the edge the content starts after.
        val distanceToTop = toolbar.top - screen.top
        val distanceToBottom = screen.bottom - toolbar.bottom

        val area = if (distanceToTop <= distanceToBottom) {
            Rect(screen.left, toolbar.bottom, screen.right, screen.bottom)
        } else {
            Rect(screen.left, screen.top, screen.right, toolbar.top)
        }

        if (area.bottom < area.top) area.bottom = area.top
        if (area.right < area.left) area.right = area.left
        return area
    }
}
