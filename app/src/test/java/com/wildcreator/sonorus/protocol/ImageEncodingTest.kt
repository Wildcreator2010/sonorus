package com.wildcreator.sonorus.protocol

import com.wildcreator.sonorus.bodyJson
import com.wildcreator.sonorus.data.remote.protocol.AnthropicAdapter
import com.wildcreator.sonorus.data.remote.protocol.GeminiAdapter
import com.wildcreator.sonorus.data.remote.protocol.OpenAiCompatAdapter
import com.wildcreator.sonorus.domain.agent.ImagePart
import com.wildcreator.sonorus.domain.agent.UnifiedChatRequest
import com.wildcreator.sonorus.domain.agent.UnifiedMessage
import com.wildcreator.sonorus.domain.agent.UnifiedRole
import com.wildcreator.sonorus.testJson
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多模态（图片输入）编码：三套协议结构完全不同，且都属于"接错就 400"的硬约定。
 * 这里用 1x1 PNG 的 base64 作为固定载荷，重点验证字段名与嵌套层级。
 */
class ImageEncodingTest {

    private val png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
    private val image = ImagePart(mimeType = "image/png", base64 = png)

    private fun request(vararg messages: UnifiedMessage) = UnifiedChatRequest(
        model = "m",
        systemPrompt = null,
        messages = messages.toList(),
        tools = null,
        maxTokens = 256
    )

    // ---------- OpenAI：content 数组 + image_url 内嵌 data URI ----------

    @Test
    fun openai_带图时content为数组且使用dataUri() {
        val adapter = OpenAiCompatAdapter(testJson)
        val payload = adapter.chatRequest(
            "https://api.openai.com/v1",
            "sk",
            request(UnifiedMessage(role = UnifiedRole.USER, text = "看图", images = listOf(image)))
        ).bodyJson()

        val content = payload["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals(2, content.size)
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("看图", content[0].jsonObject["text"]!!.jsonPrimitive.content)

        val imageBlock = content[1].jsonObject
        assertEquals("image_url", imageBlock["type"]!!.jsonPrimitive.content)
        val url = imageBlock["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
        assertEquals("data:image/png;base64,$png", url)
    }

    @Test
    fun openai_无图时content仍是纯字符串() {
        val adapter = OpenAiCompatAdapter(testJson)
        val payload = adapter.chatRequest(
            "https://api.openai.com/v1",
            "sk",
            request(UnifiedMessage(role = UnifiedRole.USER, text = "你好"))
        ).bodyJson()

        val content = payload["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive
        assertEquals("你好", content.content)
    }

    @Test
    fun openai_多图按顺序输出() {
        val adapter = OpenAiCompatAdapter(testJson)
        val payload = adapter.chatRequest(
            "https://api.openai.com/v1",
            "sk",
            request(
                UnifiedMessage(
                    role = UnifiedRole.USER,
                    text = "",
                    images = listOf(image, image.copy(mimeType = "image/jpeg"))
                )
            )
        ).bodyJson()

        val content = payload["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        // 空文本不产出 text block，只剩两张图
        assertEquals(2, content.size)
        assertTrue(
            content[0].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
                .startsWith("data:image/png;base64,")
        )
        assertTrue(
            content[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
                .startsWith("data:image/jpeg;base64,")
        )
    }

    // ---------- Anthropic：content 数组 + source.base64 ----------

    @Test
    fun anthropic_图片走source_base64并置于文本之前() {
        val adapter = AnthropicAdapter(testJson)
        val payload = adapter.chatRequest(
            "https://api.anthropic.com/v1",
            "sk-ant",
            request(UnifiedMessage(role = UnifiedRole.USER, text = "看图", images = listOf(image)))
        ).bodyJson()

        val content = payload["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals(2, content.size)

        val imageBlock = content[0].jsonObject
        assertEquals("image", imageBlock["type"]!!.jsonPrimitive.content)
        val source = imageBlock["source"]!!.jsonObject
        assertEquals("base64", source["type"]!!.jsonPrimitive.content)
        assertEquals("image/png", source["media_type"]!!.jsonPrimitive.content)
        assertEquals(png, source["data"]!!.jsonPrimitive.content)

        assertEquals("text", content[1].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun anthropic_无图时仅一个text块() {
        val adapter = AnthropicAdapter(testJson)
        val payload = adapter.chatRequest(
            "https://api.anthropic.com/v1",
            "sk-ant",
            request(UnifiedMessage(role = UnifiedRole.USER, text = "你好"))
        ).bodyJson()

        val content = payload["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals(1, content.size)
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    // ---------- Gemini：parts + inline_data（snake_case）----------

    @Test
    fun gemini_图片走inline_data且字段为snake_case() {
        val adapter = GeminiAdapter(testJson)
        val payload = adapter.chatRequest(
            "https://generativelanguage.googleapis.com/v1beta",
            "AIza",
            request(UnifiedMessage(role = UnifiedRole.USER, text = "看图", images = listOf(image)))
        ).bodyJson()

        val parts = payload["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray
        assertEquals(2, parts.size)

        val inline = parts[0].jsonObject["inline_data"]!!.jsonObject
        assertEquals("image/png", inline["mime_type"]!!.jsonPrimitive.content)
        assertEquals(png, inline["data"]!!.jsonPrimitive.content)
        // Gemini 只认 mime_type / data，不接受 mediaType 之类的 camelCase
        assertTrue(!inline.containsKey("mimeType"))

        assertEquals("看图", parts[1].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun gemini_base64不含data前缀() {
        val adapter = GeminiAdapter(testJson)
        val payload = adapter.chatRequest(
            "https://generativelanguage.googleapis.com/v1beta",
            "AIza",
            request(UnifiedMessage(role = UnifiedRole.USER, text = "x", images = listOf(image)))
        ).bodyJson()

        val data = payload["contents"]!!.jsonArray[0].jsonObject["parts"]!!
            .jsonArray[0].jsonObject["inline_data"]!!.jsonObject["data"]!!.jsonPrimitive.content
        assertTrue(!data.startsWith("data:"))
    }
}
