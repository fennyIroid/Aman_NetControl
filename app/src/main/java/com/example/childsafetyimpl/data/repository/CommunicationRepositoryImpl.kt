package com.example.childsafetyimpl.data.repository

import com.example.childsafetyimpl.data.local.CommunicationSignalDao
import com.example.childsafetyimpl.data.local.CommunicationSignalEntity
import com.example.childsafetyimpl.di.IoDispatcher
import com.example.childsafetyimpl.domain.repository.CommunicationRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CommunicationRepositoryImpl @Inject constructor(
    private val dao: CommunicationSignalDao,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : CommunicationRepository {

    override fun observeRecent(limit: Int): Flow<List<CommunicationSignalEntity>> =
        dao.observeRecent(limit)

    override fun observeCountSince(since: Long): Flow<Int> = dao.observeCountSince(since)

    override fun observeLateNightCountSince(since: Long): Flow<Int> =
        dao.observeLateNightCountSince(since)

    override suspend fun clear() = withContext(ioDispatcher) { dao.clear() }
}
