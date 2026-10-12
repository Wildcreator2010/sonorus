package com.wildcreator.sonorus.domain.repository

import com.wildcreator.sonorus.domain.model.ModelInfo
import com.wildcreator.sonorus.domain.model.Provider
import com.wildcreator.sonorus.domain.model.ProviderCredentials
import com.wildcreator.sonorus.domain.model.ProviderDraft
import kotlinx.coroutines.flow.Flow

interface ProviderRepository {
    fun observeProviders(): Flow<List<Provider>>
    suspend fun getProvider(id: Long): Provider?

    /** 保存元数据；draft.apiKey 非空时写入加密存储 */
    suspend fun saveProvider(draft: ProviderDraft): Long
    suspend fun deleteProvider(id: Long)

    /** 已保存密钥的掩码展示（末 4 位），未保存返回 null */
    suspend fun maskedApiKey(id: Long): String?

    /** 按需取出一次性凭据供网络层使用；调用方不得缓存或持久化 */
    suspend fun credentials(id: Long): ProviderCredentials?

    /** 用草稿凭据试连并拉取模型，不落库 */
    suspend fun testConnection(draft: ProviderDraft): Result<Int>

    fun observeModels(providerId: Long): Flow<List<ModelInfo>>

    /** 自动拉取模型并整体替换本地缓存；返回模型数量 */
    suspend fun refreshModels(providerId: Long): Result<Int>
}
