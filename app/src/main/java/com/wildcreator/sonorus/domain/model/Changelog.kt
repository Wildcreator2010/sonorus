package com.wildcreator.sonorus.domain.model

/**
 * 应用内置的更新日志：升级后（含首装）第一次打开弹一次，看过就不再打扰。
 *
 * 只存当前版本 —— 完整历史在 GitHub Release 正文里，应用内不做第二份真相。
 * 维护约定：改 `app/build.gradle.kts` 的 versionName 时必须同步改这里，
 * [ChangelogTest] 拿 BuildConfig.VERSION_NAME 断言两者一致，漏改直接红。
 */
object Changelog {

    const val version = "0.1.6"

    val notes = listOf(
        "聊天页右上角多了两个按钮：回到对话开头 / 回到结尾，对话长到滚得动时才出现。",
        "主页右上角多了「捐赠」：两张收款码，自愿扫，金额随意。",
        "更新后第一次打开会弹这份更新日志，看过就不再出现。"
    )

    /**
     * 该不该弹。
     * @param seen 用户上次看过日志的版本；null 或空串 = 从没看过（首装也算）
     */
    fun shouldShow(seen: String?): Boolean =
        seen?.takeIf { it.isNotBlank() } != version
}
