package com.example.childsafetyimpl.platform.accessibility

/**
 * Where a browser keeps the thing the child is looking for.
 *
 * Forcing SafeSearch at the resolver handles explicit imagery and nothing else, so a search for
 * a drug or a way to hurt yourself comes back in full. Reading the search itself is the only thing
 * left that catches those, and this is the map of where to read it from.
 *
 * The scope is deliberately narrow, and the narrowness is the whole design. Only the address bar
 * and text fields are read, never the body of a page: matching a restricted word anywhere on the
 * screen would close a news article about the drug crisis, and a protection that does that is
 * switched off by the end of the week.
 *
 * View ids belong to other people's apps and change without notice, which is why several are
 * listed per browser and why a text field is accepted as a fallback when none of them match.
 */
object BrowserSurfaces {

    /**
     * The address bar of each browser worth naming.
     *
     * Chrome's is the one that matters most, because it is what a child actually has. The others
     * are here so switching browser is not a one-tap way around this.
     */
    val addressBarIds: Map<String, Set<String>> = mapOf(
        "com.android.chrome" to setOf(
            "com.android.chrome:id/url_bar",
            "com.android.chrome:id/search_box_text",
            "com.android.chrome:id/omnibox_results_container",
        ),
        "com.chrome.beta" to setOf("com.chrome.beta:id/url_bar"),
        "com.brave.browser" to setOf("com.brave.browser:id/url_bar"),
        "com.kiwibrowser.browser" to setOf("com.kiwibrowser.browser:id/url_bar"),
        "com.microsoft.emmx" to setOf("com.microsoft.emmx:id/url_bar"),
        "org.mozilla.firefox" to setOf(
            "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
            "org.mozilla.firefox:id/mozac_browser_toolbar_edit_url_view",
            "org.mozilla.firefox:id/toolbar",
        ),
        "com.opera.browser" to setOf("com.opera.browser:id/url_field"),
        "com.opera.mini.native" to setOf("com.opera.mini.native:id/url_field"),
        "com.sec.android.app.sbrowser" to setOf(
            "com.sec.android.app.sbrowser:id/location_bar_edit_text",
            "com.sec.android.app.sbrowser:id/url_bar",
        ),
        "com.duckduckgo.mobile.android" to setOf(
            "com.duckduckgo.mobile.android:id/omnibarTextInput",
        ),
        "com.UCMobile.intl" to setOf("com.UCMobile.intl:id/address"),
        "org.torproject.torbrowser" to setOf(
            "org.torproject.torbrowser:id/mozac_browser_toolbar_url_view",
        ),
    )

    fun isBrowser(packageName: String): Boolean = packageName in addressBarIds

    fun addressBarIdsFor(packageName: String): Set<String> =
        addressBarIds[packageName].orEmpty()

    val watchedBrowserCount: Int get() = addressBarIds.size

    /**
     * The title a search engine gives its results page.
     *
     * This is what catches a search that has already run. Chrome shrinks the address bar back to
     * the bare domain once a page loads, so by the time results are on screen the query is no
     * longer in the address bar, but it is still in the page title.
     */
    private val resultTitleSuffixes = listOf(
        " - google search",
        " - google zoeken",
        " at duckduckgo",
        " - bing",
        " - search",
        " - yahoo search results",
        " - yandex",
        " - ecosia",
        " - brave search",
        " - startpage",
    )

    fun searchQueryFromTitle(title: String): String? {
        val lower = title.lowercase()
        val suffix = resultTitleSuffixes.firstOrNull { lower.endsWith(it) } ?: return null
        return title.dropLast(suffix.length).trim().takeIf { it.isNotBlank() }
    }

    /**
     * The query inside a search URL, when the text being read is a URL rather than typed words.
     *
     * Every engine uses its own parameter name, and a child pasting a link is the same event as a
     * child typing the words, so both have to resolve to the same string.
     */
    private val queryParameters = setOf("q", "p", "query", "text", "search_query", "wd", "k")

    fun searchQueryFromUrl(url: String): String? {
        val queryStart = url.indexOf('?')
        if (queryStart < 0 || queryStart == url.length - 1) return null

        val fragmentEnd = url.indexOf('#', queryStart).let { if (it < 0) url.length else it }
        return url.substring(queryStart + 1, fragmentEnd)
            .split('&')
            .firstNotNullOfOrNull { pair ->
                val separator = pair.indexOf('=')
                if (separator <= 0) return@firstNotNullOfOrNull null
                val name = pair.substring(0, separator)
                if (name !in queryParameters) return@firstNotNullOfOrNull null
                decode(pair.substring(separator + 1)).takeIf { it.isNotBlank() }
            }
    }

    /** The host of a typed address, so a rule that already refuses a site also refuses it here. */
    fun hostFrom(text: String): String? {
        val trimmed = text.trim().lowercase()
        if (trimmed.isBlank() || trimmed.contains(' ')) return null

        val withoutScheme = trimmed
            .removePrefix("https://")
            .removePrefix("http://")
        // "http://google.com@example.com" opens example.com: what precedes an @ is a user name,
        // and reading it as the host let any refused site through behind an allowed one.
        val host = withoutScheme.substringBefore('/').substringBefore('?').substringBefore('#')
            .substringAfterLast('@').substringBefore(':')
        if (!host.contains('.') || host.startsWith('.') || host.endsWith('.')) return null
        return host.takeIf { candidate -> candidate.all { it.isLetterOrDigit() || it == '.' || it == '-' } }
    }

    private fun decode(value: String): String = buildString {
        var index = 0
        while (index < value.length) {
            when (val character = value[index]) {
                '+' -> { append(' '); index++ }
                '%' -> {
                    val hex = value.getOrNull(index + 1)?.let { first ->
                        value.getOrNull(index + 2)?.let { second -> "$first$second" }
                    }
                    val code = hex?.toIntOrNull(16)
                    if (code == null) {
                        append(character); index++
                    } else {
                        append(code.toChar()); index += 3
                    }
                }
                else -> { append(character); index++ }
            }
        }
    }
}
