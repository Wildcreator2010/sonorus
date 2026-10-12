package com.wildcreator.sonorus.ui.provider

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wildcreator.sonorus.domain.model.Provider
import com.wildcreator.sonorus.domain.model.ProviderDraft
import com.wildcreator.sonorus.domain.model.ProviderProtocol
import com.wildcreator.sonorus.domain.repository.ProviderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProviderListViewModel @Inject constructor(
    private val repository: ProviderRepository
) : ViewModel() {

    val providers: StateFlow<List<Provider>> = repository.observeProviders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun delete(id: Long) {
        viewModelScope.launch { repository.deleteProvider(id) }
    }
}

@HiltViewModel
class ProviderEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ProviderRepository
) : ViewModel() {

    private val providerId: Long? =
        savedStateHandle.get<String>("providerId")?.toLongOrNull()?.takeIf { it > 0 }

    val isEdit: Boolean get() = providerId != null

    var name by mutableStateOf("")
        private set
    var baseUrl by mutableStateOf(ProviderProtocol.OPENAI.defaultBaseUrl)
        private set
    var protocol by mutableStateOf(ProviderProtocol.OPENAI)
        private set

    /**
     * 用户输入的明文密钥：仅存在于本 ViewModel 内存中，
     * 保存后立即清空，不进入 Room、不进入 SavedState、不参与序列化。
     */
    var apiKey by mutableStateOf("")
        private set

    /** 已保存密钥的掩码（用于提示"留空即沿用"），真实密钥永不出加密存储 */
    var savedKeyMasked by mutableStateOf<String?>(null)
        private set

    var saving by mutableStateOf(false)
        private set
    var testing by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    /** 保存并成功拉取模型后置为 true，通知 UI 返回 */
    var done by mutableStateOf(false)
        private set

    init {
        providerId?.let { id ->
            viewModelScope.launch {
                repository.getProvider(id)?.let { p ->
                    name = p.name
                    baseUrl = p.baseUrl
                    protocol = p.protocol
                }
                savedKeyMasked = repository.maskedApiKey(id)
            }
        }
    }

    fun onNameChange(v: String) { name = v }
    fun onBaseUrlChange(v: String) { baseUrl = v }
    fun onApiKeyChange(v: String) { apiKey = v }

    /** 切换协议：若当前地址仍是旧协议默认值，则同步为新协议默认值 */
    fun onProtocolChange(next: ProviderProtocol) {
        val previous = protocol
        protocol = next
        if (baseUrl.isBlank() ||
            baseUrl.trim().trimEnd('/') == previous.defaultBaseUrl.trimEnd('/')
        ) {
            baseUrl = next.defaultBaseUrl
        }
    }

    private fun draft(): ProviderDraft = ProviderDraft(
        id = providerId ?: 0,
        name = name.trim(),
        baseUrl = baseUrl.trim(),
        protocol = protocol,
        enabled = true,
        apiKey = apiKey.trim().takeIf { it.isNotEmpty() }
    )

    /**
     * Base URL 基础校验：非空 + https 协议。
     * 应用全局禁止明文流量（network_security_config），http:// 地址必然失败；
     * 在输入层显式拦截，给出可读提示而非运行时的晦涩错误。
     */
    private fun baseUrlError(): String? {
        val v = baseUrl.trim()
        return when {
            v.isBlank() -> "请先填写 Base URL"
            !v.startsWith("https://", ignoreCase = true) ->
                "Base URL 必须以 https:// 开头（应用已全局禁止明文请求，密钥不得走明文通道）"
            else -> null
        }
    }

    /** 试连：用当前草稿直连拉一次模型列表，不落库 */
    fun test() {
        if (testing) return
        baseUrlError()?.let {
            message = it
            return
        }
        if (apiKey.isBlank() && savedKeyMasked == null) {
            message = "请先填写 API Key"
            return
        }
        viewModelScope.launch {
            testing = true
            message = null
            val result = repository.testConnection(draft())
            message = result.fold(
                onSuccess = { "连接成功，可用模型 $it 个" },
                onFailure = { "连接失败：${it.message}" }
            )
            testing = false
        }
    }

    /** 保存并自动拉取模型列表 */
    fun save() {
        if (saving) return
        if (name.isBlank()) {
            message = "名称不能为空"
            return
        }
        baseUrlError()?.let {
            message = it
            return
        }
        if (apiKey.isBlank() && savedKeyMasked == null) {
            message = "请填写 API Key（密钥仅加密保存在本机 Keystore，不随备份外传）"
            return
        }
        viewModelScope.launch {
            saving = true
            message = null
            try {
                val id = repository.saveProvider(draft())
                val realId = providerId ?: id
                val result = repository.refreshModels(realId)
                message = result.fold(
                    onSuccess = { "已保存，自动拉取到 $it 个模型" },
                    onFailure = { "已保存，但拉取模型失败：${it.message}" }
                )
                if (result.isSuccess) {
                    apiKey = "" // 明文密钥用完即清
                    done = true
                }
            } finally {
                saving = false
            }
        }
    }
}
