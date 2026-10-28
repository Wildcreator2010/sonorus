package com.wildcreator.sonorus.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ProviderDao {
    @Query("SELECT * FROM providers ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<ProviderEntity>>

    @Query("SELECT * FROM providers WHERE id = :id")
    suspend fun getById(id: Long): ProviderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(provider: ProviderEntity): Long

    @Query("DELETE FROM providers WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface ModelDao {
    @Query("SELECT * FROM models WHERE providerId = :providerId ORDER BY modelId ASC")
    fun observeByProvider(providerId: Long): Flow<List<ModelEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(models: List<ModelEntity>)

    @Query("DELETE FROM models WHERE providerId = :providerId")
    suspend fun deleteByProvider(providerId: Long)

    @Transaction
    suspend fun replaceForProvider(providerId: Long, models: List<ModelEntity>) {
        deleteByProvider(providerId)
        upsertAll(models)
    }
}

@Dao
interface ChatDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC, id DESC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    /**
     * 按标题或消息内容模糊搜索会话。
     *
     * 用 LIKE 而不是 FTS4：
     * - 单用户设备的会话量级通常在几百条以内，LIKE 全表扫足够快；
     * - FTS4 需要额外虚表 + 触发器 + 迁移，成本远大于收益；
     * - 保留 Flow 语义：新消息落地时搜索结果自动刷新。
     *
     * 转义：SQL 里用 `\` 作为 LIKE 的转义字符，通配符 `%`/`_` 需要在调用方预处理。
     */
    @Query(
        "SELECT DISTINCT c.* FROM conversations c " +
            "LEFT JOIN messages m ON m.conversationId = c.id " +
            "WHERE c.title LIKE :pattern ESCAPE '\\' " +
            "   OR m.content LIKE :pattern ESCAPE '\\' " +
            "ORDER BY c.updatedAt DESC, c.id DESC"
    )
    fun searchConversations(pattern: String): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getConversation(id: Long): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConversation(conversation: ConversationEntity): Long

    /**
     * 会话可编辑字段的**定向**更新。
     *
     * 绝不能用 [upsertConversation] 代替：域模型 Conversation 不承载 `contextSummary` 与
     * `summaryThroughMessageId`（它们由执行器压缩历史时写入，UI 既读不到也不该改写），
     * 整行 REPLACE 会把这两列静默写成默认值 —— 已生成的摘要消失、压缩水位归零，
     * 被摘要掉的旧消息重新进入上下文且不可恢复。
     */
    @Query(
        "UPDATE conversations SET title = :title, titleAuto = :titleAuto, providerId = :providerId, " +
            "modelId = :modelId, systemPrompt = :systemPrompt, toolsEnabled = :toolsEnabled, " +
            "thinkingLevel = :thinkingLevel, updatedAt = :updatedAt WHERE id = :id"
    )
    suspend fun updateConversationFields(
        id: Long,
        title: String,
        titleAuto: Boolean,
        providerId: Long,
        modelId: String,
        systemPrompt: String,
        toolsEnabled: Boolean,
        thinkingLevel: String?,
        updatedAt: Long
    )

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversation(id: Long)

    /**
     * 删会话必须连消息一起、且在同一次事务里完成。
     * 分两步会在中间留下"会话还在、消息已空"的窗口：正在取消的回合恰好此时落库，
     * 就变成挂在已消失会话上的孤儿消息。
     */
    @Transaction
    suspend fun deleteConversationWithMessages(id: Long) {
        deleteMessagesFor(id)
        deleteConversation(id)
    }

    /** 落库前在同一个事务里确认会话仍在，避免回合收尾与删会话并发时写回孤儿消息 */
    @Transaction
    suspend fun insertMessageIfConversationExists(message: MessageEntity): Boolean {
        if (getConversation(message.conversationId) == null) return false
        insertMessage(message)
        return true
    }

    /** 某提供商名下的会话数：删除提供商前用它向用户说清会连带丢多少 */
    @Query("SELECT COUNT(*) FROM conversations WHERE providerId = :providerId")
    suspend fun countConversationsForProvider(providerId: Long): Int

    @Query("DELETE FROM messages WHERE conversationId IN " +
        "(SELECT id FROM conversations WHERE providerId = :providerId)")
    suspend fun deleteMessagesOfProvider(providerId: Long)

    @Query("DELETE FROM conversations WHERE providerId = :providerId")
    suspend fun deleteConversationsOfProvider(providerId: Long)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteMessagesFor(conversationId: Long)

    /**
     * 消息一律按 id 排序：id 是自增的写入顺序，而 `deleteMessagesFrom` / `deleteMessagesAfter`
     * 也按 id 删。若这里改成按 createdAt 排，一旦时钟回拨（或备份恢复带回旧时间戳），
     * "编辑重发"和"重新生成"就会删错行 —— 排序键和删除键必须是同一把尺子。
     */
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY id ASC")
    fun observeMessages(conversationId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY id ASC")
    suspend fun getMessages(conversationId: Long): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getMessage(id: Long): MessageEntity?

    /** 更新整条消息（多版本切换 / 追加新版本要用 REPLACE 保留其余列） */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(message: MessageEntity)

    @Query("UPDATE messages SET activeVersion = :index WHERE id = :id")
    suspend fun setActiveVersion(id: Long, index: Int)

    /** 编辑重发：删除该条及其之后的全部消息（id 自增，与列表排序一致） */
    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND id >= :fromId")
    suspend fun deleteMessagesFrom(conversationId: Long, fromId: Long): Int

    /**
     * 编辑重发：在一次事务里「删掉锚点及其之后」+「插入改过的提问」，返回新消息 id。
     *
     * 分成两次调用会留下一个会话零消息的空窗，而 [deleteEmptyConversations] 只看消息表：
     * 用户在这几十毫秒里退回列表页，整条会话就被清掉，随后插入的行因为没有外键
     * 变成挂在已消失会话上的孤儿。事务保证任何观察者看到的都是"旧尾巴没了、新消息已经在"。
     *
     * `fromId = Long.MAX_VALUE` 时删不到任何行，等价于纯插入。
     */
    @Transaction
    suspend fun replaceTailWithUserMessage(
        conversationId: Long,
        fromId: Long,
        message: MessageEntity
    ): Long {
        deleteMessagesFrom(conversationId, fromId)
        return insertMessage(message)
    }

    /** 重新生成 / 批注修订：清掉这条消息之后的整次尝试（中间 tool 行与被替换的旧答案行） */
    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND id > :afterId")
    suspend fun deleteMessagesAfter(conversationId: Long, afterId: Long): Int

    /** 最后一条用户消息 —— 重新生成 / 批注修订的锚点 */
    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId AND role = 'user' " +
            "ORDER BY id DESC LIMIT 1"
    )
    suspend fun getLastUserMessage(conversationId: Long): MessageEntity?

    /** 最后一条"最终回答"（排除只承载 tool_calls 的中间 assistant 行） */
    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId " +
            "AND role = 'assistant' AND toolCallsJson IS NULL ORDER BY id DESC LIMIT 1"
    )
    suspend fun getLastAnswerMessage(conversationId: Long): MessageEntity?

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId")
    suspend fun countMessages(conversationId: Long): Int

    /**
     * 删除所有没有任何消息的会话（含刚创建就被放弃的）。
     * 注意：只删 conversations，不触碰 messages —— 有消息的会话不会被命中。
     */
    @Query(
        "DELETE FROM conversations WHERE id NOT IN " +
            "(SELECT DISTINCT conversationId FROM messages)"
    )
    suspend fun deleteEmptyConversations(): Int

    @Query("UPDATE conversations SET title = :title, titleAuto = 0 WHERE id = :id")
    suspend fun renameConversationWithFlag(id: Long, title: String)

    /** AI 自动命名：保持 titleAuto = 1，后续仍可被新标题覆盖 */
    @Query("UPDATE conversations SET title = :title WHERE id = :id AND titleAuto = 1")
    suspend fun renameConversationAuto(id: Long, title: String)
}

@Dao
interface RequestStatDao {

    @Insert
    suspend fun insert(stat: RequestStatEntity)

    /** 全局统计（可选按时间窗过滤，sinceEpochMs = 0 表示全部） */
    @Query(
        "SELECT COUNT(*) AS total, " +
            "SUM(CASE WHEN success = 1 THEN 1 ELSE 0 END) AS successes " +
            "FROM request_stats WHERE createdAt >= :sinceEpochMs"
    )
    suspend fun summary(sinceEpochMs: Long): RequestSummary

    @Query(
        "SELECT COUNT(*) AS total, " +
            "SUM(CASE WHEN success = 1 THEN 1 ELSE 0 END) AS successes " +
            "FROM request_stats WHERE providerId = :providerId AND createdAt >= :sinceEpochMs"
    )
    suspend fun summaryForProvider(providerId: Long, sinceEpochMs: Long): RequestSummary

    /** 按提供商聚合，用于提供商列表页展示各自成功率 */
    @Query(
        "SELECT providerId, COUNT(*) AS total, " +
            "SUM(CASE WHEN success = 1 THEN 1 ELSE 0 END) AS successes, " +
            "MAX(createdAt) AS lastAt " +
            "FROM request_stats WHERE createdAt >= :sinceEpochMs GROUP BY providerId"
    )
    fun observeByProvider(sinceEpochMs: Long): Flow<List<ProviderRequestSummary>>

    @Query("DELETE FROM request_stats")
    suspend fun clearAll()
}

/** 聚合结果（Room 直接映射到 data class） */
data class RequestSummary(
    val total: Int,
    val successes: Int?
) {
    val failures: Int get() = total - (successes ?: 0)

    /** 成功率；无样本时返回 null，由 UI 显示为"—"而非误导性的 0% */
    val rate: Float? get() = if (total == 0) null else (successes ?: 0).toFloat() / total
}

data class ProviderRequestSummary(
    val providerId: Long,
    val total: Int,
    val successes: Int?,
    val lastAt: Long
) {
    val rate: Float? get() = if (total == 0) null else (successes ?: 0).toFloat() / total
}
