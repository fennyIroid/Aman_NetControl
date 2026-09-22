package com.example.childsafetyimpl.platform.youtube

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.example.childsafetyimpl.data.prefs.SafetyPreferences
import com.example.childsafetyimpl.domain.model.EventAction
import com.example.childsafetyimpl.domain.model.SafetyEventType
import com.example.childsafetyimpl.domain.repository.SafetyEventRepository
import com.example.childsafetyimpl.platform.network.AdBlockList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The supervised YouTube surface.
 *
 * The official YouTube app cannot be configured from outside itself: Restricted Mode is a setting
 * inside that app and no API opens it. So the product does not pretend otherwise. A parent who
 * wants YouTube restricted switches the YouTube app off, which the app restriction protection
 * enforces by closing it whenever it is opened, and the child watches here instead.
 *
 * Here, everything is enforceable, because this app owns the surface:
 *
 *  - **Restricted Mode is genuinely on.** The `PREF=f2=8000000` cookie and the
 *    `YouTube-Restrict: Strict` header are the two mechanisms YouTube itself honours, and both are
 *    set on every request. This is the same switch a school network uses.
 *  - **Ads are removed.** Ad requests are refused before they are made, remaining ad slots are
 *    hidden, and a video ad that arrives anyway is skipped.
 *  - **Shorts are not reachable.** The Shorts shelf and its routes are removed from the page, so
 *    the short-form control has nothing to close here in the first place.
 *
 * The trade is stated plainly on the screen itself: signing in is not available, so this is a
 * viewing surface rather than a full account.
 */
@Singleton
class YouTubeSurface @Inject constructor(
    private val eventRepository: SafetyEventRepository,
    private val preferences: SafetyPreferences,
) {

    /**
     * Cached for the same reason the browser caches it: the player makes a great many requests
     * while a video plays, and a preference read inside that path is a disk read per request.
     */
    @Volatile private var adBlockEnabled: Boolean = true

    /** Called once by the app shell, and again whenever settings change. */
    suspend fun observeSettings() {
        preferences.settings.collect { adBlockEnabled = it.adBlockEnabled }
    }

    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats.asStateFlow()

    data class Stats(
        val requestsSeen: Int = 0,
        val adsBlocked: Int = 0,
        val shortsBlocked: Int = 0,
    )

    fun resetStats() { _stats.value = Stats() }

    /**
     * The headers every request carries.
     *
     * `YouTube-Restrict` is the documented header a network administrator sets to force Restricted
     * Mode. It works from a client too, and setting it alongside the preference cookie covers both
     * the pages that read the header and the ones that read the cookie.
     */
    val restrictedHeaders: Map<String, String> = mapOf(
        "YouTube-Restrict" to "Strict",
    )

    /** The cookie form of the same switch, set on the web view's cookie jar before first load. */
    val restrictedCookies: List<Pair<String, String>> = listOf(
        ".youtube.com" to "PREF=f2=8000000&hl=en; path=/; domain=.youtube.com",
        ".youtube.com" to "YSC=restricted; path=/; domain=.youtube.com",
    )

    /** Where the surface opens. The mobile host keeps the layout usable at phone width. */
    val homeUrl: String = "https://m.youtube.com/?restrict=strict"

    fun searchUrl(query: String): String =
        "https://m.youtube.com/results?search_query=${Uri.encode(query)}&restrict=strict"

    /**
     * Whether a navigation inside the surface is allowed.
     *
     * Two jobs: keep the child inside YouTube, since a video description is full of links to
     * everywhere else, and refuse the Shorts routes outright rather than relying on the page
     * script to have hidden them.
     */
    fun allowsNavigation(url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return false

        if (host !in ALLOWED_HOSTS) return false

        if (isShortsRoute(uri)) {
            _stats.update { it.copy(shortsBlocked = it.shortsBlocked + 1) }
            runBlocking {
                eventRepository.record(
                    type = SafetyEventType.SHORT_FORM_DETECTED,
                    title = "Shorts was blocked in the safe player",
                    detail = "The short-form feed is not reachable from the built-in player.",
                    action = EventAction.BLOCKED,
                    capabilityId = "short_form_control",
                    dedupeWindowMillis = 60_000L,
                )
            }
            return false
        }
        return true
    }

    /** Public form of the route test, for the single-page navigation callback. */
    fun isShortsUrl(url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        return isShortsRoute(uri)
    }

    /** Counted separately from [allowsNavigation], which never sees a history-API navigation. */
    fun countShortsBlocked() {
        _stats.update { it.copy(shortsBlocked = it.shortsBlocked + 1) }
        runBlocking {
            eventRepository.record(
                type = SafetyEventType.SHORT_FORM_DETECTED,
                title = "Shorts was blocked in the safe player",
                detail = "The short-form feed is not reachable from the built-in player.",
                action = EventAction.BLOCKED,
                capabilityId = "short_form_control",
                dedupeWindowMillis = 60_000L,
            )
        }
    }

    private fun isShortsRoute(uri: Uri): Boolean {
        val path = uri.path.orEmpty().lowercase()
        return path.startsWith("/shorts") ||
            path.contains("/shorts/") ||
            uri.getQueryParameter("bp")?.contains("shorts", ignoreCase = true) == true
    }

    /**
     * Refuses ad requests before the player makes them.
     *
     * Returning an empty 200 rather than an error matters here specifically: the player retries a
     * failed ad request and stalls the video behind it, while an empty body is treated as an ad
     * with no content and playback continues straight into the video.
     */
    fun interceptRequest(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url?.toString() ?: return null
        _stats.update { it.copy(requestsSeen = it.requestsSeen + 1) }

        if (!adBlockEnabled) return null

        if (!AdBlockList.blocksRequest(url)) return null

        _stats.update { it.copy(adsBlocked = it.adsBlocked + 1) }
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            200,
            "OK",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0)),
        )
    }

    /**
     * Injected once the page has loaded.
     *
     * Hiding the ad slots as well as refusing their requests is what stops the page being full of
     * empty reserved rectangles, and removing the Shorts shelves is what keeps the short-form
     * control from having to fire inside a surface this app already controls.
     */
    fun pageScript(): String = """
        (function () {
          var style = document.getElementById('halo-style');
          if (!style) {
            style = document.createElement('style');
            style.id = 'halo-style';
            document.documentElement.appendChild(style);
          }
          style.textContent = ${AdBlockList.hidingCss.asJsString()} + ${SHORTS_CSS.asJsString()};

          ${AdBlockList.skipScript}

          // Shelves are added as the feed scrolls, so removal has to be continuous rather than
          // a single pass at load.
          if (!window.__haloShorts) {
            window.__haloShorts = true;
            setInterval(function () {
              var shelves = document.querySelectorAll(
                'ytm-reel-shelf-renderer, ytd-reel-shelf-renderer,' +
                'ytm-rich-section-renderer, ytd-rich-section-renderer,' +
                '[href^="/shorts"], a[href*="/shorts/"]'
              );
              for (var i = 0; i < shelves.length; i++) {
                var node = shelves[i];
                if (node.textContent && node.textContent.indexOf('Shorts') === -1 &&
                    node.tagName === 'A' && node.getAttribute('href').indexOf('/shorts') !== 0) {
                  continue;
                }
                node.style.display = 'none';
              }
            }, 900);
          }
        })();
    """.trimIndent()

    private fun String.asJsString(): String =
        "\"" + replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private companion object {
        val ALLOWED_HOSTS = setOf(
            "youtube.com",
            "youtu.be",
            "youtube-nocookie.com",
            "googlevideo.com",
            "ytimg.com",
            "ggpht.com",
        )

        val SHORTS_CSS = """
            ytm-reel-shelf-renderer, ytd-reel-shelf-renderer,
            ytm-shorts-lockup-view-model, ytm-pivot-bar-item-renderer[tab-identifier="FEshorts"],
            [tab-identifier="FEshorts"], a[href^="/shorts"] {
                display: none !important;
            }
        """.trimIndent()
    }
}
