package com.wildcreator.sonorus.ui.update

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 挂在 App 最外层：启动时静默检查一次（一周内不重复请求），
 * 有新版就盖一层"更新 / 不更新"的弹窗；点更新才下载，校验通过后交给系统安装页。
 *
 * 只提供浮层，不包 Scaffold —— 每个页面自己有 Scaffold，套两层会把内边距算错。
 */
@Composable
fun AppUpdateHost(
    viewModel: UpdateViewModel,
    content: @Composable () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val installUri by viewModel.installUri.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) { viewModel.check(manual = false) }

    LaunchedEffect(installUri) {
        val uri = installUri ?: return@LaunchedEffect
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, APK_MIME)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
        }.onFailure { error ->
            viewModel.reportInstallLaunchFailure(
                if (error is ActivityNotFoundException)
                    "已下载并校验通过，但本机没有可调起的安装程序"
                else "无法打开系统安装页：${error.message ?: "未知错误"}"
            )
        }
        viewModel.consumeInstallUri()
    }

    content()

    when (state.phase) {
        UpdatePhase.AVAILABLE -> state.update?.let { update ->
            AlertDialog(
                onDismissRequest = viewModel::dismiss,
                title = { Text("发现新版本 ${update.versionName}") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("当前版本 ${state.currentVersion}")
                        if (update.notes.isNotBlank()) {
                            Text(
                                update.notes.take(600),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text("下载完会先校验 sha256，再由系统安装页确认。", style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = { TextButton(onClick = viewModel::downloadAndInstall) { Text("更新") } },
                dismissButton = { TextButton(onClick = viewModel::dismiss) { Text("不更新") } }
            )
        }
        UpdatePhase.DOWNLOADING -> AlertDialog(
            onDismissRequest = { /* 下载中途不给关掉，免得半截文件被当成已校验 */ },
            title = { Text("正在下载 ${state.update?.versionName ?: ""}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.progress >= 0) {
                        LinearProgressIndicator(
                            progress = { state.progress / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("已下载 ${state.progress}%", style = MaterialTheme.typography.bodySmall)
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("下载中…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = viewModel::cancelDownload) { Text("取消下载") } }
        )
        else -> Unit
    }
}

/** 设置页的「版本与更新」卡片：当前版本、上次检查时间、手动检查 */
@Composable
fun UpdateSettingsCard(viewModel: UpdateViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("版本与更新", style = MaterialTheme.typography.titleMedium)
            Text("当前版本 ${state.currentVersion} · 检查源 github.com/${state.repository}")
            Text(
                formatLastCheck(state.lastCheckAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            when (state.phase) {
                UpdatePhase.CHECKING -> Text("检查中…", style = MaterialTheme.typography.bodySmall)
                UpdatePhase.DOWNLOADING -> {
                    LinearProgressIndicator(
                        progress = { state.progress.coerceAtLeast(0) / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("下载中 ${state.progress}%", style = MaterialTheme.typography.bodySmall)
                }
                UpdatePhase.AVAILABLE -> Text(
                    "发现新版本 ${state.update?.versionName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                else -> state.message?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.phase == UpdatePhase.FAILED) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (state.phase == UpdatePhase.AVAILABLE) {
                TextButton(onClick = viewModel::downloadAndInstall) { Text("立即更新") }
            } else {
                OutlinedButton(
                    onClick = { viewModel.check(manual = true) },
                    enabled = state.phase != UpdatePhase.CHECKING && state.phase != UpdatePhase.DOWNLOADING
                ) { Text("检查更新") }
            }
        }
    }
}

private const val APK_MIME = "application/vnd.android.package-archive"

@Composable
private fun formatLastCheck(epochMs: Long): String = if (epochMs <= 0L) "还没检查过更新"
else {
    val text = remember(epochMs) {
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(epochMs))
    }
    "上次检查：$text"
}
