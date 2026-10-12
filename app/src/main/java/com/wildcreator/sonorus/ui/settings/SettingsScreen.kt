package com.wildcreator.sonorus.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wildcreator.sonorus.domain.agent.ThinkingLevel
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
            UpdateSettingsCard(updateViewModel)
        }
    }
}
