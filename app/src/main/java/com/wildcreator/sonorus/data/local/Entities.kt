package com.wildcreator.sonorus.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 自定义模型提供商。
 * 安全约定：API Key 不落数据库，只存于 Keystore 加密的 SharedPreferences
 * （见 data/security/SecretStore），本表仅保存非敏感的元数据。
 */
@Entity(tableName = "providers")
data class ProviderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val baseUrl: String, // 例: https://api.openai.com/v1
    val protocol: String, // ProviderProtocol 名称：OPENAI / ANTHROPIC / GEMINI
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

/** 已拉取缓存的模型，主键 = providerId:modelId */
@Entity(tableName = "models", indices = [Index("providerId")])
data class ModelEntity(
    @PrimaryKey val id: String,
    val providerId: Long,
    val modelId: String,
    val ownedBy: String? = null,
    val fetchedAt: Long = System.currentTimeMillis()
)

/**
 * 会话。
 * `title` 上的索引是 MIGRATION_3_4 / MIGRATION_5_6 建的那个 —— **必须在这里声明**。
 * Room 校验升级结果时比较的是完整 TableInfo（含索引集），实体没声明而迁移建了索引，
 * 升级用户会直接抛 `Migration didn't properly handle: conversations`，整库打不开。
 */
@Entity(tableName = "conversations", indices = [Index("title")])
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** false = 用户手动命名/重命名过，AI 自动命名不再覆盖 */
    val titleAuto: Boolean = true,
    val providerId: Long,
    val modelId: String,
    val systemPrompt: String = "You are a helpful assistant.",
    val toolsEnabled: Boolean = true,
    /** 思考等级覆盖；null = 跟随设置页的全局默认 */
    val thinkingLevel: String? = null,
    val contextSummary: String? = null,
    val summaryThroughMessageId: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "messages", indices = [Index("conversationId")])
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: String, // user / assistant / tool / system
    val content: String,
    val imagesJson: String? = null,    // user 消息附带图片的 JSON（[{mimeType, base64}]）
    val filesJson: String? = null,     // user 消息附带文本文件的 JSON（[{name, mimeType, text}]）
    val toolCallsJson: String? = null, // assistant 发起工具调用时的原始 JSON
    val toolCallId: String? = null,    // role=tool 时对应的 tool_call id
    val toolName: String? = null,      // role=tool 时的工具名（便于 UI 展示）
    /**
     * 回答的多版本列表 JSON（含当前版本），null = 单版本旧数据。
     * content 永远是 versions[activeVersion].content —— 由 MessageVariants 统一写入，
     * 读路径（历史重建 / 导出 / UI）因此无需理解版本细节。
     */
    val versionsJson: String? = null,
    /** 当前生效的版本下标；送模型与显示的都必须是它 */
    val activeVersion: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 单次上游请求的成功/失败记录，用于统计"请求成功率"。
 * 只记非敏感元数据：不含 prompt、不含密钥、不含响应正文。
 */
@Entity(tableName = "request_stats", indices = [Index("createdAt"), Index("providerId")])
data class RequestStatEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val protocol: String,
    val modelId: String,
    val success: Boolean,
    /** 失败原因摘要（上游错误信息截断），成功时为 null */
    val errorKind: String? = null,
    val durationMs: Long,
    val createdAt: Long = System.currentTimeMillis()
)
