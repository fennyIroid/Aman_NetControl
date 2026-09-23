package com.example.childsafetyimpl.platform.browserimages

import android.graphics.Rect
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ScrimGeometryTest {

    private val screen = Rect(0, 0, 1080, 2340)

    @Test
    fun `with no toolbar known the whole screen is covered`() {
        assertThat(ScrimGeometry.contentArea(screen, toolbar = null)).isEqualTo(screen)
    }

    @Test
    fun `the content area starts below the toolbar`() {
        val toolbar = Rect(0, 100, 1080, 320)
        assertThat(ScrimGeometry.contentArea(screen, toolbar))
            .isEqualTo(Rect(0, 320, 1080, 2340))
    }

    @Test
    fun `a toolbar at the bottom of the screen still leaves the content above it`() {
        // Chrome can be configured with the address bar at the bottom.
        val toolbar = Rect(0, 2100, 1080, 2340)
        assertThat(ScrimGeometry.contentArea(screen, toolbar))
            .isEqualTo(Rect(0, 0, 1080, 2100))
    }

    @Test
    fun `a toolbar taller than the screen yields an empty area rather than a negative one`() {
        val toolbar = Rect(0, 0, 1080, 3000)
        val area = ScrimGeometry.contentArea(screen, toolbar)
        assertThat(area.height()).isAtLeast(0)
        assertThat(area.isEmpty).isTrue()
    }

    @Test
    fun `a toolbar that does not span the screen is still treated as a top bar`() {
        val toolbar = Rect(40, 100, 900, 300)
        assertThat(ScrimGeometry.contentArea(screen, toolbar))
            .isEqualTo(Rect(0, 300, 1080, 2340))
    }
}
