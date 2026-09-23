package com.example.childsafetyimpl.platform.browserimages

import android.graphics.Rect
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PhotoVerdictsTest {

    private val verdicts = PhotoVerdicts()
    private val a = Photo(1L, Rect(0, 300, 500, 700))
    private val b = Photo(2L, Rect(540, 300, 1040, 700))

    @Test
    fun `a photo nobody has scored stays covered`() {
        assertThat(verdicts.toCover(listOf(a, b))).containsExactly(a, b)
        assertThat(verdicts.unscreened(listOf(a, b))).containsExactly(a, b)
    }

    @Test
    fun `a photo scored clean is uncovered and not scored again`() {
        verdicts.record(a.id, PhotoVerdicts.Verdict.CLEAN)
        assertThat(verdicts.toCover(listOf(a, b))).containsExactly(b)
        assertThat(verdicts.unscreened(listOf(a, b))).containsExactly(b)
    }

    @Test
    fun `a photo scored explicit stays covered and is not scored again`() {
        verdicts.record(a.id, PhotoVerdicts.Verdict.BLOCKED)
        assertThat(verdicts.toCover(listOf(a, b))).containsExactly(a, b)
        assertThat(verdicts.unscreened(listOf(a, b))).containsExactly(b)
    }

    @Test
    fun `a new page forgets every verdict`() {
        verdicts.record(a.id, PhotoVerdicts.Verdict.CLEAN)
        verdicts.reset()
        assertThat(verdicts.toCover(listOf(a))).containsExactly(a)
    }
}
