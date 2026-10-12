package com.wildcreator.sonorus.ui.model

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.wildcreator.sonorus.data.prefs.SettingsManager
import com.wildcreator.sonorus.domain.model.ModelInfo
import com.wildcreator.sonorus.domain.repository.ProviderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ModelListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ProviderRepository,
    private val settings: SettingsManager
) : ViewModel() {

    private val providerId: Long = checkNotNull(savedStateHandle["providerId"])

    val models: StateFlow<List<ModelInfo>> = repository.observeModels(providerId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _event = MutableStateFlow<String?>(null)
    val event: StateFlow<String?> = _event.asStateFlow()

    init {
        refresh() // 进入页面即自动拉取一次
    }

    fun refresh() {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            val result = repository.refreshModels(providerId)
            _event.value = result.fold(
                onSuccess = { "已拉取 $it 个模型" },
                onFailure = { "拉取失败：${it.message}" }
            )
            _refreshing.value = false
        }
    }

    /** 点选模型 = 设为后续新会话的默认模型 */
    fun select(model: ModelInfo) {
        viewModelScope.launch {
            settings.saveSelection(model.providerId, model.modelId)
            _event.value = "已将 ${model.modelId} 设为默认模型"
        }
    }

    fun consumeEvent() { _event.value = null }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelListScreen(
    onBack: () -> Unit,
    viewModel: ModelListViewModel = hiltViewModel()
) {
    val models by viewModel.models.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val event by viewModel.event.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(event) {
        event?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeEvent()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("可用模型") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !refreshing) {
                        Icon(Icons.Default.Refresh, contentDescription = "重新拉取")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (models.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(if (refreshing) "正在拉取模型…" else "暂无模型，点右上角重新拉取")
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(models, key = { it.id }) { model ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.select(model) }
                    ) {
                        Text(
                            model.modelId,
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        }
    }
}
