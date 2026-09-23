package com.example.childsafetyimpl.platform.browserimages

import android.graphics.Rect
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Decides which window's content the user is looking at.
 *
 * The top-most app window. The active window is the wrong thing to go by: a system window takes
 * that role while the browser stays on screen behind it — Samsung's screenshot toolbar the moment
 * a screenshot is taken, the screen recorder's controls — and reading that as "the user left the
 * browser" lowered the scrim and uncovered every photo. The one system window that does hide the
 * page is the notification shade pulled down over it. A floating app window — a dialog such as
 * "Allow USB debugging?" — leaves the page in view around it too, so the app under it counts.
 *
 * Kept apart from the service so the choice is unit-testable; `AccessibilityWindowInfo` itself
 * cannot be built in a test.
 */
object FrontWindow {

    /** The notification shade's package. */
    const val SYSTEM_UI = "com.android.systemui"

    /**
     * What [pick] needs of a window. [packageName] is needed only for the active window, and only
     * when it is not an app window; reading it costs a request to the window's owner.
     */
    data class Summary(
        val type: Int,
        val layer: Int,
        val isActive: Boolean,
        val bounds: Rect,
        val packageName: String? = null,
    )

    /** Index into [windows] of the window in front of the user, or null when there is none. */
    fun pick(windows: List<Summary>, screen: Rect): Int? {
        val active = windows.indexOfFirst { it.isActive }
        if (active >= 0 && hidesApps(windows[active], screen)) return active
        val apps = windows.indices.filter { windows[it].type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val topApp = apps.filter { fillsScreen(windows[it], screen) }.maxByOrNull { windows[it].layer }
            ?: apps.maxByOrNull { windows[it].layer }
        return topApp ?: active.takeIf { it >= 0 }
    }

    /** Whether the active window is itself what the user is looking at, rather than an overlay. */
    private fun hidesApps(window: Summary, screen: Rect): Boolean = when (window.type) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> fillsScreen(window, screen)
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> false
        // The shade covers the page. Anything else — a toolbar, a recorder's controls, a keyboard,
        // a translucent overlay — leaves it in view, and the page stays covered: failing closed.
        else -> window.packageName == SYSTEM_UI && fillsScreen(window, screen)
    }

    private fun fillsScreen(window: Summary, screen: Rect): Boolean = area(window.bounds) * 2 >= area(screen)

    private fun area(rect: Rect): Long = rect.width().toLong() * rect.height()
}
