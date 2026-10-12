package com.wildcreator.sonorus.domain.agent

import com.wildcreator.sonorus.data.remote.ProviderApiClient
import com.wildcreator.sonorus.domain.repository.ProviderRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 用 AI 为会话生成标题（摘要）。
 *
 * 设计要点：
 * - 复用会话自身的 提供商 + 模型，不需要额外配置；
 * - 不传工具、限制 maxTokens，降低成本与延迟；
 * - 任何一步失败都返回 null，由调用方回退到本地规则（首条消息截断），
 *   标题生成属于增强能力，不应影响主流程。
 */
@Singleton
class ChatTitleGenerator @Inject constructor(
    private val providerRepository: ProviderRepository,
    private val apiClient: ProviderApiClient
) {

    suspend fun generate(providerId: Long, modelId: String, seed: String): String? {
        val credentials = providerRepository.credentials(providerId) ?: return null
        val excerpt = seed.trim().take(500)
        if (excerpt.isEmpty()) return null

        val request = UnifiedChatRequest(
            model = modelId,
            systemPrompt = TITLE_SYSTEM_PROMPT,
            messages = listOf(UnifiedMessage(role = UnifiedRole.USER, text = excerpt)),
            tools = null,
            maxTokens = 40
        )

        val sb = StringBuilder()
        runCatching {
            apiClient.streamChat(credentials, request).collect { event ->
                when (event) {
                    is ProviderApiClient.StreamEvent.Delta -> event.delta.text?.let { sb.append(it) }
                    is ProviderApiClient.StreamEvent.Error -> throw IllegalStateException(event.message)
                    ProviderApiClient.StreamEvent.Done -> Unit
                }
            }
        }.onFailure { return null }

        return sanitize(sb.toString())
    }

    /** 去掉引号、markdown 记号、「标题：」前缀，并限制长度 */
    private fun sanitize(raw: String): String? {
        val cleaned = raw.lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.removePrefix("标题：")
            ?.removePrefix("标题:")
            .orEmpty()
            .trim()
            .trim('"', '\'', '“', '”', '「', '」', '*', '#', ' ', '，', '。', '.', ':', '：')
            .replace(Regex("\\s+"), " ")
            .trim()

        if (cleaned.isEmpty()) return null
        return if (cleaned.length > MAX_TITLE_LENGTH) cleaned.take(MAX_TITLE_LENGTH) else cleaned
    }

    private companion object {
        const val MAX_TITLE_LENGTH = 20
        const val TITLE_SYSTEM_PROMPT =
            "你是会话命名助手。请用不超过 12 个字概括用户这条消息的核心意图，" +
                "直接输出标题本身，不要标点、不要引号、不要任何解释或前后缀。"
    }
}
