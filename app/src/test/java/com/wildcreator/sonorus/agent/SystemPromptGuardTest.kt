package com.wildcreator.sonorus.agent

import com.wildcreator.sonorus.domain.agent.SystemPromptGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归：纪律段的位置必须与会话新旧无关。
 *
 * 默认值里内嵌了一份纪律，老会话没有 —— 只在末尾追加就会让新会话的纪律被历史摘要
 * 挤到中间，两种形态混用。组合函数必须先把已有纪律摘掉，再统一放到最末。
 */
class SystemPromptGuardTest {

    private fun occurrences(text: String, needle: String): Int {
        var index = text.indexOf(needle)
        var count = 0
        while (index >= 0) {
            count++
            index = text.indexOf(needle, index + needle.length)
        }
        return count
    }

    @Test
    fun 空提示词时纪律本身即提示词() {
        assertEquals(SystemPromptGuard.RULES, SystemPromptGuard.apply(""))
    }

    @Test
    fun 自定义提示词后追加纪律() {
        val result = SystemPromptGuard.apply("你是一个简洁的助手。")

        assertTrue(result.startsWith("你是一个简洁的助手。"))
        assertTrue(result.contains(SystemPromptGuard.RULES))
    }

    @Test
    fun 已包含纪律时不重复追加() {
        val once = SystemPromptGuard.apply("自定义")
        val twice = SystemPromptGuard.apply(once)

        assertEquals(once, twice)
    }

    @Test
    fun 纪律覆盖两个线上问题() {
        // 思维链泄露 + 重复/未知工具调用，都必须出现在约束里
        assertTrue(SystemPromptGuard.RULES.contains("thought"))
        assertTrue(SystemPromptGuard.RULES.contains("工具名"))
    }

    @Test
    fun 纪律恒在最末尾() {
        val composed = SystemPromptGuard.compose("你是助手。", null)

        assertTrue(composed.endsWith(SystemPromptGuard.RULES))
        assertTrue(composed.startsWith("你是助手。"))
    }

    @Test
    fun 历史摘要排在纪律之前() {
        val composed = SystemPromptGuard.compose("你是助手。", "前面聊过天气")

        assertTrue(composed.indexOf("前面聊过天气") < composed.indexOf(SystemPromptGuard.RULES))
        assertTrue(composed.endsWith(SystemPromptGuard.RULES))
    }

    @Test
    fun 人设里已内嵌纪律时不翻倍而是移到末尾() {
        val withRules = "你是 Sonorus 中的助手：回答准确、简洁，使用与用户相同的语言。\n\n" +
            SystemPromptGuard.RULES

        val composed = SystemPromptGuard.compose(withRules, "前面聊过天气")

        assertEquals(1, occurrences(composed, SystemPromptGuard.RULES))
        assertTrue(composed.endsWith(SystemPromptGuard.RULES))
        assertTrue(composed.startsWith("你是 Sonorus 中的助手"))
    }

    @Test
    fun 反复组合保持幂等() {
        val once = SystemPromptGuard.compose("你是助手。", "摘要")
        val twice = SystemPromptGuard.compose(once, null)

        assertEquals(once, twice)
    }

    @Test
    fun 空白提示词只要有摘要也成立() {
        val composed = SystemPromptGuard.compose("   ", "只有摘要")

        assertTrue(composed.indexOf("只有摘要") < composed.indexOf(SystemPromptGuard.RULES))
        assertEquals(1, occurrences(composed, SystemPromptGuard.RULES))
    }
}
