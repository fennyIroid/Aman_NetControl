package com.example.childsafetyimpl.core.permission

/**
 * Every privileged access this app depends on.
 *
 * These are deliberately modelled as one type rather than raw permission strings, because most of
 * them are *not* runtime permissions at all: they are special accesses granted from a Settings
 * screen, a consent dialog, or device provisioning. Grouping them lets the setup flow explain each
 * one honestly instead of firing a dialog and hoping.
 *
 * Every string here is shown to a parent, so it says what Android hands over and what breaks
 * without it, in words that stand on their own.
 */
enum class SafetyPermission(
    val title: String,
    /** What Android actually hands over when this is granted. */
    val grants: String,
    /** The child-safety function that becomes possible. */
    val enables: String,
    /** What stays broken if the user declines. */
    val withoutIt: String,
    val grantMechanism: GrantMechanism,
) {
    USAGE_ACCESS(
        title = "Usage Access",
        grants = "Per-app foreground time and app-switch events from UsageStatsManager.",
        enables = "Screen time, per-app limits, app restriction, and AI and game budgets.",
        withoutIt = "Every time figure is unavailable, and no limit or budget can be enforced.",
        grantMechanism = GrantMechanism.SETTINGS_SCREEN,
    ),
    NOTIFICATION_LISTENER(
        title = "Notification Access",
        grants = "Titles and text of notifications posted by other apps, as they arrive.",
        enables = "Message signals: how often, at what hours, and whether language is concerning.",
        withoutIt = "No message signal at all. Time in the app is the only remaining input.",
        grantMechanism = GrantMechanism.SETTINGS_SCREEN,
    ),
    ACCESSIBILITY(
        title = "Accessibility Service",
        grants = "The on-screen view hierarchy and window-change events of other apps.",
        enables = "Shorts and Reels control, Status and Stories control, AI conversation checks.",
        withoutIt = "Shorts, Status and AI screens cannot be recognised or closed.",
        grantMechanism = GrantMechanism.SETTINGS_SCREEN,
    ),
    VPN_CONSENT(
        title = "VPN Consent",
        grants = "A local TUN interface carrying this device's outbound traffic.",
        enables = "Filtering and ad removal across every app, and VPN bypass prevention.",
        withoutIt = "Filtering and ad removal work only inside this app's own browser and player.",
        grantMechanism = GrantMechanism.CONSENT_DIALOG,
    ),
    SCREEN_CAPTURE(
        title = "Screen Capture",
        grants = "A live image stream of the device screen, for as long as the session lasts.",
        enables = "Video blurring: frames judged on this device while unsafe video plays.",
        withoutIt = "Video cannot be judged or covered while it plays in another app.",
        grantMechanism = GrantMechanism.CONSENT_DIALOG,
    ),
    OVERLAY(
        title = "Display over other apps",
        grants = "The ability to draw a window on top of other applications.",
        enables = "The block screen, the closed-screen notice, and the video blur layer.",
        withoutIt = "A rule can be detected and recorded, but nothing can be closed or covered.",
        grantMechanism = GrantMechanism.SETTINGS_SCREEN,
    ),
    POST_NOTIFICATIONS(
        title = "Send notifications",
        grants = "Permission to post notifications on Android 13 and newer.",
        enables = "Parent alerts, and the ongoing notice Android requires while protection runs.",
        withoutIt = "Alerts are recorded but never surface outside the app.",
        grantMechanism = GrantMechanism.RUNTIME_PERMISSION,
    ),
    BATTERY_UNRESTRICTED(
        title = "Unrestricted battery use",
        grants = "Exemption from Doze and app-standby background limits.",
        enables = "Protection that survives long idle periods and aggressive power management.",
        withoutIt = "Background checks are throttled, so gaps appear in enforcement.",
        grantMechanism = GrantMechanism.SETTINGS_SCREEN,
    ),
    QUERY_ALL_PACKAGES(
        title = "Full app list visibility",
        grants = "Enumeration of every installed package, not only declared ones.",
        enables = "The full installed-app list, and broader clone detection.",
        withoutIt = "Only packages declared in the manifest's queries block are visible.",
        grantMechanism = GrantMechanism.MANIFEST_DECLARATION,
    ),
    DEVICE_OWNER(
        title = "Device Owner / MDM",
        grants = "Device policy authority: app disabling, always-on VPN lockdown, uninstall blocking.",
        enables = "The restrictions Android refuses to give an app installed from the Play Store.",
        withoutIt = "Disabling other apps and forcing an always-on VPN stay out of reach.",
        grantMechanism = GrantMechanism.DEVICE_PROVISIONING,
    );

    enum class GrantMechanism(val label: String) {
        RUNTIME_PERMISSION("Runtime permission dialog"),
        SETTINGS_SCREEN("Granted manually in Android Settings"),
        CONSENT_DIALOG("System consent dialog, per session"),
        MANIFEST_DECLARATION("Declared in the manifest, reviewed by Google Play"),
        DEVICE_PROVISIONING("Requires device provisioning, not installable"),
    }
}

/** Live state of a [SafetyPermission] on the device this app is running on. */
data class PermissionState(
    val permission: SafetyPermission,
    val isGranted: Boolean,
    /** Set when the grant cannot be requested at all on this device or build. */
    val unavailableReason: String? = null,
) {
    val isRequestable: Boolean get() = !isGranted && unavailableReason == null
}
