package com.example.childsafetyimpl.platform.network

/**
 * The ad and tracker hosts the ad removal protection refuses.
 *
 * Two lists, kept apart because they are enforced at different layers and a host that is safe to
 * refuse in one is not always safe to refuse in the other.
 *
 * [dnsHosts] is applied device-wide through the tunnel. Everything on it serves only advertising
 * or tracking, so refusing the name breaks nothing a child was trying to do. Google's video ad
 * host is the one that matters for the brief: the YouTube app fetches its video ads from
 * `googleads.g.doubleclick.net` and `youtube.com/api/stats/ads`, and refusing the first at the DNS
 * layer removes the ad break rather than leaving a black screen where it was.
 *
 * [requestPatterns] is applied inside this app's own player and browser, where the full URL is
 * visible. Path-level matching belongs here and only here: it cannot be done at the DNS layer,
 * because DNS never sees a path.
 */
object AdBlockList {

    /**
     * Hosts refused for every app on the device.
     *
     * Deliberately narrow. A broad blocklist looks impressive and produces support tickets, and a
     * child's device breaking in a way a parent cannot diagnose is its own safety problem.
     */
    val dnsHosts: Set<String> = setOf(
        // Google advertising and video ad delivery
        "googleads.g.doubleclick.net",
        "pagead2.googlesyndication.com",
        "securepubads.g.doubleclick.net",
        "static.doubleclick.net",
        "ad.doubleclick.net",
        "pubads.g.doubleclick.net",
        "googleadservices.com",
        "adservice.google.com",
        "partnerad.l.doubleclick.net",
        "video-ad-stats.googlesyndication.com",
        "imasdk.googleapis.com",

        // Common third-party ad networks
        "ads.yahoo.com",
        "adnxs.com",
        "ib.adnxs.com",
        "rubiconproject.com",
        "casalemedia.com",
        "openx.net",
        "pubmatic.com",
        "criteo.com",
        "criteo.net",
        "taboola.com",
        "outbrain.com",
        "smartadserver.com",
        "adcolony.com",
        "applovin.com",
        "unityads.unity3d.com",
        "vungle.com",
        "chartboost.com",
        "inmobi.com",
        "mopub.com",
        "startappservice.com",

        // Analytics and tracking that follows a child across apps
        "graph.facebook.com",
        "connect.facebook.net",
        "an.facebook.com",
        "app-measurement.com",
        "firebase-settings.crashlytics.com",
        "branch.io",
        "adjust.com",
        "appsflyer.com",
        "amplitude.com",
        "mixpanel.com",
        "segment.io",
        "scorecardresearch.com",
        "moatads.com",
    )

    /**
     * Substrings matched against a full request URL inside this app's own surfaces.
     *
     * These are the YouTube ad endpoints specifically. They are path-based, share their host with
     * ordinary video traffic, and so can only be refused where the path is visible.
     */
    val requestPatterns: List<String> = listOf(
        "/api/stats/ads",
        "/pagead/",
        "/ptracking",
        "/get_midroll_",
        "get_video_info?.*adformat",
        "/youtubei/v1/player/ad_break",
        "/doubleclick",
        "/googleads",
        "/adservice",
        "/pubads",
        "/prebid",
        "/adsense",
        "/banner_ad",
        "/interstitial",
    )

    private val hostSuffixes: List<String> = dnsHosts.toList()

    /** True when a hostname is, or sits beneath, a refused ad host. */
    fun blocksHost(host: String): Boolean {
        val normalised = host.lowercase().removeSuffix(".").removePrefix("www.")
        if (normalised in dnsHosts) return true
        return hostSuffixes.any { normalised.endsWith(".$it") }
    }

    /** True when a full URL, path included, is an ad request. */
    fun blocksRequest(url: String): Boolean {
        val lower = url.lowercase()
        val host = runCatching { java.net.URI(url).host }.getOrNull()
        if (host != null && blocksHost(host)) return true
        return requestPatterns.any { pattern ->
            if (pattern.contains(".*")) {
                Regex(pattern).containsMatchIn(lower)
            } else {
                lower.contains(pattern)
            }
        }
    }

    /**
     * Injected into this app's player and browser once a page has loaded.
     *
     * Hiding the slot as well as refusing the request matters: a refused ad request without this
     * leaves an empty reserved rectangle, which reads to a child as the page being broken.
     */
    val hidingCss: String = """
        ytd-promoted-sparkles-web-renderer, ytd-display-ad-renderer,
        ytd-companion-slot-renderer, ytd-action-companion-ad-renderer,
        ytd-in-feed-ad-layout-renderer, ytd-ad-slot-renderer,
        ytm-promoted-video-renderer, ytm-companion-slot,
        .video-ads, .ytp-ad-module, .ytp-ad-overlay-container,
        ins.adsbygoogle, iframe[src*="doubleclick"], iframe[src*="googlesyndication"],
        [id^="google_ads_"], [class*="ad-banner"], [class*="advert"],
        [data-ad-slot], [aria-label="Ad"] {
            display: none !important;
            height: 0 !important;
            visibility: hidden !important;
        }
    """.trimIndent()

    /**
     * Skips a video ad that is already playing.
     *
     * Requests are refused first, so this only ever runs against an ad that arrived anyway. It
     * presses the skip control if one exists and otherwise seeks the ad to its end, which is what
     * the player itself does when the skip timer expires.
     */
    val skipScript: String = """
        (function () {
          if (window.__haloAdSkip) return;
          window.__haloAdSkip = true;
          setInterval(function () {
            var skip = document.querySelector(
              '.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button'
            );
            if (skip) { skip.click(); return; }
            var player = document.querySelector('.ad-showing video, .ad-interrupting video');
            if (player && isFinite(player.duration) && player.duration > 0) {
              player.currentTime = player.duration;
            }
          }, 500);
        })();
    """.trimIndent()

    val hostCount: Int get() = dnsHosts.size
    val patternCount: Int get() = requestPatterns.size
}
