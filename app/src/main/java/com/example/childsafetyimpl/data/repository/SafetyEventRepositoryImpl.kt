package com.example.childsafetyimpl.data.repository

import com.example.childsafetyimpl.data.local.SafetyEventDao
import com.example.childsafetyimpl.data.local.SafetyEventEntity
import com.example.childsafetyimpl.di.IoDispatcher
import com.example.childsafetyimpl.domain.model.EventAction
import com.example.childsafetyimpl.domain.model.EventSeverity
import com.example.childsafetyimpl.domain.model.SafetyEvent
import com.example.childsafetyimpl.domain.model.SafetyEventType
import com.example.childsafetyimpl.domain.repository.SafetyEventRepository
import com.example.childsafetyimpl.platform.alerts.SafetyNotifier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SafetyEventRepositoryImpl @Inject constructor(
    private val dao: SafetyEventDao,
    private val notifier: SafetyNotifier,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : SafetyEventRepository {

    override fun observeRecent(limit: Int): Flow<List<SafetyEvent>> =
        dao.observeRecent(limit).map { rows -> rows.map { it.toDomain() } }

    override fun observeSeriousCountSince(since: Long): Flow<Int> =
        dao.observeSeriousCountSince(since)

    override fun observeCountSince(since: Long): Flow<Int> = dao.observeCountSince(since)

    override suspend fun record(
        type: SafetyEventType,
        title: String,
        detail: String,
        packageName: String?,
        appLabel: String?,
        severity: EventSeverity,
        action: EventAction,
        capabilityId: String?,
        dedupeWindowMillis: Long,
    ): Long? = withContext(ioDispatcher) {
        val now = System.currentTimeMillis()

        if (dedupeWindowMillis > 0) {
            val recent = dao.countSimilarSince(type, packageName, now - dedupeWindowMillis)
            if (recent > 0) return@withContext null
        }

        // Only genuinely serious events alert. Alert fatigue is the failure mode the catalog flags
        // against parent alerts, so the threshold lives here rather than at each call site.
        val shouldNotify = severity.weight >= EventSeverity.HIGH.weight

        val id = dao.insert(
            SafetyEventEntity(
                type = type,
                severity = severity,
                occurredAt = now,
                packageName = packageName,
                appLabel = appLabel,
                title = title,
                detail = detail,
                action = action,
                parentNotified = shouldNotify,
                capabilityId = capabilityId,
            )
        )

        if (shouldNotify) notifier.postSafetyAlert(title, detail, severity)
        id
    }

    override suspend fun clear() = withContext(ioDispatcher) { dao.clear() }

    override suspend fun pruneOlderThan(before: Long): Int =
        withContext(ioDispatcher) { dao.deleteOlderThan(before) }
}

internal fun SafetyEventEntity.toDomain() = SafetyEvent(
    id = id,
    type = type,
    severity = severity,
    occurredAt = occurredAt,
    packageName = packageName,
    appLabel = appLabel,
    title = title,
    detail = detail,
    action = action,
    parentNotified = parentNotified,
    capabilityId = capabilityId,
)
