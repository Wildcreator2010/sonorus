package com.wildcreator.sonorus.domain.agent

import com.wildcreator.sonorus.data.local.ChatDao
import com.wildcreator.sonorus.data.local.MessageEntity
import com.wildcreator.sonorus.data.prefs.SettingsManager
import com.wildcreator.sonorus.data.remote.ProviderApiClient
import com.wildcreator.sonorus.data.remote.redactSecrets
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

    @Volatile
    private var tokenScale = 1.0

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
            chatDao.insertMessage(userMessageEntity(conversationId, userText, images))
        }
        runRound(conversationId, anchorId = anchorId, retired = null, instruction = null, note = null)
    }

    /**
     * 编辑重发：删掉被编辑的提问及其之后的整段尾巴，同时插入改过的提问。
     *
     * 删与插必须落在同一次事务里（[ChatDao.replaceTailWithUserMessage]）：分两步会留下
     * "会话零消息"的空窗，而会话列表页的空会话清理只看消息表 —— 用户退回列表的那几十毫秒，
     * 整条会话就被删掉，随后插入的行因为没有外键成为孤儿。
     */
    suspend fun editAndResend(
        conversationId: Long,
        fromMessageId: Long,
        userText: String,
        images: List<ImageAttachment> = emptyList()
    ) {
        val anchorId = withContext(Dispatchers.IO) {
            chatDao.replaceTailWithUserMessage(
                conversationId,
                fromMessageId,
                userMessageEntity(conversationId, userText, images)
            )
        }
        runRound(conversationId, anchorId = anchorId, retired = null, instruction = null, note = null)
    }

    private fun userMessageEntity(
        conversationId: Long,
        userText: String,
        images: List<ImageAttachment>
    ): MessageEntity = MessageEntity(
        conversationId = conversationId,
        role = "user",
        content = userText,
        imagesJson = images.takeIf { it.isNotEmpty() }?.let {
            imageJson.encodeToString(ListSerializer(ImageAttachment.serializer()), it)
        }
    )

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
            // 业务级放弃条件：识别重复调用与反复失败，别把 8 轮全烧在同一个不可能成功的调用上
            val toolGuard = ToolCallGuard()
            // 思考等级：会话级覆盖优先，其次全局默认。
            // 注意 conversation 是 Room 实体，thinkingLevel 为 String?，需显式转换；
            // 存量数据的非法值（理论上不会出现）按"跟随全局"处理而不是崩溃。
            val thinking = conversation.thinkingLevel
                ?.let { name -> runCatching { ThinkingLevel.valueOf(name) }.getOrNull() }
                ?: settings.thinkingLevel.first()
            val historyLimit = settings.contextLimit.first()
            maybeCompressContext(conversation, credentials, thinking, anchorId)
            val activeConversation = chatDao.getConversation(conversationId) ?: conversation
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
                val history = buildHistory(conversationId, historyLimit, upToId = anchorId, summaryThroughId = activeConversation.summaryThroughMessageId)
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
                    // 纪律约束在这里追加，而不是只改默认 prompt：改过 prompt 的会话、
                    // 升级前就存在的老会话都必须拿到同样的约束，且顺序恒为 人设→摘要→纪律
                    systemPrompt = SystemPromptGuard.compose(
                        activeConversation.systemPrompt,
                        activeConversation.contextSummary
                    ),
                    messages = history,
                    tools = if (toolsEnabled) toolRegistry.specs() else null,
                    thinkingLevel = thinking
                )
                val estimatedRequestTokens = estimateTokens(activeConversation.systemPrompt) + history.sumOf { estimateTokens(it.text.orEmpty()) }
                _state.value = _state.value.copy(
                    sentMessages = history.size,
                    totalMessages = chatDao.countMessages(conversationId)
                )

                contentSb.setLength(0)
                reasoningSb.setLength(0)
                // 每轮新建：过滤状态只在单次流内有效，跨轮复用会把下一轮正文吃掉
                val visibleFilter = VisibleTextFilter()
                // 每轮单独记收尾原因：工具调用轮会以 tool_calls 收尾，不能污染最终轮的判定
                var iterationFinish: FinishReason? = null
                val toolCalls = sortedMapOf<Int, ToolCallBuilder>()
                var streamError: String? = null

                apiClient.streamChat(credentials, request).collect { event ->
                    when (event) {
                        is ProviderApiClient.StreamEvent.Delta -> {
                            val delta = event.delta
                            delta.inputTokens?.let { actual ->
                                if (estimatedRequestTokens > 0) tokenScale = (actual.toDouble() / estimatedRequestTokens).coerceIn(0.5, 2.0)
                            }
                            delta.finish?.let { iterationFinish = it }
                            delta.reasoning?.let { piece ->
                                reasoningSb.append(piece)
                                _state.value = _state.value.copy(
                                    phase = RunPhase.REASONING,
                                    streamingReasoning = reasoningSb.toString()
                                )
                            }
                            delta.text?.let { piece ->
                                // 思维链可能被网关塞进 content（而不是 reasoning 字段）：
                                // 用户可见文本一律先过过滤器再累积、再进 UI 状态
                                val visible = visibleFilter.accept(piece)
                                if (visible.isNotEmpty()) {
                                    contentSb.append(visible)
                                    _state.value = _state.value.copy(
                                        phase = RunPhase.ANSWERING,
                                        streamingContent = contentSb.toString()
                                    )
                                }
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

                // 冲掉过滤器里滞留的尾巴：正常文本会补上，未闭合的思维链会被丢弃
                visibleFilter.finish().let { tail ->
                    if (tail.isNotEmpty()) contentSb.append(tail)
                }

                if (streamError != null) {
                    failRound(conversationId, retired, streamError)
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
                    //
                    // 即使本轮要终止，也必须为**每一个** call 写回一条 tool 结果：
                    // assistant 的 tool_calls 与 tool 结果是配对结构，少一条就是孤立 tool_result，
                    // Anthropic 会直接 400，那段历史从此不可用。
                    var stopReason: String? = null
                    for (call in calls) {
                        _state.value = _state.value.copy(
                            phase = RunPhase.TOOL,
                            toolLabel = call.name,
                            streamingContent = ""
                        )
                        val outcome = runToolCall(toolGuard, call, stopReason)
                        if (stopReason == null) stopReason = outcome.stopReason
                        chatDao.insertMessage(
                            MessageEntity(
                                conversationId = conversationId,
                                role = "tool",
                                content = outcome.resultText,
                                toolCallId = call.id,
                                toolName = call.name
                            )
                        )
                    }
                    if (stopReason != null) {
                        failRound(conversationId, retired, stopReason)
                        return@withContext
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
                    // 全被过滤掉说明模型把答复写进了思维链块；此时绝不能退回显示原文，
                    // 否则就是用户投诉的"把思考过程当回复"
                    val emptyError = if (visibleFilter.strippedAnything) {
                        "模型这一轮只返回了思考内容，没有可展示的答复（思考内容已在客户端剥离）"
                    } else {
                        "模型返回空内容（可能是协议或端点不匹配）"
                    }
                    failRound(conversationId, retired, emptyError)
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
            failRound(
                conversationId,
                retired,
                "工具调用轮次超限（$maxIterations）"
            )
        } catch (e: CancellationException) {
            // 用户点了停止：已流出的内容仍算一版回答，不能丢；一条都没流出时要把旧答案放回去
            withContext(NonCancellable) {
                if (contentSb.isNotEmpty()) {
                    persistAnswer(
                        conversationId, retired, note,
                        contentSb.toString(), reasoningSb.toString()
                    )
                } else {
                    restoreRetired(conversationId, retired)
                }
            }
            _state.value = AgentState(
                conversationId = conversationId,
                totalMessages = _state.value.totalMessages,
                contextLimit = _state.value.contextLimit
            )
            throw e
        } catch (e: Exception) {
            // 非取消的意外失败（Room 写库失败、DataStore IO 异常、畸形载荷）也必须收口：
            // running 卡在 true 会锁死**所有**会话的发送闸门（UI 读的是全局单份 state），用户只能重启应用。
            // 这里不再尝试任何数据库写入 —— 失败原因很可能正是数据库，再写一次只会把异常抛回去。
            _state.value = AgentState(
                conversationId = conversationId,
                error = "本轮异常终止：${redactSecrets(e.message ?: e.javaClass.simpleName)}"
            )
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

    /** 单个工具调用的产出：回填给模型的文本 + 这条是否要求终止本轮 */
    private data class ToolCallOutcome(val resultText: String, val stopReason: String?)

    /**
     * 解析工具名 → 登记并计数 → 执行 → 登记成败。
     *
     * 三条终止条件都在这里收口：解析不出已注册工具（确定性错误，重试不可能成功）、
     * [ToolCallGuard] 判定原地打转、同一工具反复失败。线上那个死循环正是"把错误喂回模型 →
     * 模型原样重调"刷满 8 轮造成的，所以这里宁可直接结束本轮。
     * 计数一律用 resolve 出来的注册名，用模型给的名字会被拼写漂移绕过。
     */
    private suspend fun runToolCall(
        guard: ToolCallGuard,
        call: ToolCall,
        alreadyStopped: String?
    ): ToolCallOutcome {
        if (alreadyStopped != null) return ToolCallOutcome("错误：本轮已终止，该调用未执行", null)

        val tool = toolRegistry.resolve(call.name)
            ?: return ToolCallOutcome(
                resultText = "错误：未注册的工具 ${call.name}",
                stopReason = "模型请求了未注册的工具 ${call.name}" +
                    "（可用工具：" + toolRegistry.all().joinToString("、") { it.name } + "）"
            )

        val verdict = guard.register(call, tool.name)
        if (verdict is ToolCallGuard.Verdict.Stop) {
            return ToolCallOutcome("错误：本轮已终止，该调用未执行", verdict.reason)
        }

        val result = runCatching { tool.execute(call.arguments) }
            .getOrElse { return abortedToolCall(guard, tool.name, "工具执行异常：${it.message}") }
        val failureVerdict = guard.recordResult(tool.name, failed = !result.ok)
        return ToolCallOutcome(
            resultText = result.text,
            stopReason = (failureVerdict as? ToolCallGuard.Verdict.Stop)?.reason
        )
    }

    /** 抛异常的工具调用：异常本身没有 ToolResult.ok 可用，按失败计入计数 */
    private suspend fun abortedToolCall(
        guard: ToolCallGuard,
        toolName: String,
        message: String
    ): ToolCallOutcome {
        val verdict = guard.recordResult(toolName, failed = true)
        return ToolCallOutcome(message, (verdict as? ToolCallGuard.Verdict.Stop)?.reason)
    }

    /**
     * 把「重新生成 / 按批注修改」前读出的旧回答原样写回。
     *
     * runRound 开头就 deleteMessagesAfter 删掉了旧回答行，此后旧内容只活在内存里的
     * [Retired] 中。任何没产出新答复就收口的路径都必须先走这里，否则用户看到的是
     * 那条回答凭空消失 —— 失败不该顺带删内容。
     */
    private suspend fun restoreRetired(conversationId: Long, retired: Retired?) {
        if (retired == null) return
        if (chatDao.getConversation(conversationId) == null) return
        val (mirror, versionsJson, active) =
            MessageVariants.entry(retired.versions, retired.versions.lastIndex)
        chatDao.insertMessage(
            MessageEntity(
                conversationId = conversationId,
                role = "assistant",
                content = mirror,
                versionsJson = versionsJson,
                activeVersion = active
            )
        )
    }

    /** 本轮以错误收口：先把旧答案放回去，再把错误交给界面 */
    private suspend fun failRound(conversationId: Long, retired: Retired?, error: String) {
        restoreRetired(conversationId, retired)
        _state.value = AgentState(conversationId = conversationId, error = error)
    }

    /**
     * When the conservative estimate approaches 900k tokens, summarize only
     * messages before the current anchor. Original rows remain untouched.
     */
    private suspend fun maybeCompressContext(
        conversation: com.wildcreator.sonorus.data.local.ConversationEntity,
        credentials: com.wildcreator.sonorus.domain.model.ProviderCredentials,
        thinking: ThinkingLevel,
        anchorId: Long
    ) {
        val rows = chatDao.getMessages(conversation.id)
        val eligible = rows.filter { it.id > conversation.summaryThroughMessageId && it.id < anchorId }
        val priorSummary = conversation.contextSummary.orEmpty()
        val estimate = ((estimateTokens(conversation.systemPrompt) + estimateTokens(priorSummary) +
            eligible.sumOf { estimateTokens(it.content) + (it.imagesJson?.length ?: 0) / 4 }) * tokenScale).toInt()
        if (estimate < COMPRESSION_TRIGGER_TOKENS || eligible.isEmpty()) return

        var retainedTokens = 0
        var firstRetainedIndex = eligible.size
        for (index in eligible.indices.reversed()) {
            val row = eligible[index]
            val rowTokens = estimateTokens(row.content) + (row.imagesJson?.length ?: 0) / 4
            if (retainedTokens + rowTokens > COMPRESSION_TARGET_TOKENS && firstRetainedIndex < eligible.size) break
            retainedTokens += rowTokens
            firstRetainedIndex = index
        }
        if (firstRetainedIndex == 0) return
        val toSummarize = eligible.take(firstRetainedIndex)
        val summaryMessages = buildHistory(
            conversation.id, 0, upToId = toSummarize.last().id,
            summaryThroughId = conversation.summaryThroughMessageId
        )
        if (summaryMessages.isEmpty()) return
        val messagesToSummarize = if (priorSummary.isBlank()) summaryMessages else
            listOf(UnifiedMessage(role = UnifiedRole.USER, text = "Existing summary:\n$priorSummary")) + summaryMessages
        val summaryRequest = UnifiedChatRequest(
            model = conversation.modelId,
            systemPrompt = SUMMARY_SYSTEM_PROMPT,
            messages = messagesToSummarize,
            tools = null,
            maxTokens = SUMMARY_MAX_TOKENS,
            thinkingLevel = ThinkingLevel.OFF
        )
        val summary = StringBuilder()
        var error: String? = null
        try {
            apiClient.streamChat(credentials, summaryRequest).collect { event ->
                when (event) {
                    is ProviderApiClient.StreamEvent.Delta -> event.delta.text?.let(summary::append)
                    is ProviderApiClient.StreamEvent.Error -> error = event.message
                    ProviderApiClient.StreamEvent.Done -> Unit
                }
            }
        } catch (_: Exception) {
            error = "summary request failed"
        }
        if (error == null && summary.isNotBlank()) {
            chatDao.getConversation(conversation.id)?.let { current ->
                chatDao.upsertConversation(
                    current.copy(
                        contextSummary = summary.toString().trim(),
                        summaryThroughMessageId = toSummarize.last().id
                    )
                )
            }
        }
    }

    private fun estimateTokens(text: String): Int = TokenEstimator.estimate(text)

    /**
     * Room 消息历史 -> 协议无关的统一消息序列.
     *
     * @param limit 最多保留的历史消息条数，0 = 不限制。
     * @param upToId 只取到这条消息为止（重新生成 / 批注修订要排除被替换的旧答案）。
     *   截断后若首条不是 user 消息，会继续向后丢弃 ——
     *   Anthropic 要求 messages 首条为 user，且孤立的 tool 结果会导致上游 400。
     */
    private suspend fun buildHistory(
        conversationId: Long,
        limit: Int,
        upToId: Long = Long.MAX_VALUE,
        summaryThroughId: Long = 0
    ): List<UnifiedMessage> {
        var entities = chatDao.getMessages(conversationId).filter { it.id > summaryThroughId && it.id <= upToId }
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
        const val COMPRESSION_TRIGGER_TOKENS = 900_000
        const val SUMMARY_MAX_TOKENS = 16_384
        const val COMPRESSION_TARGET_TOKENS = 600_000
        const val SUMMARY_SYSTEM_PROMPT = "Summarize the earlier conversation faithfully. Preserve decisions, requirements, facts, unresolved questions, and tool results. Be concise and structured; do not invent details."

        /** 图片编解码复用生产 Json 配置；图片字段固定，无需 ignoreUnknownKeys 之外的处理 */
        val imageJson = Json { ignoreUnknownKeys = true }
    }
}
