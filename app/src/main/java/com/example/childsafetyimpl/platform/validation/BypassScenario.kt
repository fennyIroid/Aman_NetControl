package com.example.childsafetyimpl.platform.validation

/**
 * The bypass scenarios POC 2 has to answer, as data.
 *
 * Sprint 0 asks for one thing above all others: a clear statement of which bypasses can be
 * *prevented*, which can only be *detected and reported*, and which are *platform limitations*.
 * That sentence is the deliverable, and this catalog is it in a form the app can execute and the
 * report can be generated from, rather than a spreadsheet that starts drifting from the code the
 * day after it is written.
 *
 * Keeping it in the app rather than in a document has a second effect that matters more over
 * time. A protection screen can state the limits that apply to it by reading this list, so the
 * claim a parent is shown and the claim the engineering team validated are the same sentence. The
 * moment they are two sentences, one of them starts being wrong.
 *
 * The honest framing, which belongs at the top of the results and not in a footnote: an app
 * installed from the Play Store is a guest on the device. It holds no authority over the user,
 * and the user is the person it is restricting. Almost everything in [Classification.DETECTED]
 * moves to [Classification.PREVENTED] under Device Owner provisioning, and that single
 * architectural choice determines more about this product's real-world strength than every rule
 * in the filter combined.
 */
data class BypassScenario(
    val id: String,
    val group: ScenarioGroup,
    /** What the child actually does, in the words someone running the test would use. */
    val attempt: String,
    /** What this app does about it as currently built. */
    val behaviour: String,
    val classification: Classification,
    /** The mechanism that produces the behaviour, so a failing test points at the right code. */
    val mechanism: String,
    /** What would be needed to move this to a stronger classification, when anything would. */
    val strongerWith: String? = null,
)

enum class ScenarioGroup(val label: String) {
    WEBSITE_BLOCKING("Harmful website blocking"),
    SAFE_SEARCH("Safe Search"),
    LIST_RULES("Block-list and allow-list"),
    VPN("VPN detection and restriction"),
    DNS_BYPASS("DNS and network bypass"),
    INTERNET_PAUSE("Internet pause and resume"),
    PERSISTENCE("Restart and persistence"),
    TAMPERING("Permission removal and tampering"),
}

/**
 * The three answers Sprint 0 asked for, plus the one it did not but that the results need.
 *
 * The line between [PREVENTED] and [DETECTED] is the most important one in this product, and it
 * is drawn strictly: prevented means the child does not get the thing they were trying to get.
 * Anything softer than that — an alert, a log entry, a notification to a parent who is at work —
 * is [DETECTED], however fast it happens.
 */
enum class Classification(val label: String, val shortLabel: String) {
    PREVENTED(
        "Prevented",
        "Prevented",
    ),
    DETECTED(
        "Detected and reported, not prevented",
        "Detected",
    ),
    PLATFORM_LIMIT(
        "Platform limitation: neither prevented nor reliably detected",
        "Limitation",
    ),
    NOT_APPLICABLE("Not applicable on this platform", "N/A"),
}

/**
 * The verdict the contract asks for per POC.
 *
 * Deliberately the client's own four words rather than a pass/fail, because the useful answer to
 * "can this be built" is almost never yes or no.
 */
enum class PocVerdict(val label: String) {
    GO("Go"),
    GO_WITH_LIMITATION("Go with limitation"),
    ALTERNATIVE("Alternative approach"),
    NOT_RECOMMENDED("Not recommended"),
}

/**
 * The catalog.
 *
 * Every entry is a test someone can actually run on a handset in an afternoon. Scenarios that
 * cannot be run that way do not belong here, because a validation result nobody reproduced is an
 * opinion.
 */
object BypassScenarios {

    val all: List<BypassScenario> = listOf(

        // --- Harmful website blocking -------------------------------------------------------
        BypassScenario(
            id = "web.chrome",
            group = ScenarioGroup.WEBSITE_BLOCKING,
            attempt = "Open a site in a blocked category in Chrome.",
            behaviour = "The name lookup is refused in the tunnel before the connection is made.",
            classification = Classification.PREVENTED,
            mechanism = "ChildSafetyVpnService.handleQuery via SafetyRulesRepository.evaluateHost",
        ),
        BypassScenario(
            id = "web.other_browser",
            group = ScenarioGroup.WEBSITE_BLOCKING,
            attempt = "Open the same site in Samsung Internet, Firefox, Brave or Opera.",
            behaviour = "Identical. Filtering is at the DNS layer, so it is not per-browser.",
            classification = Classification.PREVENTED,
            mechanism = "Same path. No browser-specific code is involved.",
        ),
        BypassScenario(
            id = "web.direct_ip",
            group = ScenarioGroup.WEBSITE_BLOCKING,
            attempt = "Type the site's raw IP address into the address bar.",
            behaviour = "Not blocked. No name is looked up, so nothing reaches the filter.",
            classification = Classification.PLATFORM_LIMIT,
            mechanism = "The tunnel routes DNS only, by design, so it never sees the connection.",
            strongerWith = "Routing all traffic through the tunnel, at the cost of battery, " +
                "banking-app compatibility and the risk that a bug takes the device offline. " +
                "Not recommended: the attack requires knowing an IP, and most large sites do " +
                "not serve their content from a bare address.",
        ),
        BypassScenario(
            id = "web.translate_proxy",
            group = ScenarioGroup.WEBSITE_BLOCKING,
            attempt = "Reach a blocked site through Google Translate or a web proxy.",
            behaviour = "Not blocked unless the proxy's own host is on a list.",
            classification = Classification.PLATFORM_LIMIT,
            mechanism = "The lookup is for the proxy, which is a permitted host.",
            strongerWith = "A maintained proxy and anonymiser category in the licensed feed. " +
                "This is a category-coverage problem, not an architecture problem.",
        ),

        // --- Safe Search --------------------------------------------------------------------
        BypassScenario(
            id = "search.google_off",
            group = ScenarioGroup.SAFE_SEARCH,
            attempt = "Switch SafeSearch off inside Google's own settings and search again.",
            behaviour = "Still filtered. The lookup is answered with forcesafesearch.google.com, " +
                "which serves filtered results and offers no control to turn them off.",
            classification = Classification.PREVENTED,
            mechanism = "SafeSearchDns.aliasFor",
        ),
        BypassScenario(
            id = "search.country_domain",
            group = ScenarioGroup.SAFE_SEARCH,
            attempt = "Switch to google.co.uk, google.ae or any other country domain.",
            behaviour = "Still filtered. The alias matches the shape of Google's country " +
                "domains rather than a fixed list of them.",
            classification = Classification.PREVENTED,
            mechanism = "SafeSearchDns.googleDomain regular expression",
        ),
        BypassScenario(
            id = "search.youtube_app",
            group = ScenarioGroup.SAFE_SEARCH,
            attempt = "Search inside the YouTube app rather than the website.",
            behaviour = "Restricted Mode applies. The alias covers youtubei.googleapis.com, " +
                "which is the app's own data path and not just the website.",
            classification = Classification.PREVENTED,
            mechanism = "SafeSearchDns exact alias table",
        ),

        // --- Block-list and allow-list ------------------------------------------------------
        BypassScenario(
            id = "rules.parent_domain",
            group = ScenarioGroup.LIST_RULES,
            attempt = "Visit a domain a parent added by hand.",
            behaviour = "Refused, and recorded against the rule that matched it.",
            classification = Classification.PREVENTED,
            mechanism = "DomainRule with RuleSource.PARENT",
        ),
        BypassScenario(
            id = "rules.allow_only",
            group = ScenarioGroup.LIST_RULES,
            attempt = "With allow-list-only mode on, visit anything not on the list.",
            behaviour = "Refused. Only listed names resolve.",
            classification = Classification.PREVENTED,
            mechanism = "SafetySettings.allowListOnlyBrowsing",
        ),
        BypassScenario(
            id = "rules.allow_only_breakage",
            group = ScenarioGroup.LIST_RULES,
            attempt = "With allow-list-only on, check whether the device still functions.",
            behaviour = "Connectivity checks, push notifications, Play Store updates, account " +
                "sign-in and time sync keep working, because a fixed set of system hosts stays " +
                "reachable. A listed website whose pictures come from a separate CDN may load " +
                "without them until that host is listed too: general CDNs are deliberately not " +
                "exempt, because a whole website can be served from one.",
            classification = Classification.PREVENTED,
            mechanism = "SystemTrafficExemptions, consulted only after the parent's own rules " +
                "and the blocked categories, so it never outranks a block",
            strongerWith = "Per-site CDN pairing from a licensed category feed, so listing a " +
                "site brings its asset hosts with it.",
        ),

        // --- VPN ----------------------------------------------------------------------------
        BypassScenario(
            id = "vpn.known_app",
            group = ScenarioGroup.VPN,
            attempt = "Install and open a well-known VPN app.",
            behaviour = "Closed as it comes to the foreground, and the parent is alerted.",
            classification = Classification.PREVENTED,
            mechanism = "VpnGuard.isVpnApp via the MonitoringService foreground check",
        ),
        BypassScenario(
            id = "vpn.unknown_app",
            group = ScenarioGroup.VPN,
            attempt = "Use a VPN app that is not on the known list.",
            behaviour = "Not closed on open, but the tunnel it establishes is detected within " +
                "one monitoring tick and reclaimed if reclaim is switched on.",
            classification = Classification.PREVENTED,
            mechanism = "VpnDetector transport and interface checks, then ACTION_RECLAIM",
            strongerWith = "Querying the VPN service intent finds most of them regardless of " +
                "the curated list; reclaim is the backstop that does not depend on naming.",
        ),
        BypassScenario(
            id = "vpn.builtin_settings",
            group = ScenarioGroup.VPN,
            attempt = "Configure an IKEv2 or L2TP VPN in Android Settings rather than an app.",
            behaviour = "Detected as a foreign tunnel and reclaimed. The VPN settings screen " +
                "itself can also be closed on sight when that option is on.",
            classification = Classification.PREVENTED,
            mechanism = "VpnDetector plus the VPN_SETTINGS surface signature",
        ),
        BypassScenario(
            id = "vpn.always_on_third_party",
            group = ScenarioGroup.VPN,
            attempt = "Set a third-party VPN as always-on with lockdown in Settings.",
            behaviour = "This app can no longer establish its tunnel at all, and says so. " +
                "Lockdown means Android refuses to let anything else hold the slot.",
            classification = Classification.DETECTED,
            mechanism = "Builder.establish returns null; State.setError carries the reason",
            strongerWith = "Device Owner, which owns the always-on setting and can point it at " +
                "this app permanently. This is the clearest example of the provisioning " +
                "question deciding a real capability.",
        ),
        BypassScenario(
            id = "vpn.revoke_consent",
            group = ScenarioGroup.VPN,
            attempt = "Revoke this app's VPN permission from Settings or the quick tile.",
            behaviour = "onRevoke fires, the event is recorded as critical, and the tunnel is " +
                "re-established after a short delay if reclaim is on. A determined child can " +
                "repeat this faster than the reclaim.",
            classification = Classification.DETECTED,
            mechanism = "ChildSafetyVpnService.onRevoke",
            strongerWith = "Device Owner with always-on lockdown, where the revoke is not " +
                "offered to the user at all.",
        ),

        // --- DNS and network bypass ---------------------------------------------------------
        BypassScenario(
            id = "dns.private_dns_hostname",
            group = ScenarioGroup.DNS_BYPASS,
            attempt = "Set Private DNS to dns.google in Android Settings.",
            behaviour = "Every lookup leaves encrypted and unfilterable. Detected on the next " +
                "tick and raised as a critical issue with the exact steps to undo it.",
            classification = Classification.DETECTED,
            mechanism = "PrivateDnsGuard.currentPosture",
            strongerWith = "Device Owner, which can set and lock Private DNS. No other route " +
                "exists: Android does not let an ordinary app write this setting.",
        ),
        BypassScenario(
            id = "dns.browser_doh_hostname",
            group = ScenarioGroup.DNS_BYPASS,
            attempt = "Switch on secure DNS in Chrome, chosen by provider name.",
            behaviour = "The lookup for the resolver's own hostname is refused, so Chrome falls " +
                "back to the system resolver, which is the filtered one.",
            classification = Classification.PREVENTED,
            mechanism = "EncryptedDnsPolicy.resolverHosts",
        ),
        BypassScenario(
            id = "dns.browser_doh_bootstrap_ip",
            group = ScenarioGroup.DNS_BYPASS,
            attempt = "Point secure DNS at a resolver by IP address, so no name lookup happens.",
            behaviour = "Packets to well-known resolver addresses are dropped by the host routes " +
                "the tunnel adds for exactly this case.",
            classification = Classification.PREVENTED,
            mechanism = "EncryptedDnsPolicy.blockedAddresses, added as /32 routes in startTunnel",
            strongerWith = "Coverage is a list, not a proof. A resolver not on the list, reached " +
                "by address, still works. Recorded as a known residual.",
        ),
        BypassScenario(
            id = "dns.unknown_resolver_ip",
            group = ScenarioGroup.DNS_BYPASS,
            attempt = "Use a self-hosted or obscure DoH resolver by IP.",
            behaviour = "Not blocked. Neither defence applies: no name is looked up and the " +
                "address is not on the list.",
            classification = Classification.PLATFORM_LIMIT,
            mechanism = "Out of reach of a DNS-scoped tunnel.",
            strongerWith = "Full-traffic routing with SNI inspection, which is a materially " +
                "different product with materially worse battery and compatibility.",
        ),
        BypassScenario(
            id = "dns.tethering",
            group = ScenarioGroup.DNS_BYPASS,
            attempt = "Share the connection to a second device and browse from there.",
            behaviour = "Nothing on the second device is filtered.",
            classification = Classification.PLATFORM_LIMIT,
            mechanism = "Tethered traffic does not traverse this device's VPN.",
            strongerWith = "Device Owner can disable tethering outright.",
        ),

        // --- Internet pause -----------------------------------------------------------------
        BypassScenario(
            id = "pause.applies",
            group = ScenarioGroup.INTERNET_PAUSE,
            attempt = "Pause the internet, then try to browse, stream and message.",
            behaviour = "All of it stops, on Wi-Fi and on mobile data, because the pause is " +
                "below both. Name lookups are refused rather than dropped so apps fail in " +
                "milliseconds instead of hanging.",
            classification = Classification.PREVENTED,
            mechanism = "TunnelMode.PAUSED with a default route and no forwarding",
        ),
        BypassScenario(
            id = "pause.child_message",
            group = ScenarioGroup.INTERNET_PAUSE,
            attempt = "Check what the child sees while paused.",
            behaviour = "An ongoing notice saying a parent paused the internet and nothing is " +
                "broken, rather than a connection error. Opening a browser, video, social or " +
                "messaging app also shows a paused screen once per visit; closing it returns " +
                "the child to the app, where anything offline still works.",
            classification = Classification.PREVENTED,
            mechanism = "SafetyNotifier.postInternetPausedNotice and " +
                "MonitoringService.maybeShowPauseNotice",
        ),
        BypassScenario(
            id = "pause.survives_reboot",
            group = ScenarioGroup.INTERNET_PAUSE,
            attempt = "Restart the device while the internet is paused.",
            behaviour = "The pause is re-applied on boot, because it is persisted before it is " +
                "applied rather than held in the running service.",
            classification = Classification.PREVENTED,
            mechanism = "SafetyPreferences.internetPaused and InternetPauseController.restoreAfterBoot",
        ),
        BypassScenario(
            id = "pause.process_killed",
            group = ScenarioGroup.INTERNET_PAUSE,
            attempt = "Let the system or a manufacturer's task killer end the app's process " +
                "while the internet is paused.",
            behaviour = "The tunnel reads the parent's pause from storage every time it starts, " +
                "so when Android restarts it the pause comes back rather than filtering. Where " +
                "the build does not restart it, the monitoring loop puts the pause back within " +
                "seconds. The parent is alerted only when neither is possible.",
            classification = Classification.PREVENTED,
            mechanism = "TunnelMode.restingFor on every tunnel start, and " +
                "MonitoringService.checkInternetPause",
            strongerWith = "Device Owner with always-on lockdown, which also covers a force " +
                "stop from Settings.",
        ),
        BypassScenario(
            id = "pause.revoke_vpn",
            group = ScenarioGroup.INTERNET_PAUSE,
            attempt = "Revoke VPN permission while the internet is paused.",
            behaviour = "The pause stops applying. The mismatch between what the parent asked " +
                "for and what is in force is raised as its own issue on the dashboard and as a " +
                "parent alert, rather than left silent.",
            classification = Classification.DETECTED,
            mechanism = "ProtectionHealth.IssueKind.PAUSE_NOT_ENFORCED and " +
                "MonitoringService.checkInternetPause",
            strongerWith = "Device Owner with always-on lockdown.",
        ),

        // --- Persistence --------------------------------------------------------------------
        BypassScenario(
            id = "persist.reboot",
            group = ScenarioGroup.PERSISTENCE,
            attempt = "Restart the device and check whether protection comes back.",
            behaviour = "Enforcement restarts from the boot receiver. The gap between boot and " +
                "the receiver firing is real and is reported rather than assumed away.",
            classification = Classification.PREVENTED,
            mechanism = "BootCompletedReceiver",
        ),
        BypassScenario(
            id = "persist.force_stop",
            group = ScenarioGroup.PERSISTENCE,
            attempt = "Force stop the app from Settings, App info.",
            behaviour = "Everything stops, including the receivers, and nothing restarts it " +
                "until the app is opened or the device restarts. The silence is detected " +
                "afterwards by the stale heartbeat, not while it is happening.",
            classification = Classification.DETECTED,
            mechanism = "ProtectionHealth stale-heartbeat check",
            strongerWith = "Device Owner, which can make the app non-stoppable.",
        ),
        BypassScenario(
            id = "persist.battery_optimisation",
            group = ScenarioGroup.PERSISTENCE,
            attempt = "Re-enable battery optimisation for the app and leave the device idle.",
            behaviour = "The loop is throttled and gaps appear in enforcement. Reported as " +
                "delayed checks with the fix named.",
            classification = Classification.DETECTED,
            mechanism = "Heartbeat freshness against STALE_HEARTBEAT_MILLIS",
            strongerWith = "Requesting the battery exemption during setup closes most of it, and " +
                "the parent can always re-enable optimisation afterwards. Device Owner can set " +
                "the exemption in a way the user cannot undo. Nothing else helps: Doze is a " +
                "platform guarantee and an app that defeated it would be abusing the system.",
        ),
        BypassScenario(
            id = "persist.oem_killer",
            group = ScenarioGroup.PERSISTENCE,
            attempt = "Same on an OEM with aggressive background management.",
            behaviour = "Varies by manufacturer and is the single largest source of real-world " +
                "unreliability. Must be measured per device rather than reasoned about.",
            classification = Classification.DETECTED,
            mechanism = "Same heartbeat check; the variable is the OEM, not the code.",
            strongerWith = "Per-OEM autostart guidance in the setup flow, which is what every " +
                "product in this category ends up shipping.",
        ),
        BypassScenario(
            id = "persist.safe_mode",
            group = ScenarioGroup.PERSISTENCE,
            attempt = "Boot the device into safe mode.",
            behaviour = "No third-party app runs at all. Nothing is enforced and nothing is " +
                "recorded while it lasts.",
            classification = Classification.PLATFORM_LIMIT,
            mechanism = "Android disables third-party apps in safe mode by design.",
            strongerWith = "Device Owner can block safe-mode boot.",
        ),

        // --- Tampering ----------------------------------------------------------------------
        BypassScenario(
            id = "tamper.revoke_accessibility",
            group = ScenarioGroup.TAMPERING,
            attempt = "Turn off the accessibility service in Settings.",
            behaviour = "Shorts, Status and the search-term guard stop immediately. Detected on " +
                "the next grant comparison and raised as a tamper event.",
            classification = Classification.DETECTED,
            mechanism = "MonitoringService.checkGrantChanges against lastKnownGrants",
            strongerWith = "Nothing available to a Play-installed app. No API notifies an app " +
                "that its accessibility grant was removed; the comparison is the only route.",
        ),
        BypassScenario(
            id = "tamper.revoke_usage",
            group = ScenarioGroup.TAMPERING,
            attempt = "Revoke Usage Access.",
            behaviour = "App limits and blocking stop. Reported as unavailable rather than as " +
                "zero usage, which is the rule this product refuses to break.",
            classification = Classification.DETECTED,
            mechanism = "DataResult.Unavailable from UsageStatsProvider",
            strongerWith = "Device Owner, where usage access is granted by policy rather than by " +
                "the user and cannot be handed back. Short of that this is detection only, and " +
                "the product's obligation is to show the gap rather than a plausible zero.",
        ),
        BypassScenario(
            id = "tamper.uninstall",
            group = ScenarioGroup.TAMPERING,
            attempt = "Uninstall the app.",
            behaviour = "Device admin makes uninstall require deactivation first, which is a " +
                "deterrent and not a barrier. A child who deactivates can then uninstall.",
            classification = Classification.DETECTED,
            mechanism = "SafetyDeviceAdminReceiver",
            strongerWith = "Device Owner, where uninstall is genuinely blocked.",
        ),
        BypassScenario(
            id = "tamper.clear_data",
            group = ScenarioGroup.TAMPERING,
            attempt = "Clear the app's storage from Settings.",
            behaviour = "Rules, history and settings are lost and the app returns to onboarding. " +
                "Nothing on the device records that it happened.",
            classification = Classification.PLATFORM_LIMIT,
            mechanism = "No callback exists for an app's own data being cleared.",
            strongerWith = "A backend holding the configuration, so the device re-syncs rather " +
                "than starting empty. This is a Phase 1 argument for the backend that is " +
                "independent of the parent-app one.",
        ),
        BypassScenario(
            id = "tamper.advanced_protection",
            group = ScenarioGroup.TAMPERING,
            attempt = "Switch on Android Advanced Protection Mode on Android 17 or newer.",
            behaviour = "The accessibility grant is revoked by the system and cannot be granted " +
                "again while the mode is on, because parental controls are not classed as " +
                "accessibility tools. Detected, and reported as the operating system's " +
                "decision with its own health state rather than as the child tampering.",
            classification = Classification.DETECTED,
            mechanism = "AdvancedProtectionProbe, read by ProtectionHealth and the monitoring " +
                "loop. The cause is the OS, not the child.",
            strongerWith = "Nothing. This is a deliberate platform decision and the correct " +
                "response is to tell the parent which protections stopped and why.",
        ),
    )

    fun byGroup(): Map<ScenarioGroup, List<BypassScenario>> = all.groupBy { it.group }

    fun byClassification(): Map<Classification, List<BypassScenario>> =
        all.groupBy { it.classification }

    /** Everything Device Owner provisioning would change, which is the architecture decision. */
    val deviceOwnerWouldStrengthen: List<BypassScenario> =
        all.filter { it.strongerWith?.contains("Device Owner") == true }
}
