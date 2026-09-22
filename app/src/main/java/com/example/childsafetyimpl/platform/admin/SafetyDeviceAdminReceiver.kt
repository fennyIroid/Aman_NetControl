package com.example.childsafetyimpl.platform.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * Device admin receiver.
 *
 * Registering as a device admin buys exactly one thing for a consumer app: uninstall now requires
 * the admin to be deactivated first. It is a speed bump, not protection, and the catalog records
 * it that way. Full device-policy control needs Device Owner, which
 * cannot be reached from an installed app at all.
 *
 * The receiver exists so the app can state that boundary honestly rather than
 * describing it.
 */
class SafetyDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        DevicePolicyProbe.notifyAdminStateChanged(true)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        DevicePolicyProbe.notifyAdminStateChanged(false)
    }

    /** Shown in the system dialog when the child tries to remove the admin. */
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "Turning this off removes the safety protections on this device. A parent will be notified."
}
