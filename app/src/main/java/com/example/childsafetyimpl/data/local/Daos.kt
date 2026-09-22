package com.example.childsafetyimpl.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.example.childsafetyimpl.domain.model.AppCategory
import com.example.childsafetyimpl.domain.model.RestrictedSurface
import com.example.childsafetyimpl.domain.model.EventSeverity
import com.example.childsafetyimpl.domain.model.SafetyEventType
import com.example.childsafetyimpl.domain.model.WebCategory
import kotlinx.coroutines.flow.Flow

@Dao
interface SafetyEventDao {

    @Insert
    suspend fun insert(event: SafetyEventEntity): Long

    @Query("SELECT * FROM safety_events ORDER BY occurredAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 300): Flow<List<SafetyEventEntity>>

    @Query("SELECT * FROM safety_events WHERE severity IN (:severities) ORDER BY occurredAt DESC LIMIT :limit")
    fun observeBySeverity(severities: List<EventSeverity>, limit: Int = 300): Flow<List<SafetyEventEntity>>

    @Query("SELECT COUNT(*) FROM safety_events WHERE occurredAt >= :since")
    fun observeCountSince(since: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM safety_events WHERE occurredAt >= :since AND severity IN ('HIGH','CRITICAL')")
    fun observeSeriousCountSince(since: Long): Flow<Int>

    @Query("SELECT * FROM safety_events WHERE type = :type ORDER BY occurredAt DESC LIMIT :limit")
    fun observeByType(type: SafetyEventType, limit: Int = 100): Flow<List<SafetyEventEntity>>

    /** Coalesces repeat detections so an event log does not become a flood of identical rows. */
    @Query(
        """
        SELECT COUNT(*) FROM safety_events
        WHERE type = :type AND packageName IS :packageName AND occurredAt >= :since
        """
    )
    suspend fun countSimilarSince(type: SafetyEventType, packageName: String?, since: Long): Int

    @Query("DELETE FROM safety_events WHERE occurredAt < :before")
    suspend fun deleteOlderThan(before: Long): Int

    @Query("DELETE FROM safety_events")
    suspend fun clear()
}

@Dao
interface AppRuleDao {

    @Upsert
    suspend fun upsert(rule: AppRuleEntity)

    @Query("SELECT * FROM app_rules ORDER BY appLabel COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<AppRuleEntity>>

    @Query("SELECT * FROM app_rules")
    suspend fun getAll(): List<AppRuleEntity>

    @Query("SELECT * FROM app_rules WHERE packageName = :packageName")
    suspend fun get(packageName: String): AppRuleEntity?

    @Query("DELETE FROM app_rules WHERE packageName = :packageName")
    suspend fun delete(packageName: String)
}

@Dao
interface CategoryBudgetDao {

    @Upsert
    suspend fun upsert(budget: CategoryBudgetEntity)

    @Query("SELECT * FROM category_budgets")
    fun observeAll(): Flow<List<CategoryBudgetEntity>>

    @Query("SELECT * FROM category_budgets WHERE enabled = 1")
    suspend fun getEnabled(): List<CategoryBudgetEntity>

    @Query("SELECT * FROM category_budgets WHERE category = :category")
    suspend fun get(category: AppCategory): CategoryBudgetEntity?
}

@Dao
interface DomainRuleDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rules: List<DomainRuleEntity>)

    @Upsert
    suspend fun upsert(rule: DomainRuleEntity)

    @Query("SELECT * FROM domain_rules WHERE enabled = 1")
    suspend fun getEnabled(): List<DomainRuleEntity>

    @Query("SELECT * FROM domain_rules ORDER BY source ASC, pattern ASC")
    fun observeAll(): Flow<List<DomainRuleEntity>>

    @Query("SELECT * FROM domain_rules WHERE source = 'PARENT' ORDER BY pattern ASC")
    fun observeParentRules(): Flow<List<DomainRuleEntity>>

    @Query("SELECT COUNT(*) FROM domain_rules")
    suspend fun count(): Int

    @Query("DELETE FROM domain_rules WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface WebCategoryDao {

    @Upsert
    suspend fun upsert(setting: WebCategorySettingEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(settings: List<WebCategorySettingEntity>)

    @Query("SELECT * FROM web_category_settings")
    fun observeAll(): Flow<List<WebCategorySettingEntity>>

    @Query("SELECT * FROM web_category_settings WHERE blocked = 1")
    suspend fun getBlocked(): List<WebCategorySettingEntity>

    @Query("SELECT * FROM web_category_settings WHERE category = :category")
    suspend fun get(category: WebCategory): WebCategorySettingEntity?
}

@Dao
interface DailyUsageDao {

    @Upsert
    suspend fun upsertAll(rows: List<DailyUsageEntity>)

    @Query("SELECT * FROM daily_usage WHERE epochDay >= :fromDay ORDER BY epochDay ASC")
    fun observeSince(fromDay: Long): Flow<List<DailyUsageEntity>>

    @Query("SELECT * FROM daily_usage WHERE epochDay = :day ORDER BY foregroundMillis DESC")
    suspend fun getForDay(day: Long): List<DailyUsageEntity>

    @Query("SELECT COALESCE(SUM(foregroundMillis), 0) FROM daily_usage WHERE epochDay = :day")
    suspend fun totalForDay(day: Long): Long

    @Query("DELETE FROM daily_usage WHERE epochDay < :before")
    suspend fun deleteOlderThan(before: Long)
}

@Dao
interface CommunicationSignalDao {

    @Insert
    suspend fun insert(signal: CommunicationSignalEntity): Long

    @Query("SELECT * FROM communication_signals ORDER BY postedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<CommunicationSignalEntity>>

    @Query("SELECT COUNT(*) FROM communication_signals WHERE postedAt >= :since")
    fun observeCountSince(since: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM communication_signals WHERE postedAt >= :since AND isLateNight = 1")
    fun observeLateNightCountSince(since: Long): Flow<Int>

    @Query("DELETE FROM communication_signals WHERE postedAt < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM communication_signals")
    suspend fun clear()
}

@Dao
interface AiInteractionDao {

    @Insert
    suspend fun insert(interaction: AiInteractionEntity): Long

    @Query("SELECT * FROM ai_interactions ORDER BY atMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<AiInteractionEntity>>

    @Query("SELECT COUNT(*) FROM ai_interactions WHERE atMillis >= :since")
    fun observeCountSince(since: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM ai_interactions WHERE atMillis >= :since AND riskScore >= :threshold")
    fun observeConcerningCountSince(since: Long, threshold: Int): Flow<Int>

    @Query("SELECT DISTINCT packageName FROM ai_interactions WHERE atMillis >= :since")
    fun observeAppsSince(since: Long): Flow<List<String>>

    @Query("DELETE FROM ai_interactions WHERE atMillis < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM ai_interactions")
    suspend fun clear()
}

@Dao
interface SurfaceEncounterDao {

    @Insert
    suspend fun insert(encounter: SurfaceEncounterEntity): Long

    @Query("SELECT * FROM surface_encounters ORDER BY atMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<SurfaceEncounterEntity>>

    @Query("SELECT * FROM surface_encounters WHERE surface = :surface ORDER BY atMillis DESC LIMIT :limit")
    fun observeForSurface(surface: RestrictedSurface, limit: Int = 100): Flow<List<SurfaceEncounterEntity>>

    /** The number the daily allowance is measured against. */
    @Query(
        """
        SELECT COALESCE(SUM(dwellMillis), 0) FROM surface_encounters
        WHERE surface = :surface AND epochDay = :epochDay
        """
    )
    suspend fun dwellOnDay(surface: RestrictedSurface, epochDay: Long): Long

    @Query(
        """
        SELECT COALESCE(SUM(dwellMillis), 0) FROM surface_encounters
        WHERE surface = :surface AND epochDay = :epochDay
        """
    )
    fun observeDwellOnDay(surface: RestrictedSurface, epochDay: Long): Flow<Long>

    @Query("SELECT COUNT(*) FROM surface_encounters WHERE surface = :surface AND epochDay = :epochDay")
    fun observeCountOnDay(surface: RestrictedSurface, epochDay: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM surface_encounters WHERE epochDay = :epochDay AND closed = 1")
    fun observeClosedCountOnDay(epochDay: Long): Flow<Int>

    @Query("DELETE FROM surface_encounters WHERE epochDay < :beforeDay")
    suspend fun deleteOlderThan(beforeDay: Long)

    @Query("DELETE FROM surface_encounters")
    suspend fun clear()
}

@Dao
interface CloneFindingDao {

    @Upsert
    suspend fun upsert(finding: CloneFindingEntity)

    @Query("SELECT * FROM clone_findings ORDER BY lastSeenAt DESC")
    fun observeAll(): Flow<List<CloneFindingEntity>>

    @Query("SELECT * FROM clone_findings")
    suspend fun getAll(): List<CloneFindingEntity>

    @Query("SELECT * FROM clone_findings WHERE identity = :identity")
    suspend fun get(identity: String): CloneFindingEntity?

    @Query("SELECT COUNT(*) FROM clone_findings")
    fun observeCount(): Flow<Int>

    @Query("UPDATE clone_findings SET blocked = :blocked WHERE identity = :identity")
    suspend fun setBlocked(identity: String, blocked: Boolean)

    @Query("DELETE FROM clone_findings WHERE identity = :identity")
    suspend fun delete(identity: String)

    @Query("DELETE FROM clone_findings")
    suspend fun clear()
}
