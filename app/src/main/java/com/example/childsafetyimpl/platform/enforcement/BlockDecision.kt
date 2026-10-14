package com.example.childsafetyimpl.platform.enforcement

import com.example.childsafetyimpl.domain.model.AppCategory
import com.example.childsafetyimpl.domain.model.RestrictedSurface

/** Why an app is being blocked, in words a child can read on the block screen. */
sealed interface BlockReason {

    data class DailyLimitReached(val limitMinutes: Int, val usedMinutes: Int) : BlockReason

    data class CategoryBudgetReached(
        val category: AppCategory,
        val limitMinutes: Int,
        val usedMinutes: Int,
    ) : BlockReason

    data class ScheduleWindow(val fromMinuteOfDay: Int, val toMinuteOfDay: Int) : BlockReason

    data object AlwaysBlocked : BlockReason

    data class BedtimeWindow(val fromMinuteOfDay: Int, val toMinuteOfDay: Int) : BlockReason

    /** A VPN client, closed because VPN use is restricted. */
    data object VpnApp : BlockReason

    /**
     * Another VPN is connected, so nothing but Settings and the phone can be used until it is
     * switched off.
     *
     * Not about the app in front at all, which is why the block screen offers VPN settings rather
     * than a reason to come back later: the way to get this app back is to end the VPN.
     */
    data object VpnActive : BlockReason

    /** A cloning tool or a cloned instance, closed because app cloning is restricted. */
    data object CloneApp : BlockReason

    /**
     * A routine is running and this app is not one it permits.
     *
     * Distinct from [ScheduleWindow], which is a window set on one app. A routine is a named
     * period the parent created — School, Bedtime — and the child-facing screen says which one,
     * because "blocked until 3pm" and "blocked because it is School time" are different messages
     * and only the second one is an explanation.
     */
    data class RoutineRestriction(
        val routineName: String,
        val endsAtMinuteOfDay: Int,
    ) : BlockReason

    /**
     * The internet is paused.
     *
     * Not a block on the app at all: the app opens, it simply has no network. It is in this list
     * because the child-facing message has to say which of the two is happening, and a browser
     * showing a connection error while a parent has deliberately paused the internet is the exact
     * confusion this product is supposed to remove.
     */
    data object InternetPaused : BlockReason
}

data class BlockDecision(
    val packageName: String,
    val appLabel: String,
    val reason: BlockReason,
)

/**
 * A restricted screen inside an app the child is otherwise allowed to use.
 *
 * Distinct from [BlockDecision] because the response is different in kind: the app stays open and
 * usable, and only the surface is refused. The overlay that shows this must therefore be brief and
 * dismissible, not a wall, or Instagram becomes unusable for the messaging a parent permitted.
 */
data class SurfaceBlockDecision(
    val surface: RestrictedSurface,
    val packageName: String,
    val appLabel: String,
    val message: String,
    /** False when the app refused the back action, which the child-facing text acknowledges. */
    val closed: Boolean,
)

/** How close an app is to its limit, used by the dashboard rather than by enforcement. */
data class LimitProgress(
    val packageName: String,
    val appLabel: String,
    val usedMillis: Long,
    val limitMillis: Long,
) {
    val fraction: Float
        get() = if (limitMillis <= 0) 0f else (usedMillis.toFloat() / limitMillis).coerceIn(0f, 1f)

    val remainingMillis: Long get() = (limitMillis - usedMillis).coerceAtLeast(0)
    val exceeded: Boolean get() = limitMillis in 1..usedMillis
}
