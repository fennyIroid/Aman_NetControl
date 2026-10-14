package com.example.childsafetyimpl.core.protection

import com.example.childsafetyimpl.core.permission.SafetyPermission

/**
 * Whether the product is actually protecting this child right now.
 *
 * Every other status type in this app answers a narrower question: [ProtectionStatus] says how one
 * protection is doing, [PermissionState] says whether one grant is held. This one answers the
 * question a parent actually has, which is whether the thing they installed is working.
 *
 * The rule that shapes it: **a protection that has stopped must never be indistinguishable from a
 * protection that is running.** Silence is the failure mode. A parent who opens the app and sees
 * a calm green screen while the accessibility service has been off for a week has been actively
 * misled, and that is worse than not having installed anything, because they stopped watching.
 *
 * Every issue carries three things, and the shape is not decorative — it is what the brief asks
 * for and what the support flow reads: what is wrong, why it matters to this child, and what to
 * do about it. An issue that cannot answer the third has to say so explicitly rather than leave
 * the parent hunting, which is what [Issue.resolution] being nullable is for.
 */
data class ProtectionHealth(
    val state: HealthState,
    val issues: List<Issue>,
    /** When the enforcement loop last proved it was alive. */
    val lastHeartbeatMillis: Long,
    /** Protections the parent switched on that are currently running. */
    val activeCount: Int,
    /** Protections the parent switched on that cannot run. */
    val impairedCount: Int,
) {

    /** The single line a dashboard puts at the top. */
    val headline: String
        get() = when (state) {
            HealthState.ACTIVE -> "Protection is active"
            HealthState.DEGRADED -> "Protection is running with gaps"
            HealthState.DISABLED -> "Protection is not running"
            HealthState.TAMPERED -> "Protection was switched off on this device"
        }

    val mostSevere: Issue? get() = issues.minByOrNull { it.kind.rank }

    companion object {
        /**
         * Builds the health picture from what is true right now.
         *
         * Pure, and deliberately so: the thing that decides whether a parent is told their child
         * is protected should be testable without a device, a service or a grant.
         */
        fun from(
            protectionEnabled: Boolean,
            statuses: List<ProtectionStatus>,
            monitoringRunning: Boolean,
            lastHeartbeatMillis: Long,
            nowMillis: Long,
            foreignVpnActive: Boolean,
            privateDnsBypassing: Boolean,
            privateDnsHost: String?,
            recentlyRevokedGrants: Set<SafetyPermission>,
            internetPauseRequested: Boolean,
            internetPauseEnforced: Boolean,
            /**
             * Android's Advanced Protection Mode is on. Defaulted so callers that predate it, and
             * devices before Android 16 that cannot have it, need not say anything.
             */
            advancedProtectionOn: Boolean = false,
        ): ProtectionHealth {
            val issues = mutableListOf<Issue>()

            // Advanced Protection revokes accessibility access from anything that is not an
            // accessibility tool, and a parental control is not one. That revocation is the
            // operating system's, not a child's, so it is taken out of the tamper signal below
            // and reported as what it is. Calling it tampering would accuse the child of
            // something a security setting did.
            val revokedByChild = if (advancedProtectionOn) {
                recentlyRevokedGrants - SafetyPermission.ACCESSIBILITY
            } else {
                recentlyRevokedGrants
            }

            // Revoked grants come first. This is the tamper signal, and it is the only one that
            // says somebody on this device took an action to remove a protection rather than a
            // protection failing on its own.
            revokedByChild.forEach { permission ->
                issues += Issue(
                    kind = IssueKind.PERMISSION_REVOKED,
                    title = "${permission.title} was switched off",
                    why = permission.withoutIt,
                    resolution = "Grant it again from the Permissions screen. " +
                        permission.grantMechanism.label + ".",
                    permission = permission,
                )
            }

            if (protectionEnabled && !monitoringRunning) {
                issues += Issue(
                    kind = IssueKind.SERVICE_STOPPED,
                    title = "The protection service is not running",
                    why = "Nothing is watching which app is open, so app limits, blocks and " +
                        "bedtime cannot be enforced at all.",
                    resolution = "Open the app to restart it. If this keeps happening, turn off " +
                        "battery optimisation for this app, which is the usual cause on phones " +
                        "that manage background apps aggressively.",
                )
            } else if (protectionEnabled && lastHeartbeatMillis > 0) {
                val silentFor = nowMillis - lastHeartbeatMillis
                if (silentFor > STALE_HEARTBEAT_MILLIS) {
                    issues += Issue(
                        kind = IssueKind.SERVICE_STALLED,
                        title = "Protection has not checked in recently",
                        why = "The last check was ${silentFor / 60_000} minutes ago. Gaps this " +
                            "long usually mean the system is pausing the app in the background, " +
                            "and enforcement does not happen during them.",
                        resolution = "Set battery use for this app to unrestricted.",
                    )
                }
            }

            if (foreignVpnActive) {
                issues += Issue(
                    kind = IssueKind.FOREIGN_VPN,
                    title = "Another VPN is running",
                    why = "Traffic is leaving through an app this product does not control, so " +
                        "the Private DNS filter and Safe Search do not apply to it.",
                    resolution = "Switch it off in Settings, Network and internet, VPN, and " +
                        "remove the app. With VPN restriction on, the phone stays blocked " +
                        "until it is off.",
                )
            }

            if (privateDnsBypassing) {
                // Private DNS is the filter. Two different sentences: the setting cleared, or the
                // setting pointed at a resolver that does not filter. Both leave the device
                // unfiltered, and neither is fixed by "set it to Automatic", which is no filter.
                issues += Issue(
                    kind = IssueKind.PRIVATE_DNS_BYPASS,
                    title = if (privateDnsHost == null) "Private DNS is not set"
                    else "Private DNS points at a resolver that does not filter",
                    why = "Web filtering on this device runs through Private DNS. " +
                        (
                            if (privateDnsHost == null) "It is not pointed at a filtering " +
                                "resolver, so websites are not being filtered."
                            else "Lookups are going to $privateDnsHost instead, so website " +
                                "blocking and Safe Search do not apply."
                            ),
                    resolution = "Controls, Web filtering: follow the Private DNS steps and " +
                        "enter the hostname shown there. Android does not let an app change " +
                        "this setting for you unless the device is managed.",
                )
            }

            // The pause is the one control where the gap between intent and enforcement is
            // invisible from the parent's side, so it gets its own issue rather than being
            // folded into the VPN one.
            if (internetPauseRequested && !internetPauseEnforced) {
                issues += Issue(
                    kind = IssueKind.PAUSE_NOT_ENFORCED,
                    title = "The internet is not actually paused",
                    why = "You paused the internet, but the pause is not running, so this " +
                        "device is online. The connection permission was most likely withdrawn, " +
                        "or another VPN is set as always-on.",
                    resolution = "Open the app and allow the connection request again.",
                )
            }

            if (advancedProtectionOn) {
                issues += Issue(
                    kind = IssueKind.ADVANCED_PROTECTION,
                    title = "Android Advanced Protection is on",
                    why = "While it is on, Android withholds accessibility access from apps that " +
                        "are not accessibility tools, and a parental control is not one. The " +
                        "protections that read the screen — Shorts and Reels, Status, search " +
                        "terms in other browsers and AI chat — cannot run. This is Android's " +
                        "decision, not something done to this device.",
                    resolution = "Advanced Protection is in Settings, Security and privacy. " +
                        "Leaving it on is a reasonable choice; if you do, those protections stay " +
                        "off and everything else keeps working.",
                )
            }

            statuses.filter { it.enabled && it.state == ProtectionState.NEEDS_PERMISSION }
                .forEach { status ->
                    issues += Issue(
                        kind = IssueKind.PROTECTION_IMPAIRED,
                        title = "${status.feature.title} cannot run",
                        why = status.missingRequired.joinToString(" ") { it.withoutIt },
                        resolution = "Grant " +
                            status.missingRequired.joinToString(", ") { it.title } + ".",
                        feature = status.feature,
                    )
                }

            val enabled = statuses.filter { it.enabled }
            val running = enabled.count { it.state.isRunning }
            val impaired = enabled.size - running

            val state = when {
                !protectionEnabled -> HealthState.DISABLED
                issues.any { it.kind == IssueKind.PERMISSION_REVOKED } -> HealthState.TAMPERED
                issues.any { it.kind == IssueKind.SERVICE_STOPPED } -> HealthState.DISABLED
                issues.isNotEmpty() -> HealthState.DEGRADED
                else -> HealthState.ACTIVE
            }

            return ProtectionHealth(
                state = state,
                issues = issues.sortedBy { it.kind.rank },
                lastHeartbeatMillis = lastHeartbeatMillis,
                activeCount = running,
                impairedCount = impaired,
            )
        }

        /**
         * How long the loop may go quiet before it counts as a gap.
         *
         * Generous on purpose. The loop ticks in seconds, but Doze legitimately stretches that,
         * and an alert that fires every time a phone sat in a pocket overnight is an alert a
         * parent turns off — taking the real ones with it.
         */
        const val STALE_HEARTBEAT_MILLIS = 15 * 60_000L
    }

    /** One thing that is wrong, in the shape the support flow and the alert both read. */
    data class Issue(
        val kind: IssueKind,
        /** What is wrong, as a parent would say it. */
        val title: String,
        /** Why it matters for this child, in terms of what is no longer being prevented. */
        val why: String,
        /** How to fix it, or null when nothing the parent can do will fix it. */
        val resolution: String?,
        val permission: SafetyPermission? = null,
        val feature: ProtectionFeature? = null,
    )

    /**
     * Ordered by how much it should worry a parent.
     *
     * The rank is the sort key everywhere this list is shown, so the first row is always the one
     * that matters most rather than the one that happened to be found first.
     */
    enum class IssueKind(val rank: Int, val label: String) {
        PERMISSION_REVOKED(0, "Access removed"),
        SERVICE_STOPPED(1, "Service stopped"),
        PAUSE_NOT_ENFORCED(2, "Pause not in force"),
        PRIVATE_DNS_BYPASS(3, "Filtering bypassed"),
        FOREIGN_VPN(4, "Another VPN"),
        SERVICE_STALLED(5, "Checks delayed"),
        ADVANCED_PROTECTION(6, "Android security mode"),
        PROTECTION_IMPAIRED(7, "Needs permission"),
    }
}

/**
 * The four states the brief asks for, kept separate from [ProtectionState].
 *
 * [DISABLED] and [TAMPERED] are both "not protecting", and they are deliberately not merged: one
 * is a parent's decision and the other is a child's, and a product that shows the same screen for
 * both is failing at the only job this model has.
 */
enum class HealthState(val label: String) {
    ACTIVE("Active"),
    DEGRADED("Degraded"),
    DISABLED("Not running"),
    TAMPERED("Tampered with"),
}
