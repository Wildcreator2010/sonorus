package com.wildcreator.sonorus.ui.settings

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wildcreator.sonorus.domain.agent.ThinkingLevel
import com.wildcreator.sonorus.ui.theme.ChatBackground
import com.wildcreator.sonorus.ui.theme.FontScale
import com.wildcreator.sonorus.ui.theme.ThemeMode
import com.wildcreator.sonorus.ui.update.UpdateSettingsCard
import com.wildcreator.sonorus.ui.update.UpdateViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val contextLimit by viewModel.contextLimit.collectAsStateWithLifecycle()
    val defaultPrompt by viewModel.defaultSystemPrompt.collectAsStateWithLifecycle()
    val thinkingLevel by viewModel.thinkingLevel.collectAsStateWithLifecycle()
    val globalStats by viewModel.globalStats.collectAsStateWithLifecycle()
    val providerStats by viewModel.providerStats.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val fontScale by viewModel.fontScale.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // 借用 Activity 作用域的更新实例：启动时的静默检查结果要直接显示在这张卡上
    val updateViewModel: UpdateViewModel = hiltViewModel(LocalContext.current as ComponentActivity)

    var promptInput by remember { mutableStateOf<String?>(null) }
    var confirmingReset by remember { mutableStateOf(false) }
    // 首次加载后把已保存值填进输入框；用户编辑后不再被流覆盖
    LaunchedEffect(defaultPrompt) {
        if (promptInput == null) promptInput = defaultPrompt
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // ---------- 外观（主题 + 字号）----------
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("外观", style = MaterialTheme.typography.titleMedium)

                    // 主题模式
                    Text(
                        "主题模式",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = themeMode == mode,
                                onClick = {
                                    viewModel.saveThemeMode(mode)
                                    scope.launch { snackbar.showSnackbar("主题：${mode.label}") }
                                },
                                label = { Text(mode.label) }
                            )
                        }
                    }

                    HorizontalDivider()

                    // 字号缩放
                    Text(
                        "字号缩放",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FontScale.entries.forEach { scale ->
                            FilterChip(
                                selected = fontScale == scale,
                                onClick = {
                                    viewModel.saveFontScale(scale)
                                    scope.launch { snackbar.showSnackbar("字号：${scale.label}") }
                                },
                                label = { Text(scale.label) }
                            )
                        }
                    }

                    HorizontalDivider()
                    ChatBackgroundControls(viewModel, snackbar)
                }
            }

            // ---------- 思考等级 ----------
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("思考等级", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "控制模型的推理深度，会自动适配当前协议（OpenAI 的 reasoning_effort、" +
                            "Anthropic 的 thinking budget、Gemini 的 thinkingBudget）。" +
                            "不支持该参数的模型会自动忽略，不会报错。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThinkingLevel.entries.forEach { level ->
                            FilterChip(
                                selected = thinkingLevel == level,
                                onClick = {
                                    viewModel.saveThinkingLevel(level)
                                    scope.launch { snackbar.showSnackbar("思考等级：${level.label}") }
                                },
                                label = { Text(level.label) }
                            )
                        }
                    }
                    Text(
                        thinkingLevel.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // ---------- 上下文长度 ----------
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("上下文长度", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "每次请求最多发送的历史消息条数。越短越省 token，但模型会更快忘记前文。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CONTEXT_LIMIT_PRESETS.forEach { (value, label) ->
                            FilterChip(
                                selected = contextLimit == value,
                                onClick = {
                                    viewModel.saveContextLimit(value)
                                    scope.launch { snackbar.showSnackbar("已设置为 $label") }
                                },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            }

            // ---------- 请求成功率 ----------
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("请求成功率", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "统计本机发起的模型请求结果（含拉取模型列表）。仅记在本地，不上传。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    val stats = globalStats
                    if (stats == null || stats.total == 0) {
                        Text(
                            "暂无请求样本",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            stats.rate?.let { "${(it * 100).toInt()}%" } ?: "—",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if ((stats.rate ?: 1f) >= 0.95f) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                        )
                        LinearProgressIndicator(
                            progress = { stats.rate ?: 0f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "共 ${stats.total} 次 · 成功 ${stats.successes ?: 0} · 失败 ${stats.failures}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (providerStats.isNotEmpty()) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        Text(
                            "按提供商",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        providerStats.forEach { row ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(row.providerName, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "${row.protocol} · ${row.total} 次",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    row.rate?.let { "${(it * 100).toInt()}%" } ?: "—",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    // 两段式确认：避免误触清空统计
                    if (confirmingReset) {
                        Text(
                            "确认清空全部统计样本？此操作不可撤销。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                viewModel.resetStats()
                                confirmingReset = false
                                scope.launch { snackbar.showSnackbar("统计已重置") }
                            }) { Text("确认重置") }
                            OutlinedButton(onClick = { confirmingReset = false }) { Text("取消") }
                        }
                    } else {
                        TextButton(onClick = { confirmingReset = true }) { Text("重置统计") }
                    }
                }
            }

            // ---------- 默认 System Prompt ----------
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("默认 System Prompt", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "新建会话时使用的初始设定。已有会话不受影响，可在会话内单独修改。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = promptInput.orEmpty(),
                        onValueChange = { promptInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 10,
                        placeholder = { Text("你是一个乐于助人的助手。") }
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                viewModel.saveSystemPrompt(promptInput.orEmpty().trim())
                                scope.launch { snackbar.showSnackbar("已保存") }
                            }
                        ) {
                            Text("保存")
                        }
                    }
                }
            }

            // ---------- 版本与更新 ----------
            BackupCard(viewModel, snackbar)

            UpdateSettingsCard(updateViewModel)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatBackgroundControls(viewModel: SettingsViewModel, snackbar: SnackbarHostState) {
    val background by viewModel.chatBackground.collectAsStateWithLifecycle()
    val busy by viewModel.backgroundBusy.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var opacity by remember(background.path, background.opacity) { mutableStateOf(background.opacity) }
    val showError: (String?) -> Unit = { message ->
        if (message != null) scope.launch { snackbar.showSnackbar(message) }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importChatBackground(uri, showError)
    }

    Text("聊天背景", style = MaterialTheme.typography.labelLarge)
    Text(
        "仅用于聊天页，图片保存在本机，不会发送给模型或加入备份。支持不超过 20 MB 的图片。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Box(
        modifier = Modifier.fillMaxWidth().height(160.dp).clip(MaterialTheme.shapes.medium)
            .semantics { contentDescription = "聊天背景预览" },
        contentAlignment = Alignment.Center
    ) {
        ChatBackground(
            modifier = Modifier.matchParentSize(),
            background = background.copy(opacity = opacity),
            onError = showError
        )
        if (background.path == null) Text("默认背景", color = MaterialTheme.colorScheme.onBackground)
    }
    Text("压暗强度 ${(opacity * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = opacity,
        onValueChange = { opacity = it },
        onValueChangeFinished = {
            viewModel.saveChatBackgroundOpacity(opacity) { error ->
                if (error != null) opacity = background.opacity
                showError(error)
            }
        },
        valueRange = 0f..1f,
        enabled = !busy && background.path != null,
        modifier = Modifier.semantics { contentDescription = "聊天背景压暗强度" }
    )
    Text("0% 最亮 · 100% 最暗", style = MaterialTheme.typography.bodySmall)
    if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            enabled = !busy,
            onClick = {
                try {
                    picker.launch(arrayOf("image/*"))
                } catch (e: Exception) {
                    showError("无法打开图片选择器，请检查系统文件应用")
                }
            }
        ) { Text(if (background.path == null) "选择背景图" else "更换背景图") }
        TextButton(
            enabled = !busy && background.path != null,
            onClick = { viewModel.resetChatBackground(showError) }
        ) { Text("恢复默认") }
    }
}

/**
 * 备份与恢复。
 *
 * 口令只活在输入框与这一次调用里：不进 ViewModel 状态、不写日志、用完立即清空。
 * 恢复前强制走一次预告，让用户看清会新增多少、跳过多少 —— 合并去重不是覆盖，
 * 但这点对第一次用的人不直观，静默跳过会被当成"没生效"。
 */
@Composable
private fun BackupCard(viewModel: SettingsViewModel, snackbar: SnackbarHostState) {
    val scope = rememberCoroutineScope()

    var showExportDialog by remember { mutableStateOf(false) }
    var exportPassphrase by remember { mutableStateOf("") }
    var exportConfirm by remember { mutableStateOf("") }

    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var showRestorePassDialog by remember { mutableStateOf(false) }
    var restorePassphrase by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<BackupPreview?>(null) }

    // 备份是加密后的二进制容器，用 octet-stream；扩展名由 suggestedFileName 决定
    val createBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        // 先清空再判断：用户取消文件选择器时，口令也不能继续留在输入框状态里
        val passphrase = exportPassphrase
        exportPassphrase = ""
        exportConfirm = ""
        if (uri == null) return@rememberLauncherForActivityResult
        viewModel.exportBackup(uri, passphrase) { message ->
            scope.launch { snackbar.showSnackbar(message) }
        }
    }

    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pendingUri = uri
            restorePassphrase = ""
            showRestorePassDialog = true
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("备份与恢复", style = MaterialTheme.typography.titleMedium)
            Text(
                "把全部会话、提供商与设置打包成一个文件：图片与工具调用都完整保留。" +
                    "提供商的 API Key 也在里面，但整个文件由你设定的口令加密（PBKDF2 + AES-256-GCM），" +
                    "口令本身不会被保存 —— 忘了它就再也打不开这份备份。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "恢复时按「创建时间 + 标题 + 消息数」去重：本机已有的会话跳过，已有提供商的密钥不会被覆盖。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showExportDialog = true }) { Text("导出备份") }
                OutlinedButton(onClick = { openBackup.launch(arrayOf("*/*")) }) { Text("从备份恢复") }
            }
        }
    }

    if (showExportDialog) {
        val mismatch = exportConfirm.isNotEmpty() && exportPassphrase != exportConfirm
        AlertDialog(
            onDismissRequest = {
                showExportDialog = false
                exportPassphrase = ""
                exportConfirm = ""
            },
            title = { Text("设置备份口令") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PassField(exportPassphrase, { exportPassphrase = it }, "口令")
                    PassField(
                        exportConfirm,
                        { exportConfirm = it },
                        "再输一次",
                        isError = mismatch,
                        errorText = if (mismatch) "两次输入不一致" else null
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = exportPassphrase.isNotBlank() && exportPassphrase == exportConfirm,
                    onClick = {
                        showExportDialog = false
                        createBackup.launch(viewModel.backupFileName())
                    }
                ) { Text("选择保存位置") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showExportDialog = false
                    exportPassphrase = ""
                    exportConfirm = ""
                }) { Text("取消") }
            }
        )
    }

    if (showRestorePassDialog) {
        AlertDialog(
            onDismissRequest = {
                showRestorePassDialog = false
                restorePassphrase = ""
            },
            title = { Text("输入备份口令") },
            text = {
                PassField(restorePassphrase, { restorePassphrase = it }, "口令")
            },
            confirmButton = {
                TextButton(
                    enabled = restorePassphrase.isNotBlank() && pendingUri != null,
                    onClick = {
                        val uri = pendingUri ?: return@TextButton
                        val passphrase = restorePassphrase
                        restorePassphrase = ""
                        showRestorePassDialog = false
                        viewModel.inspectBackup(uri, passphrase) { result, error ->
                            if (error != null) {
                                scope.launch { snackbar.showSnackbar("读不出来：$error") }
                            } else {
                                preview = result
                            }
                        }
                    }
                ) { Text("下一步") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showRestorePassDialog = false
                    restorePassphrase = ""
                }) { Text("取消") }
            }
        )
    }

    preview?.let { p ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text("确认恢复") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("将新增会话 ${p.plan.conversationsToInsert} 个（消息 ${p.plan.messagesToInsert} 条）")
                    Text("将跳过已存在的会话 ${p.plan.conversationsSkipped} 个")
                    Text("将新增提供商 ${p.plan.providersToAdd} 个、补全密钥 ${p.plan.providersToFillKey} 个")
                    if (p.plan.conversationsUnmatched > 0) {
                        Text(
                            "${p.plan.conversationsUnmatched} 个会话找不到对应提供商，会被跳过",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Text(
                        "备份来自 ${p.file.appVersion.ifBlank { "未知版本" }}，共 ${p.file.conversations.size} 个会话。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    preview = null
                    viewModel.restoreBackup(p.file) { message ->
                        scope.launch { snackbar.showSnackbar(message) }
                    }
                }) { Text("开始恢复") }
            },
            dismissButton = { TextButton(onClick = { preview = null }) { Text("取消") } }
        )
    }
}

/** 口令输入框：掩码显示 + 密码键盘，不让明文口令出现在任何回显里 */
@Composable
private fun PassField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    isError: Boolean = false,
    errorText: String? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        isError = isError,
        supportingText = errorText?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth()
    )
}
