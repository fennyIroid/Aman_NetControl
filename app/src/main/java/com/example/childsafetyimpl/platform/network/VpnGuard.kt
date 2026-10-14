package com.example.childsafetyimpl.platform.network

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.SystemClock
import com.example.childsafetyimpl.core.protection.ProtectionFeature
import com.example.childsafetyimpl.data.prefs.SafetyPreferences
import com.example.childsafetyimpl.data.source.KnownPackages
import com.example.childsafetyimpl.domain.model.EventAction
import com.example.childsafetyimpl.domain.model.EventSeverity
import com.example.childsafetyimpl.domain.model.InstalledApp
import com.example.childsafetyimpl.domain.model.SafetyEventType
import com.example.childsafetyimpl.domain.model.VpnPosture
import com.example.childsafetyimpl.domain.repository.SafetyEventRepository
import com.example.childsafetyimpl.platform.admin.NetworkLockdown
import com.example.childsafetyimpl.platform.usage.AppInventoryProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * VPN restriction (first-priority protection), without a VPN of our own.
 *
 * Filtering runs through Private DNS, so this app does not hold the device's VPN slot and cannot
 * end another VPN by taking it back. A consumer app also cannot forbid another app from asking for
 * a tunnel. What Android does leave open is enough, used in layers, each of which catches what the
 * one before it lets through:
 *
 *  1. **Refusing the connection request.** A VPN app cannot carry anything until the user accepts
 *     Android's own "connection request" dialog. The accessibility service sees that dialog
 *     appear and presses Cancel on the child's behalf ([onConsentDialog]), so a newly installed
 *     VPN — including one nobody has heard of — never gets its tunnel at all. This app's own
 *     request, raised for an internet pause, is the one exception, and only for a short window
 *     after the app itself asked.
 *  2. **Closing VPN apps on open.** Any installed app that declares a `VpnService` is found by
 *     asking the package manager, and is closed whenever it comes to the front.
 *  3. **Locking the phone while a VPN is up.** A VPN approved before this app arrived, or set up
 *     in Settings, can still connect without a dialog. When the network reports a tunnel this app
 *     does not own, every app is blocked ([VpnLockout]) except Settings — where the child can
 *     switch the VPN off — and the phone itself. A VPN that makes the phone unusable is a VPN that
 *     gets switched off.
 *
 * On a managed device [com.example.childsafetyimpl.platform.admin.NetworkLockdown] forbids VPN
 * configuration by policy, which replaces all three with Android simply refusing.
 *
 * The limits worth stating: layer 1 needs the accessibility service, and layers 2 and 3 need usage
 * access and permission to draw over other apps. Without them a VPN is detected and reported, not
 * prevented, and the guard says so rather than claiming a protection it does not have.
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

    private val _refusedRequests = MutableStateFlow(0)

    /** Connection requests cancelled before a VPN could start. */
    val refusedRequests: StateFlow<Int> = _refusedRequests.asStateFlow()

    private val _blockedLaunches = MutableStateFlow(0)
    val blockedLaunches: StateFlow<Int> = _blockedLaunches.asStateFlow()

    private val _lockouts = MutableStateFlow(0)

    /** Apps refused because a VPN was up when they were opened. */
    val lockouts: StateFlow<Int> = _lockouts.asStateFlow()

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

        val curated = KnownPackages.vpnTools.filter { inventory.isInstalled(it) }

        return (declared + curated)
            .asSequence()
            .filter { it != context.packageName }
            .distinct()
            .mapNotNull { inventory.describe(it) }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    fun isVpnApp(packageName: String): Boolean =
        packageName in vpnPackageCache || KnownPackages.isVpnTool(packageName)

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
            // Only a resolver that does not filter. Private DNS pointed at one that does is the
            // filter working, not a way around it.
            encryptedDnsHost = status.privateDnsHost
                ?.takeIf { status.encryptedDnsActive }
                ?.takeUnless { PrivateDnsGuard.isFilteringResolverHost(it) },
        )
        _posture.value = posture
        return posture
    }

    /**
     * Called on every monitor tick with the current posture, to tell the parent what changed.
     *
     * The lockout itself is applied by the monitoring loop, app by app, so this only records.
     * Private DNS pointed at a resolver that does not filter is reported by the monitoring loop's
     * Private DNS check rather than here, so the parent hears it once.
     */
    suspend fun onPosture(posture: VpnPosture, lockoutPossible: Boolean) {
        if (!posture.foreignTunnelActive) return

        val lockout = preferences.settings.first().vpnLockout
        // Three different outcomes, and they must not be described as one. A parent told "the
        // phone is locked" when the overlay permission is missing would believe a VPN is
        // unusable while the child is using it.
        val outcome = when {
            lockout && lockoutPossible ->
                "Every app is blocked until it is switched off, apart from Settings, where it " +
                    "can be turned off, and the phone."
            !lockout ->
                "Blocking the phone while a VPN is on is switched off, so this was recorded only."
            else ->
                "The phone cannot be blocked while it is on, because usage access or permission " +
                    "to draw over other apps is missing."
        }

        eventRepository.record(
            type = SafetyEventType.VPN_DETECTED,
            title = "A VPN is running",
            detail = buildString {
                append("A tunnel this app does not own became active")
                if (posture.tunnelInterfaces.isNotEmpty()) {
                    append(" on ${posture.tunnelInterfaces.joinToString()}")
                }
                append(". ")
                append(outcome)
            },
            severity = EventSeverity.HIGH,
            action = if (lockout && lockoutPossible) EventAction.BLOCKED else EventAction.NOT_ENFORCEABLE,
            capabilityId = CAPABILITY_ID,
            dedupeWindowMillis = 5 * 60_000L,
        )
    }

    /**
     * Android's VPN connection request is on screen. Returns whether it should be refused.
     *
     * [namesThisApp] is whether the dialog's text names this app. Both that and a request this app
     * raised moments ago are needed to let it through: the window alone would let any VPN that
     * asked during it slip past, and the name alone could be copied by an app that called itself
     * the same thing. The caller presses Cancel, because only the accessibility service can, and
     * reports back through [onConsentRefused].
     */
    suspend fun shouldRefuseConsent(namesThisApp: Boolean): Boolean {
        if (namesThisApp && ownConsentPending()) return false
        val settings = preferences.settings.first()
        return settings.protectionEnabled &&
            settings.isEnabled(ProtectionFeature.VPN_RESTRICTION) &&
            settings.blockVpnApps
    }

    suspend fun onConsentRefused(refused: Boolean) {
        if (refused) _refusedRequests.update { it + 1 }
        eventRepository.record(
            type = SafetyEventType.BYPASS_ATTEMPT,
            title = if (refused) "A VPN connection was refused" else "A VPN asked to connect",
            detail = if (refused) {
                "An app asked Android for permission to start a VPN. The request was cancelled " +
                    "before it could be accepted, so the VPN never started."
            } else {
                "An app asked Android for permission to start a VPN, and the request could not " +
                    "be cancelled automatically. If it was accepted, the phone is blocked while " +
                    "the VPN is on."
            },
            severity = EventSeverity.HIGH,
            action = if (refused) EventAction.BLOCKED else EventAction.NOT_ENFORCEABLE,
            capabilityId = CAPABILITY_ID,
            dedupeWindowMillis = 60_000L,
        )
    }

    /** Recorded by the monitor when it closes a VPN app that was opened. */
    suspend fun onVpnAppBlocked(packageName: String, appLabel: String) {
        _blockedLaunches.update { it + 1 }
        eventRepository.record(
            type = SafetyEventType.RESTRICTED_APP_OPENED,
            title = "$appLabel was closed",
            detail = "VPN apps are restricted, so this app is closed each time it is opened.",
            packageName = packageName,
            appLabel = appLabel,
            severity = EventSeverity.HIGH,
            action = EventAction.BLOCKED,
            capabilityId = CAPABILITY_ID,
            dedupeWindowMillis = 60_000L,
        )
    }

    /** Recorded by the monitor when it blocks an app because a VPN is up. */
    fun onLockout() {
        _lockouts.update { it + 1 }
    }

    companion object {
        const val CAPABILITY_ID = "vpn_restriction"
        private const val VPN_SERVICE_ACTION = "android.net.VpnService"

        /**
         * How long after this app asks for its own VPN consent the dialog is left alone.
         *
         * Long enough for a parent to read the dialog and accept it, short enough that a child
         * cannot open a pause request and then use the window for something else.
         */
        private const val OWN_CONSENT_WINDOW_MILLIS = 90_000L

        @Volatile private var ownConsentRequestedAt = 0L

        /**
         * This app's own VPN consent intent, or null when consent is already held.
         *
         * Every place that raises the dialog goes through here, because the guard cancels every
         * connection request it did not expect, and the pause needs one.
         */
        fun ownConsentIntent(context: Context): Intent? {
            val intent = runCatching { VpnService.prepare(context) }.getOrNull() ?: return null
            ownConsentRequestedAt = SystemClock.elapsedRealtime()
            NetworkLockdown.liftVpnRestriction(context)
            return intent
        }

        fun ownConsentPending(): Boolean =
            ownConsentRequestedAt != 0L &&
                SystemClock.elapsedRealtime() - ownConsentRequestedAt < OWN_CONSENT_WINDOW_MILLIS

        private val emptyPosture = VpnPosture(
            ownTunnelActive = false,
            foreignTunnelActive = false,
            tunnelInterfaces = emptyList(),
            installedVpnApps = emptyList(),
            encryptedDnsHost = null,
        )
    }
}
