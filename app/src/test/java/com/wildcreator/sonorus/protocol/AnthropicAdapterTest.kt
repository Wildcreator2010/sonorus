package com.wildcreator.sonorus.data.remote.protocol

import com.wildcreator.sonorus.bodyJson
import com.wildcreator.sonorus.str
import com.wildcreator.sonorus.domain.agent.FinishReason
import com.wildcreator.sonorus.domain.agent.ImagePart
import com.wildcreator.sonorus.domain.agent.ToolCall
import com.wildcreator.sonorus.domain.agent.UnifiedChatRequest
import com.wildcreator.sonorus.domain.agent.UnifiedMessage
import com.wildcreator.sonorus.domain.agent.UnifiedRole
import com.wildcreator.sonorus.domain.agent.UnifiedTool
import com.wildcreator.sonorus.domain.model.ProviderProtocol
import com.wildcreator.sonorus.testJson
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AnthropicAdapterTest {

    private lateinit var adapter: AnthropicAdapter

    @Before
    fun setUp() {
        adapter = AnthropicAdapter(testJson)
    }

    // ---------- baseUrl 与端点 ----------

    @Test
    fun normalizeBaseUrl_默认补v1() {
        assertEquals("https://api.anthropic.com/v1", adapter.normalizeBaseUrl("https://api.anthropic.com"))
        assertEquals("https://api.anthropic.com/v1", adapter.normalizeBaseUrl("https://api.anthropic.com/v1/"))
    }

    @Test
    fun modelsRequest_使用x_api_key与版本头() {
        val request = adapter.modelsRequest("https://api.anthropic.com/v1", "sk-ant")
        assertEquals("https://api.anthropic.com/v1/models", request.url.toString())
        assertEquals("sk-ant", request.header("x-api-key"))
        assertEquals("2023-06-01", request.header("anthropic-version"))
        // 密钥不得出现在 URL 中
        assertTrue(!request.url.toString().contains("sk-ant"))
    }

    @Test
    fun parseModels_用display_name作为归属() {
        val body = """{"data":[{"id":"claude-3-5-sonnet-20241022","display_name":"Claude 3.5 Sonnet"}]}"""
        val models = adapter.parseModels(body)
        assertEquals("claude-3-5-sonnet-20241022", models[0].id)
        assertEquals("Claude 3.5 Sonnet", models[0].ownedBy)
    }

    // ---------- 聊天请求构造 ----------

    @Test
    fun chatRequest_端点与头() {
        val request = adapter.chatRequest("https://api.anthropic.com/v1", "sk-ant", baseRequest())
        assertEquals("https://api.anthropic.com/v1/messages", request.url.toString())
        assertEquals("POST", request.method)
        assertEquals("sk-ant", request.header("x-api-key"))
        assertEquals("2023-06-01", request.header("anthropic-version"))
    }

    @Test
    fun chatRequest_system为独立字段而非消息() {
        val payload = adapter.chatRequest("https://api.anthropic.com/v1", "sk-ant", baseRequest()).bodyJson()
        assertEquals("你是助手", payload.str("system"))
        // system 不能混进 messages
        val roles = payload["messages"]!!.jsonArray.map { it.jsonObject.str("role") }
        assertEquals(listOf("user"), roles)
        assertEquals("claude-3-5-sonnet-20241022", payload.str("model"))
        assertEquals(2048, payload["max_tokens"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun chatRequest_工具声明使用input_schema() {
        val payload = adapter.chatRequest(
            "https://api.anthropic.com/v1",
            "sk-ant",
            baseRequest().copy(tools = listOf(schemaTool()))
        ).bodyJson()
        val tool = payload["tools"]!!.jsonArray[0].jsonObject
        assertEquals("get_time", tool.str("name"))
        assertEquals("object", tool["input_schema"]!!.jsonObject.str("type"))
        // Anthropic 不使用 OpenAI 的 parameters 字段
        assertNull(tool["parameters"])
    }

    @Test
    fun chatRequest_连续tool结果合并为单条user消息() {
        val request = baseRequest().copy(
            messages = listOf(
                UnifiedMessage(role = UnifiedRole.USER, text = "查两个城市时间"),
                UnifiedMessage(
                    role = UnifiedRole.ASSISTANT,
                    toolCalls = listOf(
                        ToolCall(id = "toolu_1", name = "get_time", arguments = """{"city":"北京"}"""),
                        ToolCall(id = "toolu_2", name = "get_time", arguments = """{"city":"上海"}""")
                    )
                ),
                UnifiedMessage(role = UnifiedRole.TOOL, text = "10:00", toolCallId = "toolu_1", toolName = "get_time"),
                UnifiedMessage(role = UnifiedRole.TOOL, text = "10:00", toolCallId = "toolu_2", toolName = "get_time")
            )
        )
        val payload = adapter.chatRequest("https://api.anthropic.com/v1", "sk-ant", request).bodyJson()
        val messages = payload["messages"]!!.jsonArray

        assertEquals(3, messages.size)
        assertEquals("user", messages[0].jsonObject.str("role"))

        // assistant：两个 tool_use block，input 为解析后的 JSON 对象
        val assistant = messages[1].jsonObject
        val blocks = assistant["content"]!!.jsonArray
        assertEquals(2, blocks.size)
        assertEquals("tool_use", blocks[0].jsonObject.str("type"))
        assertEquals("toolu_1", blocks[0].jsonObject.str("id"))
        assertEquals("北京", blocks[0].jsonObject["input"]!!.jsonObject.str("city"))

        // 两条 tool 结果合并进同一条 user 消息
        val merged = messages[2].jsonObject
        assertEquals("user", merged.str("role"))
        val results = merged["content"]!!.jsonArray
        assertEquals(2, results.size)
        assertEquals("tool_result", results[0].jsonObject.str("type"))
        assertEquals("toolu_1", results[0].jsonObject.str("tool_use_id"))
        assertEquals("10:00", results[0].jsonObject.str("content"))
        assertEquals("toolu_2", results[1].jsonObject.str("tool_use_id"))
    }

    @Test
    fun chatRequest_纯图片消息不生成空文本块() {
        val request = baseRequest().copy(
            systemPrompt = null,
            messages = listOf(
                UnifiedMessage(
                    role = UnifiedRole.USER,
                    text = "",
                    images = listOf(ImagePart("image/png", "AAAA"))
                )
            )
        )
        val payload = adapter.chatRequest("https://api.anthropic.com/v1", "sk-ant", request).bodyJson()
        val blocks = payload["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray

        assertEquals(1, blocks.size)
        assertEquals("image", blocks[0].jsonObject.str("type"))
    }

    @Test
    fun chatRequest_空消息列表兜底一条user() {
        val payload = adapter.chatRequest(
            "https://api.anthropic.com/v1",
            "sk-ant",
            baseRequest().copy(messages = emptyList())
        ).bodyJson()
        val messages = payload["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        assertEquals("user", messages[0].jsonObject.str("role"))
    }

    // ---------- SSE 解析 ----------

    @Test
    fun parseStream_文本增量() {
        val data = """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"你好"}}"""
        assertEquals("你好", adapter.parseStream("content_block_delta", data)!!.text)
    }

    @Test
    fun parseStream_工具调用开始携带名称() {
        val data = """
            {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"get_time","input":{}}}
        """.trimIndent()
        val call = adapter.parseStream("content_block_start", data)!!.toolCalls!!.single()
        assertEquals(1, call.index)
        assertEquals("toolu_1", call.id)
        assertEquals("get_time", call.nameSet)
    }

    @Test
    fun parseStream_文本块开始被忽略() {
        val data = """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}"""
        assertNull(adapter.parseStream("content_block_start", data))
    }

    @Test
    fun parseStream_入参分片为追加语义() {
        val data = """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"city\":"}}"""
        val call = adapter.parseStream("content_block_delta", data)!!.toolCalls!!.single()
        assertEquals("""{"city":""", call.argsChunk)
        assertNull(call.argsSet)
    }

    @Test
    fun parseStream_停止原因映射() {
        val stop = adapter.parseStream(
            "message_delta",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}"""
        )!!
        assertEquals(FinishReason.STOP, stop.finish)

        val toolUse = adapter.parseStream(
            "message_delta",
            """{"type":"message_delta","delta":{"stop_reason":"tool_use"}}"""
        )!!
        assertEquals(FinishReason.TOOL_CALLS, toolUse.finish)
    }

    @Test
    fun parseStream_缺少type时回退到event字段() {
        val data = """{"index":0,"delta":{"type":"text_delta","text":"回退"}}"""
        assertEquals("回退", adapter.parseStream("content_block_delta", data)!!.text)
    }

    @Test
    fun parseNonStream_多个工具块各自保留下标() {
        val delta = adapter.parseNonStream(
            """
            {"type":"message","role":"assistant","stop_reason":"tool_use","content":[
              {"type":"tool_use","id":"toolu_1","name":"get_time","input":{"tz":"UTC"}},
              {"type":"tool_use","id":"toolu_2","name":"calculator","input":{"expr":"1+1"}}
            ]}
            """.trimIndent()
        )!!
        assertEquals(FinishReason.TOOL_CALLS, delta.finish)
        assertEquals(listOf(0, 1), delta.toolCalls!!.map { it.index })
        assertEquals("toolu_2", delta.toolCalls!![1].id)
        assertEquals("""{"expr":"1+1"}""", delta.toolCalls!![1].argsSet)
    }

    @Test
    fun parseStream_错误事件() {
        val delta = adapter.parseStream(
            "error",
            """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""
        )!!
        assertEquals("Overloaded", delta.error)
    }

    @Test
    fun parseStream_messageStartUsageProvidesInputTokens() {
        val delta = adapter.parseStream(
            "message_start",
            """{"type":"message_start","message":{"usage":{"input_tokens":777}}}"""
        )!!
        assertEquals(777, delta.inputTokens)
    }

    @Test
    fun parseStream_未知事件返回null() {
        assertNull(adapter.parseStream("ping", """{"type":"ping"}"""))
    }

    @Test
    fun 协议标识为ANTHROPIC() {
        assertEquals(ProviderProtocol.ANTHROPIC, adapter.protocol)
    }

    private fun schemaTool() = UnifiedTool(
        name = "get_time",
        description = "查询时间",
        schema = buildJsonObject { put("type", "object") }
    )

    private fun baseRequest() = UnifiedChatRequest(
        model = "claude-3-5-sonnet-20241022",
        systemPrompt = "你是助手",
        messages = listOf(UnifiedMessage(role = UnifiedRole.USER, text = "你好")),
        tools = null,
        maxTokens = 2048
    )
}
