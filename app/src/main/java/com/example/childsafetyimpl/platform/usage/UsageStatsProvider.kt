package com.example.childsafetyimpl.platform.usage

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import com.example.childsafetyimpl.core.common.DataResult
import com.example.childsafetyimpl.core.common.TimeFormat
import com.example.childsafetyimpl.domain.model.AppCategory
import com.example.childsafetyimpl.domain.model.AppUsage
import com.example.childsafetyimpl.platform.permission.PermissionInspector
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real usage measurement through UsageStatsManager.
 *
 * Totals are rebuilt from the raw event stream rather than read from the daily buckets. The
 * buckets are cheaper but they lag and they round; walking resume and pause events gives an
 * accurate same-day figure and, as a side effect, an honest session count, which the buckets
 * cannot provide at all.
 */
@Singleton
class UsageStatsProvider @Inject constructor(
    private val usageStatsManager: UsageStatsManager,
    private val inventory: AppInventoryProvider,
    private val permissionInspector: PermissionInspector,
) {

    /**
     * Foreground time per package between two instants.
     *
     * Returns [DataResult.Unavailable] rather than an empty list when access is missing. That
     * distinction is the whole point: an empty list would read as "the child used nothing today".
     */
    fun usageBetween(startMillis: Long, endMillis: Long): DataResult<List<AppUsage>> {
        if (!permissionInspector.hasUsageAccess()) {
            return DataResult.Unavailable(
                reason = "Usage Access has not been granted, so Android returns no usage data at all.",
                resolution = DataResult.Resolution.GRANT_PERMISSION,
            )
        }

        return try {
            val events = usageStatsManager.queryEvents(startMillis, endMillis)
            val accumulator = mutableMapOf<String, Accumulator>()
            val event = UsageEvents.Event()

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val packageName = event.packageName ?: continue
                val state = accumulator.getOrPut(packageName) { Accumulator() }

                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        // A resume without a matching pause means the app is still open; the
                        // running total is closed off against endMillis below.
                        if (state.resumedAt == null) {
                            state.resumedAt = event.timeStamp
                            state.sessions++
                        }
                        state.lastSeen = maxOf(state.lastSeen, event.timeStamp)
                    }

                    UsageEvents.Event.ACTIVITY_PAUSED,
                    UsageEvents.Event.ACTIVITY_STOPPED,
                    -> {
                        val resumedAt = state.resumedAt
                        if (resumedAt != null) {
                            state.totalMillis += (event.timeStamp - resumedAt).coerceAtLeast(0)
                            state.resumedAt = null
                        }
                        state.lastSeen = maxOf(state.lastSeen, event.timeStamp)
                    }
                }
            }

            val usage = accumulator.mapNotNull { (packageName, state) ->
                // Close any session still open at the end of the window.
                state.resumedAt?.let { state.totalMillis += (endMillis - it).coerceAtLeast(0) }
                if (state.totalMillis <= 0) return@mapNotNull null

                val app = inventory.describe(packageName)
                AppUsage(
                    packageName = packageName,
                    label = app?.label ?: packageName,
                    category = app?.category ?: AppCategory.OTHER,
                    foregroundMillis = state.totalMillis,
                    sessionCount = state.sessions,
                    lastUsedAt = state.lastSeen,
                )
            }.sortedByDescending { it.foregroundMillis }

            DataResult.Success(usage)
        } catch (e: SecurityException) {
            DataResult.Unavailable(
                reason = "Android refused the usage query: ${e.message}",
                resolution = DataResult.Resolution.GRANT_PERMISSION,
            )
        } catch (e: Exception) {
            DataResult.Error("Usage query failed: ${e.message}", e)
        }
    }

    fun usageToday(): DataResult<List<AppUsage>> =
        usageBetween(TimeFormat.startOfTodayMillis(), System.currentTimeMillis())

    fun usageForDay(epochDay: Long): DataResult<List<AppUsage>> {
        val start = TimeFormat.startOfDayMillis(epochDay)
        val end = LocalDate.ofEpochDay(epochDay)
            .plusDays(1)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
            .coerceAtMost(System.currentTimeMillis())
        if (end <= start) return DataResult.Success(emptyList())
        return usageBetween(start, end)
    }

    /**
     * The current foreground package.
     *
     * getRunningTasks was closed to third-party apps in Android 5, so the only route left is the
     * most recent resume event in a short trailing window. The lag this introduces, a second or
     * two, is the reason enforcement can never be instantaneous.
     */
    fun currentForegroundApp(lookBackMillis: Long = 12_000L): DataResult<String?> {
        if (!permissionInspector.hasUsageAccess()) {
            return DataResult.Unavailable(
                reason = "Usage Access is required to see which app is in the foreground.",
                resolution = DataResult.Resolution.GRANT_PERMISSION,
            )
        }
        return try {
            val now = System.currentTimeMillis()
            val events = usageStatsManager.queryEvents(now - lookBackMillis, now)
            val event = UsageEvents.Event()
            var latestPackage: String? = null
            var latestTime = 0L
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED &&
                    event.timeStamp >= latestTime
                ) {
                    latestTime = event.timeStamp
                    latestPackage = event.packageName
                }
            }
            DataResult.Success(latestPackage)
        } catch (e: Exception) {
            DataResult.Error("Foreground query failed: ${e.message}", e)
        }
    }

    /** Total foreground time today for one package, used by the limit checks. */
    fun todayMillisFor(packageName: String): Long {
        val result = usageToday()
        return (result as? DataResult.Success)
            ?.data
            ?.firstOrNull { it.packageName == packageName }
            ?.foregroundMillis
            ?: 0L
    }

    /** Total foreground time today across every app in a category, for category budgets. */
    fun todayMillisForCategory(category: AppCategory): Long {
        val result = usageToday()
        return (result as? DataResult.Success)
            ?.data
            ?.filter { it.category == category }
            ?.sumOf { it.foregroundMillis }
            ?: 0L
    }

    /**
     * Rolling window of daily totals.
     *
     * The event stream is queried per day. It is heavier than reading the daily buckets, but the
     * buckets have their own retention window and this keeps the two paths consistent.
     */
    fun dailyTotals(days: Int = 7): DataResult<List<Pair<Long, Long>>> {
        if (!permissionInspector.hasUsageAccess()) {
            return DataResult.Unavailable(
                reason = "Usage Access is required to build a usage history.",
                resolution = DataResult.Resolution.GRANT_PERMISSION,
            )
        }
        val today = TimeFormat.todayEpochDay()
        val totals = ((today - days + 1)..today).map { day ->
            val usage = usageForDay(day)
            val total = (usage as? DataResult.Success)?.data?.sumOf { it.foregroundMillis } ?: 0L
            day to total
        }
        return DataResult.Success(totals)
    }

    private class Accumulator {
        var totalMillis: Long = 0
        var resumedAt: Long? = null
        var sessions: Int = 0
        var lastSeen: Long = 0
    }
}
