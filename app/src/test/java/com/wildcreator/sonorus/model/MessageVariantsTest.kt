package com.wildcreator.sonorus.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多版本编解码契约：
 * content 列必须永远等于 versions[activeVersion].content —— 切换版本、追加版本
 * 这两条路径都要维持它，否则"界面显示的"和"送模型的"会分家。
 */
class MessageVariantsTest {

    private fun version(text: String, reasoning: String? = null, note: String? = null) =
        AnswerVersion(content = text, reasoning = reasoning, note = note, createdAt = 1L)

    @Test
    fun 单版本且无推理不落库() {
        assertNull(MessageVariants.encode(listOf(version("只有正文"))))
    }

    @Test
    fun 单版本带推理必须落库() {
        val json = MessageVariants.encode(listOf(version("正文", reasoning = "推理")))
        assertTrue("带推理就必须存下来", json != null)
        val back = MessageVariants.decode(json, fallback = version("丢失"))
        assertEquals("推理", back.single().reasoning)
    }

    @Test
    fun 非法JSON回退为单版本() {
        val fallback = version("原文")
        val list = MessageVariants.decode("{ 坏数据", fallback)
        assertEquals(listOf(fallback), list)
    }

    @Test
    fun 空正文版本必须保留原位() {
        // 旧断言要求这里塌成 1 条 —— 那正是 bug 的根源：库里存的 activeVersion
        // 是按写入时的位置记的，解码时删掉任何一版都会让后面所有版本前移，
        // 于是 content 指向 A 版、推理与批注却来自 B 版。空正文本身合法。
        val json = """[{"content":"","reasoning":"先想了想"},{"content":"有效"}]"""
        val list = MessageVariants.decode(json, fallback = version("兜底"))

        assertEquals(2, list.size)
        assertEquals("", list[0].content)
        assertEquals("先想了想", list[0].reasoning)
        assertEquals("有效", list[1].content)
    }

    @Test
    fun 全部版本缺失时才用兜底() {
        val list = MessageVariants.decode("[]", fallback = version("兜底"))

        assertEquals(1, list.size)
        assertEquals("兜底", list.first().content)
    }

    @Test
    fun 追加版本后当前指向最新() {
        val merged = MessageVariants.append(listOf(version("第一版")), version("第二版", note = "太长了"))
        assertEquals(2, merged.size)
        val (mirror, versionsJson, active) = MessageVariants.entry(merged, merged.lastIndex)
        assertEquals("第二版", mirror)
        assertEquals(1, active)
        assertTrue(versionsJson!!.contains("太长了"))
    }

    @Test
    fun 版本数超上限时丢弃最旧() {
        var list = listOf(version("v0"))
        repeat(MessageVariants.MAX_VERSIONS + 5) { i ->
            list = MessageVariants.append(list, version("v${i + 1}"))
        }
        assertEquals(MessageVariants.MAX_VERSIONS, list.size)
        assertEquals("v15", list.last().content)
        assertFalse("最旧的一版应被淘汰", list.any { it.content == "v0" })
    }

    @Test
    fun 切换版本同步正文与下标() {
        val list = listOf(version("甲"), version("乙"), version("丙"))
        val (mirror, _, active) = MessageVariants.entry(list, 0)
        assertEquals("甲", mirror)
        assertEquals(0, active)
        // 越界下标被钳到合法区间，不能让一条脏数据写坏整行
        assertEquals("丙", MessageVariants.entry(list, 99).first)
    }

    @Test
    fun 修订指令包含批注与选区() {
        val withQuote = MessageVariants.revisionInstruction("第二段太啰嗦", "删掉重复举例")
        assertTrue(withQuote.contains("【批注针对的原文片段】"))
        assertTrue(withQuote.contains("第二段太啰嗦"))
        assertTrue(withQuote.contains("【批注】"))
        assertTrue(withQuote.contains("删掉重复举例"))
        assertTrue(withQuote.contains("直接输出修改后的完整回答"))
    }

    @Test
    fun 整条批注不出现选区标题() {
        val whole = MessageVariants.revisionInstruction(null, "换个更短的说法")
        assertFalse(whole.contains("【批注针对的原文片段】"))
        assertTrue(whole.contains("换个更短的说法"))
    }
}
