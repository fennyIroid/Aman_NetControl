package com.example.childsafetyimpl.platform.usage

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.example.childsafetyimpl.data.source.KnownPackages
import com.example.childsafetyimpl.domain.model.AppCategory
import com.example.childsafetyimpl.domain.model.ClassificationSource
import com.example.childsafetyimpl.domain.model.InstalledApp
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Installed-app discovery and classification.
 *
 * Two separate mechanisms, because Android 11 split them apart. Enumeration returns only what
 * package visibility allows; a targeted lookup still works for any package declared in the
 * manifest. The app reports both numbers so the gap between them is visible rather than
 * assumed away.
 */
@Singleton
class AppInventoryProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val packageManager: PackageManager,
) {

    /** Everything the launcher would show, filtered by package visibility on API 30+. */
    fun launchableApps(): List<InstalledApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(0L),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, 0)
        }
        return resolved
            .asSequence()
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != context.packageName }
            .mapNotNull { describe(it) }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    /**
     * Targeted presence check for the curated list.
     *
     * This is the half of discovery that survives Android 11 filtering, and it is why the catalog
     * separates "detect installed applications" from "detect specific known applications".
     */
    fun knownPackagePresence(): Map<String, InstalledApp?> =
        KnownPackages.allDeclared.associateWith { describe(it) }

    fun isInstalled(packageName: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun describe(packageName: String): InstalledApp? = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
        val appInfo = info.applicationInfo ?: return null
        val (category, source) = classify(packageName, appInfo)
        InstalledApp(
            packageName = packageName,
            label = packageManager.getApplicationLabel(appInfo).toString(),
            category = category,
            classificationSource = source,
            isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            installedAt = info.firstInstallTime,
            versionName = info.versionName,
        )
    } catch (_: PackageManager.NameNotFoundException) {
        null
    } catch (_: SecurityException) {
        null
    }

    fun labelFor(packageName: String): String = describe(packageName)?.label ?: packageName

    /**
     * Classification order matters. The curated list wins over the declared platform category
     * because a messenger and a social feed both declare CATEGORY_SOCIAL, and the product needs
     * to tell them apart. Where nothing matches, the app says so instead of guessing.
     */
    private fun classify(
        packageName: String,
        info: ApplicationInfo,
    ): Pair<AppCategory, ClassificationSource> {
        KnownPackages.categoryOf(packageName)?.let {
            return it to ClassificationSource.CURATED_LIST
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val platformCategory = when (info.category) {
                ApplicationInfo.CATEGORY_GAME -> AppCategory.GAME
                ApplicationInfo.CATEGORY_SOCIAL -> AppCategory.SOCIAL
                ApplicationInfo.CATEGORY_VIDEO -> AppCategory.VIDEO
                ApplicationInfo.CATEGORY_NEWS -> AppCategory.OTHER
                ApplicationInfo.CATEGORY_PRODUCTIVITY -> AppCategory.OTHER
                else -> null
            }
            if (platformCategory != null) {
                return platformCategory to ClassificationSource.PLATFORM_CATEGORY
            }
        }

        val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        return if (isSystem) {
            AppCategory.SYSTEM to ClassificationSource.PLATFORM_CATEGORY
        } else {
            AppCategory.OTHER to ClassificationSource.UNCLASSIFIED
        }
    }

    /**
     * The visibility gap, expressed as a number.
     *
     * Enumeration versus targeted lookup: if the second finds packages the first missed, Android's
     * package filtering is actively hiding installed apps from this build. That is the finding the
     * capability record needs, not a guess.
     */
    fun visibilityReport(): VisibilityReport {
        val enumerated = launchableApps().map { it.packageName }.toSet()
        val targeted = KnownPackages.allDeclared.filter { isInstalled(it) }.toSet()
        return VisibilityReport(
            enumeratedCount = enumerated.size,
            declaredPackagesChecked = KnownPackages.allDeclared.size,
            declaredPackagesFound = targeted.size,
            hiddenFromEnumeration = (targeted - enumerated).sorted(),
            apiLevel = Build.VERSION.SDK_INT,
        )
    }

    data class VisibilityReport(
        val enumeratedCount: Int,
        val declaredPackagesChecked: Int,
        val declaredPackagesFound: Int,
        val hiddenFromEnumeration: List<String>,
        val apiLevel: Int,
    ) {
        val filteringActive: Boolean get() = apiLevel >= Build.VERSION_CODES.R
    }
}
