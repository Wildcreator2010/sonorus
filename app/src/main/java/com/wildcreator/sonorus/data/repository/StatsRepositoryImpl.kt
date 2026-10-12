package com.wildcreator.sonorus.data.repository

import com.wildcreator.sonorus.data.local.ProviderRequestSummary
import com.wildcreator.sonorus.data.local.RequestStatDao
import com.wildcreator.sonorus.data.local.RequestSummary
import com.wildcreator.sonorus.domain.repository.StatsRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class StatsRepositoryImpl @Inject constructor(
    private val statDao: RequestStatDao
) : StatsRepository {

    override suspend fun summary(sinceEpochMs: Long): RequestSummary =
        statDao.summary(sinceEpochMs)

    override suspend fun summaryForProvider(providerId: Long, sinceEpochMs: Long): RequestSummary =
        statDao.summaryForProvider(providerId, sinceEpochMs)

    override fun observeByProvider(sinceEpochMs: Long): Flow<List<ProviderRequestSummary>> =
        statDao.observeByProvider(sinceEpochMs)

    override suspend fun clear() = statDao.clearAll()
}
