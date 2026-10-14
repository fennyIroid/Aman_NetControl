package com.example.childsafetyimpl.platform.network

/**
 * The site behind a relay that serves other sites' pages under its own name.
 *
 * A resolver sees `www-example-com.translate.goog` and `web.archive.org`, both of which are
 * permitted, and never the site the page actually came from. These relays are well known to
 * children precisely because they work against DNS filters. The ones here write the original site
 * into the address itself, so it can be read back on the device and judged by the same rules as
 * if it had been typed — refusing the relay only when what it carries would be refused, so
 * translating a news article keeps working.
 *
 * Only the address is read, never the page (rule 8), and the result is judged and dropped.
 */
object ProxyOrigin {

    /** Relays that encode the original host into a subdomain, `.` as `-` and `-` as `--`. */
    private val encodedHostRelays = listOf(".translate.goog", ".cdn.ampproject.org")

    /** The original host encoded into [host] by a relay, or null. */
    fun fromHost(host: String): String? {
        val name = host.lowercase().trimEnd('.')
        val relay = encodedHostRelays.firstOrNull { name.endsWith(it) } ?: return null
        val label = name.removeSuffix(relay).substringAfterLast('.')
        // The AMP cache falls back to a hash for names too long to encode; there is nothing to
        // read back from one.
        if (label.isEmpty() || label.length > 63) return null
        val decoded = decodeLabel(label)
        return decoded.takeIf { '.' in it && !it.startsWith('.') && !it.endsWith('.') }
    }

    /**
     * The original host of a relay that carries it in the path or query: the Wayback Machine
     * (`web.archive.org/web/2024/https://example.com/`) and the classic Google Translate proxy
     * (`translate.google.com/translate?u=https://example.com`).
     */
    fun fromUrl(text: String): String? {
        val url = text.trim().lowercase()
        if (url.isEmpty() || url.any { it.isWhitespace() }) return null
        val afterScheme = url.substringAfter("://")
        val host = afterScheme.substringBefore('/').substringBefore('?').substringBefore(':')
        fromHost(host)?.let { return it }

        val rest = afterScheme.removePrefix(host)
        val carried = when {
            host == "web.archive.org" || host == "archive.org" ->
                rest.substringAfter("/web/", "").substringAfter('/', "")
            host.startsWith("translate.google.") ->
                rest.substringAfter('?', "").split('&')
                    .firstOrNull { it.startsWith("u=") }?.removePrefix("u=")?.let(::percentDecode)
            else -> null
        }
        if (carried.isNullOrEmpty()) return null
        return carried.substringAfter("://").substringBefore('/').substringBefore('?')
            .substringBefore(':').substringAfterLast('@')
            .takeIf { '.' in it }
    }

    private fun decodeLabel(label: String): String = buildString {
        var i = 0
        while (i < label.length) {
            if (label[i] == '-') {
                if (label.getOrNull(i + 1) == '-') { append('-'); i += 2 } else { append('.'); i++ }
            } else {
                append(label[i]); i++
            }
        }
    }

    private fun percentDecode(value: String): String =
        runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}
