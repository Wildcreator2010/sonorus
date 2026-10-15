package com.wildcreator.sonorus.agent

import com.wildcreator.sonorus.domain.agent.VisibleTextFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 回归：模型把思维链写进正文时，用户可见文本必须不含它（Bug 2） */
class VisibleTextFilterTest {

    private fun collect(vararg chunks: String): String {
        val filter = VisibleTextFilter()
        val sb = StringBuilder()
        chunks.forEach { sb.append(filter.accept(it)) }
        sb.append(filter.finish())
        return sb.toString()
    }

    @Test
    fun 普通正文原样通过() {
        assertEquals("现在几点？", collect("现在几点？"))
    }

    @Test
    fun 整块思维链被剥离() {
        assertEquals("最终答案", collect("<thought>内部推理</thought>最终答案"))
    }

    @Test
    fun 跨分片的标签也能剥离() {
        // SSE 会在任意位置切分；残缺标签绝不能漏给用户
        assertEquals("答案", collect("<tho", "ught>内", "部</tho", "ught>答案"))
    }

    @Test
    fun 未闭合的思维链不会泄漏() {
        val filter = VisibleTextFilter()
        val visible = StringBuilder()
        visible.append(filter.accept("<analysis>还没推理完"))
        visible.append(filter.finish())

        assertEquals("", visible.toString())
        assertTrue(filter.strippedAnything)
    }

    @Test
    fun 标签大小写不敏感() {
        assertEquals("结果", collect("<THOUGHT>xx</Thought>结果"))
    }

    @Test
    fun 剥离后前后正文都保留() {
        assertEquals("开头结尾", collect("开头<thinking>思考中</thinking>结尾"))
    }

    @Test
    fun 流在残缺标签处断开时不留半截标签() {
        // 上游断流正好切在 <thought 后面：尾巴是标签前缀，不能当成正文交出去
        assertEquals("答案是", collect("答案是<thought"))
    }

    @Test
    fun 完整标签收尾时正文照常保留() {
        assertEquals("答案是 42", collect("答案是 42<thought>推理"))
    }

    @Test
    fun 没有思维链时不算剥离过() {
        val filter = VisibleTextFilter()
        filter.accept("纯正文")
        filter.finish()

        assertFalse(filter.strippedAnything)
    }
}
