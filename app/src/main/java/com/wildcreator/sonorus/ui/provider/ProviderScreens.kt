package com.wildcreator.sonorus.ui.provider

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wildcreator.sonorus.domain.model.Provider
import com.wildcreator.sonorus.domain.model.ProviderProtocol

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderListScreen(
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (Long) -> Unit,
    onModels: (Long) -> Unit,
    viewModel: ProviderListViewModel = hiltViewModel()
) {
    val providers by viewModel.providers.collectAsStateWithLifecycle()
    // 删除提供商现在会级联删掉它名下的会话与消息，不可恢复，必须二次确认
    var pendingDelete by remember { mutableStateOf<Provider?>(null) }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除提供商") },
            text = {
                Text("确定删除「${target.name}」吗？它的模型缓存，以及挂在这个提供商下的" +
                    "全部会话与消息都会一并删除，且无法恢复。API Key 会从加密存储中移除。")
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    viewModel.delete(target.id)
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("模型提供商") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = "添加提供商")
            }
        }
    ) { padding ->
        if (providers.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("还没有提供商，点右下角添加一个（支持 OpenAI / Anthropic / Gemini）")
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(providers, key = { it.id }) { provider ->
                    ProviderCard(
                        provider = provider,
                        onEdit = { onEdit(provider.id) },
                        onDelete = { pendingDelete = provider },
                        onModels = { onModels(provider.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderCard(
    provider: Provider,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onModels: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    provider.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f, fill = false)
                )
                SuggestionChip(
                    onClick = {},
                    enabled = false,
                    label = { Text(provider.protocol.label) }
                )
            }
            Text(
                provider.baseUrl,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onModels) {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = null)
                    Text("模型")
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "编辑")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "删除")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProtocolSelector(
    current: ProviderProtocol,
    onSelect: (ProviderProtocol) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "协议",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ProviderProtocol.entries.forEach { protocol ->
                FilterChip(
                    selected = protocol == current,
                    onClick = { onSelect(protocol) },
                    label = { Text(protocol.label) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderEditScreen(
    onBack: () -> Unit,
    viewModel: ProviderEditViewModel = hiltViewModel()
) {
    LaunchedEffect(viewModel.done) {
        if (viewModel.done) onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (viewModel.isEdit) "编辑提供商" else "添加提供商") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = viewModel.name,
                onValueChange = viewModel::onNameChange,
                label = { Text("名称（如：OpenAI / Anthropic / Gemini / 自建网关）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            ProtocolSelector(current = viewModel.protocol, onSelect = viewModel::onProtocolChange)

            OutlinedTextField(
                value = viewModel.baseUrl,
                onValueChange = viewModel::onBaseUrlChange,
                label = { Text("Base URL") },
                supportingText = { Text("留空路径时自动补 ${viewModel.protocol.defaultBaseUrl}") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = viewModel.apiKey,
                onValueChange = viewModel::onApiKeyChange,
                label = { Text("API Key") },
                placeholder = {
                    viewModel.savedKeyMasked?.let { Text(it) }
                },
                supportingText = {
                    Text(
                        if (viewModel.savedKeyMasked != null) "留空表示沿用已保存的密钥；密钥加密存于本机 Keystore"
                        else "密钥加密存于本机 Keystore，不写入数据库、不随备份外传"
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = viewModel::test,
                    enabled = !viewModel.testing && !viewModel.saving,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (viewModel.testing) "测试中…" else "测试连接")
                }
                Button(
                    onClick = viewModel::save,
                    enabled = !viewModel.saving && !viewModel.testing,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (viewModel.saving) "保存中…" else "保存并拉取模型")
                }
            }

            viewModel.message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
