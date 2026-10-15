package com.wildcreator.sonorus.data.local

import kotlinx.coroutines.flow.Flow

/**
 * 内存版 [ChatDao]：按 Room 的真实语义实现会话表与消息表，未被本测试套件用到的方法一律抛错。
 *
 * 语义要点（必须与 DAO 注解一致，否则测试会在假数据上得出假结论）：
 * - `upsertConversation` 是 `OnConflictStrategy.REPLACE` —— 整行覆盖，
 *   Kotlin 对象里没带的列会被写成实体默认值，这正是"摘要被抹掉"那类 bug 的来源；
 * - 定向 UPDATE 只改它列出的列，未列出的列保持原值。
 */
class FakeChatDao : ChatDao {

    /** 测试可直接读取的存储状态 */
    val conversations = mutableMapOf<Long, ConversationEntity>()
    val messages = mutableMapOf<Long, MessageEntity>()

    /**
     * 自增主键：单调递增、绝不复用 —— 与 SQLite 的 `sqlite_sequence` 一致，
     * 水位只记"曾经插入过的最大 id"，删掉行不会让它回退。
     * 若按"当前现存最大值 + 1"推导，删尾后新行会顶掉刚被删的 id，
     * 恰好把 `deleteMessagesFrom` 这类按 id 删除的路径测出假结论。
     */
    private var conversationIdSeq = 0L
    private var messageIdSeq = 0L

    fun put(conversation: ConversationEntity): ConversationEntity {
        val withId = if (conversation.id == 0L) {
            conversationIdSeq += 1L
            conversation.copy(id = conversationIdSeq)
        } else {
            conversation.also { conversationIdSeq = maxOf(conversationIdSeq, it.id) }
        }
        conversations[withId.id] = withId
        return withId
    }

    fun put(message: MessageEntity): MessageEntity {
        val withId = if (message.id == 0L) {
            messageIdSeq += 1L
            message.copy(id = messageIdSeq)
        } else {
            message.also { messageIdSeq = maxOf(messageIdSeq, it.id) }
        }
        messages[withId.id] = withId
        return withId
    }

    override suspend fun getConversation(id: Long): ConversationEntity? = conversations[id]

    override suspend fun upsertConversation(conversation: ConversationEntity): Long = put(conversation).id

    override suspend fun insertMessage(message: MessageEntity): Long = put(message).id

    override suspend fun updateConversationFields(
        id: Long,
        title: String,
        titleAuto: Boolean,
        providerId: Long,
        modelId: String,
        systemPrompt: String,
        toolsEnabled: Boolean,
        thinkingLevel: String?,
        updatedAt: Long
    ) {
        val current = conversations[id] ?: return
        conversations[id] = current.copy(
            title = title,
            titleAuto = titleAuto,
            providerId = providerId,
            modelId = modelId,
            systemPrompt = systemPrompt,
            toolsEnabled = toolsEnabled,
            thinkingLevel = thinkingLevel,
            updatedAt = updatedAt
        )
    }

    override suspend fun deleteConversation(id: Long) {
        conversations.remove(id)
    }

    override suspend fun countConversationsForProvider(providerId: Long): Int = NOT_COVERED()

    override suspend fun deleteMessagesOfProvider(providerId: Long) = NOT_COVERED()

    override suspend fun deleteConversationsOfProvider(providerId: Long) = NOT_COVERED()

    override suspend fun deleteMessagesFor(conversationId: Long) {
        messages.keys.filter { messages.getValue(it).conversationId == conversationId }.forEach { messages.remove(it) }
    }

    override suspend fun getMessage(id: Long): MessageEntity? = messages[id]

    override suspend fun upsertMessage(message: MessageEntity) {
        messages[message.id] = message
    }

    override suspend fun setActiveVersion(id: Long, index: Int) = NOT_COVERED()

    override suspend fun deleteMessagesFrom(conversationId: Long, fromId: Long): Int =
        doomed(conversationId) { it.id >= fromId }

    override suspend fun deleteMessagesAfter(conversationId: Long, afterId: Long): Int =
        doomed(conversationId) { it.id > afterId }

    private fun doomed(conversationId: Long, predicate: (MessageEntity) -> Boolean): Int {
        val ids = messages.values.filter { it.conversationId == conversationId && predicate(it) }.map { it.id }
        ids.forEach { messages.remove(it) }
        return ids.size
    }

    override suspend fun getLastUserMessage(conversationId: Long): MessageEntity? =
        messages.values.filter { it.conversationId == conversationId && it.role == "user" }.maxByOrNull { it.id }

    override suspend fun getLastAnswerMessage(conversationId: Long): MessageEntity? =
        messages.values.filter {
            it.conversationId == conversationId && it.role == "assistant" && it.toolCallsJson == null
        }.maxByOrNull { it.id }

    override suspend fun countMessages(conversationId: Long): Int =
        messages.values.count { it.conversationId == conversationId }

    override suspend fun deleteEmptyConversations(): Int {
        val referenced = messages.values.map { it.conversationId }.toSet()
        val doomedIds = conversations.keys.filterNot { it in referenced }
        doomedIds.forEach { conversations.remove(it) }
        return doomedIds.size
    }

    override suspend fun renameConversationWithFlag(id: Long, title: String) {
        conversations[id]?.let { conversations[id] = it.copy(title = title, titleAuto = false) }
    }

    override suspend fun renameConversationAuto(id: Long, title: String) {
        conversations[id]?.takeIf { it.titleAuto }?.let { conversations[id] = it.copy(title = title) }
    }

    override fun observeConversations(): Flow<List<ConversationEntity>> = NOT_COVERED()

    override fun searchConversations(pattern: String): Flow<List<ConversationEntity>> = NOT_COVERED()

    override fun observeMessages(conversationId: Long): Flow<List<MessageEntity>> = NOT_COVERED()

    override suspend fun getMessages(conversationId: Long): List<MessageEntity> =
        messages.values.filter { it.conversationId == conversationId }.sortedBy { it.id }

    private fun NOT_COVERED(): Nothing = error("FakeChatDao 未覆盖该方法，测试用错了路径")
}
