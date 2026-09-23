package com.example.childsafetyimpl.platform.browserimages

import java.net.URLDecoder

/**
 * An image-search results page, recognised from the address a browser shows for it.
 *
 * [key] tells one results page from another and says nothing about what was searched for: the
 * query is hashed as soon as it is read, and only the hash is kept (rule 3).
 */
data class ImageSearchPage(val engine: String, val key: String)

/**
 * Turns the text on a browser's address-bar node into an image-search page, or null for anything
 * else — an ordinary results page, a web page, half-typed text.
 *
 * Ported from SafeScreen's QuerySurfaceParser. Runs on every accessibility event while a browser
 * is in front, so it never throws: malformed input, bad percent-encoding and half-typed URLs all
 * come back as null.
 *
 * Host matching is exact-or-subdomain. `google.com.evil.example` is not Google — a `contains`
 * check here would let any host opt itself out by putting a search engine's name in its own domain.
 */
object ImageSearchSurface {

    private data class Engine(
        val name: String,
        val hosts: List<String>,
        val queryParams: List<String>,
        /** Path prefixes that are image search regardless of query parameters. */
        val imagePaths: List<String> = emptyList(),
        /** Query parameters whose presence with one of these values means image search. */
        val imageMarkers: Map<String, Set<String>> = emptyMap(),
        /** Hosts that are image search in their entirety. */
        val imageHosts: List<String> = emptyList(),
    )

    private val engines = listOf(
        Engine(
            name = "google",
            hosts = listOf("google.com", "google.co.in", "google.co.uk", "google.de", "google.fr"),
            queryParams = listOf("q"),
            imagePaths = listOf("/imghp"),
            imageMarkers = mapOf("tbm" to setOf("isch"), "udm" to setOf("2")),
        ),
        Engine(
            name = "bing",
            hosts = listOf("bing.com"),
            queryParams = listOf("q"),
            imagePaths = listOf("/images/search", "/images"),
        ),
        Engine(
            name = "duckduckgo",
            hosts = listOf("duckduckgo.com"),
            queryParams = listOf("q"),
            imageMarkers = mapOf("iax" to setOf("images"), "ia" to setOf("images")),
        ),
        Engine(
            name = "yandex",
            hosts = listOf("yandex.com", "yandex.ru"),
            queryParams = listOf("text", "q"),
            imagePaths = listOf("/images/search", "/images"),
        ),
        Engine(
            name = "yahoo",
            hosts = listOf("search.yahoo.com", "yahoo.com"),
            queryParams = listOf("p", "q"),
            imagePaths = listOf("/search/images"),
            imageHosts = listOf("images.search.yahoo.com"),
        ),
    )

    fun parse(url: String): ImageSearchPage? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null

        val withoutScheme = trimmed.substringAfter("://", trimmed)
        if (withoutScheme.isEmpty()) return null

        val hostAndPath = withoutScheme.substringBefore('#')
        val authority = hostAndPath.substringBefore('/').substringBefore('?').lowercase()
        val host = authority.substringBefore(':').removePrefix("www.")
        if (host.isEmpty()) return null

        val afterAuthority = hostAndPath.removePrefix(authority)
        val path = afterAuthority.substringBefore('?').ifEmpty { "/" }
        val rawQuery = afterAuthority.substringAfter('?', "")
        val params = parseParams(rawQuery)

        val engine = engines.firstOrNull { candidate ->
            candidate.hosts.any { it == host || host.endsWith(".$it") } ||
                candidate.imageHosts.any { it == host || host.endsWith(".$it") }
        } ?: return null

        val query = engine.queryParams.firstNotNullOfOrNull { params[it] }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        val isImage = engine.imageHosts.any { it == host || host.endsWith(".$it") } ||
            engine.imagePaths.any { path.startsWith(it) } ||
            engine.imageMarkers.any { (key, values) -> params[key] in values }

        if (!isImage) return null
        return ImageSearchPage(engine = engine.name, key = "${engine.name}:${query.lowercase().hashCode()}")
    }

    private fun parseParams(rawQuery: String): Map<String, String> {
        if (rawQuery.isEmpty()) return emptyMap()
        return rawQuery.split('&').mapNotNull { pair ->
            if (pair.isEmpty()) return@mapNotNull null
            val key = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            val decoded = decode(value) ?: return@mapNotNull null
            key.lowercase() to decoded
        }.toMap()
    }

    /**
     * Null on malformed encoding rather than throwing.
     *
     * URLDecoder does not throw on a truncated UTF-8 sequence such as `%E0%A4` — it substitutes
     * U+FFFD. A decoded value carrying a replacement character it did not start with is
     * therefore corrupt input, and corrupt input is not a query.
     */
    private fun decode(value: String): String? {
        val decoded = try {
            URLDecoder.decode(value, "UTF-8")
        } catch (e: IllegalArgumentException) {
            return null
        } catch (e: java.io.UnsupportedEncodingException) {
            return null
        }
        if (decoded.contains(REPLACEMENT_CHAR) && !value.contains(REPLACEMENT_CHAR)) return null
        return decoded
    }

    private const val REPLACEMENT_CHAR = '\uFFFD'
}
