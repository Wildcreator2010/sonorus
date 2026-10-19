package com.wildcreator.sonorus.ui.changelog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wildcreator.sonorus.domain.model.Changelog

/**
 * 挂在根上的一次性更新日志弹窗，与 `AppUpdateHost` 同构。
 *
 * 用 `Dialog + Surface` 而不是 `AlertDialog`：条目以后可能变长，需要自己带滚动。
 */
@Composable
fun ChangelogHost(
    viewModel: ChangelogViewModel = hiltViewModel(),
    content: @Composable () -> Unit
) {
    val visible by viewModel.visible.collectAsStateWithLifecycle()

    Box {
        content()
        if (visible) {
            ChangelogDialog(onDismiss = viewModel::dismiss)
        }
    }
}

@Composable
private fun ChangelogDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("更新到 ${Changelog.version}", style = MaterialTheme.typography.titleLarge)
                Changelog.notes.forEach { note ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text("·", style = MaterialTheme.typography.bodyMedium)
                        Text(note, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(onClick = onDismiss) { Text("知道了") }
                }
            }
        }
    }
}
