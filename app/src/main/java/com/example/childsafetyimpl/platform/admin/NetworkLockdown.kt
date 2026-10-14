package com.example.childsafetyimpl.platform.admin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.UserManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The DNS posture made non-negotiable, on the devices where Android allows it.
 *
 * On an ordinary install the two settings this product depends on — Private DNS pointing at a
 * filter, and no VPN carrying traffic around it — are the child's to change, and the best the app
 * can do is notice and push back. A Device Owner is different: Android lets it set Private DNS
 * itself and forbid the user from changing it, and forbid VPN configuration outright, which on
 * Android 11 and later also stops a VPN app from starting at all. That is the whole difference
 * between a filter that is watched and one that is enforced.
 *
 * Applied from the monitoring loop on every tick, and cheap when nothing changed: the restrictions
 * are compared before they are set, and the resolver is only written when the chosen host is not
 * the one last written. Every call is a no-op unless this app is the Device Owner, which an
 * install from the Play Store never is.
 *
 * The one interaction worth knowing about: the VPN restriction would refuse this app's own pause
 * tunnel as well, so it is lifted for exactly as long as a pause is in force. Nothing else can
 * take the slot meanwhile, because the pause is holding it.
 */
@Singleton
class NetworkLockdown @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val devicePolicyManager: DevicePolicyManager,
    private val probe: DevicePolicyProbe,
) {

    /** What is currently being enforced by policy, for the screens that describe it. */
    data class Applied(
        val deviceOwner: Boolean = false,
        val privateDnsLocked: Boolean = false,
        val privateDnsHost: String? = null,
        val vpnConfigBlocked: Boolean = false,
        /** Set when Android refused the resolver, so a parent is told rather than assuming. */
        val error: String? = null,
    )

    private val _applied = MutableStateFlow(Applied())
    val applied: StateFlow<Applied> = _applied.asStateFlow()

    /** Last host successfully written, so the resolver is not re-set on every tick. */
    private var writtenHost: String? = null

    fun isDeviceOwner(): Boolean =
        runCatching { devicePolicyManager.isDeviceOwnerApp(context.packageName) }
            .getOrDefault(false)

    /**
     * Brings the policies into line with what the parent asked for.
     *
     * Must be called off the main thread: setting the resolver makes Android check that it is
     * reachable before accepting it.
     */
    fun apply(
        protectionEnabled: Boolean,
        privateDnsHost: String,
        restrictVpn: Boolean,
        pauseActive: Boolean,
    ) {
        if (!isDeviceOwner()) {
            if (_applied.value.deviceOwner) _applied.value = Applied()
            writtenHost = null
            return
        }

        if (!protectionEnabled) {
            setRestriction(UserManager.DISALLOW_CONFIG_VPN, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setRestriction(UserManager.DISALLOW_CONFIG_PRIVATE_DNS, false)
            }
            _applied.value = Applied(deviceOwner = true)
            return
        }

        val vpnBlocked = restrictVpn && !pauseActive
        setRestriction(UserManager.DISALLOW_CONFIG_VPN, vpnBlocked)

        var error: String? = null
        var dnsLocked = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (writtenHost != privateDnsHost) {
                val result = runCatching {
                    devicePolicyManager.setGlobalPrivateDnsModeSpecifiedHost(
                        probe.adminComponent,
                        privateDnsHost,
                    )
                }
                val code = result.getOrNull()
                if (code == DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR) {
                    writtenHost = privateDnsHost
                } else {
                    error = when (code) {
                        DevicePolicyManager.PRIVATE_DNS_SET_ERROR_HOST_NOT_SERVING ->
                            "$privateDnsHost did not answer as a Private DNS resolver, so " +
                                "Android kept the previous setting."
                        null -> "Android refused to set Private DNS: " +
                            (result.exceptionOrNull()?.message ?: "no reason given") + "."
                        else -> "Android refused to set Private DNS (code $code)."
                    }
                }
            }
            // Locked only once our resolver is in place: locking a setting that points nowhere
            // useful would take away the parent's way to fix it by hand.
            dnsLocked = writtenHost == privateDnsHost
            setRestriction(UserManager.DISALLOW_CONFIG_PRIVATE_DNS, dnsLocked)
        }

        _applied.value = Applied(
            deviceOwner = true,
            privateDnsLocked = dnsLocked,
            privateDnsHost = writtenHost,
            vpnConfigBlocked = vpnBlocked,
            error = error,
        )
    }

    /** Lifts the VPN restriction ahead of a pause, so the pause tunnel can be established. */
    fun allowOwnTunnel() {
        if (!isDeviceOwner()) return
        liftVpnRestriction(context)
        _applied.value = _applied.value.copy(vpnConfigBlocked = false)
    }

    companion object {
        /**
         * Lifts the VPN restriction from anywhere, without the graph.
         *
         * Called right before this app raises its own VPN consent dialog, because on a managed
         * device Android closes that dialog the moment it opens while VPN configuration is
         * forbidden, and the monitoring loop that would otherwise lift it is up to a tick behind.
         * It puts the restriction back itself once the pause or the consent window is over.
         */
        fun liftVpnRestriction(context: Context) {
            runCatching {
                val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
                if (!dpm.isDeviceOwnerApp(context.packageName)) return
                dpm.clearUserRestriction(
                    ComponentName(context, SafetyDeviceAdminReceiver::class.java),
                    UserManager.DISALLOW_CONFIG_VPN,
                )
            }
        }
    }

    private fun setRestriction(key: String, on: Boolean) {
        runCatching {
            val current = devicePolicyManager.getUserRestrictions(probe.adminComponent)
                .getBoolean(key, false)
            if (current == on) return
            if (on) {
                devicePolicyManager.addUserRestriction(probe.adminComponent, key)
            } else {
                devicePolicyManager.clearUserRestriction(probe.adminComponent, key)
            }
        }
    }
}
