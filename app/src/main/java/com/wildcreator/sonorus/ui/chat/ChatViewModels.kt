package com.wildcreator.sonorus.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wildcreator.sonorus.data.prefs.SettingsManager
import com.wildcreator.sonorus.di.AppScope
import com.wildcreator.sonorus.domain.agent.AgentExecutor
import com.wildcreator.sonorus.domain.agent.ChatTitleGenerator
import com.wildcreator.sonorus.domain.agent.ThinkingLevel
import com.wildcreator.sonorus.domain.model.ChatMessage
import com.wildcreator.sonorus.domain.model.ChatRole
import com.wildcreator.sonorus.domain.model.Conversation
import com.wildcreator.sonorus.domain.model.ConversationExporter
import com.wildcreator.sonorus.domain.model.ImageAttachment
import com.wildcreator.sonorus.domain.repository.ChatRepository
import com.wildcreator.sonorus.domain.repository.ProviderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 新建会话的默认标题；AI 摘要生成后会被替换 */
const val PLACEHOLDER_TITLE = "新会话"

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ConversationListViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val providerRepository: ProviderRepository,
    private val agentExecutor: AgentExecutor,
    private val settings: SettingsManager
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    /** 列表页搜索栏绑定；空串 = 展示全部会话 */
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /**
     * 搜索结果：用 flatMapLatest 确保切换关键字时及时取消上一个订阅，
     * 避免“已删除关键字”的旧结果因时序晚到而注入列表。
     */
    val conversations: StateFlow<List<Conversation>> = _searchQuery
        .flatMapLatest { q -> chatRepository.searchConversations(q) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun purgeEmptyConversations() {
        // 有回合在跑时不清：那一轮可能还没把首条用户消息落库，此刻清理会连会话一起删掉。
        // 宁可漏删，等下次列表可见再清。
        if (agentExecutor.state.value.running) return
        viewModelScope.launch { chatRepository.purgeEmptyConversations() }
    }

    fun setSearchQuery(query: String) {
        // 限制长度：避免用户贴入巨型文本导致 LIKE 扫表变慢
        _searchQuery.value = query.take(120)
    }

    fun clearSearch() {
        _searchQuery.value = ""
    }

    /** 用默认 提供商+模型 新建会话；未配置时回调 onNeedProvider 引导用户先去添加提供商 */
    fun createConversation(onCreated: (Long) -> Unit, onNeedProvider: () -> Unit) {
        viewModelScope.launch {
            val providerId = settings.lastProviderId.first()
            val modelId = settings.lastModelId.first()
            if (providerId == null || modelId == null ||
                providerRepository.getProvider(providerId) == null
            ) {
                onNeedProvider()
                return@launch
            }
            val systemPrompt = settings.defaultSystemPrompt.first()
            val id = chatRepository.createConversation(
                title = PLACEHOLDER_TITLE,
                providerId = providerId,
                modelId = modelId,
                systemPrompt = systemPrompt
            )
            onCreated(id)
        }
    }

    /** 用户手动重命名（同时关闭 AI 自动命名） */
    fun rename(id: Long, title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { chatRepository.renameConversation(id, trimmed) }
    }

    fun delete(id: Long) {
        // 先掐掉该会话可能正在后台续写的回合，否则它会把消息写回一个已删的会话
        agentExecutor.cancelRun(id)
        viewModelScope.launch { chatRepository.deleteConversation(id) }
    }
}

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val chatRepository: ChatRepository,
    private val agentExecutor: AgentExecutor,
    private val titleGenerator: ChatTitleGenerator,
    @AppScope private val runScope: CoroutineScope
) : ViewModel() {

    private val conversationId: Long = checkNotNull(savedStateHandle["conversationId"])

    val messages: StateFlow<List<ChatMessage>> = chatRepository.observeMessages(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * 只显示属于本会话的那份执行器状态：
     * 回答在用户离开后仍会继续（见 [runScope]），不能把别的会话的流式文本显示在这里。
     */
    val agentState: StateFlow<AgentExecutor.AgentState> = agentExecutor.state
        .map { it.forConversation(conversationId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AgentExecutor.AgentState())

    /**
     * 执行器是单槽位（currentJob / state 都只有一份），全局同一时刻只允许一轮。
     * 本会话空闲但别的会话正在后台续写时必须显式告诉用户，否则点发送会毫无反应。
     */
    val runningElsewhere: StateFlow<Boolean> = agentExecutor.state
        .map { it.running && it.conversationId != conversationId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _conversation = MutableStateFlow<Conversation?>(null)
    val conversation: StateFlow<Conversation?> = _conversation.asStateFlow()

    init {
        refreshConversation()
    }

    /**
     * 发送一条消息。
     * @param images 待发送图片（base64 不含 data: 前缀），最多由 UI 侧限制数量
     *
     * 回合跑在 [runScope] 上：用户退出聊天页只是销毁 ViewModel，
     * 挂在 viewModelScope 会把 SSE 流取消在半截回答处。
     */
    fun send(text: String, images: List<ImageAttachment> = emptyList()) {
        val trimmed = text.trim()
        // 纯图片（无文字）也允许发送
        if ((trimmed.isEmpty() && images.isEmpty()) || agentExecutor.state.value.running) return
        runScope.launch {
            val isAutoTitle = chatRepository.getConversation(conversationId)?.titleAuto == true
            try {
                agentExecutor.send(conversationId, trimmed, images)
            } catch (e: CancellationException) {
                // 用户点了停止：内容已在执行器里落库，标题生成就别做了
                return@launch
            }
            refreshConversation()

            // 标题仅在"仍处于自动命名 + 本轮确实产出了回复"时才生成，避免失败重试覆盖标题
            if (!isAutoTitle) return@launch
            val conv = chatRepository.getConversation(conversationId) ?: return@launch
            // 错误必须是本轮的：执行器全局只有一份 state，别的会话此刻可能已经报错并接管了它
            val afterRun = agentExecutor.state.value
            if (afterRun.error != null && afterRun.conversationId == conversationId) return@launch
            if (!chatRepository.hasMessages(conversationId)) return@launch

            val title = titleGenerator.generate(
                providerId = conv.providerId,
                modelId = conv.modelId,
                seed = trimmed
            ) ?: fallbackTitle(trimmed)
            ?: return@launch

            chatRepository.renameConversationAuto(conversationId, title)
            refreshConversation()
        }
    }

    /** AI 标题生成失败时的本地兜底：截取首条用户消息 */
    private fun fallbackTitle(text: String): String? =
        text.trim().replace(Regex("\\s+"), " ").take(20).ifBlank { null }

    /** 修改本会话的 system prompt */
    fun updateSystemPrompt(prompt: String) {
        viewModelScope.launch {
            val conv = chatRepository.getConversation(conversationId) ?: return@launch
            chatRepository.updateConversation(conv.copy(systemPrompt = prompt))
            refreshConversation()
        }
    }

    /**
     * 会话级思考等级覆盖。传 null 表示回退到全局默认。
     * 只影响后续请求，不重发历史消息。
     */
    fun updateThinkingLevel(level: ThinkingLevel?) {
        viewModelScope.launch {
            val conv = chatRepository.getConversation(conversationId) ?: return@launch
            chatRepository.updateConversation(conv.copy(thinkingLevel = level))
            refreshConversation()
        }
    }

    /** 本会话是否启用本地工具（计算器/时间等） */
    fun updateToolsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val conv = chatRepository.getConversation(conversationId) ?: return@launch
            chatRepository.updateConversation(conv.copy(toolsEnabled = enabled))
            refreshConversation()
        }
    }

    /** 清空当前会话的输入错误提示，便于用户直接重试 */
    fun dismissError() {
        agentExecutor.clearError()
    }

    /** 停止本轮生成：已流出的文本作为一版回答落库 */
    fun stop() {
        agentExecutor.stop()
    }

    /** 重新生成最后一条回答；本轮没有回答时回调 onNothing */
    fun regenerate(onNothing: () -> Unit) {
        if (agentExecutor.state.value.running) return
        runScope.launch {
            try {
                if (!agentExecutor.regenerateLast(conversationId)) onNothing()
            } catch (e: CancellationException) {
                // 用户主动停止，静默
            }
        }
    }

    /**
     * 按批注修改最后一条回答。
     * @param quote 用户选中的原文片段；null = 针对整条回答
     */
    fun reviseWithNote(quote: String?, note: String, onNothing: () -> Unit) {
        if (agentExecutor.state.value.running || note.isBlank()) return
        runScope.launch {
            try {
                if (!agentExecutor.reviseLastWithNote(conversationId, quote, note)) onNothing()
            } catch (e: CancellationException) {
            }
        }
    }

    /** 切换某条回答的生效版本（content 列会同步） */
    fun switchVersion(messageId: Long, index: Int) {
        if (agentState.value.running) return
        viewModelScope.launch { chatRepository.setActiveVersion(messageId, index) }
    }

    /**
     * 编辑已发送的提示词并重发：删除该条及其之后的全部消息。
     * 图片沿用原消息 —— 重发的目的是改文字，不该逼用户重新选图。
     */
    fun editAndResend(messageId: Long, newText: String) {
        if (agentExecutor.state.value.running) return
        val trimmed = newText.trim()
        runScope.launch {
            val target = chatRepository.getMessage(messageId) ?: return@launch
            if (target.role != ChatRole.USER) return@launch
            chatRepository.deleteMessagesFrom(conversationId, messageId)
            send(trimmed, target.images)
        }
    }

    /**
     * 清空本会话的全部消息（保留会话与配置）。
     * 会话仍在自动命名状态时，标题也一并回到占位文案 ——
     * 否则会留下一个"标题来自已删除内容"的误导性名字。
     */
    fun clearMessages() {
        viewModelScope.launch {
            chatRepository.clearMessages(conversationId)
            val conv = chatRepository.getConversation(conversationId) ?: return@launch
            if (conv.titleAuto) {
                chatRepository.renameConversationAuto(conversationId, PLACEHOLDER_TITLE)
            }
            refreshConversation()
        }
    }

    /** AI 自动命名后刷新本地会话快照 */
    private fun refreshConversation() {
        viewModelScope.launch {
            _conversation.value = chatRepository.getConversation(conversationId)
        }
    }

    /**
     * 以指定格式导出本会话，回调参数 = (文本内容, 默认文件名不含扩展名)。
     *
     * 设计要点：
     * - 只在调用时读取一次 messages 快照，避免流式回复中途导出产生截断内容；
     * - 会话丢失（已删除）时回调 null，UI 侧提示用户；
     * - 所有序列化在 IO 线程完成，避免大对话阻塞主线程。
     */
    fun exportConversation(
        format: ExportFormat,
        onReady: (ExportBundle?) -> Unit
    ) {
        viewModelScope.launch {
            val conv = chatRepository.getConversation(conversationId)
            if (conv == null) {
                onReady(null)
                return@launch
            }
            val msgs = chatRepository.observeMessages(conversationId).first()
            val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                when (format) {
                    ExportFormat.MARKDOWN -> ConversationExporter.toMarkdown(conv, msgs)
                    ExportFormat.JSON -> ConversationExporter.toJson(conv, msgs)
                }
            }
            onReady(
                ExportBundle(
                    text = text,
                    fileName = ConversationExporter.defaultFileName(conv),
                    mimeType = format.mimeType,
                    extension = format.extension
                )
            )
        }
    }
}

/** 导出格式：与 [ConversationExporter] 一一对应，额外携带分享所需的 MIME 与扩展名 */
enum class ExportFormat(val mimeType: String, val extension: String) {
    MARKDOWN("text/markdown", "md"),
    JSON("application/json", "json")
}

/** 一次导出的产物：文本内容 + 默认文件名 + 分享元数据 */
data class ExportBundle(
    val text: String,
    val fileName: String,
    val mimeType: String,
    val extension: String
) {
    val fullName: String get() = "$fileName.$extension"
}
