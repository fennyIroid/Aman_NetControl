package com.example.childsafetyimpl.platform.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pulling the search back out of whatever the address bar happens to be showing.
 *
 * Chrome shows the typed words while the child is typing, the full URL while the omnibox has
 * focus, and only the bare domain once results have loaded. All three states have to resolve to
 * the same search, or the protection catches the query in one of them and misses it in the others.
 */
class BrowserSurfacesTest {

    @Test
    fun `the query comes out of a google search url`() {
        assertEquals(
            "buy weed online",
            BrowserSurfaces.searchQueryFromUrl("https://www.google.com/search?q=buy+weed+online"),
        )
    }

    @Test
    fun `percent encoding is decoded`() {
        assertEquals(
            "bikini photos",
            BrowserSurfaces.searchQueryFromUrl("https://www.google.com/search?q=bikini%20photos"),
        )
    }

    @Test
    fun `the query is found among other parameters`() {
        assertEquals(
            "cocaine",
            BrowserSurfaces.searchQueryFromUrl(
                "https://duckduckgo.com/?t=h_&q=cocaine&ia=web#results",
            ),
        )
    }

    @Test
    fun `a url with no query yields nothing`() {
        assertNull(BrowserSurfaces.searchQueryFromUrl("https://www.google.com/"))
        assertNull(BrowserSurfaces.searchQueryFromUrl("https://khanacademy.org/maths"))
    }

    @Test
    fun `the query comes out of a results page title`() {
        // This is what catches a search that has already run, after Chrome has shrunk the address
        // bar back to the bare domain.
        assertEquals(
            "bikini",
            BrowserSurfaces.searchQueryFromTitle("bikini - Google Search"),
        )
        assertEquals(
            "cocaine",
            BrowserSurfaces.searchQueryFromTitle("cocaine at DuckDuckGo"),
        )
    }

    @Test
    fun `an ordinary page title is not treated as a search`() {
        assertNull(BrowserSurfaces.searchQueryFromTitle("BBC News - Home"))
        assertNull(BrowserSurfaces.searchQueryFromTitle("Khan Academy"))
    }

    @Test
    fun `a typed address is recognised as a host`() {
        assertEquals("pornhub.com", BrowserSurfaces.hostFrom("https://pornhub.com/videos"))
        assertEquals("bet365.com", BrowserSurfaces.hostFrom("bet365.com"))
    }

    @Test
    fun `typed words are not mistaken for a host`() {
        assertNull(BrowserSurfaces.hostFrom("how to make bread"))
        assertNull(BrowserSurfaces.hostFrom("bikini"))
    }

    @Test
    fun `chrome is watched, and by more than one view id`() {
        assertTrue(BrowserSurfaces.isBrowser("com.android.chrome"))
        assertTrue(BrowserSurfaces.addressBarIdsFor("com.android.chrome").size > 1)
        assertTrue(BrowserSurfaces.watchedBrowserCount >= 8)
    }

    @Test
    fun `an app that is not a browser is not watched`() {
        // The guard reads text fields, so it must never be handed an app where a text field is a
        // private message rather than a search.
        assertEquals(false, BrowserSurfaces.isBrowser("com.whatsapp"))
        assertEquals(false, BrowserSurfaces.isBrowser("com.instagram.android"))
    }
}
