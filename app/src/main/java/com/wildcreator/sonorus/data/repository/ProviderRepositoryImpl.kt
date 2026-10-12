package com.wildcreator.sonorus.data.repository

import com.wildcreator.sonorus.data.local.ModelDao
import com.wildcreator.sonorus.data.local.ModelEntity
import com.wildcreator.sonorus.data.local.ProviderDao
import com.wildcreator.sonorus.data.local.ProviderEntity
import com.wildcreator.sonorus.data.remote.ProviderApiClient
import com.wildcreator.sonorus.data.remote.protocol.ProtocolAdapter
import com.wildcreator.sonorus.data.security.SecretStore
import com.wildcreator.sonorus.data.security.maskSecret
import com.wildcreator.sonorus.domain.model.ModelInfo
import com.wildcreator.sonorus.domain.model.Provider
import com.wildcreator.sonorus.domain.model.ProviderCredentials
import com.wildcreator.sonorus.domain.model.ProviderDraft
import com.wildcreator.sonorus.domain.model.ProviderProtocol
import com.wildcreator.sonorus.domain.repository.ProviderRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.jvm.JvmSuppressWildcards

class ProviderRepositoryImpl @Inject constructor(
    private val providerDao: ProviderDao,
    private val modelDao: ModelDao,
    private val apiClient: ProviderApiClient,
    private val secretStore: SecretStore,
    private val adapters: Map<ProviderProtocol, @JvmSuppressWildcards ProtocolAdapter>
) : ProviderRepository {

    override fun observeProviders(): Flow<List<Provider>> =
        providerDao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun getProvider(id: Long): Provider? = providerDao.getById(id)?.toDomain()

    override suspend fun saveProvider(draft: ProviderDraft): Long = withContext(Dispatchers.IO) {
        // 先落元数据拿到 id，再把密钥写入加密存储（新提供商 id 由 Room 生成）
        val id = providerDao.upsert(draft.toEntity())
        draft.apiKey?.takeIf { it.isNotBlank() }?.let { secretStore.put(keyAlias(id), it) }
        id
    }

    override suspend fun deleteProvider(id: Long) = withContext(Dispatchers.IO) {
        secretStore.remove(keyAlias(id))
        modelDao.deleteByProvider(id)
        providerDao.deleteById(id)
    }

    override suspend fun maskedApiKey(id: Long): String? =
        secretStore.get(keyAlias(id))?.let { maskSecret(it) }

    override suspend fun credentials(id: Long): ProviderCredentials? = withContext(Dispatchers.IO) {
        val entity = providerDao.getById(id) ?: return@withContext null
        val protocol = entity.protocolOrNull() ?: return@withContext null
        val key = secretStore.get(keyAlias(entity.id)) ?: return@withContext null
        ProviderCredentials(
            providerId = entity.id,
            protocol = protocol,
            baseUrl = adapter(protocol).normalizeBaseUrl(entity.baseUrl),
            apiKey = key
        )
    }

    /** 试连：用草稿里的密钥直连，不写库（草稿未填密钥时回退到已保存的） */
    override suspend fun testConnection(draft: ProviderDraft): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val key = draft.apiKey?.takeIf { it.isNotBlank() }
                ?: secretStore.get(keyAlias(draft.id))
                ?: error("未提供 API Key")
            val credentials = ProviderCredentials(
                providerId = draft.id,
                protocol = draft.protocol,
                baseUrl = adapter(draft.protocol).normalizeBaseUrl(draft.baseUrl),
                apiKey = key
            )
            apiClient.fetchModels(credentials).size
        }
    }

    override fun observeModels(providerId: Long): Flow<List<ModelInfo>> =
        modelDao.observeByProvider(providerId).map { list ->
            list.map { ModelInfo(id = it.id, providerId = it.providerId, modelId = it.modelId, ownedBy = it.ownedBy) }
        }

    override suspend fun refreshModels(providerId: Long): Result<Int> = withContext(Dispatchers.IO) {
        val credentials = credentials(providerId)
            ?: return@withContext Result.failure(IllegalStateException("缺少 API Key 或提供商不存在"))
        runCatching {
            val models = apiClient.fetchModels(credentials)
            val entities = models.map { model ->
                ModelEntity(
                    id = "$providerId:${model.id}",
                    providerId = providerId,
                    modelId = model.id,
                    ownedBy = model.ownedBy
                )
            }
            modelDao.replaceForProvider(providerId, entities)
            entities.size
        }
    }

    private fun adapter(protocol: ProviderProtocol): ProtocolAdapter =
        adapters[protocol] ?: error("未注册的协议适配器：$protocol")

    private companion object {
        fun keyAlias(providerId: Long) = "provider_key_$providerId"
    }
}

internal fun ProviderEntity.protocolOrNull(): ProviderProtocol? =
    runCatching { ProviderProtocol.valueOf(protocol) }.getOrNull()

internal fun ProviderEntity.toDomain() = Provider(
    id = id,
    name = name,
    baseUrl = baseUrl,
    protocol = runCatching { ProviderProtocol.valueOf(protocol) }
        .getOrDefault(ProviderProtocol.OPENAI),
    enabled = enabled
)

internal fun ProviderDraft.toEntity() = ProviderEntity(
    id = id,
    name = name.trim(),
    baseUrl = baseUrl.trim(),
    protocol = protocol.name,
    enabled = enabled
)
