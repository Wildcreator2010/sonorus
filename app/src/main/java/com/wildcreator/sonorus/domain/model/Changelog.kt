package com.wildcreator.sonorus.domain.model

/**
 * 应用内置的更新日志：升级后（含首装）第一次打开弹一次，看过就不再打扰。
 *
 * 只存当前版本 —— 完整历史在 GitHub Release 正文里，应用内不做第二份真相。
 * 维护约定：改 `app/build.gradle.kts` 的 versionName 时必须同步改这里，
 * [ChangelogTest] 拿 BuildConfig.VERSION_NAME 断言两者一致，漏改直接红。
 */
object Changelog {

    const val version = "0.1.7"

    val notes = listOf(
        "聊天输入栏可添加文本附件：txt、Markdown、JSON、日志和常见代码文件，最多 4 个；随消息保存，也包含在加密备份里。",
        "设置 → 外观新增聊天背景：可选本机图片、调压暗强度、恢复默认，只在聊天页显示。",
        "修复删除正在回答的会话后，新会话一直显示「正在回答」而发不出消息；停止和删除现在会立即断开上游请求。",
        "兼容忽略流式、直接返回完整 JSON 的网关，以及 content 数组、refusal、多行 SSE 等响应形态，减少「模型返回空内容」。",
        "一并修复：计算器负数乘方与 1e5、Gemini 多帧工具调用、Anthropic 纯图片消息、JSON 导出空消息、发布脚本与批处理退出码。"
    )

    /**
     * 该不该弹。
     * @param seen 用户上次看过日志的版本；null 或空串 = 从没看过（首装也算）
     */
    fun shouldShow(seen: String?): Boolean =
        seen?.takeIf { it.isNotBlank() } != version
}
