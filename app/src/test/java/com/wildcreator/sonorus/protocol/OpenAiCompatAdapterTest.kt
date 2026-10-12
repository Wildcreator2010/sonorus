package com.wildcreator.sonorus.data.remote.protocol

import com.wildcreator.sonorus.bodyJson
import com.wildcreator.sonorus.str
import com.wildcreator.sonorus.domain.agent.FinishReason
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

class OpenAiCompatAdapterTest {

    private lateinit var adapter: OpenAiCompatAdapter

    @Before
    fun setUp() {
        adapter = OpenAiCompatAdapter(testJson)
    }

    // ---------- baseUrl 规整 ----------

    @Test
    fun normalizeBaseUrl_仅域名时补_v1() {
        assertEquals("https://api.openai.com/v1", adapter.normalizeBaseUrl("https://api.openai.com"))
        assertEquals("https://api.openai.com/v1", adapter.normalizeBaseUrl("https://api.openai.com/"))
    }

    @Test
    fun normalizeBaseUrl_用户显式路径保持原样() {
        assertEquals("https://api.openai.com/v1", adapter.normalizeBaseUrl("https://api.openai.com/v1"))
        assertEquals("https://gw.example.com/one-api/v1", adapter.normalizeBaseUrl("https://gw.example.com/one-api/v1/"))
    }

    @Test
    fun normalizeBaseUrl_空白输入不抛异常() {
        assertEquals("", adapter.normalizeBaseUrl("   "))
    }

    // ---------- 模型列表 ----------

    @Test
    fun modelsRequest_带Bearer鉴权与正确端点() {
        val request = adapter.modelsRequest("https://api.openai.com/v1", "sk-test")
        assertEquals("https://api.openai.com/v1/models", request.url.toString())
        assertEquals("GET", request.method)
        assertEquals("Bearer sk-test", request.header("Authorization"))
    }

    @Test
    fun parseModels_解析data数组() {
        val body = """{"object":"list","data":[{"id":"gpt-4o","owned_by":"openai"},{"id":"gpt-4o-mini"}]}"""
        val models = adapter.parseModels(body)
        assertEquals(listOf("gpt-4o", "gpt-4o-mini"), models.map { it.id })
        assertEquals("openai", models[0].ownedBy)
        assertNull(models[1].ownedBy)
    }

    @Test
    fun parseModels_缺少data字段返回空() {
        assertTrue(adapter.parseModels("""{"object":"list"}""").isEmpty())
    }

    // ---------- 聊天请求构造 ----------

    @Test
    fun chatRequest_端点与鉴权头() {
        val request = adapter.chatRequest("https://api.openai.com/v1", "sk-test", baseRequest())
        assertEquals("https://api.openai.com/v1/chat/completions", request.url.toString())
        assertEquals("POST", request.method)
        assertEquals("Bearer sk-test", request.header("Authorization"))
        assertEquals("text/event-stream", request.header("Accept"))
    }

    @Test
    fun chatRequest_system作为首条消息() {
        val payload = adapter.chatRequest("https://api.openai.com/v1", "sk-test", baseRequest()).bodyJson()
        assertEquals("gpt-4o", payload.str("model"))
        assertEquals("true", payload["stream"]?.jsonPrimitive?.content)
        assertEquals(2048, payload["max_tokens"]?.jsonPrimitive?.content?.toInt())

        val messages = payload["messages"]!!.jsonArray
        assertEquals(3, messages.size)
        assertEquals("system", messages[0].jsonObject.str("role"))
        assertEquals("你是助手", messages[0].jsonObject.str("content"))
        assertEquals("user", messages[1].jsonObject.str("role"))
        assertEquals("assistant", messages[2].jsonObject.str("role"))
    }

    @Test
    fun chatRequest_工具声明走OpenAI形态() {
        val request = baseRequest().copy(tools = listOf(schemaTool()))
        val payload = adapter.chatRequest("https://api.openai.com/v1", "sk-test", request).bodyJson()
        val tool = payload["tools"]!!.jsonArray[0].jsonObject
        assertEquals("function", tool.str("type"))
        assertEquals("get_time", tool["function"]!!.jsonObject.str("name"))
        assertEquals("查询时间", tool["function"]!!.jsonObject.str("description"))
        // OpenAI 用 parameters 字段承载 schema
        assertEquals("object", tool["function"]!!.jsonObject["parameters"]!!.jsonObject.str("type"))
    }

    @Test
    fun chatRequest_无工具时不输出tools字段() {
        val payload = adapter.chatRequest("https://api.openai.com/v1", "sk-test", baseRequest()).bodyJson()
        assertNull(payload["tools"])
    }

    @Test
    fun chatRequest_工具消息带tool_call_id() {
        val request = baseRequest().copy(
            systemPrompt = null, // 本例只验证 assistant -> tool 的序列化，排除 system 消息干扰
            messages = listOf(
                UnifiedMessage(
                    role = UnifiedRole.ASSISTANT,
                    toolCalls = listOf(ToolCall(id = "call_1", name = "get_time", arguments = "{}"))
                ),
                UnifiedMessage(
                    role = UnifiedRole.TOOL,
                    text = "2026-09-12T00:00:00Z",
                    toolCallId = "call_1",
                    toolName = "get_time"
                )
            )
        )
        val payload = adapter.chatRequest("https://api.openai.com/v1", "sk-test", request).bodyJson()
        val messages = payload["messages"]!!.jsonArray

        val assistant = messages[0].jsonObject
        assertEquals("assistant", assistant.str("role"))
        val call = assistant["tool_calls"]!!.jsonArray[0].jsonObject
        assertEquals("call_1", call.str("id"))
        assertEquals("get_time", call["function"]!!.jsonObject.str("name"))

        val toolMsg = messages[1].jsonObject
        assertEquals("tool", toolMsg.str("role"))
        assertEquals("call_1", toolMsg.str("tool_call_id"))
        assertEquals("2026-09-12T00:00:00Z", toolMsg.str("content"))
    }

    // ---------- SSE 解析 ----------

    @Test
    fun parseStream_文本增量() {
        val data = """{"choices":[{"delta":{"content":"你好"}}]}"""
        val delta = adapter.parseStream(null, data)!!
        assertEquals("你好", delta.text)
        assertNull(delta.finish)
    }

    @Test
    fun parseStream_DONE哨兵返回null() {
        assertNull(adapter.parseStream(null, "[DONE]"))
    }

    @Test
    fun parseStream_非法JSON返回null() {
        assertNull(adapter.parseStream(null, "not-json"))
    }

    @Test
    fun parseStream_finish_reason映射() {
        val stop = adapter.parseStream(null, """{"choices":[{"finish_reason":"stop"}]}""")!!
        assertEquals(FinishReason.STOP, stop.finish)

        val toolCalls = adapter.parseStream(null, """{"choices":[{"finish_reason":"tool_calls"}]}""")!!
        assertEquals(FinishReason.TOOL_CALLS, toolCalls.finish)
    }

    @Test
    fun parseStream_工具调用分片累积() {
        val data = """
            {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"get_time","arguments":"{\"tz\":"}}]}}]}
        """.trimIndent()
        val delta = adapter.parseStream(null, data)!!
        val call = delta.toolCalls!!.single()
        assertEquals(0, call.index)
        assertEquals("call_1", call.id)
        assertEquals("get_time", call.nameChunk)
        assertEquals("""{"tz":""", call.argsChunk)
        assertNull(call.argsSet)
    }

    @Test
    fun parseStream_流内错误() {
        val delta = adapter.parseStream(null, """{"error":{"message":"rate limit"}}""")!!
        assertEquals("rate limit", delta.error)
    }

    @Test
    fun parseStream_空choice返回null() {
        assertNull(adapter.parseStream(null, """{"choices":[]}"""))
    }

    @Test
    fun 协议标识为OPENAI() {
        assertEquals(ProviderProtocol.OPENAI, adapter.protocol)
    }

    private fun schemaTool() = UnifiedTool(
        name = "get_time",
        description = "查询时间",
        schema = buildJsonObject {
            put("type", "object")
        }
    )

    private fun baseRequest() = UnifiedChatRequest(
        model = "gpt-4o",
        systemPrompt = "你是助手",
        messages = listOf(
            UnifiedMessage(role = UnifiedRole.USER, text = "你好"),
            UnifiedMessage(role = UnifiedRole.ASSISTANT, text = "在的")
        ),
        tools = null,
        maxTokens = 2048
    )
}
