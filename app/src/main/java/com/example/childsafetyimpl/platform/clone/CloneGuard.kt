package com.example.childsafetyimpl.platform.clone

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Build
import android.os.UserHandle
import com.example.childsafetyimpl.core.common.TimeFormat
import com.example.childsafetyimpl.data.local.CloneFindingDao
import com.example.childsafetyimpl.data.local.CloneFindingEntity
import com.example.childsafetyimpl.data.prefs.SafetyPreferences
import com.example.childsafetyimpl.data.source.KnownPackages
import com.example.childsafetyimpl.di.ApplicationScope
import com.example.childsafetyimpl.domain.model.CloneKind
import com.example.childsafetyimpl.domain.model.EventAction
import com.example.childsafetyimpl.domain.model.EventSeverity
import com.example.childsafetyimpl.domain.model.SafetyEventType
import com.example.childsafetyimpl.domain.repository.SafetyEventRepository
import com.example.childsafetyimpl.platform.usage.AppInventoryProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dual and cloned app control (first-priority protection).
 *
 * The problem this solves is specific: a child who cannot open Instagram opens a second Instagram
 * instead, installed inside a cloning tool or an OEM dual-app space, and every check that watches
 * for the original package sees nothing.
 *
 * The guard answers it in three parts.
 *
 *  1. **Find them.** [CloneDetector] runs the four independent scans, and every finding is written
 *     to the database rather than only shown. A clone that appears once and is hidden again still
 *     stays in the parent's history, which a live-only scan would lose.
 *  2. **Notice new ones as they arrive.** A [LauncherApps] callback fires when a package is added
 *     in any profile this app can see, which is how a clone created after setup is caught in
 *     seconds instead of at the next manual scan.
 *  3. **Refuse them.** Cloning tools and known clone hosts are closed when they come to the
 *     foreground, the same way any blocked app is.
 *
 * The limit worth stating plainly: an OEM clone space such as Samsung Secure Folder is isolated by
 * design and a normal app cannot see inside it. The guard reports that blind spot rather than
 * implying coverage it does not have.
 */
@Singleton
class CloneGuard @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val detector: CloneDetector,
    private val inventory: AppInventoryProvider,
    private val findingDao: CloneFindingDao,
    private val eventRepository: SafetyEventRepository,
    private val preferences: SafetyPreferences,
    @param:ApplicationScope private val scope: CoroutineScope,
) {

    private val _lastScanAt = MutableStateFlow(0L)
    val lastScanAt: StateFlow<Long> = _lastScanAt.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _blockedLaunches = MutableStateFlow(0)
    val blockedLaunches: StateFlow<Int> = _blockedLaunches.asStateFlow()

    val findings: Flow<List<CloneFindingEntity>> = findingDao.observeAll()

    val findingCount: Flow<Int> = findingDao.observeCount()

    /** Packages the monitor should close on sight, refreshed whenever a scan completes. */
    @Volatile private var blockedPackages: Set<String> = emptySet()

    private var launcherCallback: LauncherApps.Callback? = null

    fun isCloneRelated(packageName: String): Boolean =
        packageName in blockedPackages || KnownPackages.isCloneTool(packageName)

    /**
     * Runs the scan and reconciles it with what is already stored.
     *
     * Reconciling rather than replacing is what makes [CloneFindingEntity.firstSeenAt] meaningful:
     * a parent can see that a cloning tool has been on the device since Tuesday, not merely that
     * it is on the device now.
     */
    suspend fun scan(): CloneDetector.CloneScanResult {
        _scanning.value = true
        try {
            val result = detector.scan()
            val now = System.currentTimeMillis()
            val seen = mutableSetOf<String>()

            result.cloneToolsFound.forEach { app ->
                seen += app.packageName
                record(
                    identity = app.packageName,
                    packageName = app.packageName,
                    label = app.label,
                    kind = CloneKind.CLONING_TOOL,
                    instanceCount = 1,
                    now = now,
                )
            }

            result.duplicateLauncherEntries.forEach { duplicate ->
                val identity = "dup:${duplicate.packageName}"
                seen += identity
                record(
                    identity = identity,
                    packageName = duplicate.packageName,
                    label = duplicate.label,
                    kind = CloneKind.DUPLICATE_LAUNCHER_ENTRY,
                    instanceCount = duplicate.instanceCount,
                    now = now,
                )
            }

            if (result.visibleProfileCount > 1) {
                val identity = "profiles"
                seen += identity
                record(
                    identity = identity,
                    packageName = context.packageName,
                    label = "${result.visibleProfileCount} profiles on this device",
                    kind = CloneKind.SECONDARY_PROFILE,
                    instanceCount = result.visibleProfileCount,
                    now = now,
                )
            }

            if (result.runningInSecondaryUser) {
                val identity = "self-clone"
                seen += identity
                record(
                    identity = identity,
                    packageName = context.packageName,
                    label = "Protection is running in a cloned space",
                    kind = CloneKind.RUNNING_IN_CLONE,
                    instanceCount = 1,
                    now = now,
                )
            }

            // Anything previously found and now gone stays in the history, but stops being
            // enforced: closing an app that no longer exists would be a bug a parent could see.
            refreshBlockedPackages()
            _lastScanAt.value = now
            return result
        } finally {
            _scanning.value = false
        }
    }

    private suspend fun record(
        identity: String,
        packageName: String,
        label: String,
        kind: CloneKind,
        instanceCount: Int,
        now: Long,
    ) {
        val existing = findingDao.get(identity)
        findingDao.upsert(
            CloneFindingEntity(
                identity = identity,
                packageName = packageName,
                label = label,
                kind = kind,
                instanceCount = instanceCount,
                firstSeenAt = existing?.firstSeenAt ?: now,
                lastSeenAt = now,
                blocked = existing?.blocked ?: (kind == CloneKind.CLONING_TOOL),
            )
        )

        if (existing == null) {
            eventRepository.record(
                type = SafetyEventType.CLONE_APP_DETECTED,
                title = when (kind) {
                    CloneKind.CLONING_TOOL -> "A cloning tool was found"
                    CloneKind.DUPLICATE_LAUNCHER_ENTRY -> "$label exists twice on this device"
                    CloneKind.SECONDARY_PROFILE -> "A second profile is present"
                    CloneKind.RUNNING_IN_CLONE -> "Protection is running inside a clone"
                },
                detail = when (kind) {
                    CloneKind.CLONING_TOOL ->
                        "$label can run a second copy of any app outside the copy protection " +
                            "watches. It is now closed each time it is opened."
                    CloneKind.DUPLICATE_LAUNCHER_ENTRY ->
                        "$instanceCount separate instances of $packageName are installed. Time " +
                            "and limits are counted per instance, so the second one has its own."
                    CloneKind.SECONDARY_PROFILE ->
                        "This device has $instanceCount profiles. Activity inside a profile other " +
                            "than this one cannot be seen from here."
                    CloneKind.RUNNING_IN_CLONE ->
                        "This copy of protection is itself running in a cloned or secondary " +
                            "space, so it only sees activity inside that space. Install it in " +
                            "the main profile instead."
                },
                packageName = packageName,
                appLabel = label,
                severity = EventSeverity.HIGH,
                action = if (kind == CloneKind.CLONING_TOOL) EventAction.BLOCKED
                else EventAction.LOGGED,
                capabilityId = "clone_control",
            )
        }
    }

    private suspend fun refreshBlockedPackages() {
        blockedPackages = findingDao.getAll()
            .filter { it.blocked }
            .map { it.packageName }
            .toSet() + KnownPackages.cloneTools.filter { inventory.isInstalled(it) }
    }

    suspend fun setBlocked(identity: String, blocked: Boolean) {
        findingDao.setBlocked(identity, blocked)
        refreshBlockedPackages()
    }

    /** Recorded by the monitor when it closes a clone-related app. */
    suspend fun onCloneAppBlocked(packageName: String, appLabel: String) {
        _blockedLaunches.value += 1
        eventRepository.record(
            type = SafetyEventType.RESTRICTED_APP_OPENED,
            title = "$appLabel was closed",
            detail = "App cloning is restricted, so this app is closed each time it is opened.",
            packageName = packageName,
            appLabel = appLabel,
            severity = EventSeverity.HIGH,
            action = EventAction.BLOCKED,
            capabilityId = "clone_control",
            dedupeWindowMillis = 60_000L,
        )
    }

    /**
     * Watches for a clone appearing while the app is running.
     *
     * This is the difference between finding a clone at the next scan and finding it as it is
     * created. Registration can be refused on some builds, in which case the periodic scan is the
     * only route and the guard carries on without it.
     */
    fun startWatching() {
        if (launcherCallback != null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return

        val launcherApps = runCatching {
            context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        }.getOrNull() ?: return

        val callback = object : LauncherApps.Callback() {
            override fun onPackageAdded(packageName: String?, user: UserHandle?) {
                packageName ?: return
                scope.launch { onPackageAppeared(packageName) }
            }

            override fun onPackageRemoved(packageName: String?, user: UserHandle?) {
                scope.launch { scan() }
            }

            override fun onPackageChanged(packageName: String?, user: UserHandle?) = Unit

            override fun onPackagesAvailable(
                packageNames: Array<out String>?,
                user: UserHandle?,
                replacing: Boolean,
            ) = Unit

            override fun onPackagesUnavailable(
                packageNames: Array<out String>?,
                user: UserHandle?,
                replacing: Boolean,
            ) = Unit
        }

        runCatching { launcherApps.registerCallback(callback) }
            .onSuccess { launcherCallback = callback }
    }

    private suspend fun onPackageAppeared(packageName: String) {
        if (!preferences.settings.first().blockCloneTools) return
        if (KnownPackages.isCloneTool(packageName)) {
            val label = inventory.labelFor(packageName)
            eventRepository.record(
                type = SafetyEventType.CLONE_APP_DETECTED,
                title = "A cloning tool was just installed",
                detail = "$label appeared on this device. It is now closed each time it opens.",
                packageName = packageName,
                appLabel = label,
                severity = EventSeverity.CRITICAL,
                action = EventAction.BLOCKED,
                capabilityId = "clone_control",
            )
        }
        scan()
    }

    fun stopWatching() {
        val callback = launcherCallback ?: return
        runCatching {
            val launcherApps =
                context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            launcherApps.unregisterCallback(callback)
        }
        launcherCallback = null
    }

    /** Whether a scan has run today, so the UI can prompt for one rather than show stale counts. */
    val scannedToday: Flow<Boolean> = lastScanAt.map { at ->
        at != 0L && at >= TimeFormat.startOfTodayMillis()
    }
}
