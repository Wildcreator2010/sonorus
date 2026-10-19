package com.wildcreator.sonorus.ui.changelog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wildcreator.sonorus.data.prefs.SettingsManager
import com.wildcreator.sonorus.domain.model.Changelog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 决定这次启动要不要弹更新日志。
 *
 * 首帧不弹：等读到"看过的版本"再决定，否则每次冷启动都会先闪一下弹窗再消失。
 */
@HiltViewModel
class ChangelogViewModel @Inject constructor(
    private val settings: SettingsManager
) : ViewModel() {

    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    init {
        viewModelScope.launch {
            _visible.value = Changelog.shouldShow(settings.changelogSeenVersion.first())
        }
    }

    /** 关闭即记账：同一版本不再弹，直到 [Changelog.version] 被更新 */
    fun dismiss() {
        _visible.value = false
        viewModelScope.launch { settings.saveChangelogSeenVersion(Changelog.version) }
    }
}
