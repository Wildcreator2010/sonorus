package com.wildcreator.sonorus.agent

import com.wildcreator.sonorus.domain.agent.tools.CalculatorTool
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalculatorToolTest {

    private fun calc(expression: String) = runBlocking {
        CalculatorTool().execute("""{"expression":${'"'}$expression${'"'}}""")
    }

    @Test
    fun 负号优先级低于乘方() {
        assertEquals("-4", calc("-2^2").text)
        assertEquals("-9", calc("-3^2").text)
        assertEquals("9", calc("(-3)^2").text)
    }

    @Test
    fun 乘方右结合且支持负指数() {
        assertEquals("512", calc("2^3^2").text)
        assertEquals("0.125", calc("2^-3").text)
        assertEquals("0.001953125", calc("2^-3^2").text)
    }

    @Test
    fun 支持科学计数法() {
        assertEquals("100000", calc("1e5").text)
        assertEquals("150", calc("1.5E2").text)
        assertEquals("0.002", calc("2e-3").text)
    }

    @Test
    fun 多小数点和残缺指数按错误返回() {
        assertFalse(calc("1.2.3").ok)
        assertFalse(calc("1e").ok)
        assertFalse(calc("1e-").ok)
    }

    @Test
    fun 常规四则运算仍然正确() {
        assertEquals("9", calc("(1+2)*3").text)
        assertEquals("2", calc("5%3").text)
        assertEquals("7", calc("3+4").text)
        assertTrue(calc("sqrt(9)").ok)
    }
}
