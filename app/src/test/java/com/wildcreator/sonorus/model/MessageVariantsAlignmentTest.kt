package com.wildcreator.sonorus.model

import com.wildcreator.sonorus.domain.model.AnswerVersion
import com.wildcreator.sonorus.domain.model.MessageVariants
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * content / versionsJson / activeVersion 三者的镜像不能失步。
 *
 * 历史 bug：decode 会顺手过滤掉空正文版本，列表因此变短，
 * 而库里记的 activeVersion 是写入时的位置 —— 于是"当前版本"悄悄指向了另一版，
 * 界面显示 A 版的正文、却带上 B 版的推理与批注，而且用户无从察觉。
 * 空正文本身合法（模型只出推理、或只发起工具调用）。
 */
class MessageVariantsAlignmentTest {

    @Test
    fun 空正文版本不会让当前版本错位() {
        val versions = listOf(
            AnswerVersion(content = "", reasoning = "先想了想", createdAt = 1L),
            AnswerVersion(content = "最终答案", createdAt = 2L)
        )
        val json = MessageVariants.encode(versions)!!

        val decoded = MessageVariants.decode(json, AnswerVersion(content = "兜底", createdAt = 0L))

        // 两版都还在，位置与写入时一致
        assertEquals(2, decoded.size)
        assertEquals("", decoded[0].content)
        assertEquals("最终答案", decoded[1].content)
        // 指向第 0 版时，读到的正文必须是空串，而不是滑到第 1 版
        val (content, _, active) = MessageVariants.entry(decoded, index = 0)
        assertEquals("", content)
        assertEquals(0, active)
    }

    @Test
    fun entry始终从生效版本取正文() {
        val versions = listOf(
            AnswerVersion(content = "第一版", createdAt = 1L),
            AnswerVersion(content = "第二版", createdAt = 2L),
            AnswerVersion(content = "第三版", createdAt = 3L)
        )

        for (index in versions.indices) {
            val (content, _, active) = MessageVariants.entry(versions, index)
            assertEquals(versions[index].content, content)
            assertEquals(index, active)
        }
    }

    @Test
    fun 越界的activeVersion被钳回有效区间而不是抛异常() {
        val versions = listOf(AnswerVersion(content = "唯一版", createdAt = 1L))

        val (content, _, active) = MessageVariants.entry(versions, index = 99)

        assertEquals("唯一版", content)
        assertEquals(0, active)
    }

    @Test
    fun 单版本无推理无批注时不落JSON带了推理就必须落() {
        // 与旧数据形态一致以省空间；但推理/批注只存在于 versionsJson 里，省掉就等于丢信息
        val plain = listOf(AnswerVersion(content = "答案", createdAt = 1L))
        val withReasoning = listOf(AnswerVersion(content = "答案", reasoning = "想法", createdAt = 1L))

        assertEquals(null, MessageVariants.encode(plain))
        val encoded = MessageVariants.encode(withReasoning)
        assertEquals(true, encoded != null && encoded.isNotBlank())
        // 落库后必须还能原样读回推理
        assertEquals("想法", MessageVariants.decode(encoded, plain.first()).first().reasoning)
    }
}
