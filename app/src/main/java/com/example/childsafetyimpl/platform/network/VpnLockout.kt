package com.example.childsafetyimpl.platform.network

/**
 * What stays usable while another VPN is up.
 *
 * Without a tunnel of its own this app cannot end someone else's VPN, so the response to one is
 * to make the phone not worth using while it is on: every app is met with the block screen until
 * the VPN is switched off. That is only fair, and only works, if the way out stays open and the
 * phone stays a phone. So three kinds of app are never locked:
 *
 *  - **Settings**, because Settings, Network and internet, VPN is where any VPN — an app's or one
 *    configured by hand — is disconnected, and the block screen sends the child there. The VPN
 *    settings screen is closed on sight at other times; while a VPN is up it is the exit, and the
 *    surface guard leaves it open.
 *  - **The launcher and the system UI**, because blocking the home screen would leave the child
 *    nowhere to go when they close the block screen.
 *  - **Calling and emergency**, because a parental control that stops a child phoning home is
 *    worse than the VPN it is objecting to.
 *
 * The VPN app itself stays closed by the ordinary VPN-app rule. The VPN can be switched off from
 * Settings or from its own notification, and neither needs the app.
 *
 * Pure, so the list that decides whether a child can call for help is tested without a device.
 */
object VpnLockout {

    private val settingsPackages = setOf(
        "com.android.settings",
        "com.samsung.android.settings",
        "com.miui.securitycenter",
    )

    private val systemPackages = setOf(
        "android",
        "com.android.systemui",
        "com.android.vpndialogs",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
    )

    private val telephonyPrefixes = listOf(
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.samsung.android.dialer",
        "com.samsung.android.incallui",
        "com.android.incallui",
        "com.android.emergency",
        "com.google.android.apps.safetyhub",
        "com.android.contacts",
        "com.google.android.contacts",
        "com.samsung.android.app.contacts",
    )

    /**
     * Whether [packageName] stays usable while a VPN is up.
     *
     * [launchers] is the set of home-screen packages on this device, resolved by the caller,
     * because a launcher is whatever the manufacturer or the child installed and no list could
     * name them all.
     */
    fun isExempt(packageName: String, launchers: Set<String>): Boolean =
        packageName in settingsPackages ||
            packageName in systemPackages ||
            packageName in launchers ||
            telephonyPrefixes.any { packageName == it || packageName.startsWith("$it.") }

    fun isSettings(packageName: String): Boolean = packageName in settingsPackages
}
