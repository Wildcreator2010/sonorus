package com.wildcreator.sonorus.agent

import com.wildcreator.sonorus.domain.agent.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 回归：模型把 get_current_time 拼成 get_currrent_time 时不能再落入"未注册工具"循环（Bug 1） */
class ToolRegistryResolveTest {

    private val registry = ToolRegistry()

    @Test
    fun 精确名称可解析() {
        assertEquals("get_current_time", registry.resolve("get_current_time")?.name)
    }

    @Test
    fun 三个r的拼写错误仍能解析到正确工具() {
        assertEquals("get_current_time", registry.resolve("get_currrent_time")?.name)
    }

    @Test
    fun 忽略大小写() {
        assertEquals("get_current_time", registry.resolve("GET_CURRENT_TIME")?.name)
    }

    @Test
    fun 首尾空白被容忍() {
        assertNotNull(registry.resolve("  get_current_time  "))
    }

    @Test
    fun 完全不同且无注册工具的名称解析失败() {
        assertNull(registry.resolve("delete_everything"))
    }

    @Test
    fun 空名称解析失败() {
        assertNull(registry.resolve(""))
        assertNull(registry.resolve("   "))
    }

    @Test
    fun 暴露给模型的清单里没有拼写错误的名字() {
        val names = registry.specs().map { it.name }
        assertEquals(true, names.all { it == "get_current_time" || it == "calculator" })
    }
}
