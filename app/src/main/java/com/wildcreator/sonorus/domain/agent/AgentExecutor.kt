package com.wildcreator.sonorus.domain.agent

import com.wildcreator.sonorus.data.local.ChatDao
import com.wildcreator.sonorus.data.local.MessageEntity
import com.wildcreator.sonorus.data.prefs.SettingsManager
import com.wildcreator.sonorus.data.remote.ProviderApiClient
import com.wildcreator.sonorus.domain.model.AnswerVersion
import com.wildcreator.sonorus.domain.model.ImageAttachment
import com.wildcreator.sonorus.domain.model.MessageVariants
import com.wildcreator.sonorus.domain.repository.ProviderRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Agent 执行器（协议无关）：
 * 发送用户消息 -> 流式接收（正文与推理分开累积）-> 若模型请求工具则本地执行并回填 -> 循环直至最终答复。
 *
 * 凭据从加密存储按需取出，仅在本方法栈内使用，不进 UI 状态、不落库。
 *
 * 三种发起方式共用 [runRound]：
 * - [send]：新用户消息；
 * - [regenerateLast]：丢弃最后一条回答的整次尝试，重问一轮，产物作为它的下一个版本；
 * - [reviseLastWithNote]：把原回答与批注指令一起送回去，产物同样作为新版本。
 */
@Singleton
class AgentExecutor @Inject constructor(
    private val chatDao: ChatDao,
    private val providerRepository: ProviderRepository,
    private val apiClient: ProviderApiClient,
    private val toolRegistry: ToolRegistry,
    private val settings: SettingsManager,
    private val json: Json
) {

    /** 本轮进行到哪一步，决定 UI 显示"思考中 X 秒"还是"输出中" */
    enum class RunPhase { IDLE, WAITING, REASONING, ANSWERING, TOOL }

    data class AgentState(
        val running: Boolean = false,
        val phase: RunPhase = RunPhase.IDLE,
        /** 本轮归属的会话；null = 从未跑过 / 已复位。单例执行器靠它把状态切给正确的页面 */
        val conversationId: Long? = null,
        /** 本轮起始时刻（epoch ms）；UI 用它做秒表，不依赖后端心跳 */
        val startedAt: Long = 0L,
        val streamingContent: String = "",
        val streamingReasoning: String = "",
        /** 工具提示，例："调用 calculator" */
        val toolLabel: String? = null,
        val error: String? = null,
        /** 本轮实际送入模型的消息条数（截断后） */
        val sentMessages: Int = 0,
        /** 当前会话累计消息条数 */
        val totalMessages: Int = 0,
        /** 当前生效的上下文长度上限，0 = 不限制 */
        val contextLimit: Int = 0
    ) {
        /**
         * 只把属于 [conversationId] 的那部分状态暴露给某个聊天页。
         *
         * AgentExecutor 是进程级单例、单回合槽位：回答可以在用户离开后继续在后台续写，
         * 这时另一个会话的聊天页绝不能把别人的流式文本 / 错误显示成自己的。
         * 归属不明（null）时原样返回 —— 那本来就是空闲或复位后的状态。
         */
        fun forConversation(conversationId: Long?): AgentState =
            if (this.conversationId == null || this.conversationId == conversationId) this
            else AgentState()

        /**
         * 上下文命中率：送入条数 / 会话总条数。
         * 无消息或未限制时返回 null —— 由 UI 显示"全部"而不是造一个假的 100%。
         */
        val contextHitRate: Float?
            get() = when {
                totalMessages == 0 -> null
                contextLimit == 0 -> null
                else -> sentMessages.toFloat() / totalMessages
            }

        /**
         * 状态文案：不支持思考的模型也至少能看到"思考中"变成"输出中"，
         * 而不是对着一屏静止文字干等。秒数由 UI 侧用 startedAt 追加。
         */
        val statusLabel: String?
            get() = when {
                !running -> null
                phase == RunPhase.TOOL -> "调用工具 ${toolLabel ?: ""}".trim()
                phase == RunPhase.REASONING -> "思考中"
                phase == RunPhase.ANSWERING -> "输出中"
                else -> "思考中"
            }
    }

    private val _state = MutableStateFlow(AgentState())
    val state: StateFlow<AgentState> = _state

    @Volatile
    private var currentJob: kotlinx.coroutines.Job? = null

    /** 停止生成：取消当前请求，已流出的内容作为一版回答落库 */
    fun stop() {
        currentJob?.cancel()
    }

    /**
     * 仅当该会话正在生成时才取消。
     * 列表页删除会话前必须调用：否则后台续写的回合会往已经删掉的会话里插孤儿消息。
     */
    fun cancelRun(conversationId: Long) {
        if (_state.value.conversationId == conversationId) stop()
    }

    /** 清掉上一次的错误提示，保留命中率等展示字段 */
    fun clearError() {
        if (_state.value.error != null) {
            _state.value = _state.value.copy(error = null)
        }
    }

    suspend fun send(
        conversationId: Long,
        userText: String,
        images: List<ImageAttachment> = emptyList()
    ) {
        val anchorId = withContext(Dispatchers.IO) {
            chatDao.insertMessage(
                MessageEntity(
                    conversationId = conversationId,
                    role = "user",
                    content = userText,
                    imagesJson = images.takeIf { it.isNotEmpty() }?.let {
                        imageJson.encodeToString(ListSerializer(ImageAttachment.serializer()), it)
                    }
                )
            )
        }
        runRound(conversationId, anchorId = anchorId, retired = null, instruction = null, note = null)
    }

    /** 重新生成最后一条回答；没有可重生的回答时返回 false，由 UI 提示用户 */
    suspend fun regenerateLast(conversationId: Long): Boolean {
        val anchor = chatDao.getLastUserMessage(conversationId) ?: return false
        runRound(conversationId, anchorId = anchor.id, retired = retiredOf(conversationId, anchor.id), instruction = null, note = null)
        return true
    }

    /**
     * 按批注修改最后一条回答。
     * @param quote 用户选中的原文片段，null 表示整条批注
     */
    suspend fun reviseLastWithNote(conversationId: Long, quote: String?, note: String): Boolean {
        val anchor = chatDao.getLastUserMessage(conversationId) ?: return false
        val retired = retiredOf(conversationId, anchor.id) ?: return false
        runRound(
            conversationId,
            anchorId = anchor.id,
            retired = retired,
            instruction = MessageVariants.revisionInstruction(quote, note),
            note = note.trim()
        )
        return true
    }

    /** 被替换掉的那次尝试：历史版本列表 + 当前生效文本 */
    private data class Retired(
        val versions: List<AnswerVersion>,
        val activeContent: String
    )

    private suspend fun retiredOf(conversationId: Long, anchorId: Long): Retired? {
        val answer = chatDao.getLastAnswerMessage(conversationId) ?: return null
        // 回答必须在这条用户消息之后 —— 否则说明本轮根本没产出
        if (answer.id <= anchorId) return null
        return Retired(
            versions = MessageVariants.decode(
                answer.versionsJson,
                AnswerVersion(content = answer.content, createdAt = answer.createdAt)
            ),
            activeContent = answer.content
        )
    }

    private suspend fun runRound(
        conversationId: Long,
        anchorId: Long,
        retired: Retired?,
        instruction: String?,
        note: String?
    ) = withContext(Dispatchers.IO) {
        currentJob = currentCoroutineContext().job
        val contentSb = StringBuilder()
        val reasoningSb = StringBuilder()
        try {
            // 会话可能已被删除（自动清理或列表页手动删）。这里必须显式报错：
            // 用户消息已经先落库，静默返回会让界面表现为"点了发送但毫无反应"。
            val conversation = chatDao.getConversation(conversationId) ?: run {
                _state.value = AgentState(conversationId = conversationId, error = "会话已不存在，请重新发起")
                return@withContext
            }
            val credentials = providerRepository.credentials(conversation.providerId) ?: run {
                _state.value = AgentState(conversationId = conversationId, error = "缺少 API Key 或提供商不存在")
                return@withContext
            }

            _state.value = _state.value.copy(
                running = true,
                phase = RunPhase.WAITING,
                conversationId = conversationId,
                startedAt = System.currentTimeMillis(),
                streamingContent = "",
                streamingReasoning = "",
                toolLabel = null,
                error = null
            )

            val maxIterations = 8
            var iterations = 0
            // 思考等级：会话级覆盖优先，其次全局默认。
            // 注意 conversation 是 Room 实体，thinkingLevel 为 String?，需显式转换；
            // 存量数据的非法值（理论上不会出现）按"跟随全局"处理而不是崩溃。
            val thinking = conversation.thinkingLevel
                ?.let { name -> runCatching { ThinkingLevel.valueOf(name) }.getOrNull() }
                ?: settings.thinkingLevel.first()
            val historyLimit = settings.contextLimit.first()
            _state.value = _state.value.copy(
                contextLimit = historyLimit,
                totalMessages = chatDao.countMessages(conversationId)
            )

            // 重新生成 / 批注修订：先把锚点之后的整次尝试清掉（旧答案行已读进 retired.versions，
            // 稍后作为历史版本并入新行），否则遗留的 tool 行会污染下一轮上下文。
            if (retired != null) {
                chatDao.deleteMessagesAfter(conversationId, anchorId)
            }

            while (iterations < maxIterations) {
                iterations++

                val toolsEnabled = conversation.toolsEnabled && credentials.protocol.supportsTools
                val history = buildHistory(conversationId, historyLimit, upToId = anchorId)
                    .let { base ->
                        when {
                            // 批注修订：把原回答与批注指令作为本轮的临时上下文，都不落库
                            retired != null && instruction != null ->
                                base + UnifiedMessage(role = UnifiedRole.ASSISTANT, text = retired.activeContent) +
                                    UnifiedMessage(role = UnifiedRole.USER, text = instruction)
                            // 单纯重新生成：原回答不进上下文，等于重问一次
                            retired != null -> base
                            else -> base
                        }
                    }
                val request = UnifiedChatRequest(
                    model = conversation.modelId,
                    systemPrompt = conversation.systemPrompt,
                    messages = history,
                    tools = if (toolsEnabled) toolRegistry.specs() else null,
                    thinkingLevel = thinking
                )
                _state.value = _state.value.copy(
                    sentMessages = history.size,
                    totalMessages = chatDao.countMessages(conversationId)
                )

                contentSb.setLength(0)
                reasoningSb.setLength(0)
                // 每轮单独记收尾原因：工具调用轮会以 tool_calls 收尾，不能污染最终轮的判定
                var iterationFinish: FinishReason? = null
                val toolCalls = sortedMapOf<Int, ToolCallBuilder>()
                var streamError: String? = null

                apiClient.streamChat(credentials, request).collect { event ->
                    when (event) {
                        is ProviderApiClient.StreamEvent.Delta -> {
                            val delta = event.delta
                            delta.finish?.let { iterationFinish = it }
                            delta.reasoning?.let { piece ->
                                reasoningSb.append(piece)
                                _state.value = _state.value.copy(
                                    phase = RunPhase.REASONING,
                                    streamingReasoning = reasoningSb.toString()
                                )
                            }
                            delta.text?.let { piece ->
                                contentSb.append(piece)
                                _state.value = _state.value.copy(
                                    phase = RunPhase.ANSWERING,
                                    streamingContent = contentSb.toString()
                                )
                            }
                            delta.toolCalls?.forEach { tc ->
                                val builder = toolCalls.getOrPut(tc.index) { ToolCallBuilder() }
                                tc.id?.let { builder.id = it }
                                tc.nameSet?.let { builder.name = it }
                                tc.nameChunk?.let { builder.name += it }
                                tc.argsSet?.let { builder.arguments = it }
                                tc.argsChunk?.let { builder.arguments += it }
                            }
                        }
                        is ProviderApiClient.StreamEvent.Error -> streamError = event.message
                        ProviderApiClient.StreamEvent.Done -> Unit
                    }
                }

                if (streamError != null) {
                    _state.value = AgentState(conversationId = conversationId, error = streamError)
                    return@withContext
                }

                if (toolCalls.isNotEmpty()) {
                    // 1) 落库 assistant 的工具调用
                    val calls = toolCalls.values.map { it.toToolCall() }
                    chatDao.insertMessage(
                        MessageEntity(
                            conversationId = conversationId,
                            role = "assistant",
                            content = contentSb.toString(),
                            toolCallsJson = json.encodeToString(calls)
                        )
                    )
                    // 2) 本地执行并落库工具结果
                    for (call in calls) {
                        _state.value = _state.value.copy(
                            phase = RunPhase.TOOL,
                            toolLabel = call.name,
                            streamingContent = ""
                        )
                        val tool = toolRegistry.find(call.name)
                        val result = runCatching {
                            tool?.execute(call.arguments)
                                ?: "错误：未注册的工具 ${call.name}"
                        }.getOrElse { "工具执行异常：${it.message}" }
                        chatDao.insertMessage(
                            MessageEntity(
                                conversationId = conversationId,
                                role = "tool",
                                content = result,
                                toolCallId = call.id,
                                toolName = call.name
                            )
                        )
                    }
                    _state.value = _state.value.copy(
                        phase = RunPhase.WAITING,
                        streamingContent = "",
                        startedAt = System.currentTimeMillis()
                    )
                    continue // 回填后再次请求模型
                }

                // 最终答复
                val finalText = contentSb.toString()
                if (finalText.isBlank()) {
                    _state.value = AgentState(
                        conversationId = conversationId,
                        error = "模型返回空内容（可能是协议或端点不匹配）"
                    )
                    return@withContext
                }
                persistAnswer(conversationId, retired, note, finalText, reasoningSb.toString())

                // 上游以 LENGTH / BLOCKED 收尾时，这段文本并不完整。
                // 已生成的部分照旧落库（不丢用户已经拿到的内容），但必须把"不完整"说出来 ——
                // 静默存成完整答案是这批问题里最难被发现的一类，因为它看起来完全正常。
                _state.value = when (iterationFinish) {
                    FinishReason.LENGTH -> AgentState(
                        conversationId = conversationId,
                        error = "回答达到模型的长度上限被截断，已保存截断到的部分；可以再发一句「继续」"
                    )
                    FinishReason.BLOCKED -> AgentState(
                        conversationId = conversationId,
                        error = "回答被上游内容策略拦截，可能不完整"
                    )
                    else -> AgentState(conversationId = conversationId)
                }
                return@withContext
            }
            _state.value = AgentState(
                conversationId = conversationId,
                error = "工具调用轮次超限（$maxIterations）"
            )
        } catch (e: CancellationException) {
            // 用户点了停止：已流出的内容仍算一版回答，不能丢
            if (contentSb.isNotEmpty()) {
                withContext(NonCancellable) {
                    persistAnswer(
                        conversationId, retired, note,
                        contentSb.toString(), reasoningSb.toString()
                    )
                }
            }
            _state.value = AgentState(
                conversationId = conversationId,
                totalMessages = _state.value.totalMessages,
                contextLimit = _state.value.contextLimit
            )
            throw e
        } finally {
            currentJob = null
        }
    }

    /** 写入最终回答；有被替换的尝试时把新版本追加到同一份版本列表里 */
    private suspend fun persistAnswer(
        conversationId: Long,
        retired: Retired?,
        note: String?,
        content: String,
        reasoning: String
    ) {
        // 会话可能已被删除：列表页删会话会取消本回合，但落库跑在 NonCancellable 里挡不住，
        // 不在这里收口就会给已删会话留下孤儿消息。
        if (chatDao.getConversation(conversationId) == null) return
        val next = AnswerVersion(
            content = content,
            reasoning = reasoning.takeIf { it.isNotBlank() },
            note = note?.takeIf { it.isNotBlank() }
        )
        val versions = retired?.let { MessageVariants.append(it.versions, next) } ?: listOf(next)
        val (mirror, versionsJson, active) = MessageVariants.entry(versions, versions.lastIndex)
        chatDao.insertMessage(
            MessageEntity(
                conversationId = conversationId,
                role = "assistant",
                content = mirror,
                versionsJson = versionsJson,
                activeVersion = active
            )
        )
        chatDao.getConversation(conversationId)?.let {
            chatDao.upsertConversation(it.copy(updatedAt = System.currentTimeMillis()))
        }
    }

    /**
     * Room 消息历史 -> 协议无关的统一消息序列。
     *
     * @param limit 最多保留的历史消息条数，0 = 不限制。
     * @param upToId 只取到这条消息为止（重新生成 / 批注修订要排除被替换的旧答案）。
     *   截断后若首条不是 user 消息，会继续向后丢弃 ——
     *   Anthropic 要求 messages 首条为 user，且孤立的 tool 结果会导致上游 400。
     */
    private suspend fun buildHistory(
        conversationId: Long,
        limit: Int,
        upToId: Long = Long.MAX_VALUE
    ): List<UnifiedMessage> {
        var entities = chatDao.getMessages(conversationId).filter { it.id <= upToId }
        if (limit > 0 && entities.size > limit) {
            entities = entities.takeLast(limit)
            while (entities.isNotEmpty() && entities.first().role != "user") {
                entities = entities.drop(1)
            }
        }

        val result = mutableListOf<UnifiedMessage>()
        entities.forEach { m ->
            when (m.role) {
                "user" -> result += UnifiedMessage(
                    role = UnifiedRole.USER,
                    text = m.content,
                    images = m.imagesJson?.let {
                        runCatching {
                            imageJson.decodeFromString(ListSerializer(ImageAttachment.serializer()), it)
                        }.getOrNull()
                    }?.map { ImagePart(mimeType = it.mimeType, base64 = it.base64) }
                )
                "assistant" -> {
                    val calls = m.toolCallsJson?.let {
                        runCatching { json.decodeFromString<List<ToolCall>>(it) }.getOrNull()
                    }
                    result += UnifiedMessage(
                        role = UnifiedRole.ASSISTANT,
                        text = m.content.ifBlank { null },
                        toolCalls = calls
                    )
                }
                "tool" -> result += UnifiedMessage(
                    role = UnifiedRole.TOOL,
                    text = m.content,
                    toolCallId = m.toolCallId,
                    toolName = m.toolName
                )
            }
        }
        return result
    }

    private class ToolCallBuilder {
        var id: String = ""
        var name: String = ""
        var arguments: String = ""
        fun toToolCall() = ToolCall(id = id, name = name, arguments = arguments)
    }

    private companion object {
        /** 图片编解码复用生产 Json 配置；图片字段固定，无需 ignoreUnknownKeys 之外的处理 */
        val imageJson = Json { ignoreUnknownKeys = true }
    }
}
