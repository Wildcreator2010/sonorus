package com.wildcreator.sonorus.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wildcreator.sonorus.data.local.RequestSummary
import com.wildcreator.sonorus.data.prefs.CONTEXT_LIMIT_UNLIMITED
import com.wildcreator.sonorus.data.prefs.DEFAULT_SYSTEM_PROMPT
import com.wildcreator.sonorus.data.prefs.SettingsManager
import com.wildcreator.sonorus.domain.agent.ThinkingLevel
import com.wildcreator.sonorus.domain.repository.ProviderRepository
import com.wildcreator.sonorus.domain.repository.StatsRepository
import com.wildcreator.sonorus.ui.theme.FontScale
import com.wildcreator.sonorus.ui.theme.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 上下文长度预设（按消息条数计）。
 * 说明：用"条数"而非 token，避免为一个滑杆引入 tokenizer 依赖；
 * 各家模型的 token 计法不同，条数是一个可预期的近似控制。
 */
val CONTEXT_LIMIT_PRESETS = listOf(
    CONTEXT_LIMIT_UNLIMITED to "不限制",
    10 to "10 条",
    20 to "20 条",
    50 to "50 条",
    100 to "100 条"
)

/** 提供商成功率行：提供商元数据 + 该提供商的聚合结果 */
data class ProviderStatRow(
    val providerId: Long,
    val providerName: String,
    val protocol: String,
    val total: Int,
    val rate: Float?
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsManager,
    private val providerRepository: ProviderRepository,
    private val statsRepository: StatsRepository
) : ViewModel() {

    val contextLimit: StateFlow<Int> = settings.contextLimit
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CONTEXT_LIMIT_UNLIMITED)

    val defaultSystemPrompt: StateFlow<String> = settings.defaultSystemPrompt
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DEFAULT_SYSTEM_PROMPT)

    val thinkingLevel: StateFlow<ThinkingLevel> = settings.thinkingLevel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ThinkingLevel.OFF)

    val themeMode: StateFlow<ThemeMode> = settings.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ThemeMode.SYSTEM)

    val fontScale: StateFlow<FontScale> = settings.fontScale
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FontScale.DEFAULT)

    private val _globalStats = MutableStateFlow<RequestSummary?>(null)
    val globalStats: StateFlow<RequestSummary?> = _globalStats.asStateFlow()

    /**
     * 按提供商聚合的成功率。
     * statsSince 变化（用户重置统计）时通过 flatMapLatest 重新订阅，
     * 避免把重置前的样本继续算进来。
     */
    val providerStats: StateFlow<List<ProviderStatRow>> = settings.statsSince
        .flatMapLatest { since ->
            combine(
                statsRepository.observeByProvider(since),
                providerRepository.observeProviders()
            ) { summaries, providers ->
                val byId = summaries.associateBy { it.providerId }
                providers.map { p ->
                    val s = byId[p.id]
                    ProviderStatRow(
                        providerId = p.id,
                        providerName = p.name,
                        protocol = p.protocol.label,
                        total = s?.total ?: 0,
                        rate = s?.rate
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 保存输入框草稿，避免未提交时被流更新覆盖 */
    var promptDraft: String? = null

    init {
        refreshStats()
    }

    /** 拉取全局汇总（一次性查询，供卡片展示） */
    fun refreshStats() {
        viewModelScope.launch {
            _globalStats.value = statsRepository.summary(settings.statsSince.first())
        }
    }

    fun saveContextLimit(limit: Int) {
        viewModelScope.launch { settings.saveContextLimit(limit) }
    }

    fun saveThinkingLevel(level: ThinkingLevel) {
        viewModelScope.launch { settings.saveThinkingLevel(level) }
    }

    fun saveSystemPrompt(prompt: String) {
        viewModelScope.launch {
            settings.saveDefaultSystemPrompt(prompt)
            promptDraft = null
        }
    }

    fun saveThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settings.saveThemeMode(mode) }
    }

    fun saveFontScale(scale: FontScale) {
        viewModelScope.launch { settings.saveFontScale(scale) }
    }

    /**
     * 重置统计：清空历史样本，并把时间窗推到"现在"。
     * 只清样本不清设置 —— 用户的上下文长度、思考等级不受影响。
     */
    fun resetStats() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            settings.saveStatsSince(now)
            statsRepository.clear()
            _globalStats.value = statsRepository.summary(now)
        }
    }
}
