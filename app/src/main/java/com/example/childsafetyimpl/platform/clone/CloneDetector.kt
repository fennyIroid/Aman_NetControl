package com.example.childsafetyimpl.platform.clone

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import com.example.childsafetyimpl.data.source.KnownPackages
import com.example.childsafetyimpl.domain.model.InstalledApp
import com.example.childsafetyimpl.platform.usage.AppInventoryProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Clone and duplicate-app detection.
 *
 * Four independent checks, because no single one is sufficient and the question that matters is
 * how much coverage is really achievable:
 *
 *  1. Known cloning tools by package name. Reliable for what is on the list, blind to the rest.
 *  2. Profiles visible to this user. Catches a work profile, misses an OEM clone space.
 *  3. Whether this very instance is running inside a cloned or secondary space.
 *  4. Duplicate launcher entries for the same package across user handles.
 *
 * Together they produce a coverage picture, not a guarantee, and the result object says which
 * checks were even possible on this build.
 */
@Singleton
class CloneDetector @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val userManager: UserManager,
    private val inventory: AppInventoryProvider,
) {

    data class CloneScanResult(
        val cloneToolsFound: List<InstalledApp>,
        val cloneToolsChecked: Int,
        val visibleProfileCount: Int,
        val runningInSecondaryUser: Boolean,
        val duplicateLauncherEntries: List<DuplicateEntry>,
        val launcherAppsAccessible: Boolean,
        val notes: List<String>,
    ) {
        val anySignal: Boolean
            get() = cloneToolsFound.isNotEmpty() ||
                visibleProfileCount > 1 ||
                runningInSecondaryUser ||
                duplicateLauncherEntries.isNotEmpty()
    }

    data class DuplicateEntry(val packageName: String, val label: String, val instanceCount: Int)

    fun scan(): CloneScanResult {
        val notes = mutableListOf<String>()

        // 1. Known cloning tools.
        val toolsFound = KnownPackages.cloneTools.mapNotNull { inventory.describe(it) }
        if (toolsFound.isEmpty()) {
            notes += "No known cloning tool is installed. A cloner not on the curated list, or one " +
                "renamed by its developer, would not be found by this check."
        }

        // 2. Profiles this user can see.
        val profiles = runCatching { userManager.userProfiles }.getOrDefault(emptyList())
        if (profiles.size <= 1) {
            notes += "One profile is visible. An OEM clone space such as Samsung Secure Folder or " +
                "MIUI Dual Apps is isolated by design and is not reported here even when it exists."
        }

        // 3. Is this instance itself running somewhere other than the primary user?
        val runningInSecondary = isRunningInSecondaryUser()
        if (runningInSecondary) {
            notes += "This app is running in a secondary or cloned user space, so it can only see " +
                "activity inside that space."
        }

        // 4. Duplicate launcher entries for one package across user handles.
        val duplicates = mutableListOf<DuplicateEntry>()
        var launcherAccessible = false
        try {
            val launcherApps =
                context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            launcherAccessible = true
            val counts = mutableMapOf<String, Int>()
            profiles.forEach { profile ->
                runCatching { launcherApps.getActivityList(null, profile) }
                    .getOrDefault(emptyList())
                    .forEach { activity ->
                        val packageName = activity.applicationInfo.packageName
                        counts[packageName] = (counts[packageName] ?: 0) + 1
                    }
            }
            counts.filterValues { it > 1 }.forEach { (packageName, count) ->
                duplicates += DuplicateEntry(
                    packageName = packageName,
                    label = inventory.labelFor(packageName),
                    instanceCount = count,
                )
            }
        } catch (e: Exception) {
            notes += "Launcher enumeration across profiles was refused: ${e.message}."
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            notes += "Android 11 package visibility filtering is active, so enumeration only " +
                "returns launchable and declared packages."
        }

        return CloneScanResult(
            cloneToolsFound = toolsFound,
            cloneToolsChecked = KnownPackages.cloneTools.size,
            visibleProfileCount = profiles.size,
            runningInSecondaryUser = runningInSecondary,
            duplicateLauncherEntries = duplicates,
            launcherAppsAccessible = launcherAccessible,
            notes = notes,
        )
    }

    /**
     * A user handle serialises to zero for the primary user. A non-zero value means this process
     * lives in a secondary, work or cloned space, which is the blind spot this guard warns about,
     * seen from the other side.
     */
    private fun isRunningInSecondaryUser(): Boolean = try {
        val handle: UserHandle = Process.myUserHandle()
        userManager.getSerialNumberForUser(handle) != 0L
    } catch (_: Exception) {
        false
    }
}
