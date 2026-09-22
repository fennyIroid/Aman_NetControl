package com.example.childsafetyimpl.platform.network

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import com.example.childsafetyimpl.data.prefs.SafetyPreferences
import com.example.childsafetyimpl.domain.model.EventAction
import com.example.childsafetyimpl.domain.model.EventSeverity
import com.example.childsafetyimpl.domain.model.InstalledApp
import com.example.childsafetyimpl.domain.model.SafetyEventType
import com.example.childsafetyimpl.domain.model.VpnPosture
import com.example.childsafetyimpl.domain.repository.SafetyEventRepository
import com.example.childsafetyimpl.platform.usage.AppInventoryProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * VPN restriction (first-priority protection).
 *
 * A consumer app cannot forbid another app from asking for a tunnel, so this guard restricts VPN
 * use through the three routes Android does leave open, and runs all three together because each
 * one alone is escapable:
 *
 *  1. **Discovery.** Any installed app that declares a `VpnService` is found by asking the package
 *     manager which services answer the VPN intent. That catches VPN clients this app has never
 *     heard of, which a curated package list cannot.
 *  2. **Refusal on open.** When a discovered VPN app comes to the foreground it is closed the same
 *     way any blocked app is. This is what actually stops a child using one.
 *  3. **Reclaiming the tunnel.** Android permits exactly one active VPN. When a foreign tunnel
 *     appears, this guard re-establishes our own, which the platform honours by tearing the other
 *     one down. That turns "we noticed a VPN" into "the VPN stopped".
 *
 * The limit worth stating: reclaiming needs our own VPN consent to have been granted once. Without
 * it, steps 1 and 2 still work and the guard reports that it is running at reduced strength rather
 * than claiming a protection it does not have.
 */
@Singleton
class VpnGuard @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val packageManager: PackageManager,
    private val detector: VpnDetector,
    private val inventory: AppInventoryProvider,
    private val eventRepository: SafetyEventRepository,
    private val preferences: SafetyPreferences,
) {

    private val _posture = MutableStateFlow(emptyPosture)
    val posture: StateFlow<VpnPosture> = _posture.asStateFlow()

    private val _reclaimCount = MutableStateFlow(0)
    val reclaimCount: StateFlow<Int> = _reclaimCount.asStateFlow()

    private val _blockedLaunches = MutableStateFlow(0)
    val blockedLaunches: StateFlow<Int> = _blockedLaunches.asStateFlow()

    /**
     * Every installed app that can establish a tunnel.
     *
     * Resolved from the VPN service intent rather than from the curated list, so a VPN client the
     * list has never seen is still found. The curated list is merged in afterwards to cover
     * packages that Android 11 visibility filtering would otherwise hide from the query.
     */
    fun installedVpnApps(): List<InstalledApp> {
        val declared = try {
            val intent = Intent(VPN_SERVICE_ACTION)
            val services = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.queryIntentServices(
                    intent,
                    PackageManager.ResolveInfoFlags.of(0L),
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.queryIntentServices(intent, 0)
            }
            services.map { it.serviceInfo.packageName }.toSet()
        } catch (_: Exception) {
            emptySet()
        }

        val curated = com.example.childsafetyimpl.data.source.KnownPackages.vpnTools
            .filter { inventory.isInstalled(it) }

        return (declared + curated)
            .asSequence()
            .filter { it != context.packageName }
            .distinct()
            .mapNotNull { inventory.describe(it) }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    fun isVpnApp(packageName: String): Boolean =
        packageName in vpnPackageCache || com.example.childsafetyimpl.data.source.KnownPackages
            .isVpnTool(packageName)

    /** Refreshed on the monitor's tick, so the foreground check stays a set lookup. */
    private var vpnPackageCache: Set<String> = emptySet()

    fun refreshPosture(): VpnPosture {
        val status = detector.currentStatus()
        val apps = installedVpnApps()
        vpnPackageCache = apps.map { it.packageName }.toSet()

        val posture = VpnPosture(
            ownTunnelActive = status.ownTunnelActive,
            foreignTunnelActive = status.thirdPartyVpnActive,
            tunnelInterfaces = status.tunnelInterfaces,
            installedVpnApps = apps,
            encryptedDnsHost = status.privateDnsHost.takeIf { status.encryptedDnsActive },
        )
        _posture.value = posture
        return posture
    }

    /**
     * Called on every monitor tick with the current posture.
     *
     * Records what changed and, when the parent has asked for it, takes the tunnel back.
     */
    suspend fun onPosture(posture: VpnPosture) {
        if (posture.foreignTunnelActive) {
            val wantsReclaim = preferences.settings.first().reclaimTunnel
            val reclaimed = if (wantsReclaim) reclaimTunnel() else false

            // Three different outcomes, and they must not be described as one. A parent told
            // "reclaiming is off" when the truth is "you never allowed our tunnel" would go
            // looking for a switch that is already in the right position.
            val outcome = when {
                reclaimed ->
                    "Protection reclaimed the device tunnel, which ends the other VPN."
                !wantsReclaim ->
                    "Taking the tunnel back is switched off, so this was recorded only."
                else ->
                    "Protection could not take the tunnel back, because its own filtering " +
                        "tunnel has never been allowed. Start it once from Controls and this " +
                        "becomes automatic."
            }

            eventRepository.record(
                type = SafetyEventType.VPN_DETECTED,
                title = if (reclaimed) "A VPN was switched off" else "A VPN is running",
                detail = buildString {
                    append("A tunnel this app does not own became active")
                    if (posture.tunnelInterfaces.isNotEmpty()) {
                        append(" on ${posture.tunnelInterfaces.joinToString()}")
                    }
                    append(". ")
                    append(outcome)
                },
                severity = EventSeverity.HIGH,
                action = if (reclaimed) EventAction.BLOCKED else EventAction.NOT_ENFORCEABLE,
                capabilityId = "vpn_restriction",
                dedupeWindowMillis = 5 * 60_000L,
            )
        }

        // Only in the tunnel posture. In the Private DNS postures a named resolver is the
        // filter rather than a way around it, and the monitoring loop judges it there against
        // the resolvers that filter; reporting it from here as well would tell a parent that
        // following the setup screen was a bypass.
        if (posture.encryptedDnsHost != null &&
            preferences.settings.first().filteringMode == FilteringMode.VPN_TUNNEL
        ) {
            eventRepository.record(
                type = SafetyEventType.BYPASS_ATTEMPT,
                title = "Private DNS is pointed elsewhere",
                detail = "Name lookups are going to ${posture.encryptedDnsHost} over an encrypted " +
                    "channel, which passes around the filter. Turning Private DNS off in Android " +
                    "settings restores filtering.",
                severity = EventSeverity.HIGH,
                action = EventAction.NOT_ENFORCEABLE,
                capabilityId = "vpn_restriction",
                dedupeWindowMillis = 60 * 60_000L,
            )
        }
    }

    /**
     * Re-establishes our own tunnel, which ends whichever VPN currently holds it.
     *
     * Only possible once VPN consent has been granted; `prepare` returning an intent means it has
     * not been, and a background service cannot show that dialog, so the guard reports the gap
     * instead of failing silently.
     */
    fun reclaimTunnel(): Boolean {
        val consentNeeded = try {
            VpnService.prepare(context) != null
        } catch (_: Exception) {
            true
        }
        if (consentNeeded) return false

        return try {
            val intent = Intent(context, ChildSafetyVpnService::class.java)
                .setAction(ChildSafetyVpnService.ACTION_RECLAIM)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            _reclaimCount.value += 1
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Recorded by the monitor when it closes a VPN app that was opened. */
    suspend fun onVpnAppBlocked(packageName: String, appLabel: String) {
        _blockedLaunches.value += 1
        eventRepository.record(
            type = SafetyEventType.RESTRICTED_APP_OPENED,
            title = "$appLabel was closed",
            detail = "VPN apps are restricted, so this app is closed each time it is opened.",
            packageName = packageName,
            appLabel = appLabel,
            severity = EventSeverity.HIGH,
            action = EventAction.BLOCKED,
            capabilityId = "vpn_restriction",
            dedupeWindowMillis = 60_000L,
        )
    }

    private companion object {
        const val VPN_SERVICE_ACTION = "android.net.VpnService"

        val emptyPosture = VpnPosture(
            ownTunnelActive = false,
            foreignTunnelActive = false,
            tunnelInterfaces = emptyList(),
            installedVpnApps = emptyList(),
            encryptedDnsHost = null,
        )
    }
}
