package com.example.childsafetyimpl.platform.browserimages

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.IdentityHashMap

@RunWith(RobolectricTestRunner::class)
class AnchorTrackerTest {

    private val page = 1L
    private val viewer = 2L
    private val screen = Rect(0, 258, 1080, 2299)

    /**
     * Where the browser says each node is now; a node missing here is gone from the page. By
     * identity: Robolectric's nodes all compare equal.
     */
    private val live = IdentityHashMap<AccessibilityNodeInfo, Rect>()
    private val read: (AccessibilityNodeInfo) -> Rect? = { live[it]?.let(::Rect) }

    private fun node(bounds: Rect) = AccessibilityNodeInfo.obtain().also { live[it] = Rect(bounds) }

    private fun move(node: AccessibilityNodeInfo, dy: Int) {
        live.getValue(node).offset(0, dy)
    }

    @Test
    fun `a still page has no shift`() {
        val tracker = AnchorTracker()
        val photo = Photo(1L, Rect(0, 400, 530, 800), page)
        tracker.place(listOf(photo), mapOf(1L to node(photo.bounds)), screen)

        val track = tracker.sample(now = 100L, read = read).getValue(page)

        assertThat(track.shiftY).isEqualTo(0)
        assertThat(track.velocityY).isEqualTo(0f)
    }

    @Test
    fun `the shift is how far the anchor has moved since it was placed`() {
        val tracker = AnchorTracker()
        val photo = Photo(1L, Rect(0, 400, 530, 800), page)
        val anchor = node(photo.bounds)
        tracker.place(listOf(photo), mapOf(1L to anchor), screen)

        move(anchor, -150)
        val track = tracker.sample(now = 100L, read = read).getValue(page)

        assertThat(track.shiftY).isEqualTo(-150)
        // The read describes the page a little before it was made; see BROWSER_LAG_MILLIS.
        assertThat(track.atUptime).isEqualTo(100L - AnchorTracker.BROWSER_LAG_MILLIS)
    }

    @Test
    fun `the speed comes from successive changes of the anchor's position`() {
        val tracker = AnchorTracker()
        val photo = Photo(1L, Rect(0, 400, 530, 800), page)
        val anchor = node(photo.bounds)
        tracker.place(listOf(photo), mapOf(1L to anchor), screen)

        move(anchor, -100)
        tracker.sample(now = 1_000L, read = read)
        // The browser has not updated yet: a repeated position is not a stop.
        tracker.sample(now = 1_016L, read = read)
        move(anchor, -100)
        val track = tracker.sample(now = 1_050L, read = read).getValue(page)

        assertThat(track.velocityY).isWithin(0.01f).of(-2f)
        assertThat(track.measured).isTrue()
    }

    @Test
    fun `a first change after standing still has no speed yet`() {
        // The time since the last change says nothing about how fast a gesture just started.
        val tracker = AnchorTracker()
        val photo = Photo(1L, Rect(0, 400, 530, 800), page)
        val anchor = node(photo.bounds)
        tracker.place(listOf(photo), mapOf(1L to anchor), screen)

        move(anchor, -40)
        val track = tracker.sample(now = 5_000L, read = read).getValue(page)

        assertThat(track.velocityY).isEqualTo(0f)
        assertThat(track.measured).isFalse()
    }

    @Test
    fun `a page that stops changing is taken to have stopped`() {
        val tracker = AnchorTracker()
        val photo = Photo(1L, Rect(0, 400, 530, 800), page)
        val anchor = node(photo.bounds)
        tracker.place(listOf(photo), mapOf(1L to anchor), screen)
        move(anchor, -100)
        tracker.sample(now = 1_000L, read = read)
        move(anchor, -100)
        tracker.sample(now = 1_050L, read = read)

        val track = tracker.sample(now = 1_050L + AnchorTracker.STOPPED_AFTER_MILLIS + 1, read = read).getValue(page)

        assertThat(track.velocityY).isEqualTo(0f)
        assertThat(track.shiftY).isEqualTo(-200)
    }

    @Test
    fun `each scroll container follows its own anchor`() {
        val tracker = AnchorTracker()
        val behind = Photo(1L, Rect(0, 400, 530, 800), page)
        val inViewer = Photo(2L, Rect(0, 1200, 530, 1600), viewer)
        val behindNode = node(behind.bounds)
        val viewerNode = node(inViewer.bounds)
        tracker.place(listOf(behind, inViewer), mapOf(1L to behindNode, 2L to viewerNode), screen)

        move(viewerNode, -300)
        val tracks = tracker.sample(now = 100L, read = read)

        assertThat(tracks.getValue(page).shiftY).isEqualTo(0)
        assertThat(tracks.getValue(viewer).shiftY).isEqualTo(-300)
    }

    @Test
    fun `an anchor gone from the page is replaced by another photo of the same container`() {
        val tracker = AnchorTracker()
        val first = Photo(1L, Rect(0, 1000, 530, 1400), page)
        val second = Photo(2L, Rect(0, 1500, 530, 1900), page)
        val firstNode = node(first.bounds)
        val secondNode = node(second.bounds)
        tracker.place(listOf(first, second), mapOf(1L to firstNode, 2L to secondNode), screen)

        live.remove(firstNode)
        move(secondNode, -250)
        val track = tracker.sample(now = 100L, read = read).getValue(page)

        assertThat(track.shiftY).isEqualTo(-250)
    }

    @Test
    fun `a container whose photos are all gone has no track`() {
        val tracker = AnchorTracker()
        val photo = Photo(1L, Rect(0, 400, 530, 800), page)
        val anchor = node(photo.bounds)
        tracker.place(listOf(photo), mapOf(1L to anchor), screen)

        live.remove(anchor)

        assertThat(tracker.sample(now = 100L, read = read)).doesNotContainKey(page)
    }

    @Test
    fun `the anchor is the photo nearest the middle of the screen`() {
        // It is the one most likely to stay on the page while the user scrolls.
        val tracker = AnchorTracker()
        val top = Photo(1L, Rect(0, 300, 530, 500), page)
        val middle = Photo(2L, Rect(0, 1100, 530, 1500), page)
        val topNode = node(top.bounds)
        val middleNode = node(middle.bounds)
        tracker.place(listOf(top, middle), mapOf(1L to topNode, 2L to middleNode), screen)

        move(middleNode, -80)
        val track = tracker.sample(now = 100L, read = read).getValue(page)

        assertThat(track.shiftY).isEqualTo(-80)
    }
}
