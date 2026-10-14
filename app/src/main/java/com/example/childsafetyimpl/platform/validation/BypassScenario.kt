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
            behaviour = "The name lookup goes to the filtering resolver through Private DNS, " +
                "which refuses it before the connection is made.",
            classification = Classification.PREVENTED,
            mechanism = "Android Private DNS pointed at a filtering resolver, checked by " +
                "PrivateDnsGuard.currentPosture",
            strongerWith = "Coverage is the resolver's category list. A resolver of our own " +
                "would let this product hold the list it is measured against.",
        ),
        BypassScenario(
            id = "web.other_browser",
            group = ScenarioGroup.WEBSITE_BLOCKING,
            attempt = "Open the same site in Samsung Internet, Firefox, Brave or Opera.",
            behaviour = "Identical for any browser that uses the system resolver. Private DNS is " +
                "a system setting, so it is not per-browser. A browser configured with its " +
                "own secure-DNS provider is the exception; see dns.browser_doh.",
            classification = Classification.PREVENTED,
            mechanism = "Same path. No browser-specific code is involved.",
        ),
        BypassScenario(
            id = "web.direct_ip",
            group = ScenarioGroup.WEBSITE_BLOCKING,
            attempt = "Type the site's raw IP address into the address bar.",
            behaviour = "No name is looked up, so the resolver never sees it; the address bar " +
                "does. Any public address opened in a browser is refused while filtering is " +
                "on, because nobody browses by address. Private addresses (a router at " +
                "192.168.1.1, a printer) are untouched, and a parent can allow an address by " +
                "hand. Apps other than browsers that connect to an address they were built " +
                "with are not covered.",
            classification = Classification.PREVENTED,
            mechanism = "DirectAddress, applied in SafetyRulesRepository.evaluateHost for the " +
                "built-in browser and SearchTermGuard for other browsers",
            strongerWith = "Seeing addresses outside browsers takes a tunnel, at the cost of " +
                "banking-app compatibility — the cost this product moved to Private DNS to " +
                "avoid. Not recommended.",
        ),
        BypassScenario(
            id = "web.disguised_ip",
            group = ScenarioGroup.WEBSITE_BLOCKING,
            attempt = "Open the address in a disguised form: http://1123971625, " +
                "http://0x42fe7229, http://allowed.com@66.254.114.41, or 66-254-114-41.sslip.io.",
            behaviour = "Each is read back to the address it stands for and refused the same " +
                "way. A user name before an @ is no longer mistaken for the site, which also " +
                "closes http://allowed.com@refused.com against the parent's own rules.",
            classification = Classification.PREVENTED,
            mechanism = "DirectAddress.fromAddressBar and BrowserSurfaces.hostFrom",
        ),
        BypassScenario(
            id = "web.translate_proxy",
            group = ScenarioGroup.WEBSITE_BLOCKING,
            attempt = "Reach a blocked site through Google Translate, the AMP cache or the " +
                "Wayback Machine.",
            behaviour = "Each writes the original site into its own address " +
                "(www-site-com.translate.goog, web.archive.org/web/…/site.com), which is read " +
                "back and judged as the site. Only a relay carrying a refused site is refused, " +
                "so translating an ordinary page keeps working. The Wayback form is caught " +
                "while the full address is visible in the bar.",
            classification = Classification.PREVENTED,
            mechanism = "ProxyOrigin, from SafetyRulesRepository.evaluateHost and SearchTermGuard",
        ),
        BypassScenario(
            id = "web.web_proxy",
            group = ScenarioGroup.WEBSITE_BLOCKING,
            attempt = "Reach a blocked site through a general web proxy site.",
            behaviour = "A general proxy hides the site it fetches, so nothing on the device " +
                "can read it back. Refused only if the resolver lists the proxy itself: " +
                "measured on ten well-known proxies, CleanBrowsing Family and KAHF Guard " +
                "refused all ten, Cloudflare for Families and AdGuard Family none.",
            classification = Classification.PLATFORM_LIMIT,
            mechanism = "The lookup is for the proxy, which is a permitted host.",
            strongerWith = "A maintained proxy and anonymiser category at the resolver. This " +
                "is a category-coverage problem, not an architecture problem.",
        ),

        // --- Safe Search --------------------------------------------------------------------
        BypassScenario(
            id = "search.google_off",
            group = ScenarioGroup.SAFE_SEARCH,
            attempt = "Switch SafeSearch off inside Google's own settings and search again.",
            behaviour = "Still filtered with a resolver that forces SafeSearch: Google's lookup " +
                "is answered with its SafeSearch address, which serves filtered results and " +
                "offers no control to turn them off. With a resolver that does not force it, " +
                "the protection screen says so rather than claiming it.",
            classification = Classification.PREVENTED,
            mechanism = "The resolver's SafeSearch enforcement, declared per resolver as " +
                "PrivateDnsResolver.forcesSafeSearch",
        ),
        BypassScenario(
            id = "search.country_domain",
            group = ScenarioGroup.SAFE_SEARCH,
            attempt = "Switch to google.co.uk, google.ae or any other country domain.",
            behaviour = "Still filtered with CleanBrowsing Family, which applies SafeSearch to " +
                "Google's country domains.",
            classification = Classification.PREVENTED,
            mechanism = "The resolver's SafeSearch enforcement",
        ),
        BypassScenario(
            id = "search.youtube_app",
            group = ScenarioGroup.SAFE_SEARCH,
            attempt = "Search inside the YouTube app rather than the website.",
            behaviour = "Restricted Mode applies with CleanBrowsing Family, which answers the " +
                "YouTube app's own hosts with the restricted address.",
            classification = Classification.PREVENTED,
            mechanism = "The resolver's YouTube Restricted Mode enforcement",
        ),

        // --- Block-list and allow-list ------------------------------------------------------
        BypassScenario(
            id = "rules.parent_domain",
            group = ScenarioGroup.LIST_RULES,
            attempt = "Visit a domain a parent added by hand, in a browser.",
            behaviour = "A public resolver cannot hold one family's list, so the address bar is " +
                "read on the device and the browser is left when the host is refused. The page " +
                "can start loading first, and apps other than browsers are not covered.",
            classification = Classification.PREVENTED,
            mechanism = "SearchTermGuard.onBrowserScreen via SafetyRulesRepository.evaluateHost",
            strongerWith = "A per-family resolver, so the parent's list is refused at the " +
                "lookup for every app. Phase 1 backend work.",
        ),
        BypassScenario(
            id = "rules.allow_only",
            group = ScenarioGroup.LIST_RULES,
            attempt = "With allow-list-only mode on, visit anything not on the list.",
            behaviour = "The browser is left as soon as its address bar shows a host that is " +
                "not on the list. Apps other than browsers are not limited by it.",
            classification = Classification.DETECTED,
            mechanism = "SafetySettings.allowListOnlyBrowsing, read by " +
                "SafetyRulesRepository.evaluateHost from SearchTermGuard",
            strongerWith = "A per-family resolver that answers only listed names, or restricting " +
                "every other browser from App restriction and leaving the built-in browser, " +
                "which enforces the list before a page loads.",
        ),
        BypassScenario(
            id = "rules.allow_only_breakage",
            group = ScenarioGroup.LIST_RULES,
            attempt = "With allow-list-only on, check whether the device still functions.",
            behaviour = "Everything outside browsers keeps working, because nothing refuses " +
                "lookups device-wide: connectivity checks, push, Play updates, sign-in and " +
                "time sync are untouched. Inside the built-in browser, a fixed set of system " +
                "hosts stays reachable and general CDNs are deliberately not exempt.",
            classification = Classification.PREVENTED,
            mechanism = "SystemTrafficExemptions, consulted only after the parent's own rules " +
                "and the blocked categories, so it never outranks a block",
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
            id = "vpn.new_app_consent",
            group = ScenarioGroup.VPN,
            attempt = "Install a VPN app the product has never heard of and accept its " +
                "connection request.",
            behaviour = "Android's connection request is cancelled by the accessibility " +
                "service as it appears, so the VPN never gets permission to start. Its app is " +
                "closed on open as well, because it declares a VPN service.",
            classification = Classification.PREVENTED,
            mechanism = "SafetyAccessibilityService.onVpnDialog and VpnGuard.shouldRefuseConsent",
            strongerWith = "Device Owner, where VPN setup is forbidden by policy and Android " +
                "refuses on its own (NetworkLockdown). Without the accessibility service the " +
                "request is not refused, and the lockout below is what remains.",
        ),
        BypassScenario(
            id = "vpn.already_approved",
            group = ScenarioGroup.VPN,
            attempt = "Use a VPN that was approved before protection was installed, from its " +
                "notification or quick tile so its app is never opened.",
            behaviour = "No dialog appears, so nothing can be refused, and it connects. Within " +
                "about four seconds every app but Settings, the home screen and the phone is " +
                "blocked, with a button to the VPN screen, until it is switched off.",
            classification = Classification.PREVENTED,
            mechanism = "VpnDetector, then VpnLockout in MonitoringService.evaluate",
            strongerWith = "Device Owner, which stops it starting at all. Without usage access " +
                "or permission to draw over apps the phone cannot be blocked and this is " +
                "reported only.",
        ),
        BypassScenario(
            id = "vpn.builtin_settings",
            group = ScenarioGroup.VPN,
            attempt = "Configure an IKEv2 or L2TP VPN in Android Settings rather than an app.",
            behaviour = "The VPN settings screen is closed on sight. A profile created anyway " +
                "is a foreign tunnel when it connects, and the phone is blocked until it is " +
                "switched off; the VPN screen is left open for exactly that.",
            classification = Classification.PREVENTED,
            mechanism = "The VPN_SETTINGS surface signature, then VpnLockout",
        ),
        BypassScenario(
            id = "vpn.always_on_third_party",
            group = ScenarioGroup.VPN,
            attempt = "Set a third-party VPN as always-on with lockdown in Settings.",
            behaviour = "The phone is blocked while it is connected, as for any VPN. It also " +
                "stops an internet pause being applied, because lockdown refuses every other " +
                "tunnel; that is raised as its own issue.",
            classification = Classification.PREVENTED,
            mechanism = "VpnLockout, and PauseTunnelService reporting the refused tunnel",
            strongerWith = "Device Owner, which forbids VPN configuration so always-on cannot " +
                "be set at all.",
        ),

        // --- DNS and network bypass ---------------------------------------------------------
        BypassScenario(
            id = "dns.private_dns_hostname",
            group = ScenarioGroup.DNS_BYPASS,
            attempt = "Set Private DNS to dns.google, or switch it off, in Android Settings.",
            behaviour = "The settings screen that holds it is closed on sight when VPN " +
                "restriction is on. Where that fails, every lookup leaves unfiltered, and it " +
                "is detected on the next tick and raised as a critical issue with the steps " +
                "to undo it.",
            classification = Classification.DETECTED,
            mechanism = "The VPN_SETTINGS surface signature and PrivateDnsGuard.currentPosture",
            strongerWith = "Device Owner, where this app sets Private DNS itself and locks it " +
                "(NetworkLockdown). No other route exists: Android does not let an ordinary " +
                "app write this setting.",
        ),
        BypassScenario(
            id = "dns.browser_doh",
            group = ScenarioGroup.DNS_BYPASS,
            attempt = "Switch on secure DNS inside a browser and choose a provider.",
            behaviour = "A browser with its own secure-DNS setting can send its lookups to a " +
                "resolver of its choosing, around Private DNS. The browser has to find that " +
                "resolver by name first, and CleanBrowsing Family refuses those names: " +
                "measured, it refused nine of ten common ones (dns.google, cloudflare-dns.com, " +
                "dns.quad9.net, NextDNS and others; not its own doh.cleanbrowsing.org, which " +
                "filters the same way). A resolver entered by address, such as " +
                "https://1.1.1.1/dns-query, needs no lookup and is not stopped. Cloudflare and " +
                "AdGuard Family refuse none of the ten. The parent's own site rules, the " +
                "address check and the relay check still apply in the address bar.",
            classification = Classification.PLATFORM_LIMIT,
            mechanism = "The resolver's own refusal of DNS-service names; nothing on the device " +
                "sees the browser's lookups.",
            strongerWith = "Restricting browsers that offer their own secure DNS from App " +
                "restriction. Device Owner can also disable the browser's setting through " +
                "managed configuration where the browser supports it.",
        ),
        BypassScenario(
            id = "dns.tethering",
            group = ScenarioGroup.DNS_BYPASS,
            attempt = "Share the connection to a second device and browse from there.",
            behaviour = "Nothing on the second device is filtered.",
            classification = Classification.PLATFORM_LIMIT,
            mechanism = "Private DNS applies to this device's own lookups only.",
            strongerWith = "Device Owner can disable tethering outright.",
        ),

        // --- Internet pause -----------------------------------------------------------------
        BypassScenario(
            id = "pause.applies",
            group = ScenarioGroup.INTERNET_PAUSE,
            attempt = "Pause the internet, then try to browse, stream and message.",
            behaviour = "All of it stops, on Wi-Fi and on mobile data and in this app's own " +
                "browser, because the pause holds the device's VPN slot and carries nothing. " +
                "Name lookups are refused rather than dropped so apps fail in milliseconds " +
                "instead of hanging.",
            classification = Classification.PREVENTED,
            mechanism = "PauseTunnelService: IPv4 and IPv6 default routes, nothing forwarded",
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
            behaviour = "The pause service reads the parent's pause from storage every time it " +
                "starts, so when Android restarts it the pause comes back. Where the build " +
                "does not restart it, the monitoring loop puts the pause back within seconds. " +
                "The parent is alerted only when neither is possible.",
            classification = Classification.PREVENTED,
            mechanism = "PauseTunnelService.onStartCommand, and " +
                "MonitoringService.checkInternetPause",
            strongerWith = "Device Owner with always-on lockdown, which also covers a force " +
                "stop from Settings.",
        ),
        BypassScenario(
            id = "pause.revoke_vpn",
            group = ScenarioGroup.INTERNET_PAUSE,
            attempt = "Revoke the connection permission while the internet is paused.",
            behaviour = "The pause stops applying. It is recorded as critical and put back " +
                "when Android allows, and the mismatch between what the parent asked for and " +
                "what is in force is raised as its own issue rather than left silent.",
            classification = Classification.DETECTED,
            mechanism = "PauseTunnelService.onRevoke, ProtectionHealth.IssueKind." +
                "PAUSE_NOT_ENFORCED and MonitoringService.checkInternetPause",
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
