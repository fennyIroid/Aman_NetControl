package com.example.childsafetyimpl.core.common

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** Time helpers shared by the dashboard, the usage report and the event log. */
object TimeFormat {

    private val timeFormatter = DateTimeFormatter.ofPattern("h:mm a")
    private val dateFormatter = DateTimeFormatter.ofPattern("d MMM")
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, h:mm a")
    private val weekdayFormatter = DateTimeFormatter.ofPattern("EEE")

    /** "2h 15m" style. Compact because it appears inside rings and chips. */
    fun duration(millis: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(millis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
        return when {
            hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
            hours > 0 -> "${hours}h"
            minutes > 0 -> "${minutes}m"
            millis > 0 -> "under 1m"
            else -> "0m"
        }
    }

    fun durationFromMinutes(minutes: Int): String = duration(minutes * 60_000L)

    fun clock(epochMillis: Long): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault())
            .format(timeFormatter)

    fun dateTime(epochMillis: Long): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault())
            .format(dateTimeFormatter)

    fun date(epochDay: Long): String =
        LocalDate.ofEpochDay(epochDay).format(dateFormatter)

    fun weekday(epochDay: Long): String =
        LocalDate.ofEpochDay(epochDay).format(weekdayFormatter).take(1)

    /** "12 min ago" style, used in the live event feed. */
    fun relative(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
        val delta = (now - epochMillis).coerceAtLeast(0)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(delta)
        val hours = TimeUnit.MILLISECONDS.toHours(delta)
        val days = TimeUnit.MILLISECONDS.toDays(delta)
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "${minutes}m ago"
            hours < 24 -> "${hours}h ago"
            days < 7 -> "${days}d ago"
            else -> dateTime(epochMillis)
        }
    }

    fun todayEpochDay(): Long = LocalDate.now().toEpochDay()

    fun startOfTodayMillis(): Long =
        LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    fun startOfDayMillis(epochDay: Long): Long =
        LocalDate.ofEpochDay(epochDay).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    fun minuteOfDayNow(): Int {
        val now = LocalDateTime.now()
        return now.hour * 60 + now.minute
    }

    fun formatMinuteOfDay(minuteOfDay: Int): String {
        val hour24 = (minuteOfDay / 60) % 24
        val minute = minuteOfDay % 60
        val suffix = if (hour24 < 12) "AM" else "PM"
        val hour12 = when {
            hour24 % 12 == 0 -> 12
            else -> hour24 % 12
        }
        return "%d:%02d %s".format(hour12, minute, suffix)
    }

    /** True when [minuteOfDay] falls inside a window that may wrap past midnight. */
    fun isWithinWindow(minuteOfDay: Int, from: Int, to: Int): Boolean =
        if (from <= to) minuteOfDay in from until to else minuteOfDay >= from || minuteOfDay < to
}
