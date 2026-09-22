package com.example.childsafetyimpl.navigation

import android.net.Uri
import com.example.childsafetyimpl.core.protection.ProtectionFeature

/**
 * Route definitions, following the custom type-safe navigation abstraction: a route knows its own
 * pattern and how to build a concrete destination from arguments, so no call site assembles a path
 * string by hand.
 */
sealed interface SafetyRoute {
    val pattern: String
}

/** A destination with no arguments. */
data class SimpleRoute(override val pattern: String) : SafetyRoute {
    operator fun invoke(): String = pattern
}

/** A destination taking one path argument. */
data class ArgRoute(private val base: String, val argName: String) : SafetyRoute {
    override val pattern: String = "$base/{$argName}"
    operator fun invoke(value: String): String = "$base/${Uri.encode(value)}"
}

object Routes {

    val onboarding = SimpleRoute("onboarding")

    // The four tabs.
    val dashboard = SimpleRoute("dashboard")
    val protections = SimpleRoute("protections")
    val controls = SimpleRoute("controls")
    val events = SimpleRoute("events")

    // One screen per protection.
    val vpnProtection = SimpleRoute("protection/vpn")
    val cloneProtection = SimpleRoute("protection/clones")
    val shortFormProtection = SimpleRoute("protection/short_form")
    val statusProtection = SimpleRoute("protection/status")
    val aiProtection = SimpleRoute("protection/ai_games")
    val appRestriction = SimpleRoute("protection/apps")
    val searchFiltering = SimpleRoute("protection/search")
    val adRemoval = SimpleRoute("protection/ads")
    val imageBlurring = SimpleRoute("protection/images")
    val videoBlurring = SimpleRoute("protection/video")

    // Surfaces the child uses.
    val safeBrowser = SimpleRoute("safe_browser")
    val youtubePlayer = SimpleRoute("youtube_player")

    val permissions = SimpleRoute("permissions")
    val appLimits = SimpleRoute("app_limits")
    val blockPreview = SimpleRoute("block_preview")

    val appDetail = ArgRoute("app", "packageName")

    /** The four destinations reachable from the bottom bar. */
    val bottomTabs = listOf(
        dashboard.pattern,
        protections.pattern,
        controls.pattern,
        events.pattern,
    )

    /**
     * Where a protection card leads.
     *
     * Held here rather than on the feature enum so the catalog stays a description of what the
     * app protects, and a route rename cannot silently orphan a screen.
     */
    fun screenFor(feature: ProtectionFeature): String = when (feature) {
        ProtectionFeature.VPN_RESTRICTION -> vpnProtection()
        ProtectionFeature.CLONE_CONTROL -> cloneProtection()
        ProtectionFeature.SHORT_FORM_CONTROL -> shortFormProtection()
        ProtectionFeature.STATUS_CONTROL -> statusProtection()
        ProtectionFeature.AI_GAME_MONITORING -> aiProtection()
        ProtectionFeature.APP_RESTRICTION -> appRestriction()
        ProtectionFeature.SEARCH_FILTERING -> searchFiltering()
        ProtectionFeature.AD_REMOVAL -> adRemoval()
        ProtectionFeature.IMAGE_BLURRING -> imageBlurring()
        ProtectionFeature.VIDEO_BLURRING -> videoBlurring()
    }
}
