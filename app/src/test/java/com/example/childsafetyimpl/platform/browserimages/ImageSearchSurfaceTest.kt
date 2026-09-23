package com.example.childsafetyimpl.platform.browserimages

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Ported from SafeScreen's QuerySurfaceTest, narrowed to what this path acts on: image grids. */
class ImageSearchSurfaceTest {

    private fun parse(url: String) = ImageSearchSurface.parse(url)

    @Test
    fun `an ordinary web search is not an image search`() {
        assertThat(parse("https://www.google.com/search?q=weather+tomorrow")).isNull()
    }

    @Test
    fun `the modern google image marker is recognised`() {
        // udm=2 is what mobile Google actually serves now.
        assertThat(parse("https://www.google.com/search?q=drags&udm=2")?.engine).isEqualTo("google")
    }

    @Test
    fun `the legacy google image marker is recognised`() {
        assertThat(parse("https://google.com/search?q=drags&tbm=isch")).isNotNull()
    }

    @Test
    fun `bing, duckduckgo, yandex and yahoo image search are recognised`() {
        assertThat(parse("https://www.bing.com/images/search?q=drugs")?.engine).isEqualTo("bing")
        assertThat(parse("https://duckduckgo.com/?q=drugs&iax=images&ia=images")?.engine).isEqualTo("duckduckgo")
        assertThat(parse("https://yandex.com/images/search?text=drugs")?.engine).isEqualTo("yandex")
        assertThat(parse("https://images.search.yahoo.com/search/images?p=drugs")?.engine).isEqualTo("yahoo")
    }

    @Test
    fun `a url with no scheme still parses - chrome elides https`() {
        assertThat(parse("google.com/search?q=drags&udm=2")).isNotNull()
    }

    @Test
    fun `the same search is the same page however it is encoded, and a different one is not`() {
        val plus = parse("https://www.google.com/search?q=ice+cream&udm=2")
        val percent = parse("https://www.google.com/search?q=ice%20cream&udm=2")
        assertThat(plus?.key).isEqualTo(percent?.key)
        assertThat(parse("https://www.google.com/search?q=cats&udm=2")?.key).isNotEqualTo(plus?.key)
    }

    @Test
    fun `the key does not carry the search`() {
        // Rule 3: what was searched for is read, hashed and dropped.
        val page = parse("https://www.google.com/search?q=something+private&udm=2")
        assertThat(page?.key).doesNotContain("private")
    }

    @Test
    fun `a non-search page is not a surface`() {
        assertThat(parse("https://news.example.com/article/123")).isNull()
        assertThat(parse("https://www.google.com/maps")).isNull()
    }

    @Test
    fun `an empty query is not a surface`() {
        assertThat(parse("https://www.google.com/search?q=&udm=2")).isNull()
        assertThat(parse("https://www.google.com/search?udm=2")).isNull()
    }

    @Test
    fun `malformed input returns null rather than throwing`() {
        // This runs inside the accessibility service. It may not throw.
        listOf("", "   ", "::::", "http://", "%%%", "google.com/search?q=%E0%A4&udm=2")
            .forEach { assertThat(parse(it)).isNull() }
    }

    @Test
    fun `a lookalike host is not treated as google`() {
        assertThat(parse("https://google.com.evil.example/search?q=drags&udm=2")).isNull()
    }
}
