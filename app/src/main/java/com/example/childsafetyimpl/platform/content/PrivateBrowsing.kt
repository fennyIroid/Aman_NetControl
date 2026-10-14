package com.example.childsafetyimpl.platform.content

/**
 * What happens when a browser shows a private or incognito tab.
 *
 * A private tab marks its window FLAG_SECURE, and Android blacks a secure window out of every
 * screen capture — MediaProjection and the accessibility screenshot alike. Measured on a realme
 * RMX3870, Android 16, Chrome incognito: `dumpsys window` shows `fl=… SECURE`, an adb screenshot of
 * the tab is solid black, and the covering loop received nothing it could judge. No app can see a
 * picture in a private tab, so none can be judged there, and covering only what fails would cover
 * nothing while the switch still read as on. Both choices here therefore stop exposure without
 * judging anything (rule 12), and they differ in what the child keeps.
 */
enum class PrivateTabAction(val label: String, val description: String) {

    /**
     * Hide every picture on the page and leave the text. The default, because it keeps private
     * browsing usable for the reasons a child legitimately uses it, and still never shows a
     * picture nobody could look at first.
     */
    COVER_PICTURES(
        label = "Cover every picture",
        description = "Pictures in a private tab cannot be checked, so all of them are covered. " +
            "The page's text stays readable.",
    ),

    /** Send the child out of the browser whenever a private tab is in front. */
    LEAVE_TAB(
        label = "Don't allow private tabs",
        description = "The child is sent to the home screen whenever a private tab comes to the " +
            "front, and told private browsing isn't available.",
    ),
    ;

    companion object {
        val default = COVER_PICTURES
    }
}

/** A rectangle in whole pixels, kept free of android.graphics so the logic here is testable. */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val isEmpty: Boolean get() = right <= left || bottom <= top

    fun contains(x: Int, y: Int): Boolean = x in left until right && y in top until bottom

    fun offsetBy(dy: Int): Box = copy(top = top + dy, bottom = bottom + dy)

    fun intersect(other: Box): Box? = Box(
        maxOf(left, other.left),
        maxOf(top, other.top),
        minOf(right, other.right),
        minOf(bottom, other.bottom),
    ).takeUnless { it.isEmpty }

    fun scaled(sx: Float, sy: Float): Box = Box(
        (left * sx).toInt(),
        (top * sy).toInt(),
        kotlin.math.ceil(right * sx).toInt(),
        kotlin.math.ceil(bottom * sy).toInt(),
    )
}

/**
 * Whether a captured frame is a secure window, judged only from the part this app is not covering.
 *
 * `ScreenAnalysisService`'s whole-frame blank test is right for a banking app and wrong here: the
 * moment the private-tab cover puts its patches up, the capture shows them (rule 17), the frame is
 * no longer blank, and a test that counted them would decide the tab had stopped being private and
 * take the covers down — which would make it blank again. So the covers are left out, and so are
 * the status and navigation bars, which the system draws outside the secure window.
 */
object SecureFrame {

    /** Luminance, 0-255, below which a sampled pixel counts as black. */
    const val DARK_LUMINANCE = 16

    /** Share of the uncovered samples that must be black. A secure window is black, not dark. */
    const val DARK_SHARE = 0.97f

    /**
     * Below this share of black the window is plainly not secure. Between the two the answer is
     * "cannot tell": a few stray pixels — the edge of a cover, a toast — must never be enough to
     * decide a private tab has ended. Driven on a realme RMX3870, a still incognito tab read as
     * not black on 3% of its samples and every cover dropped; an ordinary page, even a dark one
     * under Chrome's toolbar, sits far below this.
     */
    const val LIT_SHARE = 0.90f

    /** Below this share of the frame left uncovered, there is too little to judge either way. */
    const val MIN_VISIBLE_SHARE = 0.15f

    /** The system bars, drawn outside any app's window and never secure. */
    const val TOP_BAR_SHARE = 0.05f
    const val BOTTOM_BAR_SHARE = 0.07f

    private const val GRID = 32

    /**
     * True when the uncovered frame is black, false when it is plainly lit, and null when it is
     * in between or so little is uncovered that the answer would be about the covers rather than
     * the window.
     */
    fun isBlackedOut(
        width: Int,
        height: Int,
        ignore: List<Box>,
        luminanceAt: (x: Int, y: Int) -> Int,
    ): Boolean? {
        if (width <= 0 || height <= 0) return null
        val top = (height * TOP_BAR_SHARE).toInt()
        val bottom = height - (height * BOTTOM_BAR_SHARE).toInt()
        val stepX = (width / GRID).coerceAtLeast(1)
        val stepY = ((bottom - top) / GRID).coerceAtLeast(1)

        var cells = 0
        var visible = 0
        var dark = 0
        var y = top + stepY / 2
        while (y < bottom) {
            var x = stepX / 2
            while (x < width) {
                cells++
                if (ignore.none { it.contains(x, y) }) {
                    visible++
                    if (luminanceAt(x, y) < DARK_LUMINANCE) dark++
                }
                x += stepX
            }
            y += stepY
        }
        if (cells == 0 || visible < cells * MIN_VISIBLE_SHARE) return null
        return when {
            dark >= visible * DARK_SHARE -> true
            dark < visible * LIT_SHARE -> false
            else -> null
        }
    }
}

/**
 * Decides which browser, if any, is showing a private tab, frame by frame.
 *
 * Two signals, and it takes both to enter. A black frame alone is any secure app — a bank, a
 * streaming service — and covering or closing those is not this feature. A browser in front alone
 * is ordinary browsing. A browser in front *and* a black window is a private tab: a normal page in
 * a dark theme still has a toolbar well above black, and only a secure window takes the toolbar
 * with it.
 *
 * Leaving is asymmetric on purpose. Another app in use ends it at once. A frame that is not black
 * ends it only once that has held for [EXIT_AFTER_FRAMES] frames across [EXIT_AFTER_MILLIS], with
 * no more than [MAX_EVIDENCE_GAP_MILLIS] between them — two stray readings minutes apart are not
 * a tab that turned normal, and that was measured doing exactly that on a still page. The
 * capture shows the covers a frame or two late, so mid-scroll it still has them where they were,
 * outside where they are now, and that one frame read as "not black". Driven on a realme RMX3870
 * it dropped every cover for half a second in the middle of a scroll, which is the exposure this
 * exists to prevent. Keeping covers up a moment too long over a tab that really did turn normal
 * costs nothing (rule 12). A frame too covered to tell ([SecureFrame.isBlackedOut] returning null)
 * changes nothing either way, so the covers can never argue themselves down.
 *
 * A still screen is the exception to needing a second frame, because it never sends one: a virtual
 * display produces a frame only when something on it changes (rule 28). Leaving a private tab for
 * a normal one and then reading it produces one frame, and the cover stood over the normal page
 * until the child scrolled — driven on a Galaxy M36, a box stayed over the Google logo on an
 * ordinary results page. So [confirmStill]: when the latest frame was clear and no frame has
 * arrived for [EXIT_AFTER_MILLIS], the screen has not changed since, that frame is what is on it
 * now, and the evidence has held for as long as two frames would have shown. Mid-scroll this never
 * applies, because frames keep arriving and each one moves [lastFrameAt].
 */
class PrivateTabTracker {

    var current: String? = null
        private set

    private var clearFrames = 0
    private var clearSince = 0L
    private var lastClearAt = 0L
    private var lastFrameAt = 0L

    fun next(foreground: String, foregroundIsBrowser: Boolean, blackedOut: Boolean?, now: Long): String? {
        lastFrameAt = now
        when {
            foregroundIsBrowser && blackedOut == true -> {
                current = foreground
                clearFrames = 0
            }
            current == null -> Unit
            current != foreground -> end()
            blackedOut == false -> {
                if (clearFrames == 0 || now - lastClearAt > MAX_EVIDENCE_GAP_MILLIS) {
                    clearFrames = 0
                    clearSince = now
                }
                clearFrames++
                lastClearAt = now
                if (clearFrames >= EXIT_AFTER_FRAMES && now - clearSince >= EXIT_AFTER_MILLIS) end()
            }
        }
        return current
    }

    /**
     * Ends the tab if the screen has stayed still since a clear frame; see the class comment.
     * Returns what is current afterwards.
     */
    fun confirmStill(now: Long): String? {
        val latestWasClear = clearFrames > 0 && lastClearAt == lastFrameAt
        if (current != null && latestWasClear && now - lastFrameAt >= EXIT_AFTER_MILLIS) end()
        return current
    }

    /** Forgets the tab, when something other than a frame has said it is over. */
    fun reset() = end()

    /**
     * Takes up a tab something other than a frame has said is private — a browser that was left
     * while private coming back to the front — so that it ends only on the same evidence as one
     * this tracker found itself.
     */
    fun adopt(packageName: String) {
        if (current == packageName) return
        current = packageName
        clearFrames = 0
    }

    private fun end() {
        current = null
        clearFrames = 0
    }

    companion object {
        const val EXIT_AFTER_FRAMES = 2
        const val EXIT_AFTER_MILLIS = 400L
        const val MAX_EVIDENCE_GAP_MILLIS = 1_000L
    }
}

/**
 * Where each cover goes, from where the photos were when the tree was last walked.
 *
 * The browser keeps scrolling between walks, and a walk takes tens of milliseconds of IPC, so the
 * photos are carried by however far the page has moved since — as the browser itself reported it
 * (rule 27) — and clipped to the page, so a patch never lands on the browser's own toolbar.
 * [photos] null means the walk could not account for every photo, and the answer is then the
 * whole page (rule 12).
 */
object PrivateCoverGeometry {

    fun place(photos: List<Box>?, scrolledSinceWalk: Int, page: Box, padding: Int = 0): List<Box> {
        if (photos == null) return listOf(page)
        return photos.mapNotNull { photo ->
            Box(photo.left - padding, photo.top - padding, photo.right + padding, photo.bottom + padding)
                .offsetBy(-scrolledSinceWalk)
                .intersect(page)
        }
    }
}
