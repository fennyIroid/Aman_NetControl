package com.example.childsafetyimpl.platform.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Finds where the photos are, so the scrim can cover them and leave the rest of the page readable.
 *
 * Ported unchanged from the `blurtest2` branch (`platform/browserimages`), where it was driven on
 * Chrome 152 and 153. Here it serves [PrivateTabGuard]: a private tab cannot be captured, but its
 * accessibility tree is not hidden, so this is how its pictures are found.
 *
 * Two ways a photo shows up in the tree:
 * - an image node — `android.widget.Image` for an `<img>` with alt text, `ImageView` natively;
 * - Chrome's `hasImage` extra. An `<img alt="">` is decorative and dropped from the tree, which
 *   is how Google Images renders its whole grid. Chrome still flags the node that held it, and
 *   every ancestor of that node. Measured on Chrome 153.0.8010.37, Android 14: each grid photo is
 *   a leaf `button` whose bounds are the photo's.
 *
 * Chrome flags icons the same way — an SVG counts as an image — so neither signal alone says
 * "photo". A photo is the deepest pictorial node that is big enough to show one and is not merely
 * a holder of icons. Anything else pictorial is an icon: it gets no patch of its own, and it does
 * not stop the node it sits in from being the photo. Measured on Chrome 152.0.7977.82, Android
 * 16: Google Images' viewer photo is flagged only on the node that holds both the photo and the
 * Lens button, and taking the deepest flagged node covered the button and left the photo clear.
 */
/**
 * One photo on the page. [id] is stable for as long as the page keeps the element — it is the
 * node's identity in the tree — so a verdict recorded against it survives scrolling and reloads
 * of the tree, and never lands on another photo. [scroller] is the scroll container the photo
 * moves with: a page can scroll inside an overlay while the rest of it stays put.
 */
data class Photo(val id: Long, val bounds: Rect, val scroller: Long = ImageRegions.NO_SCROLLER)

object ImageRegions {

    /** Budget for one walk of the tree. A Google Images results page is a few hundred nodes. */
    const val MAX_NODES = 3000

    /** [Photo.scroller] for a photo with no scroll container above it. */
    const val NO_SCROLLER = Long.MIN_VALUE

    /**
     * A node that holds only icons besides its picture carries at most this much text of its own.
     * A video's duration or a "Product" badge fits; a search result's heading does not.
     */
    const val MAX_CAPTION_CHARS = 40

    /** Icons inside a picture cover at most one part in this many of it. */
    private const val ICON_SHARE = 5

    private const val HAS_IMAGE_EXTRA = "AccessibilityNodeInfo.hasImage"
    private const val UNCLIPPED_LEFT = "AccessibilityNodeInfo.unclippedLeft"
    private const val UNCLIPPED_TOP = "AccessibilityNodeInfo.unclippedTop"
    private const val UNCLIPPED_RIGHT = "AccessibilityNodeInfo.unclippedRight"
    private const val UNCLIPPED_BOTTOM = "AccessibilityNodeInfo.unclippedBottom"

    private val imageClasses = setOf("android.widget.Image", "android.widget.ImageView")

    /** The page itself. Chrome flags it whenever anything on it is an image. */
    private const val PAGE_CLASS = "android.webkit.WebView"

    fun isImage(className: CharSequence?): Boolean = className?.toString() in imageClasses

    /**
     * AccessibilityNodeInfo's hash is built from its view id, virtual id and window id — the
     * element's identity, the same on every fetch and different for every element in a window.
     */
    private fun identityOf(node: AccessibilityNodeInfo): Long = node.hashCode().toLong()

    private fun flaggedAsHoldingImage(node: AccessibilityNodeInfo): Boolean =
        node.extras?.get(HAS_IMAGE_EXTRA)?.toString() == "true"

    /** One node as walked, and — once the walk is done — what its subtree holds. */
    private class Walked(val node: AccessibilityNodeInfo, val parent: Int, val scroller: Long) {
        var bounds = Rect()
        var pictorial = false
        var bigEnough = false
        var expanded = false

        var isPhoto = false
        var photoBelow = false
        var iconBelow = false
        var iconArea = 0L
        var captionChars = 0
        var editableBelow = false
        var incomplete = false

        val area: Long get() = bounds.width().toLong() * bounds.height()
    }

    /**
     * Every photo under [root], at its true bounds — off screen included, where Chrome reports
     * them. Null when the tree is larger than [maxNodes]: a partial list would leave the photos it
     * never reached uncovered, so the caller must cover the whole area instead.
     *
     * With [near] given, a subtree whose true bounds lie more than [reachScreens] screens away from
     * it is skipped: nothing there can scroll into view before the next walk. [minSide] is the smallest
     * width and height, in pixels, of a photo; anything pictorial below it is an icon. With
     * [nodes] given, each photo's node is put there by photo id, to be read again later; with
     * [pages], every page — web content root — walked through.
     */
    fun find(
        root: AccessibilityNodeInfo,
        maxNodes: Int = MAX_NODES,
        idOf: (AccessibilityNodeInfo) -> Long = ::identityOf,
        near: Rect? = null,
        reachScreens: Float = 1f,
        minSide: Int = 0,
        nodes: MutableMap<Long, AccessibilityNodeInfo>? = null,
        pages: MutableList<AccessibilityNodeInfo>? = null,
    ): List<Photo>? {
        val reach = near?.let { reachOf(it, reachScreens) }
        val walked = ArrayList<Walked>()
        val pending = ArrayDeque<Walked>().apply { add(Walked(root, parent = -1, scroller = NO_SCROLLER)) }

        while (pending.isNotEmpty()) {
            val entry = pending.removeFirst()
            if (walked.size >= maxNodes) return null
            val index = walked.size
            walked += entry

            val node = entry.node
            if (pages != null && node.className?.toString() == PAGE_CLASS) pages += node
            entry.bounds = trueBounds(node)
            entry.pictorial = isImage(node.className) || flaggedAsHoldingImage(node)
            entry.bigEnough = entry.bounds.width() >= minSide && entry.bounds.height() >= minSide

            // Empty bounds say nothing about where a subtree is, so only a known, distant one is
            // skipped.
            val far = node !== root && reach != null && !entry.bounds.isEmpty && !Rect.intersects(entry.bounds, reach)
            if (far) continue

            entry.expanded = true
            val childScroller = if (node.isScrollable) idOf(node) else entry.scroller
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { pending.add(Walked(it, index, childScroller)) }
            }
        }

        // Children come after their parents in a breadth-first walk, so going backwards every
        // subtree is complete by the time its own node is reached: decide it, then report it up.
        for (index in walked.indices.reversed()) {
            val entry = walked[index]
            entry.isPhoto = entry.expanded && isPhotoCandidate(entry) && !entry.photoBelow && showsOnePicture(entry, near)
            if (entry.parent < 0) continue
            val parent = walked[entry.parent]
            if (entry.isPhoto || entry.photoBelow) parent.photoBelow = true
            if (!entry.expanded || entry.incomplete) parent.incomplete = true
            if (entry.node.isEditable || entry.editableBelow) parent.editableBelow = true
            if (entry.pictorial) {
                // An icon, or a photo that rules its parent out anyway. Its label is not caption.
                // Its own inner icons are part of it, not counted again.
                parent.iconBelow = true
                parent.iconArea += entry.area
            } else {
                parent.iconBelow = parent.iconBelow || entry.iconBelow
                parent.iconArea += entry.iconArea
                parent.captionChars += textLength(entry.node) + entry.captionChars
            }
        }

        return walked.filter { it.isPhoto }.map { entry ->
            Photo(idOf(entry.node), entry.bounds, entry.scroller).also { nodes?.put(it.id, entry.node) }
        }
    }

    /** How far a walk near [near] looks: [screens] times its size beyond it on every side. */
    fun reachOf(near: Rect, screens: Float = 1f): Rect = Rect(near).apply {
        inset(-(near.width() * screens).toInt(), -(near.height() * screens).toInt())
    }

    private fun isPhotoCandidate(entry: Walked): Boolean =
        entry.pictorial && entry.bigEnough && entry.node.className?.toString() != PAGE_CLASS

    /**
     * Whether a pictorial node with no photo below it is one picture. An image node is. So is a
     * node Chrome flags with nothing pictorial inside it: its own dropped `<img>` is the reason.
     * With icons inside, the flag may come from the icons alone — a search box, a result card, a
     * row of buttons — so it counts only as a picture with a few small icons laid over it: little
     * text, nothing to type into, no larger than the screen, and every part of it walked.
     */
    private fun showsOnePicture(entry: Walked, near: Rect?): Boolean {
        if (isImage(entry.node.className) || !entry.iconBelow) return true
        val fits = near == null || (entry.bounds.width() <= near.width() && entry.bounds.height() <= near.height())
        return fits &&
            !entry.incomplete &&
            !entry.editableBelow &&
            entry.captionChars <= MAX_CAPTION_CHARS &&
            entry.iconArea * ICON_SHARE <= entry.area
    }

    private fun textLength(node: AccessibilityNodeInfo): Int =
        (node.text?.length ?: 0) + (node.contentDescription?.length ?: 0)

    /** Chrome clips bounds to the viewport and keeps the real ones in extras; prefer those. */
    fun trueBounds(node: AccessibilityNodeInfo): Rect {
        val extras = node.extras
        val left = (extras?.get(UNCLIPPED_LEFT) as? Number)?.toInt()
        val top = (extras?.get(UNCLIPPED_TOP) as? Number)?.toInt()
        val right = (extras?.get(UNCLIPPED_RIGHT) as? Number)?.toInt()
        val bottom = (extras?.get(UNCLIPPED_BOTTOM) as? Number)?.toInt()
        if (left != null && top != null && right != null && bottom != null) {
            return Rect(left, top, right, bottom)
        }
        return Rect().also(node::getBoundsInScreen)
    }

    /**
     * Pad each photo by [padding] without clipping, for patches the scrim will move itself; the
     * scrim's window does the clipping. Photos with no size are dropped.
     */
    fun padded(images: List<Photo>, padding: Int): List<Photo> =
        images.filterNot { it.bounds.isEmpty }
            .map { it.copy(bounds = Rect(it.bounds).apply { inset(-padding, -padding) }) }
}
