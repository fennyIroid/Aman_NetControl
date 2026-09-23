package com.example.childsafetyimpl.platform.browserimages

import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TouchSentinelTest {

    private lateinit var windowManager: WindowManager
    private var touches = 0
    private lateinit var sentinel: TouchSentinel
    private val params = slot<WindowManager.LayoutParams>()
    private val view = slot<View>()

    @Before
    fun setUp() {
        windowManager = mockk(relaxed = true)
        every { windowManager.addView(capture(view), capture(params)) } returns Unit
        sentinel = TouchSentinel(windowManager) { touches++ }
    }

    @Test
    fun `it watches touches outside itself without taking them`() {
        sentinel.attach(RuntimeEnvironment.getApplication())
        val flags = params.captured.flags
        assertThat(flags and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH).isNotEqualTo(0)
        assertThat(flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL).isNotEqualTo(0)
        assertThat(flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE).isNotEqualTo(0)
        // Outside touches are only reported to a window that is itself touchable.
        assertThat(flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE).isEqualTo(0)
    }

    @Test
    fun `it is one pixel, so it swallows nothing anyone could aim at`() {
        sentinel.attach(RuntimeEnvironment.getApplication())
        assertThat(params.captured.width).isEqualTo(1)
        assertThat(params.captured.height).isEqualTo(1)
    }

    @Test
    fun `it is an accessibility overlay`() {
        sentinel.attach(RuntimeEnvironment.getApplication())
        assertThat(params.captured.type)
            .isEqualTo(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
    }

    @Test
    fun `a finger landing anywhere is reported`() {
        sentinel.attach(RuntimeEnvironment.getApplication())
        view.captured.dispatchTouchEvent(event(MotionEvent.ACTION_OUTSIDE))
        assertThat(touches).isEqualTo(1)
    }

    @Test
    fun `attach adds one window however often it is called`() {
        sentinel.attach(RuntimeEnvironment.getApplication())
        sentinel.attach(RuntimeEnvironment.getApplication())
        verify(exactly = 1) { windowManager.addView(any(), any()) }
    }

    @Test
    fun `detach removes the window`() {
        sentinel.attach(RuntimeEnvironment.getApplication())
        sentinel.detach()
        verify(exactly = 1) { windowManager.removeView(view.captured) }
    }

    private fun event(action: Int): MotionEvent = MotionEvent.obtain(0L, 0L, action, 0f, 0f, 0)
}
