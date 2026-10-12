package com.wildcreator.sonorus.domain.repository

import com.wildcreator.sonorus.data.local.ProviderRequestSummary
import com.wildcreator.sonorus.data.local.RequestSummary
import kotlinx.coroutines.flow.Flow

/** 请求成功率统计的读取与维护 */
interface StatsRepository {

    /** 全局汇总（自 [sinceEpochMs] 起，0 = 全部） */
    suspend fun summary(sinceEpochMs: Long = 0L): RequestSummary

    suspend fun summaryForProvider(providerId: Long, sinceEpochMs: Long = 0L): RequestSummary

    /** 按提供商聚合，供提供商列表展示 */
    fun observeByProvider(sinceEpochMs: Long): Flow<List<ProviderRequestSummary>>

    /** 清空所有统计样本 */
    suspend fun clear()
}
