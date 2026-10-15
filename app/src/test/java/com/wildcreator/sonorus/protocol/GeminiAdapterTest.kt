package com.wildcreator.sonorus.data.remote.protocol

import com.wildcreator.sonorus.bodyJson
import com.wildcreator.sonorus.str
import com.wildcreator.sonorus.domain.agent.FinishReason
import com.wildcreator.sonorus.domain.agent.ThinkingLevel
import com.wildcreator.sonorus.domain.agent.ToolCall
import com.wildcreator.sonorus.domain.agent.UnifiedChatRequest
import com.wildcreator.sonorus.domain.agent.UnifiedMessage
import com.wildcreator.sonorus.domain.agent.UnifiedRole
import com.wildcreator.sonorus.domain.agent.UnifiedTool
import com.wildcreator.sonorus.domain.model.ProviderProtocol
import com.wildcreator.sonorus.testJson
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GeminiAdapterTest {

    private lateinit var adapter: GeminiAdapter

    @Before
    fun setUp() {
        adapter = GeminiAdapter(testJson)
    }

    // ---------- baseUrl 与端点 ----------

    @Test
    fun normalizeBaseUrl_默认补v1beta() {
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta",
            adapter.normalizeBaseUrl("https://generativelanguage.googleapis.com")
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta",
            adapter.normalizeBaseUrl("https://generativelanguage.googleapis.com/v1beta/")
        )
    }

    @Test
    fun modelsRequest_密钥走头不走query() {
        val request = adapter.modelsRequest("https://generativelanguage.googleapis.com/v1beta", "AIza-test")
        val url = request.url.toString()
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models", url)
        assertEquals("AIza-test", request.header("x-goog-api-key"))
        // 密钥绝不能出现在 URL 里（会被代理/日志/Referer 记录）
        assertTrue(!url.contains("AIza-test"))
        assertTrue(!url.contains("key="))
    }

    @Test
    fun parseModels_去掉models前缀并按能力过滤() {
        val body = """
            {"models":[
              {"name":"models/gemini-1.5-pro","displayName":"Gemini 1.5 Pro","supportedGenerationMethods":["generateContent","countTokens"]},
              {"name":"models/text-embedding-004","supportedGenerationMethods":["embedContent"]},
              {"name":"models/gemini-1.5-flash"}
            ]}
        """.trimIndent()
        val models = adapter.parseModels(body)
        assertEquals(listOf("gemini-1.5-pro", "gemini-1.5-flash"), models.map { it.id })
        assertEquals("Gemini 1.5 Pro", models[0].ownedBy)
    }

    // ---------- 聊天请求构造 ----------

    @Test
    fun chatRequest_端点为streamGenerateContent_sse() {
        val request = adapter.chatRequest(
            "https://generativelanguage.googleapis.com/v1beta",
            "AIza-test",
            baseRequest("gemini-1.5-pro")
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-pro:streamGenerateContent?alt=sse",
            request.url.toString()
        )
        assertEquals("POST", request.method)
        assertEquals("AIza-test", request.header("x-goog-api-key"))
    }

    @Test
    fun chatRequest_模型名带models前缀时去重() {
        val request = adapter.chatRequest(
            "https://generativelanguage.googleapis.com/v1beta",
            "AIza-test",
            baseRequest("models/gemini-1.5-pro")
        )
        assertTrue(request.url.toString().contains("/models/gemini-1.5-pro:streamGenerateContent"))
        assertTrue(!request.url.toString().contains("models/models/"))
    }

    @Test
    fun chatRequest_system为独立字段() {
        val payload = adapter.chatRequest(
            "https://generativelanguage.googleapis.com/v1beta",
            "AIza-test",
            baseRequest("gemini-1.5-pro")
        ).bodyJson()
        val system = payload["systemInstruction"]!!.jsonObject
        assertEquals("你是助手", system["parts"]!!.jsonArray[0].jsonObject.str("text"))
        assertEquals(
            2048,
            payload["generationConfig"]!!.jsonObject["maxOutputTokens"]!!.jsonPrimitive.content.toInt()
        )
    }

    @Test
    fun chatRequest_工具声明包在functionDeclarations里() {
        val payload = adapter.chatRequest(
            "https://generativelanguage.googleapis.com/v1beta",
            "AIza-test",
            baseRequest("gemini-1.5-pro").copy(tools = listOf(schemaTool()))
        ).bodyJson()
        val decls = payload["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!.jsonArray
        assertEquals(1, decls.size)
        assertEquals("get_time", decls[0].jsonObject.str("name"))
        assertEquals("object", decls[0].jsonObject["parameters"]!!.jsonObject.str("type"))
    }

    @Test
    fun chatRequest_角色映射与工具回执结构() {
        val request = baseRequest("gemini-1.5-pro").copy(
            messages = listOf(
                UnifiedMessage(role = UnifiedRole.USER, text = "现在几点"),
                UnifiedMessage(
                    role = UnifiedRole.ASSISTANT,
                    toolCalls = listOf(ToolCall(id = "g1", name = "get_time", arguments = """{"city":"北京"}"""))
                ),
                UnifiedMessage(role = UnifiedRole.TOOL, text = "10:00", toolCallId = "g1", toolName = "get_time")
            )
        )
        val contents = adapter.chatRequest("https://generativelanguage.googleapis.com/v1beta", "AIza-test", request)
            .bodyJson()["contents"]!!.jsonArray

        assertEquals(3, contents.size)
        assertEquals("user", contents[0].jsonObject.str("role"))
        assertEquals("model", contents[1].jsonObject.str("role"))

        val call = contents[1].jsonObject["parts"]!!.jsonArray[0].jsonObject["functionCall"]!!.jsonObject
        assertEquals("get_time", call.str("name"))
        assertEquals("北京", call["args"]!!.jsonObject.str("city"))

        // 工具结果：role 必须是 user，形态为 functionResponse
        val response = contents[2].jsonObject
        assertEquals("user", response.str("role"))
        val fnResp = response["parts"]!!.jsonArray[0].jsonObject["functionResponse"]!!.jsonObject
        assertEquals("get_time", fnResp.str("name"))
        assertEquals("10:00", fnResp["response"]!!.jsonObject.str("result"))
    }

    // ---------- SSE 解析 ----------

    @Test
    fun parseStream_文本增量() {
        val data = """{"candidates":[{"content":{"parts":[{"text":"你好"}]}}]}"""
        val delta = adapter.parseStream(null, data)!!
        assertEquals("你好", delta.text)
        assertNull(delta.finish)
    }

    @Test
    fun parseStream_多text块拼接() {
        val data = """{"candidates":[{"content":{"parts":[{"text":"你好"},{"text":"世界"}]}}]}"""
        assertEquals("你好世界", adapter.parseStream(null, data)!!.text)
    }

    @Test
    fun parseStream_函数调用为整体赋值且finish为TOOL_CALLS() {
        val data = """
            {"candidates":[{"content":{"parts":[{"functionCall":{"name":"get_time","args":{"city":"北京"}}}]},"finishReason":"STOP"}]}
        """.trimIndent()
        val delta = adapter.parseStream(null, data)!!
        val call = delta.toolCalls!!.single()
        assertEquals("get_time", call.nameSet)
        assertEquals("""{"city":"北京"}""", call.argsSet)
        assertTrue(call.id!!.startsWith("gemini_call_"))
        // Gemini 没有 tool_use 停止原因，靠本帧是否含 functionCall 判定
        assertEquals(FinishReason.TOOL_CALLS, delta.finish)
    }

    @Test
    fun parseStream_usageMetadataProvidesInputTokens() {
        val delta = adapter.parseStream(null, """{"usageMetadata":{"promptTokenCount":888},"candidates":[]}""")!!
        assertEquals(888, delta.inputTokens)
    }

    @Test
    fun parseStream_纯文本收尾为STOP() {
        val data = """{"candidates":[{"content":{"parts":[{"text":"好的"}]},"finishReason":"STOP"}]}"""
        assertEquals(FinishReason.STOP, adapter.parseStream(null, data)!!.finish)
    }

    @Test
    fun parseStream_流内错误() {
        val delta = adapter.parseStream(null, """{"error":{"code":429,"message":"quota exceeded"}}""")!!
        assertEquals("quota exceeded", delta.error)
    }

    @Test
    fun 只有收尾原因的末帧不能被丢弃() {
        // 旧断言要求这里返回 null —— 那正是 bug：收尾原因一丢，
        // "被 SAFETY 拦截的半截答案"和"正常说完"就再也分不开，用户会以为回答完整。
        val finishOnly = adapter.parseStream(null, """{"candidates":[{"finishReason":"STOP"}]}""")
        assertEquals(FinishReason.STOP, finishOnly?.finish)

        // 真正没有内容的空帧仍然要返回 null，避免每帧都产生一个无意义的更新
        assertNull(adapter.parseStream(null, """{"candidates":[]}"""))
        assertNull(adapter.parseStream(null, """{"candidates":[{}]}"""))

        // 拦截与截断必须落到各自的枚举上
        assertEquals(
            FinishReason.LENGTH,
            adapter.parseStream(null, """{"candidates":[{"finishReason":"MAX_TOKENS"}]}""")?.finish
        )
        assertEquals(
            FinishReason.BLOCKED,
            adapter.parseStream(null, """{"candidates":[{"finishReason":"SAFETY"}]}""")?.finish
        )
    }

    @Test
    fun 协议标识为GEMINI() {
        assertEquals(ProviderProtocol.GEMINI, adapter.protocol)
    }

    @Test
    fun 开启思考时必须同时索要思考分片() {
        // 只发 thinkingBudget 而上游不回 thought 分片，界面就永远没有思考过程 ——
        // 解析侧写得再对也是死代码，所以这条必须钉在请求体上
        val thinking = thinkingConfigOf(ThinkingLevel.HIGH)
        val includeThoughts = thinking?.get("includeThoughts")?.jsonPrimitive?.contentOrNull?.toBoolean()
        val budget = thinking?.get("thinkingBudget")?.jsonPrimitive?.contentOrNull?.toIntOrNull()

        assertEquals(true, includeThoughts)
        assertTrue((budget ?: 0) > 0)
    }

    @Test
    fun 关闭思考时不下发thinkingConfig() {
        assertNull(thinkingConfigOf(ThinkingLevel.OFF))
    }

    private fun thinkingConfigOf(level: ThinkingLevel) = adapter.chatRequest(
        "https://generativelanguage.googleapis.com/v1beta",
        "k",
        UnifiedChatRequest(
            model = "gemini-2.5-pro",
            systemPrompt = "你是助手",
            messages = listOf(UnifiedMessage(role = UnifiedRole.USER, text = "在吗")),
            thinkingLevel = level
        )
    ).bodyJson()["generationConfig"]?.jsonObject?.get("thinkingConfig")?.jsonObject

    private fun schemaTool() = UnifiedTool(
        name = "get_time",
        description = "查询时间",
        schema = buildJsonObject { put("type", "object") }
    )

    private fun baseRequest(model: String) = UnifiedChatRequest(
        model = model,
        systemPrompt = "你是助手",
        messages = listOf(UnifiedMessage(role = UnifiedRole.USER, text = "你好")),
        tools = null,
        maxTokens = 2048
    )
}
