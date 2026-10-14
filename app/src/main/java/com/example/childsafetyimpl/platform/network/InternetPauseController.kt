package com.example.childsafetyimpl.platform.network

import android.content.Context
import android.net.VpnService
import com.example.childsafetyimpl.data.prefs.SafetyPreferences
import com.example.childsafetyimpl.di.ApplicationScope
import com.example.childsafetyimpl.domain.model.EventAction
import com.example.childsafetyimpl.domain.model.EventSeverity
import com.example.childsafetyimpl.domain.model.SafetyEventType
import com.example.childsafetyimpl.domain.repository.SafetyEventRepository
import com.example.childsafetyimpl.platform.admin.NetworkLockdown
import com.example.childsafetyimpl.platform.alerts.SafetyNotifier
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-tap Internet Pause, and the reasons it is built the way it is.
 *
 * Pausing a child's internet is the most abrupt control in the product, so the requirement that
 * shapes every decision here is the one from the brief: the child must never see a generic
 * network failure. A phone that looks broken produces a factory reset and a support call. A phone
 * that says a parent paused the internet produces a conversation, which is the entire point.
 *
 * Three things follow from that.
 *
 * **It runs in a tunnel, not on the radios.** An app cannot switch off Wi-Fi or mobile data on
 * a modern Android release, and an app that could would be indistinguishable from malware. What
 * this app can do is hold the device's one VPN slot and refuse to carry anything, which covers
 * Wi-Fi and mobile data together because both sit underneath it. Filtering itself runs through
 * Private DNS with no tunnel at all; [PauseTunnelService] exists for the pause and nothing else,
 * and is up only while a pause is in force.
 *
 * **Name lookups are refused rather than dropped.** Explained where it happens, in the pump: a
 * refusal fails in milliseconds and a drop hangs for half a minute. The child reaching the notice
 * quickly is the difference between the two designs.
 *
 * **The paused state is persisted before it is applied.** A pause that a reboot silently lifts is
 * worse than no pause: the parent believes the internet is off and it is not. So the flag is
 * written first, and [restoreAfterBoot] puts the device back into the paused posture on the next
 * start. That ordering is deliberate — if the process dies between the two, the device comes back
 * paused, which is the safe direction to fail in.
 *
 * The honest limit, recorded against the capability and stated on the screen that offers it: this
 * holds while this app holds the tunnel. A child who revokes VPN consent in Settings ends the
 * pause, and the app finds out and tells the parent rather than preventing it. Preventing it
 * needs always-on VPN with lockdown, which Android only grants to a Device Owner.
 */
@Singleton
class InternetPauseController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val preferences: SafetyPreferences,
    private val eventRepository: SafetyEventRepository,
    private val notifier: SafetyNotifier,
    private val networkLockdown: NetworkLockdown,
    @param:ApplicationScope private val scope: CoroutineScope,
) {

    /**
     * Whether the parent has the internet paused.
     *
     * Read from preferences rather than from the tunnel, because this is the parent's intent and
     * it stays true across a reboot, a revoked consent and a killed process. Whether the intent
     * is currently being *enforced* is a different question, answered by [isEnforced].
     */
    val paused: Flow<Boolean> = preferences.settings.map { it.internetPaused }

    /**
     * Whether the pause is actually in force right now.
     *
     * The gap between this and [paused] is the honest part of the feature, and the protection
     * health model turns that gap into a parent alert rather than letting it sit unnoticed.
     */
    fun isEnforced(): Boolean = PauseTunnelService.State.running.value

    /** True when the tunnel cannot be driven because consent was never given or was withdrawn. */
    fun needsVpnConsent(): Boolean =
        runCatching { VpnService.prepare(context) != null }.getOrDefault(true)

    /**
     * Pauses the internet.
     *
     * Returns the outcome rather than a boolean, because "we could not do this and here is
     * exactly why" is a state the parent's screen has to render honestly. Silently failing to
     * pause is the failure mode this whole class exists to avoid.
     */
    suspend fun pause(): PauseOutcome {
        // On a managed device VPN configuration is forbidden by policy, and that would refuse
        // this tunnel too. Lifted first, because the consent check below reads it.
        networkLockdown.allowOwnTunnel()
        if (needsVpnConsent()) return PauseOutcome.NeedsConsent

        // Written before it is applied, so a crash between the two leaves the device paused
        // rather than quietly connected.
        preferences.setInternetPaused(true)

        val delivered = startTunnel()
        if (!delivered) {
            preferences.setInternetPaused(false)
            return PauseOutcome.Failed("The filtering service could not be started.")
        }

        notifier.postInternetPausedNotice()
        eventRepository.record(
            type = SafetyEventType.INTERNET_PAUSED,
            title = "Internet paused",
            detail = "A parent paused the internet on this device. Wi-Fi and mobile data are " +
                "both covered because the pause is applied in a tunnel underneath them. Apps " +
                "that work offline keep working.",
            severity = EventSeverity.INFO,
            action = EventAction.BLOCKED,
            capabilityId = CAPABILITY_ID,
        )
        return PauseOutcome.Paused
    }

    /** Lifts the pause and takes the tunnel down; filtering carries on through Private DNS. */
    suspend fun resume() {
        preferences.setInternetPaused(false)
        PauseTunnelService.stop(context)
        notifier.clearInternetPausedNotice()
        eventRepository.record(
            type = SafetyEventType.INTERNET_PAUSED,
            title = "Internet resumed",
            detail = "A parent lifted the pause. Filtering carries on through Private DNS.",
            severity = EventSeverity.INFO,
            action = EventAction.LOGGED,
            capabilityId = CAPABILITY_ID,
        )
    }

    suspend fun toggle(): PauseOutcome {
        return if (preferences.settings.first().internetPaused) {
            resume()
            PauseOutcome.Resumed
        } else {
            pause()
        }
    }

    /**
     * Re-applies a pause that was in force before the device restarted.
     *
     * Called from the boot receiver. If this is missed, a child learns that restarting the phone
     * is how you get the internet back, and the control is worthless from then on.
     */
    fun restoreAfterBoot() {
        scope.launch {
            if (!preferences.settings.first().internetPaused) return@launch
            if (needsVpnConsent()) {
                eventRepository.record(
                    type = SafetyEventType.BYPASS_ATTEMPT,
                    title = "Internet pause did not survive the restart",
                    detail = "The internet was paused before this device restarted, but VPN " +
                        "consent is no longer granted, so the pause could not be re-applied. " +
                        "The device is online.",
                    severity = EventSeverity.CRITICAL,
                    action = EventAction.NOT_ENFORCEABLE,
                    capabilityId = CAPABILITY_ID,
                )
                return@launch
            }
            networkLockdown.allowOwnTunnel()
            startTunnel()
            notifier.postInternetPausedNotice()
        }
    }

    /**
     * Puts a pause back in force when the parent's intent and the tunnel disagree.
     *
     * Called from the monitoring loop. The tunnel restores a pause itself whenever Android
     * restarts it, but a process killed on a build that does not restart sticky services leaves
     * nothing running to do that, and the monitor is the thing that survives. Returns false when
     * consent is gone, which is the one case only a parent can fix.
     */
    fun reapply(): Boolean {
        networkLockdown.allowOwnTunnel()
        if (needsVpnConsent()) return false
        return startTunnel()
    }

    private fun startTunnel(): Boolean = runCatching { PauseTunnelService.start(context) }.isSuccess

    /** What happened when a parent asked for a pause, in terms the parent's screen can render. */
    sealed interface PauseOutcome {
        data object Paused : PauseOutcome
        data object Resumed : PauseOutcome

        /** The tunnel has never been allowed, so the consent dialog has to be raised first. */
        data object NeedsConsent : PauseOutcome
        data class Failed(val reason: String) : PauseOutcome
    }

    companion object {
        const val CAPABILITY_ID = "internet_pause"

        /**
         * Apps whose whole purpose is the network.
         *
         * Used only to decide whether to put the paused notice in front of the child when one of
         * them is opened. It is not an enforcement list: nothing here is blocked, the network is
         * simply not there. Showing the notice for a browser and not for a calculator is what
         * makes the pause legible rather than mysterious.
         */
        val networkDependentPrefixes: List<String> = listOf(
            "com.android.chrome", "org.mozilla", "com.opera", "com.brave", "com.microsoft.emmx",
            "com.sec.android.app.sbrowser", "com.duckduckgo", "com.UCMobile", "com.kiwibrowser",
            "com.google.android.youtube", "com.instagram", "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill", "com.snapchat", "com.facebook", "com.twitter",
            "com.netflix", "com.spotify", "com.roblox", "com.whatsapp", "org.telegram",
            "com.discord", "com.reddit", "com.pinterest",
        )

        fun isNetworkDependent(packageName: String): Boolean =
            networkDependentPrefixes.any { packageName.startsWith(it) }
    }
}
