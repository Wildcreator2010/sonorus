package com.wildcreator.sonorus.ui.theme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wildcreator.sonorus.data.background.ChatBackgroundSettings
import com.wildcreator.sonorus.data.prefs.SettingsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Root 层 ViewModel：订阅主题与字号偏好，传入 [SonorusTheme]。
 *
 * 设计理由：
 * - Composable 内直接读 DataStore 会让首帧渲染阻塞在 IO 上（观察 flow）；
 * - 由本 ViewModel 统一订阅后传入，首帧就能拿到默认值，避免"先闪浅色再切深色"的抖动。
 */
@HiltViewModel
class RootViewModel @Inject constructor(
    settings: SettingsManager
) : ViewModel() {
    val themeMode = settings.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ThemeMode.SYSTEM)
    val fontScale = settings.fontScale
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FontScale.DEFAULT)
    val chatBackground = settings.chatBackground
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChatBackgroundSettings())
}
