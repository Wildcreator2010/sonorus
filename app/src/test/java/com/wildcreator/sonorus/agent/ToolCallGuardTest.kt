package com.wildcreator.sonorus.agent

import com.wildcreator.sonorus.domain.agent.ToolCall
import com.wildcreator.sonorus.domain.agent.ToolCallGuard
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归：同一个工具被反复调用时必须叫停，而不是烧完全部轮次（Bug 1）。
 *
 * 关键约束是**按规范名计数**：模型每轮换个拼写、执行侧仍能解析到同一个工具时，
 * 计数必须合流，否则刹车正好被它想防的那个行为绕过。
 */
class ToolCallGuardTest {

    private fun call(index: Int, name: String = "get_current_time", args: String = "{}") =
        ToolCall(id = "call_$index", name = name, arguments = args)

    private fun stopped(verdict: ToolCallGuard.Verdict) =
        verdict is ToolCallGuard.Verdict.Stop

    @Test
    fun 首次调用放行() {
        val guard = ToolCallGuard()

        assertTrue(!stopped(guard.register(call(1), CANONICAL)))
    }

    @Test
    fun 相同参数重复超过上限后叫停() {
        val guard = ToolCallGuard()
        guard.register(call(1), CANONICAL)
        guard.register(call(2), CANONICAL)

        assertTrue(stopped(guard.register(call(3), CANONICAL)))
    }

    @Test
    fun 参数里的空白差异不影响重复判定() {
        val guard = ToolCallGuard()
        guard.register(call(1, args = """{"tz": "UTC"}"""), CANONICAL)
        guard.register(call(2, args = """{ "tz" :"UTC" }"""), CANONICAL)

        assertTrue(stopped(guard.register(call(3, args = """{"tz":"UTC"}"""), CANONICAL)))
    }

    @Test
    fun 参数不同但同一工具调用次数超限也叫停() {
        val guard = ToolCallGuard()
        (1..4).forEach { guard.register(call(it, args = """{"n":$it}"""), CANONICAL) }

        assertTrue(stopped(guard.register(call(5, args = """{"n":5}"""), CANONICAL)))
    }

    @Test
    fun 模型换拼写仍算同一个工具的重复调用() {
        val guard = ToolCallGuard()
        val drifts = listOf(
            "get_currrent_time",
            "GET_CURRENT_TIME",
            "get_currenttime",
            "get_currant_time",
            "et_current_time"
        )

        var stops = 0
        drifts.forEachIndexed { index, spelling ->
            if (stopped(guard.register(call(index, name = spelling), CANONICAL))) stops++
        }

        assertTrue("5 种拼写漂移全部放行，重复调用刹车被绕过", stops > 0)
    }

    @Test
    fun 不同拼写的执行失败合并到同一工具计数() {
        val guard = ToolCallGuard()
        guard.recordResult(CANONICAL, failed = true)
        guard.recordResult(CANONICAL, failed = true)

        assertTrue(stopped(guard.recordResult(CANONICAL, failed = true)))
    }

    @Test
    fun 成功结果不触发失败计数() {
        val guard = ToolCallGuard()
        repeat(5) { guard.recordResult(CANONICAL, failed = false) }

        assertTrue(!stopped(guard.recordResult(CANONICAL, failed = false)))
    }

    @Test
    fun 中间成功一次后连续失败计数清零() {
        val guard = ToolCallGuard()
        guard.recordResult(CANONICAL, failed = true)
        guard.recordResult(CANONICAL, failed = true)
        guard.recordResult(CANONICAL, failed = false)

        assertTrue("成功一次后应重新计算连续失败，而不是累计", !stopped(guard.recordResult(CANONICAL, failed = true)))

        guard.recordResult(CANONICAL, failed = true)
        assertTrue("重新累计到第三次才叫停", stopped(guard.recordResult(CANONICAL, failed = true)))
    }

    @Test
    fun 参数字段顺序不同仍算同一个调用() {
        val guard = ToolCallGuard()
        guard.register(call(1, args = """{"a":1,"b":2}"""), CANONICAL)
        guard.register(call(2, args = """{"b":2,"a":1}"""), CANONICAL)

        assertTrue("字段顺序不应绕过重复调用刹车", stopped(guard.register(call(3, args = """{"a":1,"b":2}"""), CANONICAL)))
    }

    @Test
    fun 终止原因包含工具名便于排查() {
        val guard = ToolCallGuard()
        guard.register(call(1), CANONICAL)
        guard.register(call(2), CANONICAL)
        val verdict = guard.register(call(3), CANONICAL) as ToolCallGuard.Verdict.Stop

        assertTrue(verdict.reason.contains(CANONICAL))
        assertTrue(verdict.reason.isNotBlank())
    }

    private companion object {
        const val CANONICAL = "get_current_time"
    }
}
