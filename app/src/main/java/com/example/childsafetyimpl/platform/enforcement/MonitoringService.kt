package com.example.childsafetyimpl.platform.enforcement

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.example.childsafetyimpl.core.common.DataResult
import com.example.childsafetyimpl.core.common.TimeFormat
import com.example.childsafetyimpl.core.permission.SafetyPermission
import com.example.childsafetyimpl.core.protection.ProtectionFeature
import com.example.childsafetyimpl.data.prefs.SafetyPreferences
import com.example.childsafetyimpl.data.prefs.SafetySettings
import com.example.childsafetyimpl.di.ApplicationScope
import com.example.childsafetyimpl.domain.model.AppCategory
import com.example.childsafetyimpl.domain.model.EventAction
import com.example.childsafetyimpl.domain.model.EventSeverity
import com.example.childsafetyimpl.domain.model.SafetyEventType
import com.example.childsafetyimpl.domain.repository.SafetyEventRepository
import com.example.childsafetyimpl.domain.repository.SafetyRulesRepository
import com.example.childsafetyimpl.platform.admin.AdvancedProtectionProbe
import com.example.childsafetyimpl.platform.admin.NetworkLockdown
import com.example.childsafetyimpl.platform.ai.AiActivityMonitor
import com.example.childsafetyimpl.platform.alerts.SafetyNotifier
import com.example.childsafetyimpl.platform.clone.CloneGuard
import com.example.childsafetyimpl.platform.network.VpnGuard
import com.example.childsafetyimpl.platform.network.InternetPauseController
import com.example.childsafetyimpl.platform.network.PrivateDnsGuard
import com.example.childsafetyimpl.platform.network.VpnLockout
import com.example.childsafetyimpl.platform.permission.PermissionInspector
import com.example.childsafetyimpl.platform.projection.ScreenAnalysisService
import com.example.childsafetyimpl.platform.usage.AppInventoryProvider
import com.example.childsafetyimpl.platform.usage.UsageStatsProvider
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The enforcement loop.
 *
 * A foreground service that polls the usage event stream to learn which app is in front, and acts
 * on it. Polling rather than reacting, because Android gives a third-party app no callback for
 * "the foreground app changed"; the event stream is the only route and it lags by a second or two.
 * That lag is why a blocked app always flashes briefly before it is closed, and the protection
 * screens say so rather than implying an instant block.
 *
 * Each tick does five things, cheapest first:
 *
 *  1. Re-checks the privileged grants, so a permission being switched off becomes an event rather
 *     than a silent gap in protection.
 *  2. Refreshes the VPN posture, and on a managed device puts the DNS and VPN policies back.
 *  3. Reads the foreground app.
 *  4. Decides whether that app should be closed: a VPN client, any app while another VPN is up,
 *     a cloning tool, an app the parent switched off, a schedule, a limit, or a category budget.
 *  5. Closes it by showing the block screen, and records whether that actually worked.
 *
 * The order in step four is not arbitrary. A VPN or clone verdict is a set lookup, a rule check
 * reads a cached list, and only a limit check has to query the usage stream, which is by far the
 * most expensive thing this loop can do.
 */
@AndroidEntryPoint
class MonitoringService : Service() {

    @Inject lateinit var usageStatsProvider: UsageStatsProvider
    @Inject lateinit var inventory: AppInventoryProvider
    @Inject lateinit var rulesRepository: SafetyRulesRepository
    @Inject lateinit var eventRepository: SafetyEventRepository
    @Inject lateinit var permissionInspector: PermissionInspector
    @Inject lateinit var vpnGuard: VpnGuard
    @Inject lateinit var cloneGuard: CloneGuard
    @Inject lateinit var aiMonitor: AiActivityMonitor
    @Inject lateinit var overlayController: BlockOverlayController
    @Inject lateinit var notifier: SafetyNotifier
    @Inject lateinit var preferences: SafetyPreferences
    @Inject lateinit var internetPauseController: InternetPauseController
    @Inject lateinit var privateDnsGuard: PrivateDnsGuard
    @Inject lateinit var advancedProtectionProbe: AdvancedProtectionProbe
    @Inject lateinit var networkLockdown: NetworkLockdown

    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    private var loopJob: Job? = null

    /** Set by [block] so the pause notice never lands on top of, or instead of, a block screen. */
    private var blockedThisTick = false

    /** The app the pause notice was last shown over, so it is shown once per visit, not per tick. */
    private var pauseNoticeShownFor: String? = null

    /** When the parent's pause and the tunnel were first seen to disagree; 0 while they agree. */
    private var pauseMismatchSince = 0L
    private var lastPauseReapplyAt = 0L
    private var pauseMismatchReported = false

    /** The last Private DNS posture seen, so a change is reported once rather than every tick. */
    private var lastPrivateDnsPosture: PrivateDnsGuard.Posture? = null
    private var lastAdvancedProtection: AdvancedProtectionProbe.Mode? = null

    /**
     * Consecutive ticks a foreign tunnel has been seen. The lockout waits for two, because the
     * pause tunnel's own interface can outlive it by a moment, and a phone that locks itself for
     * a second every time a parent lifts a pause would teach everyone to ignore the lock.
     */
    private var foreignVpnTicks = 0

    /** Home-screen packages, which stay usable while a VPN is up. Refreshed with the clone scan. */
    private var launcherPackages: Set<String> = emptySet()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopMonitoring()
            return START_NOT_STICKY
        }
        startMonitoring()
        return START_STICKY
    }

    private fun startMonitoring() {
        if (loopJob?.isActive == true) return

        val notification = notifier.monitoringNotification("Watching apps, limits and screens")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                SafetyNotifier.MONITORING_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(SafetyNotifier.MONITORING_NOTIFICATION_ID, notification)
        }

        State.setRunning(true)
        cloneGuard.startWatching()
        loopJob = scope.launch { monitorLoop() }
    }

    private suspend fun monitorLoop() {
        var lastForegroundPackage: String? = null
        var ticksSinceScan = 0

        while (scope.isActive) {
            val startedAt = System.currentTimeMillis()

            runCatching {
                val settings = preferences.settings.first()
                if (!settings.protectionEnabled) {
                    applyNetworkLockdown(settings)
                    overlayController.hideAll()
                    State.setForegroundPackage(null)
                    delay(POLL_INTERVAL_MILLIS)
                    return@runCatching
                }

                checkGrantChanges()
                applyNetworkLockdown(settings)
                checkVpnPosture(settings)
                checkCaptureAlive(settings)
                checkInternetPause(settings)
                checkPrivateDns()
                checkAdvancedProtection()

                // The clone scan walks every launcher entry in every profile, which is far too
                // expensive to do every two seconds and far too useful to do only on demand. The
                // home-screen list rides on the same timer, and is read on the first tick too.
                if (launcherPackages.isEmpty() || ticksSinceScan++ >= TICKS_BETWEEN_CLONE_SCANS) {
                    ticksSinceScan = 0
                    launcherPackages = resolveLaunchers()
                    if (settings.isEnabled(ProtectionFeature.CLONE_CONTROL)) cloneGuard.scan()
                }

                when (val foreground = usageStatsProvider.currentForegroundApp()) {
                    is DataResult.Success -> {
                        val packageName = foreground.data
                        State.setForegroundPackage(packageName)

                        if (packageName != null && packageName != this.packageName) {
                            if (packageName != lastForegroundPackage) {
                                lastForegroundPackage = packageName
                                pauseNoticeShownFor = null
                                overlayController.hide()
                            }
                            blockedThisTick = false
                            evaluate(packageName, settings)
                            if (!blockedThisTick) maybeShowPauseNotice(packageName, settings)
                        }
                    }

                    is DataResult.Unavailable -> {
                        State.setUnavailable(foreground.reason)
                        // Nothing can be enforced without usage access, and saying so is the point.
                        eventRepository.record(
                            type = SafetyEventType.MONITORING_PERMISSION_LOST,
                            title = "Protection cannot see which app is open",
                            detail = foreground.reason,
                            severity = EventSeverity.CRITICAL,
                            action = EventAction.NOT_ENFORCEABLE,
                            capabilityId = ProtectionFeature.APP_RESTRICTION.id,
                            dedupeWindowMillis = 30 * 60_000L,
                        )
                    }

                    else -> Unit
                }

                preferences.recordHeartbeat(System.currentTimeMillis())
                State.recordTick(System.currentTimeMillis() - startedAt)
            }

            delay(POLL_INTERVAL_MILLIS)
        }
    }

    /**
     * Decides whether the app in front should be closed.
     *
     * Returns as soon as a reason is found, so a VPN app is never also evaluated against a
     * schedule: the first true statement is the one the child is shown, and it should be the most
     * specific one.
     */
    private suspend fun evaluate(packageName: String, settings: SafetySettings) {
        val label = inventory.labelFor(packageName)

        if (settings.isEnabled(ProtectionFeature.VPN_RESTRICTION) &&
            settings.blockVpnApps &&
            vpnGuard.isVpnApp(packageName)
        ) {
            if (block(packageName, label, BlockReason.VpnApp)) {
                vpnGuard.onVpnAppBlocked(packageName, label)
            }
            return
        }

        // Another VPN is up. This app holds no tunnel to take back, so the answer is that nothing
        // but Settings and the phone can be used until it is switched off. See VpnLockout.
        if (settings.isEnabled(ProtectionFeature.VPN_RESTRICTION) &&
            settings.vpnLockout &&
            State.vpnActive.value &&
            !VpnLockout.isExempt(packageName, launcherPackages)
        ) {
            if (block(packageName, label, BlockReason.VpnActive)) vpnGuard.onLockout()
            return
        }

        if (settings.isEnabled(ProtectionFeature.CLONE_CONTROL) &&
            settings.blockCloneTools &&
            cloneGuard.isCloneRelated(packageName)
        ) {
            if (block(packageName, label, BlockReason.CloneApp)) {
                cloneGuard.onCloneAppBlocked(packageName, label)
            }
            return
        }

        val app = inventory.describe(packageName)
        val category = app?.category ?: AppCategory.OTHER

        // AI and game budgets belong to their own protection, so they are checked before the
        // app-restriction gate below. Putting them after it would mean switching app restriction
        // off quietly switched the budgets off too.
        if (settings.isEnabled(ProtectionFeature.AI_GAME_MONITORING)) {
            val budget = when (category) {
                AppCategory.AI -> settings.aiDailyBudgetMinutes
                AppCategory.GAME -> settings.gameDailyBudgetMinutes
                else -> null
            }
            if (budget != null && aiMonitor.budgetExhausted(category, budget)) {
                val used = usageStatsProvider.todayMillisForCategory(category)
                block(
                    packageName,
                    label,
                    BlockReason.CategoryBudgetReached(category, budget, (used / 60_000L).toInt()),
                )
                return
            }
        }

        if (!settings.isEnabled(ProtectionFeature.APP_RESTRICTION)) return

        val rule = rulesRepository.appRules().firstOrNull { it.packageName == packageName }
        val nowMinute = TimeFormat.minuteOfDayNow()

        // The parent switched this app off. This is the path a blocked YouTube takes, and it is
        // checked before anything time-based because it does not expire.
        if (rule?.blocked == true) {
            block(packageName, label, BlockReason.AlwaysBlocked)
            return
        }

        if (rule?.blockFromMinuteOfDay != null && rule.blockToMinuteOfDay != null) {
            if (TimeFormat.isWithinWindow(nowMinute, rule.blockFromMinuteOfDay, rule.blockToMinuteOfDay)) {
                block(
                    packageName,
                    label,
                    BlockReason.ScheduleWindow(rule.blockFromMinuteOfDay, rule.blockToMinuteOfDay),
                )
                return
            }
        }

        // Bedtime applies to the categories a child would stay awake for, not to every app on the
        // device: blocking a clock or a torch at night would be actively unhelpful.
        val bedtimeApplies = category in BEDTIME_CATEGORIES &&
            TimeFormat.isWithinWindow(
                nowMinute,
                settings.bedtimeStartMinuteOfDay,
                settings.bedtimeEndMinuteOfDay,
            )
        if (bedtimeApplies) {
            block(
                packageName,
                label,
                BlockReason.BedtimeWindow(
                    settings.bedtimeStartMinuteOfDay,
                    settings.bedtimeEndMinuteOfDay,
                ),
            )
            return
        }

        val limitMinutes = rule?.dailyLimitMinutes
        if (limitMinutes != null && limitMinutes > 0) {
            val usedMillis = usageStatsProvider.todayMillisFor(packageName)
            if (usedMillis >= limitMinutes * 60_000L) {
                block(
                    packageName,
                    label,
                    BlockReason.DailyLimitReached(limitMinutes, (usedMillis / 60_000L).toInt()),
                )
                return
            }
        }

        val categoryBudget = rulesRepository.observeCategoryBudgets().first()
            .firstOrNull { it.category == category && it.enabled && it.dailyLimitMinutes != null }
        if (categoryBudget?.dailyLimitMinutes != null) {
            val usedMillis = usageStatsProvider.todayMillisForCategory(category)
            if (usedMillis >= categoryBudget.dailyLimitMinutes * 60_000L) {
                block(
                    packageName,
                    label,
                    BlockReason.CategoryBudgetReached(
                        category,
                        categoryBudget.dailyLimitMinutes,
                        (usedMillis / 60_000L).toInt(),
                    ),
                )
            }
        }
    }

    /**
     * Shows the block screen, and returns whether it could actually be shown.
     *
     * The return value is what keeps the event log honest: without the overlay permission the app
     * can detect that a blocked app was opened and can tell the parent, but it cannot stop it, and
     * those two outcomes must never look the same in the history.
     */
    private suspend fun block(
        packageName: String,
        label: String,
        reason: BlockReason,
    ): Boolean {
        val decision = BlockDecision(packageName, label, reason)
        blockedThisTick = true

        return if (overlayController.canShow()) {
            overlayController.show(decision) { State.recordDismissal() }
            State.recordBlock(decision)
            recordBlockEvent(decision, EventAction.BLOCKED)
            true
        } else {
            notifier.postBlockNotice(label, describeReason(reason))
            State.recordBlock(decision)
            recordBlockEvent(decision, EventAction.NOT_ENFORCEABLE)
            false
        }
    }

    private suspend fun recordBlockEvent(decision: BlockDecision, action: EventAction) {
        // A VPN or clone block is recorded by its own guard, which knows more about why than this
        // loop does. Recording it here as well would double every entry in the history. A
        // lockout is recorded once per VPN by the guard, not once per app the child tries.
        if (decision.reason is BlockReason.VpnApp ||
            decision.reason is BlockReason.VpnActive ||
            decision.reason is BlockReason.CloneApp
        ) return

        val (type, featureId) = when (decision.reason) {
            is BlockReason.DailyLimitReached ->
                SafetyEventType.USAGE_LIMIT_REACHED to ProtectionFeature.APP_RESTRICTION.id
            is BlockReason.CategoryBudgetReached ->
                SafetyEventType.USAGE_LIMIT_REACHED to ProtectionFeature.AI_GAME_MONITORING.id
            is BlockReason.ScheduleWindow,
            is BlockReason.BedtimeWindow,
            -> SafetyEventType.SCHEDULE_BLOCK to ProtectionFeature.APP_RESTRICTION.id
            BlockReason.AlwaysBlocked ->
                SafetyEventType.RESTRICTED_APP_OPENED to ProtectionFeature.APP_RESTRICTION.id
            BlockReason.VpnApp, BlockReason.VpnActive, BlockReason.CloneApp ->
                SafetyEventType.RESTRICTED_APP_OPENED to ProtectionFeature.APP_RESTRICTION.id
            is BlockReason.RoutineRestriction ->
                SafetyEventType.ROUTINE_RESTRICTION to ProtectionFeature.APP_RESTRICTION.id
            BlockReason.InternetPaused ->
                SafetyEventType.INTERNET_PAUSED to InternetPauseController.CAPABILITY_ID
        }

        eventRepository.record(
            type = type,
            title = "${decision.appLabel} was " +
                if (action == EventAction.BLOCKED) "closed" else "opened",
            detail = describeReason(decision.reason) + if (action == EventAction.NOT_ENFORCEABLE) {
                " It could not be closed, because permission to draw over other apps is missing."
            } else "",
            packageName = decision.packageName,
            appLabel = decision.appLabel,
            action = action,
            capabilityId = featureId,
            dedupeWindowMillis = 5 * 60_000L,
        )
    }

    private fun describeReason(reason: BlockReason): String = when (reason) {
        is BlockReason.DailyLimitReached ->
            "Today's ${reason.limitMinutes} minute limit is used up."
        is BlockReason.CategoryBudgetReached ->
            "The ${reason.category.label.lowercase()} budget of ${reason.limitMinutes} minutes " +
                "is used up."
        is BlockReason.ScheduleWindow ->
            "Outside allowed hours (${TimeFormat.formatMinuteOfDay(reason.fromMinuteOfDay)} to " +
                "${TimeFormat.formatMinuteOfDay(reason.toMinuteOfDay)})."
        is BlockReason.BedtimeWindow ->
            "It is bedtime (${TimeFormat.formatMinuteOfDay(reason.fromMinuteOfDay)} to " +
                "${TimeFormat.formatMinuteOfDay(reason.toMinuteOfDay)})."
        BlockReason.AlwaysBlocked ->
            "This app is switched off by a parent."
        BlockReason.VpnApp ->
            "VPN apps are restricted on this device."
        BlockReason.VpnActive ->
            "A VPN is on, and apps stay closed until it is switched off."
        BlockReason.CloneApp ->
            "App cloning is restricted on this device."
        is BlockReason.RoutineRestriction ->
            "The ${reason.routineName} routine is running until " +
                "${TimeFormat.formatMinuteOfDay(reason.endsAtMinuteOfDay)}."
        BlockReason.InternetPaused ->
            "A parent has the internet paused on this device."
    }

    /**
     * Tells the child the internet is paused when they open an app that needs it.
     *
     * Without this the pause reaches them as the app's own "no connection" error, which reads as
     * a broken phone rather than a parent's decision. It is a notice, not a block: the app is
     * allowed and anything in it that works offline still works, so closing the notice hands the
     * child back to the app rather than sending them home, and it is shown once per visit.
     *
     * Only while the pause is actually in force. Telling a child the internet is paused while it
     * is not would be the same lie in the other direction.
     */
    private suspend fun maybeShowPauseNotice(packageName: String, settings: SafetySettings) {
        if (!settings.internetPaused || !internetPauseController.isEnforced()) return
        if (!InternetPauseController.isNetworkDependent(packageName)) return
        if (pauseNoticeShownFor == packageName || !overlayController.canShow()) return

        pauseNoticeShownFor = packageName
        val label = inventory.labelFor(packageName)
        overlayController.show(
            decision = BlockDecision(packageName, label, BlockReason.InternetPaused),
            onDismiss = { State.recordDismissal() },
            leaveAppOnClose = false,
        )
        eventRepository.record(
            type = SafetyEventType.INTERNET_PAUSED,
            title = "$label was opened while the internet is paused",
            detail = describeReason(BlockReason.InternetPaused) + " The child was shown the " +
                "paused notice rather than a connection error.",
            packageName = packageName,
            appLabel = label,
            action = EventAction.LOGGED,
            capabilityId = InternetPauseController.CAPABILITY_ID,
            dedupeWindowMillis = 5 * 60_000L,
        )
    }

    /**
     * Keeps the parent's pause and the pause tunnel in agreement, and says so when it cannot.
     *
     * The tunnel restores a pause whenever Android restarts it, but a manufacturer's task killer
     * can end the process on a build that never restarts sticky services, and then nothing is
     * left running to do it. This loop is what survives, so it re-applies the pause, and only when
     * that is impossible — VPN consent withdrawn — does it tell the parent. A short grace comes
     * first because raising the pause tunnel takes a moment and the two disagree while it does.
     */
    private suspend fun checkInternetPause(settings: SafetySettings) {
        if (!settings.internetPaused || internetPauseController.isEnforced()) {
            pauseMismatchSince = 0L
            pauseMismatchReported = false
            return
        }

        val now = System.currentTimeMillis()
        if (pauseMismatchSince == 0L) {
            pauseMismatchSince = now
            return
        }
        val mismatchFor = now - pauseMismatchSince
        if (mismatchFor < PAUSE_GRACE_MILLIS) return

        val consentGone = internetPauseController.needsVpnConsent()
        if (!consentGone && now - lastPauseReapplyAt >= PAUSE_REAPPLY_INTERVAL_MILLIS) {
            lastPauseReapplyAt = now
            internetPauseController.reapply()
        }

        if (pauseMismatchReported) return
        if (!consentGone && mismatchFor < PAUSE_REPORT_AFTER_MILLIS) return

        pauseMismatchReported = true
        eventRepository.record(
            type = SafetyEventType.PROTECTION_DEGRADED,
            title = "The internet is not actually paused",
            detail = if (consentGone) {
                "You paused the internet, but the connection permission the pause runs on was " +
                    "withdrawn, so this device is online. Open the app and allow the " +
                    "connection request again."
            } else {
                "You paused the internet, but the pause could not be put back in force after " +
                    "the pause tunnel stopped, so this device is online."
            },
            severity = EventSeverity.CRITICAL,
            action = EventAction.NOT_ENFORCEABLE,
            capabilityId = InternetPauseController.CAPABILITY_ID,
            dedupeWindowMillis = 0L,
        )
    }

    /**
     * Watches the Private DNS setting, which is the web filter.
     *
     * A child clearing it or pointing it at an unfiltered resolver switches web filtering off in
     * three taps. On an unmanaged device Android offers no way to stop that beyond closing the
     * settings screen on sight; this is how the parent hears about it when that fails. Reported on
     * a change rather than every tick, and a setting that was never completed is left to the setup
     * screen and the dashboard rather than alerted on: a parent part-way through setup is being
     * asked, not told.
     */
    private suspend fun checkPrivateDns() {
        val posture = privateDnsGuard.currentPosture()
        val previous = lastPrivateDnsPosture
        lastPrivateDnsPosture = posture
        if (posture == previous) return

        val advice = privateDnsGuard.explain(posture)
        val wasFiltering = previous is PrivateDnsGuard.Posture.Filtering
        when (posture) {
            is PrivateDnsGuard.Posture.ForeignResolver -> eventRepository.record(
                type = SafetyEventType.BYPASS_ATTEMPT,
                title = "Private DNS was pointed at an unfiltered resolver",
                detail = advice.whatItMeans + " " + advice.whatToDo.orEmpty(),
                severity = EventSeverity.CRITICAL,
                action = EventAction.NOT_ENFORCEABLE,
                capabilityId = ProtectionFeature.SEARCH_FILTERING.id,
                // A process restart forgets the last posture, and a bypass that is still in
                // place is not news every time that happens.
                dedupeWindowMillis = 60 * 60_000L,
            )

            PrivateDnsGuard.Posture.NotConfigured -> if (wasFiltering) {
                eventRepository.record(
                    type = SafetyEventType.BYPASS_ATTEMPT,
                    title = "Private DNS was switched off",
                    detail = advice.whatItMeans + " " + advice.whatToDo.orEmpty(),
                    severity = EventSeverity.CRITICAL,
                    action = EventAction.NOT_ENFORCEABLE,
                    capabilityId = ProtectionFeature.SEARCH_FILTERING.id,
                    dedupeWindowMillis = 0L,
                )
            }

            else -> Unit
        }
    }

    /**
     * Notices Android's Advanced Protection Mode, which withdraws accessibility access by design.
     *
     * Checked here as well as on the dashboard so the parent hears it when it happens, and so the
     * accessibility grant it removes is described as the operating system's doing rather than
     * left to read as a child switching a protection off.
     */
    private suspend fun checkAdvancedProtection() {
        val mode = advancedProtectionProbe.current()
        val previous = lastAdvancedProtection
        lastAdvancedProtection = mode
        State.setAdvancedProtectionOn(mode == AdvancedProtectionProbe.Mode.ON)
        if (mode != AdvancedProtectionProbe.Mode.ON || previous == mode) return

        eventRepository.record(
            type = SafetyEventType.PROTECTION_DEGRADED,
            title = "Android Advanced Protection is on",
            detail = "Android withholds accessibility access from apps that are not " +
                "accessibility tools while this mode is on, so the protections that read the " +
                "screen cannot run. This is a security setting, not tampering.",
            severity = EventSeverity.HIGH,
            action = EventAction.NOT_ENFORCEABLE,
            capabilityId = ADVANCED_PROTECTION_CAPABILITY,
            dedupeWindowMillis = 12 * 60 * 60_000L,
        )
    }

    /**
     * Bypass detection through grant comparison.
     *
     * Nothing tells an app that a special access was revoked, so the only route is to re-check and
     * compare against what was true last time. This is why the detection is always after the fact
     * and never preventive.
     */
    private suspend fun checkGrantChanges() {
        val current = permissionInspector.grantedSet()
        val previous = preferences.settings.first().lastKnownGrants
        if (previous.isEmpty()) {
            preferences.recordGrants(current)
            return
        }
        val lost = previous - current
        // Kept for the dashboard's health model, which is the only reader of "recently": a grant
        // stays on the list until it comes back.
        State.setRecentlyRevoked((State.recentlyRevoked.value + lost) - current)

        val advancedProtectionTookIt = lost == setOf(SafetyPermission.ACCESSIBILITY.name) &&
            advancedProtectionProbe.current() == AdvancedProtectionProbe.Mode.ON
        if (advancedProtectionTookIt) {
            // Reported by checkAdvancedProtection, as the operating system's decision. Recording
            // it here as well would call the same event a bypass attempt.
        } else if (lost.isNotEmpty()) {
            eventRepository.record(
                type = SafetyEventType.BYPASS_ATTEMPT,
                title = "Protection access was removed",
                detail = "These were switched off since the last check: " +
                    lost.joinToString(", ") { it.replace('_', ' ').lowercase() } + ".",
                severity = EventSeverity.CRITICAL,
                action = EventAction.NOT_ENFORCEABLE,
                capabilityId = ProtectionFeature.APP_RESTRICTION.id,
                dedupeWindowMillis = 10 * 60_000L,
            )
        }
        preferences.recordGrants(current)
    }

    /**
     * Notices that video blurring has stopped, and offers the one thing that can restart it.
     *
     * This is the only protection in the app that cannot bring itself back. Android issues screen
     * capture consent one session at a time and ties it to the process that asked, so a process
     * that is killed — by a manufacturer's task killer, by memory pressure, by a child clearing
     * recents on a build that kills with the task — cannot replay the consent it was holding. The
     * service is gone, its session is gone, and nothing in it is left to say so.
     *
     * So the check lives here instead, in the loop that survives all of those, and it compares two
     * things that only disagree when something went wrong: the parent asked for capture and left
     * it asked for, and capture is not running. A notification with one action is what closes that
     * gap, because a tap on a system dialog is genuinely the only way back.
     *
     * Silent about the case where a parent has switched video blurring on and never started it.
     * That is a setup step, not a failure, and reporting it as one would teach a parent to ignore
     * the alert that matters.
     */
    private suspend fun checkCaptureAlive(settings: SafetySettings) {
        val wanted = settings.isEnabled(ProtectionFeature.VIDEO_BLURRING) &&
            settings.videoBlurEnabled &&
            settings.captureRequested
        if (!wanted || ScreenAnalysisService.State.running.value) return

        notifier.postCaptureResumeNotice()
        eventRepository.record(
            type = SafetyEventType.MONITORING_STOPPED,
            title = "Video blurring is not running",
            detail = "Screen capture was started and is no longer running. Android will not " +
                "re-issue capture consent to a process it has killed, so this cannot restart " +
                "itself; the notification offers to start it again.",
            severity = EventSeverity.CRITICAL,
            action = EventAction.NOT_ENFORCEABLE,
            capabilityId = ProtectionFeature.VIDEO_BLURRING.id,
            dedupeWindowMillis = 30 * 60_000L,
        )
    }

    private suspend fun checkVpnPosture(settings: SafetySettings) {
        if (!settings.isEnabled(ProtectionFeature.VPN_RESTRICTION)) {
            foreignVpnTicks = 0
            State.setVpnActive(false)
            return
        }
        val posture = vpnGuard.refreshPosture()
        foreignVpnTicks = if (posture.foreignTunnelActive) foreignVpnTicks + 1 else 0
        val active = foreignVpnTicks >= FOREIGN_VPN_CONFIRM_TICKS
        State.setVpnActive(active)

        if (!active) {
            // The VPN went, so the lock goes with it rather than waiting for the child to close it.
            if (overlayController.visible.value?.reason == BlockReason.VpnActive) {
                overlayController.hide()
            }
            return
        }
        vpnGuard.onPosture(
            posture,
            lockoutPossible = settings.vpnLockout && overlayController.canShow(),
        )
    }

    /**
     * On a managed device, sets Private DNS to the parent's resolver and locks it, and forbids
     * VPN configuration. A no-op on every other device. See NetworkLockdown.
     */
    private fun applyNetworkLockdown(settings: SafetySettings) {
        networkLockdown.apply(
            protectionEnabled = settings.protectionEnabled,
            privateDnsHost = settings.privateDnsHostname,
            restrictVpn = settings.isEnabled(ProtectionFeature.VPN_RESTRICTION),
            // The policy would refuse this app's own pause tunnel too, and the consent dialog
            // that a first pause raises.
            pauseActive = settings.internetPaused || VpnGuard.ownConsentPending(),
        )
    }

    /** Every installed home screen, which a VPN lockout must never cover. */
    private fun resolveLaunchers(): Set<String> = runCatching {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val matches = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                home,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY)
        }
        matches.map { it.activityInfo.packageName }.toSet()
    }.getOrDefault(emptySet())

    private fun stopMonitoring() {
        loopJob?.cancel()
        loopJob = null
        cloneGuard.stopWatching()
        overlayController.hideAll()
        State.setRunning(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * When the child swipes the app away, some OEM builds kill the service with it. Recording that
     * here is what makes the "protection stopped" alert real rather than theoretical.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        scope.launch {
            eventRepository.record(
                type = SafetyEventType.MONITORING_STOPPED,
                title = "Protection may have been stopped",
                detail = "The app was swiped away from recents. On several manufacturers' builds " +
                    "this also stops the protection service, leaving a gap until it restarts.",
                severity = EventSeverity.CRITICAL,
                action = EventAction.NOT_ENFORCEABLE,
                capabilityId = ProtectionFeature.APP_RESTRICTION.id,
                dedupeWindowMillis = 60 * 60_000L,
            )
        }
        // Restarting from here is what actually closes the gap on builds that allow it.
        runCatching { start(applicationContext) }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        loopJob?.cancel()
        cloneGuard.stopWatching()
        State.setRunning(false)
        super.onDestroy()
    }

    /** Observable state for the live monitor card and the dashboard. */
    object State {
        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        private val _foregroundPackage = MutableStateFlow<String?>(null)
        val foregroundPackage: StateFlow<String?> = _foregroundPackage.asStateFlow()

        private val _unavailableReason = MutableStateFlow<String?>(null)
        val unavailableReason: StateFlow<String?> = _unavailableReason.asStateFlow()

        private val _vpnActive = MutableStateFlow(false)
        val vpnActive: StateFlow<Boolean> = _vpnActive.asStateFlow()

        /** Grant names removed since they were last seen held, until they come back. */
        private val _recentlyRevoked = MutableStateFlow<Set<String>>(emptySet())
        val recentlyRevoked: StateFlow<Set<String>> = _recentlyRevoked.asStateFlow()

        private val _advancedProtectionOn = MutableStateFlow(false)
        val advancedProtectionOn: StateFlow<Boolean> = _advancedProtectionOn.asStateFlow()

        fun setRecentlyRevoked(value: Set<String>) { _recentlyRevoked.value = value }
        fun setAdvancedProtectionOn(value: Boolean) { _advancedProtectionOn.value = value }

        private val _lastBlock = MutableStateFlow<BlockDecision?>(null)
        val lastBlock: StateFlow<BlockDecision?> = _lastBlock.asStateFlow()

        private val _telemetry = MutableStateFlow(Telemetry())
        val telemetry: StateFlow<Telemetry> = _telemetry.asStateFlow()

        fun setRunning(value: Boolean) {
            _running.value = value
            if (!value) _foregroundPackage.value = null
        }

        fun setForegroundPackage(value: String?) {
            _foregroundPackage.value = value
            _unavailableReason.value = null
        }

        fun setUnavailable(reason: String) { _unavailableReason.value = reason }
        fun setVpnActive(value: Boolean) { _vpnActive.value = value }

        fun recordBlock(decision: BlockDecision) {
            _lastBlock.value = decision
            _telemetry.value = _telemetry.value.copy(
                blocksIssued = _telemetry.value.blocksIssued + 1,
            )
        }

        fun recordDismissal() {
            _telemetry.value =
                _telemetry.value.copy(blocksDismissed = _telemetry.value.blocksDismissed + 1)
        }

        fun recordTick(durationMillis: Long) {
            val current = _telemetry.value
            _telemetry.value = current.copy(
                ticks = current.ticks + 1,
                totalTickMillis = current.totalTickMillis + durationMillis,
                lastTickAt = System.currentTimeMillis(),
            )
        }

        data class Telemetry(
            val ticks: Int = 0,
            val totalTickMillis: Long = 0,
            val blocksIssued: Int = 0,
            val blocksDismissed: Int = 0,
            val lastTickAt: Long = 0,
        ) {
            val averageTickMillis: Long get() = if (ticks == 0) 0 else totalTickMillis / ticks
        }
    }

    companion object {
        const val ACTION_STOP = "com.example.childsafetyimpl.STOP_MONITORING"

        /**
         * Two seconds. Fast enough that a blocked app closes almost as it opens, slow enough that
         * the usage query does not become the battery cost this app exists to police.
         */
        private const val POLL_INTERVAL_MILLIS = 2000L

        /** Roughly every ten minutes at the poll interval above. */
        private const val TICKS_BETWEEN_CLONE_SCANS = 300

        /** Two ticks, about four seconds; see foreignVpnTicks. */
        private const val FOREIGN_VPN_CONFIRM_TICKS = 2

        /** Raising the pause tunnel takes a moment; the two briefly disagree while it does. */
        private const val PAUSE_GRACE_MILLIS = 6_000L
        private const val PAUSE_REAPPLY_INTERVAL_MILLIS = 30_000L

        /** How long a re-apply is given to work before the parent is told it has not. */
        private const val PAUSE_REPORT_AFTER_MILLIS = 45_000L

        private const val ADVANCED_PROTECTION_CAPABILITY = "advanced_protection"

        private val BEDTIME_CATEGORIES = setOf(
            AppCategory.SOCIAL,
            AppCategory.COMMUNICATION,
            AppCategory.GAME,
            AppCategory.VIDEO,
            AppCategory.AI,
        )

        fun start(context: Context) {
            val intent = Intent(context, MonitoringService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, MonitoringService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
