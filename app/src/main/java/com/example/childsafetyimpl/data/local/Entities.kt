package com.example.childsafetyimpl.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.childsafetyimpl.domain.model.AppCategory
import com.example.childsafetyimpl.domain.model.CloneKind
import com.example.childsafetyimpl.domain.model.EventAction
import com.example.childsafetyimpl.domain.model.EventSeverity
import com.example.childsafetyimpl.domain.model.RestrictedSurface
import com.example.childsafetyimpl.domain.model.RuleAction
import com.example.childsafetyimpl.domain.model.RuleSource
import com.example.childsafetyimpl.domain.model.SafetyEventType
import com.example.childsafetyimpl.domain.model.WebCategory

@Entity(
    tableName = "safety_events",
    indices = [Index("occurredAt"), Index("type"), Index("severity")],
)
data class SafetyEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: SafetyEventType,
    val severity: EventSeverity,
    val occurredAt: Long,
    val packageName: String?,
    val appLabel: String?,
    val title: String,
    val detail: String,
    val action: EventAction,
    val parentNotified: Boolean,
    val capabilityId: String?,
)

@Entity(tableName = "app_rules")
data class AppRuleEntity(
    @PrimaryKey val packageName: String,
    val appLabel: String,
    val dailyLimitMinutes: Int?,
    val blocked: Boolean,
    val blockFromMinuteOfDay: Int?,
    val blockToMinuteOfDay: Int?,
    val updatedAt: Long,
)

@Entity(tableName = "category_budgets")
data class CategoryBudgetEntity(
    @PrimaryKey val category: AppCategory,
    val dailyLimitMinutes: Int?,
    val enabled: Boolean,
)

@Entity(
    tableName = "domain_rules",
    indices = [Index(value = ["pattern"], unique = true), Index("category")],
)
data class DomainRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pattern: String,
    val category: WebCategory,
    val action: RuleAction,
    val source: RuleSource,
    val enabled: Boolean,
)

/** Category-level switches for the web filter, separate from individual domain rules. */
@Entity(tableName = "web_category_settings")
data class WebCategorySettingEntity(
    @PrimaryKey val category: WebCategory,
    val blocked: Boolean,
)

/**
 * Daily usage totals cached locally.
 *
 * The platform keeps its own daily buckets for a limited window only, so history has to be owned
 * by the app if a parent is ever going to see a trend longer than that window.
 */
@Entity(
    tableName = "daily_usage",
    primaryKeys = ["epochDay", "packageName"],
    indices = [Index("epochDay")],
)
data class DailyUsageEntity(
    val epochDay: Long,
    val packageName: String,
    val appLabel: String,
    val category: AppCategory,
    val foregroundMillis: Long,
    val sessionCount: Int,
)

/**
 * A communication signal derived from a notification.
 *
 * Deliberately stores no message text. What is kept is the shape of the traffic and a risk verdict,
 * which is the minimum a parent needs and the maximum this app is willing to hold.
 */
@Entity(tableName = "communication_signals", indices = [Index("postedAt")])
data class CommunicationSignalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appLabel: String,
    val postedAt: Long,
    val senderHash: String,
    val messageLength: Int,
    val riskScore: Int,
    val riskTags: String,
    val isLateNight: Boolean,
)

/**
 * One AI conversation the monitor observed.
 *
 * The prompt itself is never written. What is kept is its length, the risk categories it matched
 * and which app it happened in, which is everything a parent needs and nothing more.
 */
@Entity(tableName = "ai_interactions", indices = [Index("atMillis"), Index("packageName")])
data class AiInteractionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appLabel: String,
    val atMillis: Long,
    val promptLength: Int,
    val riskScore: Int,
    val riskTags: String,
)

/**
 * One encounter with a restricted screen inside another app.
 *
 * [dwellMillis] is what makes a daily allowance possible: the short-form control can permit a
 * number of minutes a day rather than refusing outright, and this table is where that time is
 * counted.
 */
@Entity(
    tableName = "surface_encounters",
    indices = [Index("atMillis"), Index("epochDay"), Index("surface")],
)
data class SurfaceEncounterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val surface: RestrictedSurface,
    val packageName: String,
    val appLabel: String,
    val atMillis: Long,
    val epochDay: Long,
    val dwellMillis: Long,
    val closed: Boolean,
    val matchedOn: String,
)

/**
 * A cloned app or cloning tool found on the device.
 *
 * Kept rather than recomputed so a clone that appears once and is hidden again still shows up in
 * the parent's history, which is exactly the case a live-only scan would miss.
 */
@Entity(tableName = "clone_findings", indices = [Index("firstSeenAt")])
data class CloneFindingEntity(
    @PrimaryKey val identity: String,
    val packageName: String,
    val label: String,
    val kind: CloneKind,
    val instanceCount: Int,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val blocked: Boolean,
)
