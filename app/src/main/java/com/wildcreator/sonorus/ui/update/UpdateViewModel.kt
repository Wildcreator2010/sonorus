package com.wildcreator.sonorus.ui.update

import android.content.Context
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wildcreator.sonorus.data.prefs.SettingsManager
import com.wildcreator.sonorus.data.remote.UpdateClient
import com.wildcreator.sonorus.domain.model.ReleaseParser
import com.wildcreator.sonorus.domain.model.ReleaseUpdate
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import android.net.Uri
import javax.inject.Inject

/** 更新流程的可视阶段 */
enum class UpdatePhase { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, FAILED }

data class UpdateUiState(
    val currentVersion: String = "",
    val repository: String = "",
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val update: ReleaseUpdate? = null,
    /** 0..100；-1 = 总长未知的"下载中" */
    val progress: Int = 0,
    val message: String? = null,
    val lastCheckAt: Long = 0L
) {
    /** 发布方没给校验值时不提供安装入口 */
    val installable: Boolean get() = update?.sha256 != null
}

/**
 * 应用内更新。Activity 作用域单例：启动静默检查、设置页手动检查、
 * 以及"有新版本吗？"弹窗必须共用同一份状态，否则会重复请求发布页。
 */
@HiltViewModel
class UpdateViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val client: UpdateClient,
    private val settings: SettingsManager
) : ViewModel() {

    private val _state = MutableStateFlow(
        UpdateUiState(currentVersion = client.currentVersionName(), repository = client.repository)
    )
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    /** 校验通过后交给系统安装页的 content uri；消费一次即清空 */
    private val _installUri = MutableStateFlow<Uri?>(null)
    val installUri: StateFlow<Uri?> = _installUri.asStateFlow()

    /** 下载任务句柄，"取消下载"要用 */
    private var downloadJob: Job? = null

    /**
     * @param manual 设置页点了按钮 = true，可以反复查；
     *   启动时的静默检查受 [CHECK_INTERVAL_MS] 约束，一周最多打一次网络。
     */
    fun check(manual: Boolean) {
        if (_state.value.phase == UpdatePhase.CHECKING || _state.value.phase == UpdatePhase.DOWNLOADING) return
        viewModelScope.launch {
            val lastAt = settings.lastUpdateCheckAt.first()
            if (!manual && lastAt > 0L && System.currentTimeMillis() - lastAt < CHECK_INTERVAL_MS) {
                _state.value = _state.value.copy(lastCheckAt = lastAt)
                return@launch
            }
            _state.value = _state.value.copy(phase = UpdatePhase.CHECKING, message = null)
            val result = client.fetchLatest()
            val now = System.currentTimeMillis()
            result.fold(
                onSuccess = { update ->
                    settings.saveLastUpdateCheck(now)
                    val dismissed = settings.dismissedUpdateVersion.first()
                    val current = client.currentVersionName()
                    when {
                        !ReleaseParser.isNewer(update.versionName, current) ->
                            _state.value = _state.value.copy(
                                phase = UpdatePhase.UP_TO_DATE, currentVersion = current,
                                update = null, message = "已是最新版 $current", lastCheckAt = now
                            )
                        update.versionName == dismissed ->
                            _state.value = _state.value.copy(
                                phase = UpdatePhase.IDLE, update = update,
                                message = "已跳过 ${update.versionName}", lastCheckAt = now
                            )
                        update.sha256 == null ->
                            // 有新版但发布方没写校验值：宁可不动，也不能装一个无法验证的包
                            _state.value = _state.value.copy(
                                phase = UpdatePhase.FAILED, update = update,
                                message = "发现 ${update.versionName}，但该发布未提供 sha256，已拒绝安装",
                                lastCheckAt = now
                            )
                        else -> _state.value = _state.value.copy(
                            phase = UpdatePhase.AVAILABLE, update = update,
                            message = null, lastCheckAt = now
                        )
                    }
                },
                onFailure = { e ->
                    // 静默检查失败不打扰用户：GitHub 接口偶发不通是常态
                    _state.value = _state.value.copy(
                        phase = if (manual) UpdatePhase.FAILED else UpdatePhase.IDLE,
                        message = if (manual) "检查失败：${e.message ?: "网络不通"}" else null,
                        lastCheckAt = if (manual) lastAt else now
                    )
                    if (manual) settings.saveLastUpdateCheck(now)
                }
            )
        }
    }

    /** 下载 + 校验，成功后把安装 uri 交给 Activity */
    fun downloadAndInstall() {
        val update = _state.value.update ?: return
        _state.value = _state.value.copy(phase = UpdatePhase.DOWNLOADING, progress = 0, message = null)
        downloadJob = viewModelScope.launch {
            client.download(update) { percent ->
                _state.value = _state.value.copy(progress = percent)
            }.onSuccess { file ->
                val uri = runCatching {
                    FileProvider.getUriForFile(
                        appContext, "${appContext.packageName}.fileprovider", file
                    )
                }.getOrNull()
                if (uri == null) {
                    _state.value = _state.value.copy(
                        phase = UpdatePhase.FAILED, message = "无法生成安装文件路径"
                    )
                } else {
                    _installUri.value = uri
                    _state.value = _state.value.copy(
                        phase = UpdatePhase.IDLE, update = null, progress = 0,
                        message = "请在系统安装页确认安装"
                    )
                }
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    phase = UpdatePhase.FAILED, progress = 0,
                    message = e.message ?: "下载失败"
                )
            }
        }
    }

    /** 取消下载：回到空闲，残留文件由下一次 download 清理 */
    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _state.value = _state.value.copy(phase = UpdatePhase.IDLE, progress = 0)
    }

    /** 安装页调起失败（本机没有安装程序 / Intent 被拒） */
    fun reportInstallLaunchFailure(message: String) {
        _state.value = _state.value.copy(phase = UpdatePhase.FAILED, message = message)
    }

    /** "以后再说"：同一版本不再自动提示，直到出现更新的版本 */
    fun dismiss() {
        val update = _state.value.update ?: return
        _state.value = _state.value.copy(phase = UpdatePhase.IDLE, update = null)
        viewModelScope.launch { settings.saveDismissedUpdateVersion(update.versionName) }
    }

    fun consumeInstallUri() {
        _installUri.value = null
    }

    /** 关掉结果提示（"已是最新版"等），避免设置页一直挂着 */
    fun acknowledgeMessage() {
        _state.value = _state.value.copy(message = null, phase = UpdatePhase.IDLE)
    }

    private companion object {
        /** 静默检查最小间隔：7 天 */
        const val CHECK_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000
    }
}
