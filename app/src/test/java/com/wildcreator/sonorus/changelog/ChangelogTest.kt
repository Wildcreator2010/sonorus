package com.wildcreator.sonorus.changelog

import com.wildcreator.sonorus.BuildConfig
import com.wildcreator.sonorus.domain.model.Changelog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新日志的"弹一次"规则。
 *
 * 日志内容内置在代码里，唯一的漂移风险是"升了 versionName 却忘了写这一版的日志" ——
 * 那样用户升级后看到的还是上一版的内容，或者干脆不弹。用 BuildConfig 的编译期常量钉住它：
 * 只改版本不改日志，这条用例就红。
 */
class ChangelogTest {

    @Test
    fun `日志版本必须等于 APK 版本`() {
        assertEquals(
            "改了 versionName 就得补这一版的更新日志（否则升级后弹的是旧内容）",
            BuildConfig.VERSION_NAME,
            Changelog.version
        )
    }

    @Test
    fun `每条日志都要有内容`() {
        assertTrue("当前版本不能一条日志都没有", Changelog.notes.isNotEmpty())
        Changelog.notes.forEach { assertTrue("不允许空白条目", it.isNotBlank()) }
    }

    @Test
    fun `首装从未看过日志也要弹`() {
        assertTrue(Changelog.shouldShow(seen = null))
    }

    @Test
    fun `同一版本第二次打开不弹`() {
        assertFalse(Changelog.shouldShow(seen = Changelog.version))
    }

    @Test
    fun `版本变了才弹`() {
        assertTrue(Changelog.shouldShow(seen = "0.0.9"))
    }

    @Test
    fun `空串按没看过处理`() {
        // DataStore 里存进空串的可能性是写入侧的疏漏，不能因此让日志永远不弹
        assertTrue(Changelog.shouldShow(seen = ""))
    }
}
