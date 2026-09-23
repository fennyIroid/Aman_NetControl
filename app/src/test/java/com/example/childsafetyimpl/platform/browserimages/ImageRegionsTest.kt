package com.example.childsafetyimpl.platform.browserimages

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ImageRegionsTest {

    private fun photo(bounds: Rect, id: Long = 1L) = Photo(id, bounds)

    @Test
    fun `web and native images are both images`() {
        assertThat(ImageRegions.isImage("android.widget.Image")).isTrue()
        assertThat(ImageRegions.isImage("android.widget.ImageView")).isTrue()
        assertThat(ImageRegions.isImage("android.widget.TextView")).isFalse()
        assertThat(ImageRegions.isImage(null)).isFalse()
    }

    @Test
    fun `the finder collects every image in the tree, nested ones included`() {
        val root = node("android.webkit.WebView", Rect(0, 228, 1080, 2408))
        val tile = node("android.view.View", Rect(0, 300, 540, 800))
        val photo = node("android.widget.Image", Rect(10, 310, 530, 700))
        val caption = node("android.widget.TextView", Rect(10, 710, 530, 790))
        val banner = node("android.widget.Image", Rect(0, 900, 1080, 1200))
        shadowOf(tile).addChild(photo)
        shadowOf(tile).addChild(caption)
        shadowOf(root).addChild(tile)
        shadowOf(root).addChild(banner)

        val images = ImageRegions.find(root)!!.map { it.bounds }

        assertThat(images).containsExactly(Rect(10, 310, 530, 700), Rect(0, 900, 1080, 1200))
    }

    @Test
    fun `a photo Chrome only flags as holding an image is found at its deepest node`() {
        // Google Images drops the <img> itself from the tree; Chrome marks the photo's button
        // and every ancestor with hasImage. Only the deepest one is the photo — its ancestors
        // also span the caption, and the root spans the whole page.
        val root = node("android.webkit.WebView", Rect(0, 228, 1080, 2408), hasImage = true)
        val tile = node("android.view.View", Rect(8, 829, 537, 1414), hasImage = true)
        val photo = node("android.widget.Button", Rect(8, 829, 537, 1296), hasImage = true)
        val caption = node("android.widget.TextView", Rect(8, 1300, 537, 1414))
        shadowOf(tile).addChild(photo)
        shadowOf(tile).addChild(caption)
        shadowOf(root).addChild(tile)

        assertThat(ImageRegions.find(root)!!.map { it.bounds }).containsExactly(Rect(8, 829, 537, 1296))
    }

    @Test
    fun `each photo carries its own node's identity`() {
        // Verdicts are remembered by id, so one photo's verdict must never land on another.
        // Robolectric pins every node's hashCode to 0, so the identity is supplied here; the
        // platform's own identity is checked on a device.
        val root = node("android.webkit.WebView", Rect(0, 228, 1080, 2408))
        val first = node("android.widget.Image", Rect(0, 300, 500, 700))
        val second = node("android.widget.Image", Rect(540, 300, 1040, 700))
        shadowOf(root).addChild(first)
        shadowOf(root).addChild(second)
        val ids = mapOf(first to 11L, second to 22L)

        val photos = ImageRegions.find(root, idOf = { ids[it] ?: -1L })!!

        assertThat(photos).containsExactly(
            Photo(11L, Rect(0, 300, 500, 700)),
            Photo(22L, Rect(540, 300, 1040, 700)),
        )
    }

    @Test
    fun `a tree too large to walk in budget gives no answer rather than a partial one`() {
        // A partial list would leave the photos it never reached uncovered. Null makes the
        // caller cover the whole area instead.
        val root = node("android.webkit.WebView", Rect(0, 0, 1080, 2408))
        repeat(10) { shadowOf(root).addChild(node("android.widget.Image", Rect(0, 0, 10, 10))) }

        assertThat(ImageRegions.find(root, maxNodes = 5)).isNull()
    }

    @Test
    fun `a photo partly or wholly off screen is placed where Chrome says it really is`() {
        // Chrome clips a node's bounds to the viewport and keeps the true ones in extras. The
        // true ones let a patch be waiting when the photo scrolls in.
        val root = node("android.webkit.WebView", Rect(0, 228, 1080, 2408))
        val below = node("android.widget.Image", Rect(0, 2274, 500, 2274)).apply {
            extras.putInt("AccessibilityNodeInfo.unclippedLeft", 0)
            extras.putInt("AccessibilityNodeInfo.unclippedTop", 2300)
            extras.putInt("AccessibilityNodeInfo.unclippedRight", 500)
            extras.putInt("AccessibilityNodeInfo.unclippedBottom", 2700)
        }
        shadowOf(root).addChild(below)

        assertThat(ImageRegions.find(root)!!.map { it.bounds }).containsExactly(Rect(0, 2300, 500, 2700))
    }

    @Test
    fun `a subtree far from the screen is not walked`() {
        // A long results page grows past any walk budget. What is screens away cannot need a
        // patch before the next walk, and skipping it keeps the walk fast and complete.
        val root = node("android.webkit.WebView", Rect(0, 228, 1080, 2408))
        val near = node("android.widget.Image", Rect(0, 300, 500, 700))
        val farRow = node("android.view.View", Rect(0, 2408, 1080, 2408)).apply {
            extras.putInt("AccessibilityNodeInfo.unclippedLeft", 0)
            extras.putInt("AccessibilityNodeInfo.unclippedTop", 12_000)
            extras.putInt("AccessibilityNodeInfo.unclippedRight", 1080)
            extras.putInt("AccessibilityNodeInfo.unclippedBottom", 12_500)
        }
        val farPhoto = node("android.widget.Image", Rect(0, 2408, 500, 2408))
        shadowOf(farRow).addChild(farPhoto)
        shadowOf(root).addChild(near)
        shadowOf(root).addChild(farRow)

        val found = ImageRegions.find(root, near = Rect(0, 0, 1080, 2408))!!.map { it.bounds }

        assertThat(found).containsExactly(Rect(0, 300, 500, 700))
    }

    @Test
    fun `an icon laid over a photo does not hide the photo`() {
        // Measured, Chrome 152.0.7977.82 on Android 16, Google Images' photo viewer. Chrome flags
        // neither the photo's own toggle button nor its wrapper; it flags the node holding both
        // the photo and the Lens button, and the Lens button's wrapper, which is 69 dp — large
        // enough to pass for a photo. Taking the deepest flagged node covered the Lens button and
        // left the drug photo itself in the clear.
        val root = node("android.webkit.WebView", Rect(0, 258, 1080, 2299), hasImage = true)
        val viewer = node("android.view.View", Rect(0, 258, 1080, 2299), hasImage = true)
        val photo = node("android.view.View", Rect(0, 308, 1080, 1087), hasImage = true)
        val wrapper = node("android.view.View", Rect(0, 308, 1080, 1087))
        val toggle = node("android.widget.ToggleButton", Rect(0, 308, 1080, 1087))
        val lensWrapper = node("android.view.View", Rect(0, 893, 194, 1087), hasImage = true)
        val lens = node("android.widget.Button", Rect(45, 938, 149, 1042), hasImage = true)
            .apply { contentDescription = "Search inside image" }
        val title = node(
            "android.widget.TextView",
            Rect(45, 1118, 767, 1233),
            text = "2,071 Drag Drug Royalty-Free Images, Stock Photos & Pictures",
        )
        shadowOf(wrapper).addChild(toggle)
        shadowOf(lensWrapper).addChild(lens)
        shadowOf(photo).addChild(wrapper)
        shadowOf(photo).addChild(lensWrapper)
        shadowOf(viewer).addChild(photo)
        shadowOf(viewer).addChild(title)
        shadowOf(root).addChild(viewer)

        val found = ImageRegions.find(root, near = SCREEN, minSide = MIN_SIDE)!!.map { it.bounds }

        assertThat(found).containsExactly(Rect(0, 308, 1080, 1087))
    }

    @Test
    fun `buttons with icons get no patch`() {
        // The viewer's close and menu buttons, and the Visit button: covering them left the user
        // no visible way out of the viewer.
        val root = node("android.webkit.WebView", Rect(0, 263, 1080, 2340), hasImage = true)
        val bar = node("android.view.View", Rect(780, 280, 1060, 400), hasImage = true)
        val menu = node("android.widget.Button", Rect(790, 290, 900, 390), hasImage = true)
        val close = node("android.widget.Button", Rect(940, 290, 1050, 390), hasImage = true)
        val visit = node("android.widget.Button", Rect(810, 1210, 1030, 1310), hasImage = true, text = "Visit")
        shadowOf(bar).addChild(menu)
        shadowOf(bar).addChild(close)
        shadowOf(root).addChild(bar)
        shadowOf(root).addChild(visit)

        assertThat(ImageRegions.find(root, near = SCREEN, minSide = MIN_SIDE)).isEmpty()
    }

    @Test
    fun `an image too small to show anything gets no patch`() {
        val root = node("android.webkit.WebView", Rect(0, 263, 1080, 2340))
        val favicon = node("android.widget.Image", Rect(45, 300, 125, 380))
        val photo = node("android.widget.Image", Rect(0, 400, 1080, 1100))
        shadowOf(root).addChild(favicon)
        shadowOf(root).addChild(photo)

        val found = ImageRegions.find(root, near = SCREEN, minSide = MIN_SIDE)!!.map { it.bounds }

        assertThat(found).containsExactly(Rect(0, 400, 1080, 1100))
    }

    @Test
    fun `a block of text with an icon in it is not a photo`() {
        // A search result: Chrome flags the whole card because of its site icon.
        val root = node("android.webkit.WebView", Rect(0, 263, 1080, 2340), hasImage = true)
        val card = node("android.view.View", Rect(0, 600, 1080, 1000), hasImage = true)
        val favicon = node("android.view.View", Rect(40, 620, 120, 700), hasImage = true)
        val heading = node("android.widget.TextView", Rect(40, 710, 1040, 800), text = "Drug addiction: symptoms and causes")
        val snippet = node("android.widget.TextView", Rect(40, 810, 1040, 980), text = "Drug addiction is a disease that affects")
        shadowOf(card).addChild(favicon)
        shadowOf(card).addChild(heading)
        shadowOf(card).addChild(snippet)
        shadowOf(root).addChild(card)

        assertThat(ImageRegions.find(root, near = SCREEN, minSide = MIN_SIDE)).isEmpty()
    }

    @Test
    fun `a search box is not a photo`() {
        val root = node("android.webkit.WebView", Rect(0, 263, 1080, 2340), hasImage = true)
        val header = node("android.view.View", Rect(0, 263, 1080, 560), hasImage = true)
        val field = node("android.widget.EditText", Rect(40, 300, 860, 430), text = "drag drug")
            .apply { isEditable = true }
        val mic = node("android.widget.Button", Rect(880, 310, 990, 420), hasImage = true)
        shadowOf(header).addChild(field)
        shadowOf(header).addChild(mic)
        shadowOf(root).addChild(header)

        assertThat(ImageRegions.find(root, near = SCREEN, minSide = MIN_SIDE)).isEmpty()
    }

    @Test
    fun `a photo with a play button and a duration on it is still a photo`() {
        val root = node("android.webkit.WebView", Rect(0, 263, 1080, 2340), hasImage = true)
        val tile = node("android.widget.Button", Rect(0, 600, 530, 900), hasImage = true)
        val play = node("android.view.View", Rect(215, 700, 315, 800), hasImage = true)
        val duration = node("android.widget.TextView", Rect(430, 850, 520, 890), text = "0:32")
        shadowOf(tile).addChild(play)
        shadowOf(tile).addChild(duration)
        shadowOf(root).addChild(tile)

        val found = ImageRegions.find(root, near = SCREEN, minSide = MIN_SIDE)!!.map { it.bounds }

        assertThat(found).containsExactly(Rect(0, 600, 530, 900))
    }

    @Test
    fun `a container holding a photo is not a photo itself`() {
        // A grid tile holds the photo and its caption; only the photo is covered.
        val root = node("android.webkit.WebView", Rect(0, 263, 1080, 2340), hasImage = true)
        val tile = node("android.view.View", Rect(8, 829, 537, 1414), hasImage = true)
        val photo = node("android.widget.Button", Rect(8, 829, 537, 1296), hasImage = true)
        val source = node("android.widget.TextView", Rect(8, 1300, 537, 1414), text = "Shutterstock")
        shadowOf(tile).addChild(photo)
        shadowOf(tile).addChild(source)
        shadowOf(root).addChild(tile)

        val found = ImageRegions.find(root, near = SCREEN, minSide = MIN_SIDE)!!.map { it.bounds }

        assertThat(found).containsExactly(Rect(8, 829, 537, 1296))
    }

    @Test
    fun `each photo knows which scroll container it moves with`() {
        // The viewer scrolls inside an overlay while the results behind it stay put.
        val root = node("android.webkit.WebView", Rect(0, 263, 1080, 2340)).apply { isScrollable = true }
        val result = node("android.widget.Image", Rect(0, 300, 530, 700))
        val viewer = node("android.view.View", Rect(0, 263, 1080, 2340)).apply { isScrollable = true }
        val content = node("android.view.View", Rect(0, 263, 1080, 3000))
        val related = node("android.widget.Image", Rect(0, 1200, 530, 1600))
        shadowOf(content).addChild(related)
        shadowOf(viewer).addChild(content)
        shadowOf(root).addChild(result)
        shadowOf(root).addChild(viewer)
        val ids = mapOf(root to 1L, viewer to 2L, result to 10L, related to 20L)

        val found = ImageRegions.find(root, idOf = { ids[it] ?: -1L })!!

        assertThat(found).containsExactly(
            Photo(10L, Rect(0, 300, 530, 700), scroller = 1L),
            Photo(20L, Rect(0, 1200, 530, 1600), scroller = 2L),
        )
    }

    @Test
    fun `a photo with no size gets no patch`() {
        assertThat(ImageRegions.padded(listOf(photo(Rect(300, 600, 300, 600))), padding = 10)).isEmpty()
    }

    @Test
    fun `padding without clipping keeps a photo's patch whole for the scrim to move`() {
        val padded = ImageRegions.padded(listOf(photo(Rect(0, 2300, 500, 2700))), padding = 10)
        assertThat(padded).containsExactly(photo(Rect(-10, 2290, 510, 2710)))
    }

    private fun node(
        className: String,
        bounds: Rect,
        hasImage: Boolean = false,
        text: String? = null,
    ): AccessibilityNodeInfo =
        AccessibilityNodeInfo.obtain().apply {
            this.className = className
            this.text = text
            setBoundsInScreen(bounds)
            if (hasImage) extras.putCharSequence("AccessibilityNodeInfo.hasImage", "true")
        }

    private companion object {
        /** 48 dp at density 2.8125, the Samsung SM-M366B the defects were seen on. */
        const val MIN_SIDE = 135
        val SCREEN = Rect(0, 258, 1080, 2299)
    }
}
