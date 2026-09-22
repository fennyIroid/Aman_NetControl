package com.example.childsafetyimpl.platform.admin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Uninstall protection, and an honest account of what device policy can and cannot give this app.
 *
 * Device admin is worth having for exactly one reason: with it active, the app cannot be
 * uninstalled without first being deactivated, which turns "delete the app" from a two-tap action
 * into something a child has to deliberately work at and that raises an event when they do.
 *
 * Everything beyond that needs Device Owner, which cannot be granted to an app installed from the
 * Play Store. It requires provisioning on an unconfigured device, over ADB, NFC or a QR enrolment
 * after a factory reset. This class reports that state rather than implying the app could disable
 * other apps or force an always-on VPN, because it cannot.
 */
@Singleton
class DevicePolicyProbe @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val devicePolicyManager: DevicePolicyManager,
    private val userManager: UserManager,
) {

    val adminComponent: ComponentName =
        ComponentName(context, SafetyDeviceAdminReceiver::class.java)

    data class PolicyState(
        /** Full device policy authority. Not reachable from a normal install. */
        val isDeviceOwner: Boolean,
        val isProfileOwner: Boolean,
        /** Active device admin: the uninstall-protection level, which is reachable. */
        val isAdminActive: Boolean,
        val hasSecondaryProfiles: Boolean,
        val profileCount: Int,
        val isSystemUser: Boolean,
        val androidVersion: Int,
    ) {
        val uninstallProtected: Boolean get() = isAdminActive || isDeviceOwner

        /** What a parent can still reach from here, in one line. */
        val summary: String
            get() = when {
                isDeviceOwner ->
                    "This device is managed, so app disabling and always-on VPN are available."
                isAdminActive ->
                    "Protection cannot be uninstalled without being switched off first."
                else ->
                    "Protection can be uninstalled without a password. Turning on device admin " +
                        "adds a step a child has to take deliberately."
            }
    }

    fun currentState(): PolicyState {
        val profiles = runCatching { userManager.userProfiles }.getOrDefault(emptyList())
        return PolicyState(
            isDeviceOwner = devicePolicyManager.isDeviceOwnerApp(context.packageName),
            isProfileOwner = devicePolicyManager.isProfileOwnerApp(context.packageName),
            isAdminActive = devicePolicyManager.isAdminActive(adminComponent),
            hasSecondaryProfiles = profiles.size > 1,
            profileCount = profiles.size,
            isSystemUser = runCatching { userManager.isSystemUser }.getOrDefault(true),
            androidVersion = Build.VERSION.SDK_INT,
        )
    }

    /**
     * The intent that asks the user to activate device admin.
     *
     * Must be launched from an Activity: this is a system consent screen, not something a service
     * can raise on its own.
     */
    fun activationIntent(): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            .putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Stops protection being removed from this device without a parent switching it " +
                    "off first. It does not give access to anything on the device.",
            )

    /** Deactivates device admin, which is what a parent must do before uninstalling. */
    fun deactivate() {
        runCatching { devicePolicyManager.removeActiveAdmin(adminComponent) }
    }

    /** Observable admin state, published by the receiver when the system changes it. */
    companion object State {
        private val _adminActive = MutableStateFlow(false)
        val adminActive: StateFlow<Boolean> = _adminActive.asStateFlow()

        fun notifyAdminStateChanged(active: Boolean) {
            _adminActive.value = active
        }
    }
}
