package com.example.childsafetyimpl.domain.model

/** How an installed app is grouped for rules and reporting. */
enum class AppCategory(val label: String) {
    SOCIAL("Social media"),
    COMMUNICATION("Messaging"),
    GAME("Games"),
    AI("AI assistants"),
    VIDEO("Video"),
    BROWSER("Browsers"),
    CLONE_TOOL("App cloning tools"),
    VPN_TOOL("VPN apps"),
    EDUCATION("Learning"),
    SYSTEM("System"),
    OTHER("Other"),
}

/** One installed application, including how its category was decided. */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val category: AppCategory,
    /** How the category was decided, so the UI can be honest about guesswork. */
    val classificationSource: ClassificationSource,
    val isSystemApp: Boolean,
    val installedAt: Long,
    val versionName: String?,
)

enum class ClassificationSource(val label: String) {
    /** ApplicationInfo.category, declared by the developer and shown by Play. */
    PLATFORM_CATEGORY("Declared by the app"),
    /** Matched against the app's curated safety list. */
    CURATED_LIST("Known safety list"),
    /** Nothing matched. */
    UNCLASSIFIED("Not classified"),
}

/** Foreground time for one package over one query window. */
data class AppUsage(
    val packageName: String,
    val label: String,
    val category: AppCategory,
    val foregroundMillis: Long,
    val sessionCount: Int,
    val lastUsedAt: Long,
)

/** A parent-configured rule for one app. */
data class AppRule(
    val packageName: String,
    val appLabel: String,
    val dailyLimitMinutes: Int?,
    val blocked: Boolean,
    /** Minutes from midnight, or null when no schedule applies. */
    val blockFromMinuteOfDay: Int?,
    val blockToMinuteOfDay: Int?,
)

/** A parent-configured budget shared by every app in a category. */
data class CategoryBudget(
    val category: AppCategory,
    val dailyLimitMinutes: Int?,
    val enabled: Boolean,
)

/** Totals for one day, cached locally because the platform's own retention is limited. */
data class DailyUsage(
    val epochDay: Long,
    val totalMillis: Long,
    val byCategoryMillis: Map<AppCategory, Long>,
)
