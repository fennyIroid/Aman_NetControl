package com.example.childsafetyimpl.platform.permission

import android.Manifest
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.example.childsafetyimpl.core.permission.PermissionState
import com.example.childsafetyimpl.core.permission.SafetyPermission
import com.example.childsafetyimpl.platform.projection.ScreenAnalysisService
import com.example.childsafetyimpl.platform.accessibility.SafetyAccessibilityService
import com.example.childsafetyimpl.platform.admin.SafetyDeviceAdminReceiver
import com.example.childsafetyimpl.platform.notification.SafetyNotificationListener
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live grant state for every privileged access this app uses.
 *
 * Almost none of these are runtime permissions, so there is no single API to ask. Each one is
 * checked through whatever mechanism Android actually exposes: an app op for usage access, a
 * secure setting string for accessibility, a package list for notification access, a null-intent
 * check for VPN consent. Getting this right is what stops the rest of the app from reporting a
 * confident zero when the truth is that it was never allowed to look.
 */
@Singleton
class PermissionInspector @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val appOpsManager: AppOpsManager,
    private val devicePolicyManager: DevicePolicyManager,
    private val powerManager: PowerManager,
) {

    fun stateOf(permission: SafetyPermission): PermissionState = when (permission) {
        SafetyPermission.USAGE_ACCESS -> PermissionState(permission, hasUsageAccess())
        SafetyPermission.NOTIFICATION_LISTENER -> PermissionState(permission, hasNotificationAccess())
        SafetyPermission.ACCESSIBILITY -> PermissionState(permission, hasAccessibilityService())
        SafetyPermission.VPN_CONSENT -> PermissionState(permission, hasVpnConsent())
        // Granted for exactly as long as a session runs, so the live session is the answer.
        // Reporting it as permanently unobtainable, which it is not, put it on the permissions
        // list beside Device Owner as something a parent could never have, next to a button that
        // could in fact ask for it.
        SafetyPermission.SCREEN_CAPTURE -> PermissionState(
            permission = permission,
            isGranted = ScreenAnalysisService.State.running.value,
        )
        SafetyPermission.OVERLAY -> PermissionState(permission, canDrawOverlays())
        SafetyPermission.POST_NOTIFICATIONS -> PermissionState(permission, hasNotificationPermission())
        SafetyPermission.BATTERY_UNRESTRICTED -> PermissionState(permission, isIgnoringBatteryOptimizations())
        SafetyPermission.QUERY_ALL_PACKAGES -> queryAllPackagesState()
        SafetyPermission.DEVICE_OWNER -> deviceOwnerState()
    }

    fun allStates(): List<PermissionState> = SafetyPermission.entries.map { stateOf(it) }

    /** The set used by bypass detection to spot a grant disappearing between sessions. */
    fun grantedSet(): Set<String> = SafetyPermission.entries
        .filter { stateOf(it).isGranted }
        .map { it.name }
        .toSet()

    // --- individual checks -------------------------------------------------------------------

    /**
     * Usage access is an app op, not a permission. The unsafe check is the documented route and
     * returns MODE_DEFAULT on some builds, which then has to fall back to the permission check.
     */
    fun hasUsageAccess(): Boolean {
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOpsManager.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOpsManager.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        }
        return when (mode) {
            AppOpsManager.MODE_ALLOWED -> true
            AppOpsManager.MODE_DEFAULT -> ContextCompat.checkSelfPermission(
                context,
                "android.permission.PACKAGE_USAGE_STATS",
            ) == PackageManager.PERMISSION_GRANTED
            else -> false
        }
    }

    /**
     * Notification access lives in a colon-separated secure setting. Reading the flattened
     * component list is more dependable across OEM builds than the support-library helper, which
     * has been observed returning stale results after a force stop.
     */
    fun hasNotificationAccess(): Boolean {
        val component = ComponentName(context, SafetyNotificationListener::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ).orEmpty()
        return enabled.split(':').any {
            val parsed = ComponentName.unflattenFromString(it)
            parsed != null && parsed.packageName == context.packageName &&
                parsed.className == component.className
        }
    }

    /**
     * Accessibility state is read from the secure settings string rather than from
     * AccessibilityManager, because the manager reports services that are enabled system-wide
     * and does not distinguish this app's service reliably on every build.
     */
    fun hasAccessibilityService(): Boolean {
        val enabledGlobally = try {
            Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED)
        } catch (_: Settings.SettingNotFoundException) {
            0
        }
        if (enabledGlobally != 1) return false

        val services = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        val target = ComponentName(context, SafetyAccessibilityService::class.java)
        return services.split(':').any {
            val parsed = ComponentName.unflattenFromString(it)
            parsed != null && parsed.packageName == target.packageName &&
                parsed.className == target.className
        }
    }

    /**
     * VpnService.prepare returns null when consent already exists. It must be called with an
     * Activity context on some builds, so a failure here is reported as "not granted" rather than
     * being allowed to crash a background check.
     */
    fun hasVpnConsent(): Boolean = try {
        VpnService.prepare(context) == null
    } catch (_: Exception) {
        false
    }

    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    fun hasNotificationPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    fun isIgnoringBatteryOptimizations(): Boolean =
        powerManager.isIgnoringBatteryOptimizations(context.packageName)

    /**
     * QUERY_ALL_PACKAGES is granted at install time when declared, so the meaningful check is
     * whether the declaration is present. Below API 30 package visibility filtering does not
     * exist and the question is moot.
     */
    private fun queryAllPackagesState(): PermissionState {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return PermissionState(
                permission = SafetyPermission.QUERY_ALL_PACKAGES,
                isGranted = true,
                unavailableReason = null,
            )
        }
        val declared = try {
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions
                ?.contains(Manifest.permission.QUERY_ALL_PACKAGES) == true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
        return PermissionState(
            permission = SafetyPermission.QUERY_ALL_PACKAGES,
            isGranted = declared,
            unavailableReason = if (declared) null
            else "Not declared in the manifest. Only packages listed in <queries> are visible.",
        )
    }

    private fun deviceOwnerState(): PermissionState {
        val isOwner = devicePolicyManager.isDeviceOwnerApp(context.packageName)
        val isAdmin = devicePolicyManager.isAdminActive(
            ComponentName(context, SafetyDeviceAdminReceiver::class.java),
        )
        return PermissionState(
            permission = SafetyPermission.DEVICE_OWNER,
            isGranted = isOwner,
            unavailableReason = when {
                isOwner -> null
                isAdmin -> "Device admin is active, which only adds a deactivation step before " +
                    "uninstall. Device Owner policy control is still unavailable."
                else -> "Device Owner can only be set on an unprovisioned device, via ADB, NFC or " +
                    "a QR enrolment after a factory reset. It cannot be granted from inside the app."
            },
        )
    }

    // --- routes to the grant screens ---------------------------------------------------------

    /**
     * The intent that takes the user to the right place, or null when there is nothing to open.
     *
     * Several of these resolve to different destinations on different OEM builds, so callers must
     * verify the intent resolves before launching it. That check lives in [resolves].
     */
    fun settingsIntentFor(permission: SafetyPermission): Intent? = when (permission) {
        SafetyPermission.USAGE_ACCESS ->
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

        SafetyPermission.NOTIFICATION_LISTENER ->
            Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")

        SafetyPermission.ACCESSIBILITY ->
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

        SafetyPermission.OVERLAY ->
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )

        SafetyPermission.BATTERY_UNRESTRICTED ->
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

        SafetyPermission.POST_NOTIFICATIONS ->
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

        // Not a Settings screen at all: capture consent is a system dialog that only opens over
        // an Activity, so this points at the invisible one that exists to host it. Falling back
        // to the app's settings page here, as every other ungrantable permission does, would put
        // a button on the permissions list that cannot grant the thing it names.
        SafetyPermission.SCREEN_CAPTURE ->
            com.example.childsafetyimpl.platform.projection.CaptureConsentActivity.intent(context)

        SafetyPermission.VPN_CONSENT,
        SafetyPermission.QUERY_ALL_PACKAGES,
        SafetyPermission.DEVICE_OWNER,
        -> null
    }

    fun resolves(intent: Intent): Boolean =
        intent.resolveActivity(context.packageManager) != null

    /** Falls back to this app's own settings page when a specific screen does not exist. */
    fun appSettingsIntent(): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}"),
    )
}
