package com.example.childsafetyimpl.platform.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Reading back the site a relay serves under its own name. */
class ProxyOriginTest {

    @Test
    fun `Google Translate's proxy host decodes to the original site`() {
        assertEquals("www.example.com", ProxyOrigin.fromHost("www-example-com.translate.goog"))
        assertEquals("my-site.co.uk", ProxyOrigin.fromHost("my--site-co-uk.translate.goog"))
    }

    @Test
    fun `the AMP cache host decodes the same way`() {
        assertEquals("news.example.com", ProxyOrigin.fromHost("news-example-com.cdn.ampproject.org"))
    }

    @Test
    fun `an ordinary host carries nothing`() {
        listOf("example.com", "translate.goog", "translate.google.com", "cdn.ampproject.org")
            .forEach { assertNull(it, ProxyOrigin.fromHost(it)) }
    }

    @Test
    fun `the Wayback Machine carries the site in its path`() {
        assertEquals("example.com", ProxyOrigin.fromUrl("https://web.archive.org/web/20240101000000/https://example.com/page"))
        assertEquals("example.com", ProxyOrigin.fromUrl("web.archive.org/web/2024*/example.com"))
        assertNull(ProxyOrigin.fromUrl("https://web.archive.org/"))
    }

    @Test
    fun `the classic translate proxy carries the site in its query`() {
        assertEquals(
            "example.com",
            ProxyOrigin.fromUrl("https://translate.google.com/translate?sl=auto&tl=en&u=https%3A%2F%2Fexample.com%2Fx"),
        )
        assertNull(ProxyOrigin.fromUrl("https://translate.google.com/?sl=auto&tl=ar&text=hello"))
    }

    @Test
    fun `a search is not a relay`() {
        assertNull(ProxyOrigin.fromUrl("how does google translate work"))
    }
}
