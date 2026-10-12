package com.wildcreator.sonorus.domain.model

import com.wildcreator.sonorus.domain.agent.ThinkingLevel
import kotlinx.serialization.Serializable

/** 支持的线协议：同一份 UI / 存储，三套适配器 */
enum class ProviderProtocol(
    val label: String,
    val defaultBaseUrl: String,
    val supportsTools: Boolean
) {
    OPENAI("OpenAI 兼容", "https://api.openai.com/v1", true),
    ANTHROPIC("Anthropic", "https://api.anthropic.com/v1", true),
    GEMINI("Google Gemini", "https://generativelanguage.googleapis.com/v1beta", true)
}

data class Provider(
    val id: Long = 0,
    val name: String,
    val baseUrl: String,
    val protocol: ProviderProtocol,
    val enabled: Boolean = true
)

/** 编辑态草稿：apiKey 为 null 表示"沿用已保存的密钥"，不覆盖 */
data class ProviderDraft(
    val id: Long = 0,
    val name: String,
    val baseUrl: String,
    val protocol: ProviderProtocol,
    val enabled: Boolean = true,
    val apiKey: String? = null
)

/**
 * 一次性凭据：仅在发起请求前从加密存储取出，用完即弃。
 * 不进入 ViewModel 状态、不落数据库、不参与序列化。
 */
data class ProviderCredentials(
    /** 统计埋点需要归属到具体提供商；草稿试连时为草稿 id（可能为 0） */
    val providerId: Long,
    val protocol: ProviderProtocol,
    val baseUrl: String,
    val apiKey: String
)

data class ModelInfo(
    val id: String,
    val providerId: Long,
    val modelId: String,
    val ownedBy: String? = null
)

data class Conversation(
    val id: Long = 0,
    val title: String,
    val providerId: Long,
    val modelId: String,
    val systemPrompt: String,
    val toolsEnabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    /** true = 标题可被 AI 自动命名覆盖；用户重命名后置 false */
    val titleAuto: Boolean = true,
    /** 会话级思考等级覆盖；null = 跟随设置页全局默认 */
    val thinkingLevel: ThinkingLevel? = null
)

enum class ChatRole { USER, ASSISTANT, TOOL, SYSTEM }

/**
 * 一条回答的单个版本。
 * 推理文本与正文同属一个版本 —— 换一版答案必然换一版推理，拆开存会错位。
 */
@Serializable
data class AnswerVersion(
    val content: String,
    val reasoning: String? = null,
    /** 触发这次修订的批注；null = 初版 */
    val note: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

data class ChatMessage(
    val id: Long = 0,
    val conversationId: Long,
    val role: ChatRole,
    val content: String,
    val toolName: String? = null,
    /** user 消息附带的图片；base64 不含 data: 前缀 */
    val images: List<ImageAttachment> = emptyList(),
    /** 回答版本列表，映射后恒为非空；单版本时长度为 1 */
    val versions: List<AnswerVersion> = emptyList(),
    val activeVersion: Int = 0
) {
    /** 当前生效版本的推理内容（无则 null） */
    val reasoning: String? get() = versions.getOrNull(activeVersion)?.reasoning

    /** 除当前版本外的其余版本，供「◀ 2/3 ▶」计数与切换 */
    val versionCount: Int get() = maxOf(versions.size, 1)
}

/** UI 与存储共用的图片载体 */
@Serializable
data class ImageAttachment(
    val mimeType: String,
    val base64: String
)
