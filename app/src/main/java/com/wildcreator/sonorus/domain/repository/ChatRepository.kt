package com.wildcreator.sonorus.domain.repository

import com.wildcreator.sonorus.domain.model.ChatMessage
import com.wildcreator.sonorus.domain.model.Conversation
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    fun observeConversations(): Flow<List<Conversation>>

    /**
     * 模糊搜索会话：标题或消息内容包含 [query] 即命中。
     * 传空字符串等价于 [observeConversations]，调用方可直接依赖本方法。
     */
    fun searchConversations(query: String): Flow<List<Conversation>>

    suspend fun getConversation(id: Long): Conversation?

    /** systemPrompt 由调用方传入（可来自设置页的默认值），便于新建会话即带上用户偏好 */
    suspend fun createConversation(
        title: String,
        providerId: Long,
        modelId: String,
        systemPrompt: String
    ): Long

    suspend fun updateConversation(conversation: Conversation)

    /** 用户手动重命名：同时关闭 AI 自动命名 */
    suspend fun renameConversation(id: Long, title: String)

    /** AI 自动命名：仅在会话仍处于自动命名状态时生效 */
    suspend fun renameConversationAuto(id: Long, title: String)

    /** 删除所有没有消息的会话，返回删除条数 */
    suspend fun purgeEmptyConversations(): Int

    /** 会话是否已有消息（用于避免自动删除误伤正在使用的会话） */
    suspend fun hasMessages(conversationId: Long): Boolean

    /** 清空会话内的全部消息，但保留会话本身（标题与配置不变） */
    suspend fun clearMessages(conversationId: Long)

    suspend fun getMessage(id: Long): ChatMessage?

    /** 编辑重发：删除 [fromMessageId] 及其之后的全部消息 */
    suspend fun deleteMessagesFrom(conversationId: Long, fromMessageId: Long)

    /** 切换回答的生效版本；content 列会同步为该版本文本 */
    suspend fun setActiveVersion(messageId: Long, index: Int)

    suspend fun deleteConversation(id: Long)
    fun observeMessages(conversationId: Long): Flow<List<ChatMessage>>
}
