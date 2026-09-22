package com.example.childsafetyimpl.core.protection

import com.example.childsafetyimpl.core.permission.SafetyPermission

/**
 * The protections this app ships, as one registry.
 *
 * Every feature screen, every dashboard tile and every enforcement decision resolves to an entry
 * here, so a protection can never appear in the UI without the engine that backs it, and the
 * grants a protection needs are declared next to the protection rather than inferred at the point
 * of use.
 */
enum class ProtectionFeature(
    val id: String,
    val title: String,
    /** One line a parent reads on a card. */
    val summary: String,
    /** What the app actually does when this is switched on. */
    val mechanism: String,
    val group: ProtectionGroup,
    /** Grants without which this protection cannot run at all. */
    val requires: List<SafetyPermission>,
    /** Grants that make the protection stronger but are not required to start it. */
    val strengthenedBy: List<SafetyPermission> = emptyList(),
) {

    VPN_RESTRICTION(
        id = "vpn_restriction",
        title = "VPN restriction",
        summary = "Stops the child using a VPN app to get around filtering.",
        mechanism = "Watches for any tunnel that is not ours, blocks VPN apps as they open, and " +
            "reclaims the device tunnel so a third-party VPN cannot hold it.",
        group = ProtectionGroup.NETWORK,
        requires = listOf(SafetyPermission.USAGE_ACCESS, SafetyPermission.OVERLAY),
        strengthenedBy = listOf(SafetyPermission.VPN_CONSENT, SafetyPermission.ACCESSIBILITY),
    ),

    CLONE_CONTROL(
        id = "clone_control",
        title = "Dual and cloned app control",
        summary = "Finds cloned copies of restricted apps and the tools that make them.",
        mechanism = "Scans every visible profile and launcher entry for duplicate installs, watches " +
            "for new clones appearing, and blocks cloning tools and cloned instances on open.",
        group = ProtectionGroup.APPS,
        requires = listOf(SafetyPermission.USAGE_ACCESS, SafetyPermission.OVERLAY),
        strengthenedBy = listOf(SafetyPermission.QUERY_ALL_PACKAGES),
    ),

    SHORT_FORM_CONTROL(
        id = "short_form_control",
        title = "Shorts and Reels control",
        summary = "Closes short-form feeds inside YouTube, Instagram, TikTok and Facebook.",
        mechanism = "Reads the live screen of the app in front and, when it recognises a short-form " +
            "feed, leaves the feed and shows the child why. A daily allowance can be set instead.",
        group = ProtectionGroup.CONTENT,
        requires = listOf(SafetyPermission.ACCESSIBILITY),
        strengthenedBy = listOf(SafetyPermission.OVERLAY),
    ),

    STATUS_CONTROL(
        id = "status_control",
        title = "Status and Stories control",
        summary = "Keeps the child out of Status, Stories and disappearing-post surfaces.",
        mechanism = "Recognises the Status tab in WhatsApp and the Stories surfaces in Instagram, " +
            "Snapchat and Telegram, and closes them the moment they open.",
        group = ProtectionGroup.CONTENT,
        requires = listOf(SafetyPermission.ACCESSIBILITY),
        strengthenedBy = listOf(SafetyPermission.OVERLAY),
    ),

    AI_GAME_MONITORING(
        id = "ai_game_monitoring",
        title = "AI chat and game monitoring",
        summary = "Watches AI assistants and games for time, risk and concerning conversations.",
        mechanism = "Recognises AI and gaming apps, measures the time spent in each, reads the " +
            "conversation surface for risk indicators, and enforces the daily budget you set.",
        group = ProtectionGroup.MONITORING,
        requires = listOf(SafetyPermission.USAGE_ACCESS),
        strengthenedBy = listOf(SafetyPermission.ACCESSIBILITY, SafetyPermission.OVERLAY),
    ),

    APP_RESTRICTION(
        id = "app_restriction",
        title = "App restriction",
        summary = "A blocked app cannot be opened at all, YouTube included.",
        mechanism = "Watches which app comes to the front and closes any app you have switched " +
            "off, immediately and every time it is opened.",
        group = ProtectionGroup.APPS,
        requires = listOf(SafetyPermission.USAGE_ACCESS, SafetyPermission.OVERLAY),
    ),

    AD_REMOVAL(
        id = "ad_removal",
        title = "Ad removal",
        summary = "Strips ads from video and web, in this app and across the device.",
        mechanism = "Refuses ad and tracker hosts at the DNS layer for every app, and removes ad " +
            "requests and ad slots inside the built-in player and browser.",
        group = ProtectionGroup.NETWORK,
        requires = emptyList(),
        strengthenedBy = listOf(SafetyPermission.VPN_CONSENT),
    ),

    IMAGE_BLURRING(
        id = "image_blurring",
        title = "Browser image blurring",
        summary = "Blurs unsafe pictures before the page can show them.",
        mechanism = "Every image the built-in browser requests is fetched, scored on this device " +
            "and, if it fails, delivered blurred. The original bytes never reach the page.",
        group = ProtectionGroup.CONTENT,
        requires = emptyList(),
    ),

    SEARCH_FILTERING(
        id = "search_filtering",
        title = "Search and site filtering",
        summary = "Forces SafeSearch in every browser and closes searches for restricted things.",
        mechanism = "Answers the name lookups Chrome and every other app make with the filtered " +
            "address each search engine publishes, so SafeSearch cannot be switched off from " +
            "inside the browser. Reads the address bar and search box of any browser and leaves " +
            "the search when what is being looked for is restricted.",
        group = ProtectionGroup.NETWORK,
        requires = emptyList(),
        strengthenedBy = listOf(
            SafetyPermission.VPN_CONSENT,
            SafetyPermission.ACCESSIBILITY,
            SafetyPermission.OVERLAY,
        ),
    ),

    VIDEO_BLURRING(
        id = "video_blurring",
        title = "Video blurring",
        summary = "Blurs unsafe video playing in YouTube and social apps.",
        mechanism = "Samples the screen a few times a second, classifies each frame on this " +
            "device, and covers the picture with a frosted layer while it stays unsafe.",
        group = ProtectionGroup.CONTENT,
        requires = listOf(SafetyPermission.SCREEN_CAPTURE, SafetyPermission.OVERLAY),
    );

    /** The features listed first in the product, in the order the brief asks for them. */
    companion object {
        val primary: List<ProtectionFeature> = listOf(
            VPN_RESTRICTION,
            CLONE_CONTROL,
            SHORT_FORM_CONTROL,
            STATUS_CONTROL,
            AI_GAME_MONITORING,
        )

        val secondary: List<ProtectionFeature> = listOf(
            APP_RESTRICTION,
            SEARCH_FILTERING,
            AD_REMOVAL,
            IMAGE_BLURRING,
            VIDEO_BLURRING,
        )

        val ordered: List<ProtectionFeature> = primary + secondary

        fun find(id: String): ProtectionFeature? = entries.firstOrNull { it.id == id }
    }
}

enum class ProtectionGroup(val label: String) {
    NETWORK("Network"),
    APPS("Apps"),
    CONTENT("Content"),
    MONITORING("Monitoring"),
}

/**
 * Where a protection currently stands on this device.
 *
 * The distinction between [NEEDS_PERMISSION] and [OFF] carries the product's honesty rule: a
 * protection that is switched on but cannot run must never look the same as one the parent chose
 * to leave off.
 */
enum class ProtectionState(val label: String, val shortLabel: String) {
    ACTIVE("Protecting", "On"),
    PARTIAL("Partly active", "Partial"),
    NEEDS_PERMISSION("Needs permission", "Needs access"),
    OFF("Switched off", "Off");

    val isRunning: Boolean get() = this == ACTIVE || this == PARTIAL
}

/** The live status of one protection, assembled by [ProtectionStatusProvider]. */
data class ProtectionStatus(
    val feature: ProtectionFeature,
    val enabled: Boolean,
    val state: ProtectionState,
    /** Grants the feature needs that are not currently granted. */
    val missingRequired: List<SafetyPermission>,
    val missingOptional: List<SafetyPermission>,
    /** A live number worth putting on the card, such as "14 blocked today". */
    val headline: String,
    val detail: String,
)
