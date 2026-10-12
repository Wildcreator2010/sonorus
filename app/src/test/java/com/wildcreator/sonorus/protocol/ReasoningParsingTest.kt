package com.wildcreator.sonorus.data.remote.protocol

import com.wildcreator.sonorus.domain.agent.UnifiedRole
import com.wildcreator.sonorus.testJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * 推理（思考）内容解析测试。
 *
 * 这三家的字段名/嵌套完全不同，而且都会和正文出现在同一帧里，
 * 一旦解析错位就会出现"思考过程里混进答案"或"答案丢字"——所以逐家断言。
 * 同包放置：适配器是 internal-free 的 public 类，同包可省一批 import。
 */
class ReasoningParsingTest {

    private val openAi = OpenAiCompatAdapter(testJson)
    private val anthropic = AnthropicAdapter(testJson)
    private val gemini = GeminiAdapter(testJson)

    // ---------- OpenAI 兼容 ----------

    @Test
    fun openai取reasoning_content() {
        val delta = openAi.parseStream(
            null,
            """{"choices":[{"delta":{"reasoning_content":"先算 2+2"}}]}"""
        )
        assertEquals("先算 2+2", delta?.reasoning)
        assertNull("推理帧不应带正文", delta?.text)
    }

    @Test
    fun openai兼容网关用reasoning字段() {
        val delta = openAi.parseStream(
            null,
            """{"choices":[{"delta":{"reasoning":"xAI 风格"}}]}"""
        )
        assertEquals("xAI 风格", delta?.reasoning)
    }

    @Test
    fun openai同帧同时给推理与正文() {
        val delta = openAi.parseStream(
            null,
            """{"choices":[{"delta":{"reasoning":"想","content":"答"}}]}"""
        )
        assertEquals("想", delta?.reasoning)
        assertEquals("答", delta?.text)
    }

    @Test
    fun openai空推理帧被忽略() {
        // 空字符串不应把状态机推进到"思考中"
        assertNull(openAi.parseStream(null, """{"choices":[{"delta":{"reasoning_content":""}}]}"""))
    }

    // ---------- Anthropic ----------

    @Test
    fun anthropic取thinking_delta() {
        val delta = anthropic.parseStream(
            "content_block_delta",
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"逐步推导"}}"""
        )
        assertEquals("逐步推导", delta?.reasoning)
        assertNull(delta?.text)
    }

    @Test
    fun anthropic忽略签名帧() {
        assertNull(
            anthropic.parseStream(
                "content_block_delta",
                """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"abc"}}"""
            )
        )
    }

    @Test
    fun anthropic正文帧不产生推理() {
        val delta = anthropic.parseStream(
            "content_block_delta",
            """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"答案"}}"""
        )
        assertEquals("答案", delta?.text)
        assertNull(delta?.reasoning)
    }

    // ---------- Gemini ----------

    @Test
    fun gemini取thought标记的文本() {
        val delta = gemini.parseStream(
            null,
            """{"candidates":[{"content":{"parts":[{"text":"我在想","thought":true}]}}]}"""
        )
        assertEquals("我在想", delta?.reasoning)
        assertNull(delta?.text)
    }

    @Test
    fun gemini推理与正文分区() {
        val delta = gemini.parseStream(
            null,
            """{"candidates":[{"content":{"parts":[
                {"text":"推理","thought":true},
                {"text":"正文"}
            ]}}]}"""
        )
        assertEquals("推理", delta?.reasoning)
        assertEquals("正文", delta?.text)
    }

    @Test
    fun gemini普通文本不会被当成推理() {
        val delta = gemini.parseStream(
            null,
            """{"candidates":[{"content":{"parts":[{"text":"只有正文"}]}}]}"""
        )
        assertEquals("只有正文", delta?.text)
        assertNull(delta?.reasoning)
    }

    @Test
    fun 统一消息角色仍只有三种() {
        // 推理不落进独立 role —— 它随版本存进 messages，历史重建只用 content
        assertEquals(3, UnifiedRole.entries.size)
    }
}
