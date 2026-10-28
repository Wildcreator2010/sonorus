package com.wildcreator.sonorus.export

import com.wildcreator.sonorus.domain.model.ChatMessage
import com.wildcreator.sonorus.domain.model.ChatRole
import com.wildcreator.sonorus.domain.model.Conversation
import com.wildcreator.sonorus.domain.model.ConversationExporter
import com.wildcreator.sonorus.domain.model.ImageAttachment
import com.wildcreator.sonorus.domain.model.TextAttachment
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导出器契约测试：
 * - Markdown / JSON 结构稳定（后续升级 schema 需要显式改这里的断言）；
 * - **绝不携带敏感元数据**：不出现 baseUrl / apiKey / providerId 字段名；
 * - 图片仅保留元数据，不夹带 base64 内容；
 * - systemPrompt 含 ``` 时围栏自动加长，不会提前闭合。
 */
class ConversationExporterTest {

    private val conversation = Conversation(
        id = 42L,
        title = "关于 Android 编译的问答",
        providerId = 7L,
        modelId = "gpt-4o-mini",
        systemPrompt = "你是一个乐于助人的助手。",
        toolsEnabled = true,
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_100_000L
    )

    private val messages = listOf(
        ChatMessage(id = 1, conversationId = 42, role = ChatRole.USER, content = "Gradle 8.9 支持 JDK 25 吗？"),
        ChatMessage(id = 2, conversationId = 42, role = ChatRole.ASSISTANT, content = "不支持，建议 JDK 17。"),
        ChatMessage(
            id = 3, conversationId = 42, role = ChatRole.TOOL, content = "{\"result\": 42}",
            toolName = "calculator"
        ),
        ChatMessage(
            id = 4, conversationId = 42, role = ChatRole.USER, content = "看图",
            images = listOf(ImageAttachment(mimeType = "image/png", base64 = "AAAA".repeat(64)))
        )
    )

    @Test
    fun markdown包含标题模型和全部消息() {
        val md = ConversationExporter.toMarkdown(conversation, messages)
        assertTrue(md.startsWith("# 关于 Android 编译的问答"))
        assertTrue("模型 id 需以行内代码呈现", md.contains("`gpt-4o-mini`"))
        assertTrue(md.contains("Gradle 8.9 支持 JDK 25 吗？"))
        assertTrue(md.contains("不支持，建议 JDK 17。"))
        assertTrue("工具消息需要标记工具名", md.contains("工具 · calculator"))
    }

    @Test
    fun markdown不泄露敏感元数据() {
        val md = ConversationExporter.toMarkdown(conversation, messages)
        // providerId 只是本地主键，不算敏感；但 baseUrl / apiKey / Authorization 绝不能出现
        listOf("baseUrl", "apiKey", "Authorization", "x-api-key", "x-goog-api-key").forEach { kw ->
            assertFalse("导出不得包含敏感字段名：$kw", md.contains(kw, ignoreCase = true))
        }
    }

    @Test
    fun markdown图片以占位符呈现不含base64() {
        val md = ConversationExporter.toMarkdown(conversation, messages)
        assertTrue("应有图片占位说明", md.contains("图片 1"))
        assertTrue("应包含 mimeType", md.contains("image/png"))
        // 原 base64 是 "AAAA" 重复 64 次；导出里不应出现连续 100 字符以上的 base64
        val longRun = Regex("A{100,}").find(md)
        assertTrue("不得把图片 base64 写进 Markdown", longRun == null)
    }

    @Test
    fun 文本附件在导出里只保留元数据() {
        val withFile = ChatMessage(
            id = 5, conversationId = 42, role = ChatRole.USER, content = "看文件",
            files = listOf(TextAttachment("a.md", "text/markdown", "# 标题\n正文"))
        )
        val md = ConversationExporter.toMarkdown(conversation, listOf(withFile))
        assertTrue("Markdown 要标出附件名", md.contains("a.md"))
        assertFalse("附件正文不塞进 Markdown", md.contains("# 标题"))

        val json = ConversationExporter.toJson(conversation, listOf(withFile))
        val root = Json.parseToJsonElement(json).jsonObject
        val message = (root["messages"] as JsonArray)[0].jsonObject
        val file = (message["files"] as JsonArray)[0].jsonObject
        assertEquals("a.md", file["name"]?.jsonPrimitive?.content)
        assertEquals(7, file["chars"]?.jsonPrimitive?.int)
        assertFalse("附件正文不塞进 JSON", json.contains("正文"))
    }

    @Test
    fun json与markdown都过滤空assistant占位行() {
        val placeholder = ChatMessage(
            id = 5, conversationId = 42, role = ChatRole.ASSISTANT, content = ""
        )
        val withPlaceholder = messages + placeholder

        val md = ConversationExporter.toMarkdown(conversation, withPlaceholder)
        assertTrue("Markdown 的消息数不能把工具占位行算进去", md.contains("消息数：4"))

        val json = ConversationExporter.toJson(conversation, withPlaceholder)
        val arr = Json.parseToJsonElement(json).jsonObject["messages"] as JsonArray
        assertEquals("JSON 不能把空 assistant 占位行导出成一条空消息", 4, arr.size)
    }

    @Test
    fun systemPrompt含反引号时围栏自动加长() {
        val tricky = conversation.copy(systemPrompt = "示例：\n```\ncode\n```\n结束")
        val md = ConversationExporter.toMarkdown(tricky, emptyList())
        // 至少要用 4 连反引号围栏才能安全包住 3 连反引号正文
        assertTrue("应使用 >=4 反引号围栏", md.contains("````"))
        // 结构完整性：外层围栏应该正好开合各一次
        val outerFence = "````"
        assertEquals(2, md.split(outerFence).size - 1)
    }

    @Test
    fun json结构符合v1契约() {
        val text = ConversationExporter.toJson(conversation, messages)
        val root = Json.parseToJsonElement(text).jsonObject
        assertEquals("sonorus.conversation/v1", root["schema"]?.jsonPrimitive?.content)
        assertEquals("关于 Android 编译的问答", root["title"]?.jsonPrimitive?.content)
        assertEquals("gpt-4o-mini", root["modelId"]?.jsonPrimitive?.content)
        assertNotNull("导出必须带 exportedAt 时间戳", root["exportedAt"])

        val arr = root["messages"]?.jsonArray
        assertNotNull(arr)
        assertEquals(4, arr!!.size)

        val first = arr[0].jsonObject
        assertEquals("user", first["role"]?.jsonPrimitive?.content)
        assertEquals("Gradle 8.9 支持 JDK 25 吗？", first["content"]?.jsonPrimitive?.content)

        val tool = arr[2].jsonObject
        assertEquals("tool", tool["role"]?.jsonPrimitive?.content)
        assertEquals("calculator", tool["toolName"]?.jsonPrimitive?.content)

        val withImage = arr[3].jsonObject
        val images = withImage["images"] as? JsonArray
        assertNotNull("含图片的消息必须保留 images 元数据数组", images)
        val img0 = (images as JsonArray)[0].jsonObject
        assertEquals("image/png", img0["mimeType"]?.jsonPrimitive?.content)
        // base64 字符串长度 = 4 * 64 = 256
        assertEquals(256, img0["bytes"]?.jsonPrimitive?.int)
    }

    @Test
    fun json不含base64原文与敏感字段() {
        val text = ConversationExporter.toJson(conversation, messages)
        assertFalse("JSON 不得写入 base64 字节", text.contains("A".repeat(100)))
        listOf("baseUrl", "apiKey", "providerId").forEach { kw ->
            assertFalse("JSON 不得包含敏感字段名：$kw", text.contains(kw))
        }
    }

    @Test
    fun 默认文件名过滤非法字符且带会话id() {
        val weird = conversation.copy(title = "a/b\\c:d*e?f\"g<h>i|j")
        val name = ConversationExporter.defaultFileName(weird)
        // Windows/Android 文件名黑名单：\ / : * ? " < > |
        assertFalse(name.contains('/'))
        assertFalse(name.contains('\\'))
        assertFalse(name.contains(':'))
        assertFalse(name.contains('*'))
        assertFalse(name.contains('?'))
        assertFalse(name.contains('"'))
        assertFalse(name.contains('<'))
        assertFalse(name.contains('>'))
        assertFalse(name.contains('|'))
        assertTrue("文件名末尾应带会话 id 便于区分", name.endsWith("_42"))
    }

    @Test
    fun 空标题会话回退为未命名占位() {
        val blank = conversation.copy(title = "")
        val md = ConversationExporter.toMarkdown(blank, emptyList())
        assertTrue(md.startsWith("# 未命名会话"))
        val name = ConversationExporter.defaultFileName(blank)
        assertEquals("conversation_42", name)
    }

    @Test
    fun markdown换行标题被压平() {
        val multiline = conversation.copy(title = "第一行\n第二行")
        val md = ConversationExporter.toMarkdown(multiline, emptyList())
        val firstLine = md.lineSequence().first()
        assertEquals("# 第一行 第二行", firstLine)
    }

    @Test
    fun 空消息列表也能产出合法结构() {
        val md = ConversationExporter.toMarkdown(conversation, emptyList())
        assertTrue(md.contains("## 对话"))
        assertTrue(md.contains("消息数：0"))

        val json = ConversationExporter.toJson(conversation, emptyList())
        val root = Json.parseToJsonElement(json).jsonObject
        assertEquals(0, (root["messages"] as JsonArray).size)
    }
}
