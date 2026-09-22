package com.example.childsafetyimpl.domain.model

/**
 * The screens inside other apps that this app recognises and can close.
 *
 * These are surfaces, not apps. The distinction is the whole point of the short-form and status
 * controls: a child may be allowed to use Instagram to message a friend and still not be allowed
 * into Reels, and only a surface-level signal can tell those apart.
 */
enum class RestrictedSurface(val label: String, val childFacingName: String) {
    SHORT_FORM_FEED("Short-form feed", "Shorts and Reels"),
    STATUS_STORY("Status and Stories", "Status and Stories"),
    AI_CONVERSATION("AI conversation", "AI chat"),
    VPN_SETTINGS("VPN settings", "VPN settings"),
    CLONE_WORKSPACE("Cloned workspace", "Cloned apps"),

    /**
     * A search in a browser this app does not own.
     *
     * Listed here so the child-facing notice reads the same as every other one, but deliberately
     * not allowanced: "fifteen minutes of searching for drugs a day" is not a product.
     */
    WEB_SEARCH("Restricted search", "Searches like that"),
}

/** How a duplicate app instance came to exist. */
enum class CloneKind(val label: String) {
    CLONING_TOOL("Cloning tool installed"),
    DUPLICATE_LAUNCHER_ENTRY("Same app in two spaces"),
    SECONDARY_PROFILE("Separate work or clone profile"),
    RUNNING_IN_CLONE("This app is itself cloned"),
}

/** What the VPN guard sees on the device right now. */
data class VpnPosture(
    val ownTunnelActive: Boolean,
    val foreignTunnelActive: Boolean,
    val tunnelInterfaces: List<String>,
    val installedVpnApps: List<InstalledApp>,
    val encryptedDnsHost: String?,
) {
    val clean: Boolean get() = !foreignTunnelActive && encryptedDnsHost == null
}

/** A per-surface allowance, in the same shape as an app rule so the UI can reuse its controls. */
data class SurfaceRule(
    val surface: RestrictedSurface,
    val enabled: Boolean,
    /** Null means refuse outright. A number means allow that many minutes a day. */
    val dailyAllowanceMinutes: Int?,
)

/** Today's counted time in one restricted surface, against its allowance. */
data class SurfaceUsage(
    val surface: RestrictedSurface,
    val encounters: Int,
    val dwellMillis: Long,
    val allowanceMinutes: Int?,
) {
    val allowanceMillis: Long get() = (allowanceMinutes ?: 0) * 60_000L

    val exhausted: Boolean
        get() = allowanceMinutes == null || dwellMillis >= allowanceMillis

    val fraction: Float
        get() = if (allowanceMillis <= 0) 1f
        else (dwellMillis.toFloat() / allowanceMillis).coerceIn(0f, 1f)
}
