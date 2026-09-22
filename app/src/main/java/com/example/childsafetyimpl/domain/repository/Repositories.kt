package com.example.childsafetyimpl.domain.repository

import com.example.childsafetyimpl.data.local.CommunicationSignalEntity
import com.example.childsafetyimpl.domain.model.AppCategory
import com.example.childsafetyimpl.domain.model.AppRule
import com.example.childsafetyimpl.domain.model.CategoryBudget
import com.example.childsafetyimpl.domain.model.DomainRule
import com.example.childsafetyimpl.domain.model.EventAction
import com.example.childsafetyimpl.domain.model.EventSeverity
import com.example.childsafetyimpl.domain.model.FilterVerdict
import com.example.childsafetyimpl.domain.model.SafetyEvent
import com.example.childsafetyimpl.domain.model.SafetyEventType
import com.example.childsafetyimpl.domain.model.WebCategory
import kotlinx.coroutines.flow.Flow

interface SafetyEventRepository {

    fun observeRecent(limit: Int = 300): Flow<List<SafetyEvent>>

    fun observeSeriousCountSince(since: Long): Flow<Int>

    fun observeCountSince(since: Long): Flow<Int>

    /**
     * Records an event and decides whether it deserves a parent alert.
     *
     * [detail] must never carry message text, screen text or a full URL. That rule lives on this
     * method specifically, because it is the only write path into the log.
     */
    suspend fun record(
        type: SafetyEventType,
        title: String,
        detail: String,
        packageName: String? = null,
        appLabel: String? = null,
        severity: EventSeverity = type.defaultSeverity,
        action: EventAction = EventAction.LOGGED,
        capabilityId: String? = null,
        /** Suppresses a duplicate of the same type and app within this window. */
        dedupeWindowMillis: Long = 0L,
    ): Long?

    suspend fun clear()

    suspend fun pruneOlderThan(before: Long): Int
}

interface SafetyRulesRepository {

    fun observeAppRules(): Flow<List<AppRule>>

    suspend fun appRules(): List<AppRule>

    suspend fun upsertAppRule(rule: AppRule)

    suspend fun deleteAppRule(packageName: String)

    fun observeCategoryBudgets(): Flow<List<CategoryBudget>>

    suspend fun setCategoryBudget(category: AppCategory, minutes: Int?, enabled: Boolean)

    fun observeDomainRules(): Flow<List<DomainRule>>

    fun observeParentDomainRules(): Flow<List<DomainRule>>

    suspend fun addParentDomainRule(pattern: String, block: Boolean)

    suspend fun deleteDomainRule(id: Long)

    fun observeWebCategories(): Flow<Map<WebCategory, Boolean>>

    suspend fun setWebCategoryBlocked(category: WebCategory, blocked: Boolean)

    /** Evaluates one host against the current rule set. Used by both the browser and the tunnel. */
    suspend fun evaluateHost(host: String): FilterVerdict

    /** Refreshes the in-memory rule snapshot the filter engine evaluates against. */
    suspend fun refreshFilterCache()
}

interface CommunicationRepository {

    fun observeRecent(limit: Int = 200): Flow<List<CommunicationSignalEntity>>

    fun observeCountSince(since: Long): Flow<Int>

    fun observeLateNightCountSince(since: Long): Flow<Int>

    suspend fun clear()
}
