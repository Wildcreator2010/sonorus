package com.wildcreator.sonorus.ui.chat

import android.content.Intent
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NavigateBefore
import androidx.compose.material.icons.filled.NavigateNext
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wildcreator.sonorus.R
import com.wildcreator.sonorus.domain.agent.AgentExecutor
import com.wildcreator.sonorus.domain.agent.ThinkingLevel
import com.wildcreator.sonorus.domain.model.ChatMessage
import com.wildcreator.sonorus.domain.model.ChatRole
import com.wildcreator.sonorus.domain.model.Conversation
import com.wildcreator.sonorus.domain.model.ImageAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 单条消息最多附带的图片数（上游普遍有此限制） */
private const val MAX_IMAGE_COUNT = 4

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(
    onOpenChat: (Long) -> Unit,
    onOpenProviders: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: ConversationListViewModel = hiltViewModel()
) {
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<Conversation?>(null) }
    // 删除会话不可恢复，必须二次确认——避免列表项右手的删除图标被误触
    var deleting by remember { mutableStateOf<Conversation?>(null) }

    // 自动删除空对话。用「重新进入 composition」而不是 ON_RESUME 作为可见信号：
    // 全部导航都在同一 Activity 内，pop 回列表不会派发任何生命周期事件。
    LaunchedEffect(Unit) { viewModel.purgeEmptyConversations() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sonorus") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Tune, contentDescription = "设置")
                    }
                    IconButton(onClick = onOpenProviders) {
                        Icon(Icons.Default.Settings, contentDescription = "管理提供商")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    viewModel.createConversation(
                        onCreated = onOpenChat,
                        onNeedProvider = onOpenProviders
                    )
                }
            ) {
                Icon(Icons.Default.Add, contentDescription = "新会话")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            ConversationSearchBar(
                query = searchQuery,
                onQueryChange = viewModel::setSearchQuery,
                onClear = viewModel::clearSearch
            )
            when {
                conversations.isEmpty() && searchQuery.isNotBlank() ->
                    EmptySearchResult(
                        query = searchQuery,
                        modifier = Modifier.fillMaxSize()
                    )
                conversations.isEmpty() ->
                    EmptyConversations(modifier = Modifier.fillMaxSize())
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(conversations, key = { it.id }) { conv ->
                        ConversationCard(
                            conversation = conv,
                            onClick = { onOpenChat(conv.id) },
                            onRename = { renaming = conv },
                            onDelete = { deleting = conv }
                        )
                    }
                }
            }
        }
    }

    renaming?.let { target ->
        RenameDialog(
            initial = target.title,
            onConfirm = { newTitle ->
                viewModel.rename(target.id, newTitle)
                renaming = null
            },
            onDismiss = { renaming = null }
        )
    }

    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除会话") },
            text = {
                Text("确定删除《${target.title}》吗？会话与历史消息将一并移除，此操作不可撤销。")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(target.id)
                    deleting = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消") }
            }
        )
    }
}

/**
 * 会话搜索栏：标题与消息内容模糊匹配。
 * 不默认隐藏：搜索是高频入口，一旦折叠进图标会让新用户找不到。
 */
@Composable
private fun ConversationSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        singleLine = true,
        placeholder = { Text("搜索会话标题或内容…") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Default.Close, contentDescription = "清除搜索")
                }
            }
        }
    )
}

@Composable
private fun EmptySearchResult(query: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Search,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "没有包含“$query”的会话",
            modifier = Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EmptyConversations(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(R.drawable.logo_sonorus),
            contentDescription = null,
            modifier = Modifier.size(96.dp)
        )
        Text(
            "点右下角开始新会话",
            modifier = Modifier.padding(top = 16.dp),
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            "首次使用请先在右上角添加提供商",
            modifier = Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationCard(
    conversation: Conversation,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    conversation.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    conversation.modelId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onRename) {
                Icon(Icons.Default.Edit, contentDescription = "重命名")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "删除会话")
            }
        }
    }
}

@Composable
private fun RenameDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名会话") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank()
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onBack: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val agentState by viewModel.agentState.collectAsStateWithLifecycle()
    val runningElsewhere by viewModel.runningElsewhere.collectAsStateWithLifecycle()
    val conversation by viewModel.conversation.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var input by remember { mutableStateOf("") }
    // 用普通 List 存在 State 里：MutableStateList 与 Compose 快照配合时有较复杂的推断问题
    var pendingImages by remember { mutableStateOf<List<ImageAttachment>>(emptyList()) }
    var showSystemPromptDialog by remember { mutableStateOf(false) }
    var showTuningDialog by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    // 编辑重发 / 批注修订的目标消息；null = 无弹窗
    var editingMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var annotatingMessage by remember { mutableStateOf<ChatMessage?>(null) }
    // 上次发送的文本：失败后留在输入框，避免用户重打
    var lastSent by remember { mutableStateOf("") }
    // 只有最后一条最终回答能重新生成 / 批注修改（tool 承载行正文为空，天然被排除）
    val lastAnswerId = remember(messages) {
        messages.lastOrNull { it.role == ChatRole.ASSISTANT && it.content.isNotBlank() }?.id
    }
    val clipboard = LocalClipboardManager.current

    // Photo Picker：无需存储权限，返回 Uri
    val pickImages = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(MAX_IMAGE_COUNT)
    ) { uris ->
        if (uris.isNotEmpty()) {
            val remaining = MAX_IMAGE_COUNT - pendingImages.size
            scope.launch {
                val decoded = withContext(Dispatchers.IO) {
                    uris.take(remaining).mapNotNull { uri -> context.readImageAttachment(uri) }
                }
                pendingImages = pendingImages + decoded
            }
        }
    }

    /**
     * 导出到文件（SAF / ACTION_CREATE_DOCUMENT）：不需要任何存储权限，
     * 用户在系统选择器里自己决定落到哪里（下载/云盘/其他 App）。
     * pendingExport 存放待写入的内容，回调时取用。
     */
    var pendingExport by remember { mutableStateOf<ExportBundle?>(null) }
    val saveDocument = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/*")
    ) { uri ->
        val bundle = pendingExport
        pendingExport = null
        if (uri == null || bundle == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(bundle.text.toByteArray(Charsets.UTF_8))
                        out.flush()
                    } ?: error("无法打开目标文件")
                }.isSuccess
            }
            snackbar.showSnackbar(if (ok) "已导出到所选位置" else "导出失败，目标不可写")
        }
    }

    /**
     * 流式滚动优化：
     * - 只在"接近底部"时才自动滚动，用户向上翻阅历史时不再被拉回；
     * - 150ms 防抖合并高频 chunk，避免每个片段都触发滚动动画。
     */
    var lastScrollTime by remember { mutableStateOf(0L) }
    LaunchedEffect(messages.size, agentState.streamingContent.length) {
        val info = listState.layoutInfo
        val lastIndex = info.totalItemsCount - 1
        if (lastIndex < 0) return@LaunchedEffect
        val lastVisibleIndex = info.visibleItemsInfo.lastOrNull()?.index ?: -1
        val nearBottom = lastIndex - lastVisibleIndex <= 2

        if (nearBottom) {
            val now = System.currentTimeMillis()
            if (now - lastScrollTime > 150) {
                listState.animateScrollToItem(lastIndex)
                lastScrollTime = now
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(conversation?.title ?: "会话")
                        Text(
                            conversation?.modelId ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showTuningDialog = true }) {
                        Icon(Icons.Default.Tune, contentDescription = "会话参数")
                    }
                    IconButton(onClick = { showSystemPromptDialog = true }) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "System Prompt")
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("复制全部对话") },
                                onClick = {
                                    menuExpanded = false
                                    val all = messages.joinToString("\n\n") { m ->
                                        val who = when (m.role) {
                                            ChatRole.USER -> "我"
                                            ChatRole.TOOL -> "工具 ${m.toolName ?: ""}"
                                            else -> "助手"
                                        }
                                        "$who：${m.content}"
                                    }
                                    if (all.isNotBlank()) {
                                        clipboard.setText(AnnotatedString(all))
                                        scope.launch { snackbar.showSnackbar("已复制全部对话") }
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("复制为 Markdown") },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.exportConversation(ExportFormat.MARKDOWN) { bundle ->
                                        if (bundle == null) {
                                            scope.launch { snackbar.showSnackbar("会话不存在，无法导出") }
                                            return@exportConversation
                                        }
                                        clipboard.setText(AnnotatedString(bundle.text))
                                        scope.launch { snackbar.showSnackbar("已复制 Markdown（${bundle.text.length} 字符）") }
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("分享…") },
                                onClick = {
                                    menuExpanded = false
                                    // 分享以 Markdown 为主：可读性好，且各家 IM 客户端都能直接预览
                                    viewModel.exportConversation(ExportFormat.MARKDOWN) { bundle ->
                                        if (bundle == null) {
                                            scope.launch { snackbar.showSnackbar("会话不存在，无法分享") }
                                            return@exportConversation
                                        }
                                        val send = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_SUBJECT, bundle.fileName)
                                            putExtra(Intent.EXTRA_TITLE, bundle.fileName)
                                            putExtra(Intent.EXTRA_TEXT, bundle.text)
                                        }
                                        runCatching {
                                            context.startActivity(Intent.createChooser(send, "分享会话"))
                                        }.onFailure {
                                            scope.launch { snackbar.showSnackbar("没有可处理分享的应用") }
                                        }
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("导出到文件…") },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.exportConversation(ExportFormat.MARKDOWN) { bundle ->
                                        if (bundle == null) {
                                            scope.launch { snackbar.showSnackbar("会话不存在，无法导出") }
                                            return@exportConversation
                                        }
                                        pendingExport = bundle
                                        runCatching {
                                            saveDocument.launch(bundle.fullName)
                                        }.onFailure {
                                            pendingExport = null
                                            scope.launch { snackbar.showSnackbar("系统文件选择器不可用") }
                                        }
                                    }
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("清空消息") },
                                onClick = {
                                    menuExpanded = false
                                    showClearDialog = true
                                }
                            )
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageBubble(
                        message = message,
                        // 重新生成 / 批注修改只作用于最后一条回答（与后端能力一致）
                        canRevise = !agentState.running && !runningElsewhere &&
                            message.role == ChatRole.ASSISTANT &&
                            message.content.isNotBlank() &&
                            message.id == lastAnswerId,
                        onCopy = {
                            if (message.content.isNotBlank()) {
                                clipboard.setText(AnnotatedString(message.content))
                                scope.launch { snackbar.showSnackbar("已复制该条消息") }
                            }
                        },
                        onEditResend = { editingMessage = message },
                        onRegenerate = {
                            viewModel.regenerate {
                                scope.launch { snackbar.showSnackbar("这条会话还没有可重新生成的回答") }
                            }
                        },
                        onAnnotate = { annotatingMessage = message },
                        onSwitchVersion = { index -> viewModel.switchVersion(message.id, index) }
                    )
                }
                if (agentState.running) {
                    item(key = "streaming") {
                        StreamingIndicator(agentState)
                    }
                }
                agentState.error?.let { err ->
                    item(key = "error") {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    err,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Row {
                                    TextButton(
                                        // 执行器全局单槽：别的会话在后台续写时这一轮发不出去，
                                        // 不禁掉就会"点了毫无反应"
                                        enabled = !runningElsewhere,
                                        onClick = {
                                            viewModel.dismissError()
                                            if (lastSent.isNotBlank()) viewModel.send(lastSent)
                                        }
                                    ) { Text("重试") }
                                    TextButton(onClick = { viewModel.dismissError() }) { Text("忽略") }
                                }
                            }
                        }
                    }
                }
            }

            // 上下文状态条：让"这次到底送了多少"可见，避免用户误以为模型失忆是 bug
            if (agentState.totalMessages > 0) {
                ContextStatusBar(agentState)
            }

            // 待发送图片预览
            if (pendingImages.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    pendingImages.forEach { attachment ->
                        Box {
                            attachment.toImageBitmap(context)?.let { bitmap ->
                                Image(
                                    bitmap = bitmap,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(64.dp)
                                        .background(
                                            MaterialTheme.colorScheme.surfaceVariant,
                                            RoundedCornerShape(8.dp)
                                        )
                                )
                            }
                            IconButton(
                                onClick = { pendingImages = pendingImages - attachment },
                                modifier = Modifier.size(24.dp).align(Alignment.TopEnd)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "移除图片",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 执行器同一时刻只跑一轮：别的会话在后台续写时要点明原因，否则这里点发送会毫无反应
            if (runningElsewhere) {
                Text(
                    "另一个会话正在生成回答，完成后可在此发送",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        pickImages.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    enabled = pendingImages.size < MAX_IMAGE_COUNT && !agentState.running && !runningElsewhere
                ) {
                    Icon(Icons.Default.Image, contentDescription = "添加图片")
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入消息…") },
                    maxLines = 4
                )
                if (agentState.running) {
                    // 生成中：发送键变停止键，已流出的内容会保留为一版回答
                    IconButton(onClick = viewModel::stop) {
                        Icon(Icons.Default.Close, contentDescription = "停止生成")
                    }
                } else {
                    IconButton(
                        onClick = {
                            lastSent = input
                            viewModel.send(input, pendingImages)
                            input = ""
                            pendingImages = emptyList()
                        },
                        enabled = (input.isNotBlank() || pendingImages.isNotEmpty()) && !runningElsewhere
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                    }
                }
            }
        }
    }

    if (showSystemPromptDialog) {
        SystemPromptDialog(
            initial = conversation?.systemPrompt.orEmpty(),
            onConfirm = {
                viewModel.updateSystemPrompt(it)
                showSystemPromptDialog = false
            },
            onDismiss = { showSystemPromptDialog = false }
        )
    }

    if (showTuningDialog) {
        TuningDialog(
            sessionThinking = conversation?.thinkingLevel,
            toolsEnabled = conversation?.toolsEnabled ?: false,
            onThinkingChange = viewModel::updateThinkingLevel,
            onToolsChange = viewModel::updateToolsEnabled,
            onDismiss = { showTuningDialog = false }
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空消息") },
            text = { Text("将删除本会话的全部消息，会话本身与配置会保留。此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearMessages()
                    showClearDialog = false
                    scope.launch { snackbar.showSnackbar("已清空消息") }
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消") }
            }
        )
    }

    editingMessage?.let { target ->
        EditResendDialog(
            initial = target.content,
            hasImages = target.images.isNotEmpty(),
            onConfirm = { text ->
                lastSent = text
                viewModel.editAndResend(target.id, text)
                editingMessage = null
            },
            onDismiss = { editingMessage = null }
        )
    }

    annotatingMessage?.let { target ->
        AnnotateDialog(
            answer = target.content,
            onConfirm = { quote, note ->
                viewModel.reviseWithNote(quote, note) {
                    scope.launch { snackbar.showSnackbar("这条会话还没有可修改的回答") }
                }
                annotatingMessage = null
            },
            onDismiss = { annotatingMessage = null }
        )
    }
}

/**
 * 上下文状态条：把「本轮送入条数 / 会话总条数 / 生效上限 / 思考等级」摊开显示。
 * 命中率只有在上限 > 0 时才有意义，不限制时显示"全部"而不是假的 100%。
 */
@Composable
private fun ContextStatusBar(state: AgentExecutor.AgentState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val rate = state.contextHitRate
        Text(
            if (rate != null) {
                "上下文 ${state.sentMessages}/${state.totalMessages} 条 · 命中 ${(rate * 100).toInt()}%"
            } else {
                "上下文 ${state.sentMessages}/${state.totalMessages} 条 · 未限制"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            if (state.contextLimit == 0) "上限 不限制" else "上限 ${state.contextLimit} 条",
            style = MaterialTheme.typography.labelSmall,
            color = if (rate != null && rate < 0.5f) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

/**
 * 会话参数弹窗：思考等级（含"跟随全局"）与工具开关。
 * @param sessionThinking null 表示跟随设置页的全局默认
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TuningDialog(
    sessionThinking: ThinkingLevel?,
    toolsEnabled: Boolean,
    onThinkingChange: (ThinkingLevel?) -> Unit,
    onToolsChange: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("会话参数") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "思考等级（仅当前会话）",
                    style = MaterialTheme.typography.labelLarge
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = sessionThinking == null,
                        onClick = { onThinkingChange(null) },
                        label = { Text("跟随全局") }
                    )
                    ThinkingLevel.entries.forEach { level ->
                        FilterChip(
                            selected = sessionThinking == level,
                            onClick = { onThinkingChange(level) },
                            label = { Text(level.label) }
                        )
                    }
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("启用本地工具", style = MaterialTheme.typography.labelLarge)
                        Text(
                            "让模型可调用计算器、时间等本地工具（部分协议不支持则自动跳过）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = toolsEnabled, onCheckedChange = onToolsChange)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } }
    )
}

@Composable
private fun SystemPromptDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("System Prompt") },
        text = {
            Column {
                Text(
                    "仅对当前会话生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    minLines = 4,
                    maxLines = 10
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LongPressMenu(
    menuContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Box(
            modifier = modifier.combinedClickable(
                onClick = {},
                onLongClick = { expanded = true }
            )
        ) { content() }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) { menuContent() }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    canRevise: Boolean,
    onCopy: () -> Unit,
    onEditResend: () -> Unit,
    onRegenerate: () -> Unit,
    onAnnotate: () -> Unit,
    onSwitchVersion: (Int) -> Unit
) {
    when (message.role) {
        ChatRole.USER -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            LongPressMenu(
                menuContent = {
                    DropdownMenuItem(text = { Text("复制") }, onClick = onCopy)
                    DropdownMenuItem(text = { Text("编辑重发") }, onClick = onEditResend)
                },
                modifier = Modifier.widthIn(max = 300.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        message.images.forEach { attachment ->
                            attachment.toImageBitmap(LocalContext.current)?.let { bitmap ->
                                Image(
                                    bitmap = bitmap,
                                    contentDescription = null,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(180.dp)
                                )
                            }
                        }
                        if (message.content.isNotBlank()) {
                            Text(message.content)
                        }
                    }
                }
            }
        }
        ChatRole.TOOL -> LongPressMenu(
            menuContent = {
                DropdownMenuItem(text = { Text("复制") }, onClick = onCopy)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "工具 ${message.toolName ?: ""}：${message.content.take(200)}",
                    modifier = Modifier.padding(8.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        else -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start
        ) {
            Column(modifier = Modifier.widthIn(max = 320.dp)) {
                // 推理内容默认收起：多数时候用户只要答案，想知道"它怎么想的"点一下就有
                message.reasoning?.takeIf { it.isNotBlank() }?.let {
                    ThinkingBlock(reasoning = it)
                }
                LongPressMenu(
                    menuContent = {
                        DropdownMenuItem(text = { Text("复制") }, onClick = onCopy)
                        if (canRevise) {
                            DropdownMenuItem(text = { Text("批注修改") }, onClick = onAnnotate)
                            DropdownMenuItem(text = { Text("重新生成") }, onClick = onRegenerate)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(message.content)
                            message.versions.getOrNull(message.activeVersion)?.note
                                ?.takeIf { it.isNotBlank() }?.let {
                                    Text(
                                        "批注：$it",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                        }
                    }
                }
                if (message.versionCount > 1) {
                    VersionSwitcher(
                        index = message.activeVersion,
                        total = message.versionCount,
                        onSwitch = onSwitchVersion
                    )
                }
            }
        }
    }
}

@Composable
private fun AssistantBubble(content: String, modifier: Modifier = Modifier) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.widthIn(max = 320.dp).then(modifier)
        ) {
            Text(content, modifier = Modifier.padding(12.dp))
        }
    }
}

/** 可折叠的"思考过程"块 */
@Composable
private fun ThinkingBlock(reasoning: String, expandedByDefault: Boolean = false) {
    var expanded by remember { mutableStateOf(expandedByDefault) }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small,
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    if (expanded) "收起思考过程" else "思考过程",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (expanded) {
                Text(
                    reasoning,
                    modifier = Modifier.padding(horizontal = 8.dp).padding(bottom = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 多版本切换：第 2/3 版，左右箭头 */
@Composable
private fun VersionSwitcher(index: Int, total: Int, onSwitch: (Int) -> Unit) {
    Row(
        modifier = Modifier.padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = { onSwitch(index - 1) }, enabled = index > 0) {
            Icon(
                Icons.Default.NavigateBefore,
                contentDescription = "上一版",
                modifier = Modifier.size(18.dp)
            )
        }
        Text(
            "第 ${index + 1}/$total 版",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        IconButton(onClick = { onSwitch(index + 1) }, enabled = index < total - 1) {
            Icon(
                Icons.Default.NavigateNext,
                contentDescription = "下一版",
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * 流式期间的占位区：思考过程 + 已输出正文 + 带秒数的状态。
 * 秒数由本地 ticker 算，不依赖上游有没有心跳 —— 这正是"不思考的模型也别让人干等"的关键。
 */
@Composable
private fun StreamingIndicator(state: AgentExecutor.AgentState) {
    val seconds = rememberElapsedSeconds(state.running, state.startedAt)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (state.streamingReasoning.isNotEmpty()) {
            ThinkingBlock(reasoning = state.streamingReasoning, expandedByDefault = true)
        }
        if (state.streamingContent.isNotEmpty()) {
            AssistantBubble(state.streamingContent)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(start = 4.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "${state.statusLabel ?: "思考中"} $seconds 秒",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 每 500ms 刷新一次的秒表；只在 active 时跑，避免离开聊天页还在空转 */
@Composable
private fun rememberElapsedSeconds(active: Boolean, startedAt: Long): Int {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active, startedAt) {
        if (!active || startedAt <= 0L) return@LaunchedEffect
        now = System.currentTimeMillis()
        while (true) {
            delay(500)
            now = System.currentTimeMillis()
        }
    }
    if (startedAt <= 0L) return 0
    return ((now - startedAt) / 1000).toInt().coerceAtLeast(0)
}

/** 编辑重发弹窗 */
@Composable
private fun EditResendDialog(
    initial: String,
    hasImages: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑并重发") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "这条之后的全部消息会被删除，并按新内容重新提问。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (hasImages) {
                    Text(
                        "原消息里的图片会一并重发。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 8
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) {
                Text("重发")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/**
 * 批注弹窗：选中回答里的一段再写意见，AI 按批注重写并作为新的一版。
 *
 * 为什么正文放在可编辑字段里：Compose 1.7 的只读字段不响应选择手势，
 * 而 SelectionContainer 拿不到稳定的选区坐标 —— 用 TextFieldValue.selection
 * 是当前依赖版本里唯一不引入新库、也不依赖真机才验得出的写法。
 * 正文一旦被改动就退回"整条批注"，避免用改动后的下标去切原文取到错位片段。
 */
@Composable
private fun AnnotateDialog(
    answer: String,
    onConfirm: (String?, String) -> Unit,
    onDismiss: () -> Unit
) {
    var body by remember { mutableStateOf(TextFieldValue(answer)) }
    var note by remember { mutableStateOf("") }
    val edited = body.text != answer
    val selection: TextRange? = body.selection.takeIf { !it.collapsed }
    val quote = if (edited) null else selection?.let {
        runCatching { answer.substring(it.start, it.end).trim() }.getOrNull()
    }?.takeIf { it.isNotEmpty() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("批注这条回答") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when {
                        edited -> "正文被改动过，这次按整条回答修改。"
                        quote != null -> "已选中一段，批注只针对它。"
                        else -> "双击可选中一段文字；不选就是针对整条回答。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6
                )
                quote?.let {
                    Text(
                        "选中：${it.take(40)}${if (it.length > 40) "…" else ""}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 5,
                    label = { Text("批注") },
                    placeholder = { Text("想要怎么改？") }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(quote, note.trim()) },
                enabled = note.isNotBlank()
            ) { Text("按批注修改") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ---------- 图片辅助 ----------

/**
 * 读取并压缩图片：长边限制到 [MAX_IMAGE_EDGE]，以 JPEG/PNG 编码后转 base64。
 * 必须压缩 —— 现代手机照片原图 base64 后可达数 MB，会直接超出上游请求体上限。
 */
private fun android.content.Context.readImageAttachment(uri: android.net.Uri): ImageAttachment? {
    return runCatching {
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val sample = calculateInSampleSize(bounds.outWidth, bounds.outHeight, MAX_IMAGE_EDGE)
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions) ?: return null
        val scaled = scaleDown(bitmap, MAX_IMAGE_EDGE)

        val isPng = bounds.outMimeType?.contains("png") == true
        val format = if (isPng) android.graphics.Bitmap.CompressFormat.PNG
        else android.graphics.Bitmap.CompressFormat.JPEG
        val mime = if (isPng) "image/png" else "image/jpeg"

        val out = java.io.ByteArrayOutputStream()
        scaled.compress(format, 85, out)
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        ImageAttachment(mimeType = mime, base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
    }.getOrNull()
}

private const val MAX_IMAGE_EDGE = 1024

private fun calculateInSampleSize(width: Int, height: Int, maxEdge: Int): Int {
    var sample = 1
    var longest = maxOf(width, height)
    while (longest / 2 >= maxEdge) {
        longest /= 2
        sample *= 2
    }
    return sample
}

private fun scaleDown(
    bitmap: android.graphics.Bitmap,
    maxEdge: Int
): android.graphics.Bitmap {
    val longest = maxOf(bitmap.width, bitmap.height)
    if (longest <= maxEdge) return bitmap
    val ratio = maxEdge.toFloat() / longest
    return android.graphics.Bitmap.createScaledBitmap(
        bitmap,
        (bitmap.width * ratio).toInt().coerceAtLeast(1),
        (bitmap.height * ratio).toInt().coerceAtLeast(1),
        true
    )
}

/** base64 -> ImageBitmap；解码失败返回 null（UI 静默跳过，不让坏数据崩界面） */
private fun ImageAttachment.toImageBitmap(context: android.content.Context): ImageBitmap? =
    runCatching {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }.getOrNull()
