package com.wildcreator.sonorus.agent

import com.wildcreator.sonorus.domain.agent.tools.CalculatorTool
import com.wildcreator.sonorus.domain.agent.tools.TimeTool
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归：工具的业务失败必须是**可判定的结果**，而不是靠抛异常（Bug 2）。
 *
 * 内置工具全都把错误包成返回文本，执行器只看 runCatching().isFailure，
 * 于是"反复失败"那条刹车永远收不到信号，错误文本还原样喂回模型诱使它重试。
 */
class ToolResultReportingTest {

    @Test
    fun 语法错误的表达式算一次失败() {
        val result = runBlocking { CalculatorTool().execute("""{"expression":"1+"}""") }

        assertFalse(result.ok)
        assertTrue(result.text.contains("计算失败"))
    }

    @Test
    fun 缺少参数算一次失败() {
        val result = runBlocking { CalculatorTool().execute("{}") }

        assertFalse(result.ok)
    }

    @Test
    fun 未知函数算一次失败() {
        val result = runBlocking { CalculatorTool().execute("""{"expression":"foo(1)"}""") }

        assertFalse(result.ok)
    }

    @Test
    fun 正常计算算成功() {
        val result = runBlocking { CalculatorTool().execute("""{"expression":"1+2"}""") }

        assertTrue(result.ok)
        assertEquals("3", result.text)
    }

    @Test
    fun 时间工具成功返回当前时间() {
        val result = runBlocking { TimeTool().execute("{}") }

        assertTrue(result.ok)
        assertTrue(result.text.isNotBlank())
    }
}
