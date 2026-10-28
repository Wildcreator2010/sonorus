package com.wildcreator.sonorus.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.wildcreator.sonorus.data.background.ChatBackgroundSettings
import com.wildcreator.sonorus.domain.agent.SystemPromptGuard
import com.wildcreator.sonorus.domain.agent.ThinkingLevel
import com.wildcreator.sonorus.ui.theme.FontScale
import com.wildcreator.sonorus.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * 默认 system prompt：新建会话时作为初始值。
 *
 * 这里直接内嵌 [SystemPromptGuard.RULES]，用户在设置页能看见并自行调整；
 * 而请求组装处仍会调用 [SystemPromptGuard.apply] 兜底 —— 改过 prompt 的会话、
 * 默认值升级前就已存在的会话都不会漏掉纪律约束。
 */
const val DEFAULT_SYSTEM_PROMPT =
    "你是 Sonorus 中的助手：回答准确、简洁，使用与用户相同的语言。\n\n" + SystemPromptGuard.RULES

/** 上下文长度（消息条数）取值：0 表示不限制 */
const val CONTEXT_LIMIT_UNLIMITED = 0

/** 记录全局偏好：默认 提供商+模型、上下文长度、默认 system prompt */
class SettingsManager(private val dataStore: DataStore<Preferences>) {

    /** 生产入口：沿用 Context 上的单例 DataStore */
    constructor(context: Context) : this(context.dataStore)

    private object Keys {
        val LAST_PROVIDER_ID = longPreferencesKey("last_provider_id")
        val LAST_MODEL_ID = stringPreferencesKey("last_model_id")
        val CONTEXT_LIMIT = intPreferencesKey("context_limit")
        val DEFAULT_SYSTEM_PROMPT = stringPreferencesKey("default_system_prompt")
        val THINKING_LEVEL = stringPreferencesKey("thinking_level")
        val STATS_SINCE = longPreferencesKey("stats_since")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val FONT_SCALE = floatPreferencesKey("font_scale")
        val CHAT_BACKGROUND_PATH = stringPreferencesKey("chat_background_path")
        val CHAT_BACKGROUND_OPACITY = floatPreferencesKey("chat_background_opacity")
        val LAST_UPDATE_CHECK = longPreferencesKey("last_update_check_at")
        val DISMISSED_UPDATE = stringPreferencesKey("dismissed_update_version")
        val CHANGELOG_SEEN = stringPreferencesKey("changelog_seen_version")
    }

    val lastProviderId: Flow<Long?> = dataStore.data.map { it[Keys.LAST_PROVIDER_ID] }
    val lastModelId: Flow<String?> = dataStore.data.map { it[Keys.LAST_MODEL_ID] }

    /**
     * 送入模型的历史消息条数上限，0 = 不限制。
     * 衡量的是消息条数（user/assistant/tool 各算一条），不是 token —— 避免引入额外 tokenizer 依赖。
     */
    val contextLimit: Flow<Int> = dataStore.data.map {
        it[Keys.CONTEXT_LIMIT] ?: CONTEXT_LIMIT_UNLIMITED
    }

    val defaultSystemPrompt: Flow<String> = dataStore.data.map {
        it[Keys.DEFAULT_SYSTEM_PROMPT] ?: DEFAULT_SYSTEM_PROMPT
    }

    /** 全局默认思考等级，新建会话继承；会话内可单独覆盖 */
    val thinkingLevel: Flow<ThinkingLevel> = dataStore.data.map { prefs ->
        prefs[Keys.THINKING_LEVEL]?.let { name ->
            runCatching { ThinkingLevel.valueOf(name) }.getOrNull()
        } ?: ThinkingLevel.OFF
    }

    /** 统计起始时间：用户"重置统计"后更新，用于只统计重置之后的数据 */
    val statsSince: Flow<Long> = dataStore.data.map { it[Keys.STATS_SINCE] ?: 0L }

    /** 主题模式；非法值/未设置都回退到 SYSTEM（跟随系统） */
    val themeMode: Flow<ThemeMode> = dataStore.data.map { prefs ->
        prefs[Keys.THEME_MODE]?.let { name ->
            runCatching { ThemeMode.valueOf(name) }.getOrNull()
        } ?: ThemeMode.SYSTEM
    }

    /** 字号缩放；将 DataStore 中的 float 映射回最接近的档位，避免存下非法中间值 */
    val fontScale: Flow<FontScale> = dataStore.data.map { prefs ->
        val stored = prefs[Keys.FONT_SCALE]
        if (stored == null) FontScale.DEFAULT
        else FontScale.entries.minByOrNull { kotlin.math.abs(it.value - stored) } ?: FontScale.DEFAULT
    }

    val chatBackground: Flow<ChatBackgroundSettings> = dataStore.data.map { prefs ->
        ChatBackgroundSettings.fromStored(prefs[Keys.CHAT_BACKGROUND_PATH], prefs[Keys.CHAT_BACKGROUND_OPACITY])
    }

    suspend fun saveChatBackgroundPath(path: String) {
        require(path.isNotBlank())
        dataStore.edit { it[Keys.CHAT_BACKGROUND_PATH] = path }
    }

    suspend fun saveChatBackgroundOpacity(opacity: Float) {
        val normalized = ChatBackgroundSettings.fromStored(null, opacity).opacity
        dataStore.edit { prefs ->
            if (!prefs[Keys.CHAT_BACKGROUND_PATH].isNullOrBlank()) {
                prefs[Keys.CHAT_BACKGROUND_OPACITY] = normalized
            }
        }
    }

    suspend fun resetChatBackground() {
        dataStore.edit { prefs ->
            prefs.remove(Keys.CHAT_BACKGROUND_PATH)
            prefs.remove(Keys.CHAT_BACKGROUND_OPACITY)
        }
    }

    /** 上次成功发起更新检查的时刻；0 = 从未检查过 */
    val lastUpdateCheckAt: Flow<Long> = dataStore.data.map {
        it[Keys.LAST_UPDATE_CHECK] ?: 0L
    }

    /** 用户点过"以后再说"的版本：同版本不再打扰，出现更新版本时才重新提示 */
    val dismissedUpdateVersion: Flow<String?> = dataStore.data.map {
        it[Keys.DISMISSED_UPDATE]
    }

    suspend fun saveLastUpdateCheck(at: Long) {
        dataStore.edit { prefs -> prefs[Keys.LAST_UPDATE_CHECK] = at }
    }

    suspend fun saveDismissedUpdateVersion(version: String?) {
        dataStore.edit { prefs ->
            if (version.isNullOrBlank()) prefs.remove(Keys.DISMISSED_UPDATE)
            else prefs[Keys.DISMISSED_UPDATE] = version
        }
    }

    /** 用户看过更新日志的版本；null = 从没弹过（首装也弹一次） */
    val changelogSeenVersion: Flow<String?> = dataStore.data.map {
        it[Keys.CHANGELOG_SEEN]
    }

    suspend fun saveChangelogSeenVersion(version: String) {
        dataStore.edit { prefs -> prefs[Keys.CHANGELOG_SEEN] = version }
    }

    suspend fun saveThinkingLevel(level: ThinkingLevel) {
        dataStore.edit { prefs ->
            prefs[Keys.THINKING_LEVEL] = level.name
        }
    }

    suspend fun saveStatsSince(epochMs: Long) {
        dataStore.edit { prefs ->
            prefs[Keys.STATS_SINCE] = epochMs
        }
    }

    suspend fun saveSelection(providerId: Long, modelId: String) {
        dataStore.edit { prefs ->
            prefs[Keys.LAST_PROVIDER_ID] = providerId
            prefs[Keys.LAST_MODEL_ID] = modelId
        }
    }

    suspend fun saveContextLimit(limit: Int) {
        dataStore.edit { prefs ->
            prefs[Keys.CONTEXT_LIMIT] = limit.coerceAtLeast(0)
        }
    }

    suspend fun saveDefaultSystemPrompt(prompt: String) {
        dataStore.edit { prefs ->
            if (prompt.isBlank()) prefs.remove(Keys.DEFAULT_SYSTEM_PROMPT)
            else prefs[Keys.DEFAULT_SYSTEM_PROMPT] = prompt
        }
    }

    suspend fun saveThemeMode(mode: ThemeMode) {
        dataStore.edit { prefs ->
            // SYSTEM 是默认值，直接移除键以保持存储精简
            if (mode == ThemeMode.SYSTEM) prefs.remove(Keys.THEME_MODE)
            else prefs[Keys.THEME_MODE] = mode.name
        }
    }

    suspend fun saveFontScale(scale: FontScale) {
        dataStore.edit { prefs ->
            if (scale == FontScale.DEFAULT) prefs.remove(Keys.FONT_SCALE)
            else prefs[Keys.FONT_SCALE] = scale.value
        }
    }
}
