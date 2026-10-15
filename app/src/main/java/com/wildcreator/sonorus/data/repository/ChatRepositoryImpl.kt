package com.wildcreator.sonorus.data.repository

import com.wildcreator.sonorus.data.local.ChatDao
import com.wildcreator.sonorus.data.local.ConversationEntity
import com.wildcreator.sonorus.data.local.MessageEntity
import com.wildcreator.sonorus.domain.agent.ThinkingLevel
import com.wildcreator.sonorus.domain.model.AnswerVersion
import com.wildcreator.sonorus.domain.model.ChatMessage
import com.wildcreator.sonorus.domain.model.ChatRole
import com.wildcreator.sonorus.domain.model.Conversation
import com.wildcreator.sonorus.domain.model.ImageAttachment
import com.wildcreator.sonorus.domain.model.MessageVariants
import com.wildcreator.sonorus.domain.repository.ChatRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject

/** 图片 JSON 编解码：与 Room 的 imagesJson 列格式绑定 */
private val ImageJson = Json { ignoreUnknownKeys = true }

class ChatRepositoryImpl @Inject constructor(
    private val chatDao: ChatDao
) : ChatRepository {

    override fun observeConversations(): Flow<List<Conversation>> =
        chatDao.observeConversations().map { list -> list.map { it.toDomain() } }

    override fun searchConversations(query: String): Flow<List<Conversation>> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return observeConversations()
        // 将用户输入里的 LIKE 通配符转义，避免 "50%" 这种查询被当作匹配任意后缀
        val escaped = trimmed.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return chatDao.searchConversations("%$escaped%").map { list -> list.map { it.toDomain() } }
    }

    override suspend fun getConversation(id: Long): Conversation? =
        chatDao.getConversation(id)?.toDomain()

    override suspend fun createConversation(
        title: String,
        providerId: Long,
        modelId: String,
        systemPrompt: String
    ): Long = chatDao.upsertConversation(
        ConversationEntity(
            title = title,
            titleAuto = true,
            providerId = providerId,
            modelId = modelId,
            systemPrompt = systemPrompt
        )
    )

    override suspend fun updateConversation(conversation: Conversation) {
        // 定向 UPDATE：整行 REPLACE 会把摘要列抹成默认值，见 ChatDao.updateConversationFields
        chatDao.updateConversationFields(
            id = conversation.id,
            title = conversation.title,
            titleAuto = conversation.titleAuto,
            providerId = conversation.providerId,
            modelId = conversation.modelId,
            systemPrompt = conversation.systemPrompt,
            toolsEnabled = conversation.toolsEnabled,
            thinkingLevel = conversation.thinkingLevel?.name,
            updatedAt = System.currentTimeMillis()
        )
    }

    override suspend fun renameConversation(id: Long, title: String) {
        // 重命名即视为用户意图：置 title 同时把 titleAuto 关闭，AI 不再覆盖
        chatDao.renameConversationWithFlag(id, title)
    }

    override suspend fun renameConversationAuto(id: Long, title: String) {
        chatDao.renameConversationAuto(id, title)
    }

    override suspend fun purgeEmptyConversations(): Int = chatDao.deleteEmptyConversations()

    override suspend fun hasMessages(conversationId: Long): Boolean =
        chatDao.countMessages(conversationId) > 0

    override suspend fun clearMessages(conversationId: Long) {
        chatDao.deleteMessagesFor(conversationId)
    }

    override suspend fun getMessage(id: Long): ChatMessage? =
        chatDao.getMessage(id)?.toDomain()

    /**
     * 切换版本必须同时改 content —— 只动 activeVersion 会让「送模型的文本」和
     * 「界面显示的文本」分家（buildHistory 与导出都读 content）。
     */
    override suspend fun setActiveVersion(messageId: Long, index: Int) {
        val entity = chatDao.getMessage(messageId) ?: return
        val list = MessageVariants.decode(
            entity.versionsJson,
            AnswerVersion(content = entity.content, createdAt = entity.createdAt)
        )
        if (index !in list.indices) return
        val (content, versionsJson, active) = MessageVariants.entry(list, index)
        chatDao.upsertMessage(entity.copy(content = content, versionsJson = versionsJson, activeVersion = active))
    }

    override suspend fun deleteConversation(id: Long) {
        chatDao.deleteMessagesFor(id)
        chatDao.deleteConversation(id)
    }

    override fun observeMessages(conversationId: Long): Flow<List<ChatMessage>> =
        chatDao.observeMessages(conversationId).map { list -> list.map { it.toDomain() } }
}

internal fun ConversationEntity.toDomain() = Conversation(
    id = id,
    title = title,
    providerId = providerId,
    modelId = modelId,
    systemPrompt = systemPrompt,
    toolsEnabled = toolsEnabled,
    createdAt = createdAt,
    updatedAt = updatedAt,
    titleAuto = titleAuto,
    thinkingLevel = thinkingLevel?.let {
        runCatching { ThinkingLevel.valueOf(it) }.getOrNull()
    }
)

internal fun MessageEntity.toDomain(): ChatMessage {
    val list = MessageVariants.decode(versionsJson, AnswerVersion(content = content, createdAt = createdAt))
    // 旧行 activeVersion = 0；越界一律钳到有效区间，不让一条脏数据毁掉整屏渲染
    val active = activeVersion.coerceIn(0, list.lastIndex)
    return ChatMessage(
        id = id,
        conversationId = conversationId,
        role = runCatching { ChatRole.valueOf(role.uppercase()) }.getOrDefault(ChatRole.ASSISTANT),
        // content 恒等于生效版本的正文；两者不一致的脏行以"非空的那个"为准 ——
        // 正常写入下它们必然相同（entry 是唯一构造点），只有历史损坏行才会分叉
        content = list.getOrNull(active)?.content?.takeIf { it.isNotBlank() } ?: content,
        toolName = toolName,
        images = imagesJson.decodeImages(),
        versions = list,
        activeVersion = active
    )
}

/** 存库序列化：只存 mimeType + base64，失败时降级为空（图片不应导致消息丢失） */
internal fun List<ImageAttachment>.encodeImages(): String? =
    takeIf { it.isNotEmpty() }?.let {
        runCatching { ImageJson.encodeToString(ListSerializer(ImageAttachment.serializer()), it) }.getOrNull()
    }

internal fun String?.decodeImages(): List<ImageAttachment> =
    if (this.isNullOrBlank()) emptyList()
    else runCatching {
        ImageJson.decodeFromString(ListSerializer(ImageAttachment.serializer()), this)
    }.getOrDefault(emptyList())
