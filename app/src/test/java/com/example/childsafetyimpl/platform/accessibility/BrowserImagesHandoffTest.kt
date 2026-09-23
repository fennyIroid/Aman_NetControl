package com.example.childsafetyimpl.platform.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserImagesHandoffTest {

    @Test
    fun `the capture loop keeps out while the browser path holds the screen and briefly after`() {
        val store = ScreenSignalStore()
        assertFalse(store.browserImages.value.holdsScreen(nowUptime = 10_000, settleMillis = 400))

        store.setBrowserImagesActive(true, atUptime = 10_000)
        assertTrue(store.browserImages.value.holdsScreen(nowUptime = 99_000, settleMillis = 400))

        // The capture shows the browser path's covers for a frame or two after they come down.
        store.setBrowserImagesActive(false, atUptime = 100_000)
        assertTrue(store.browserImages.value.holdsScreen(nowUptime = 100_200, settleMillis = 400))
        assertFalse(store.browserImages.value.holdsScreen(nowUptime = 100_500, settleMillis = 400))
    }

    @Test
    fun `a repeated release does not restart the settle`() {
        val store = ScreenSignalStore()
        store.setBrowserImagesActive(true, atUptime = 1_000)
        store.setBrowserImagesActive(false, atUptime = 2_000)
        store.setBrowserImagesActive(false, atUptime = 2_300)
        assertFalse(store.browserImages.value.holdsScreen(nowUptime = 2_450, settleMillis = 400))
    }
}
