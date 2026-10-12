package com.wildcreator.sonorus.data.remote.protocol

import com.wildcreator.sonorus.bodyJson
import com.wildcreator.sonorus.bodyText
import com.wildcreator.sonorus.domain.agent.ImagePart
import com.wildcreator.sonorus.domain.agent.ThinkingLevel
import com.wildcreator.sonorus.domain.agent.UnifiedChatRequest
import com.wildcreator.sonorus.domain.agent.UnifiedMessage
import com.wildcreator.sonorus.domain.agent.UnifiedRole
import com.wildcreator.sonorus.str
import com.wildcreator.sonorus.testJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 思考等级 -> 三协议字段映射。
 *
 * 关键约束（回归重点）：
 * - OFF 必须完全不下发相关字段，避免模型不支持时报 400；
 * - Anthropic 的 thinking.budget_tokens 必须严格小于 max_tokens，否则上游直接拒绝；
 * - 三家的字段名与嵌套层级各不相同，写错任何一层都是静默失效。
 */
class ThinkingLevelEncodingTest {

    private val adapters = mapOf(
        "openai" to OpenAiCompatAdapter(testJson),
        "anthropic" to AnthropicAdapter(testJson),
        "gemini" to GeminiAdapter(testJson)
    )

    private fun request(level: ThinkingLevel, maxTokens: Int = 4096, text: String = "你好") =
        UnifiedChatRequest(
            model = "test-model",
            systemPrompt = null,
            messages = listOf(UnifiedMessage(role = UnifiedRole.USER, text = text)),
            maxTokens = maxTokens,
            thinkingLevel = level
        )

    /**
     * 便捷包装：接口签名是 chatRequest(baseUrl, apiKey, request)，
     * 这里统一成 (request, baseUrl, apiKey) 让断言更聚焦在请求体本身。
     */
    private fun ProtocolAdapter.build(req: UnifiedChatRequest, baseUrl: String, key: String) =
        chatRequest(baseUrl, key, req)

    // ---------- OpenAI 兼容 ----------

    @Test
    fun openai_未开启思考时不下发reasoning_effort() {
        val body = adapters.getValue("openai").build(
            request(ThinkingLevel.OFF), "https://api.example.com/v1", "sk-x"
        ).bodyJson()

        assertFalse("OFF 不能出现 reasoning_effort", body.containsKey("reasoning_effort"))
    }

    @Test
    fun openai_思考等级映射为reasoning_effort() {
        val expected = mapOf(
            ThinkingLevel.LOW to "low",
            ThinkingLevel.MEDIUM to "medium",
            ThinkingLevel.HIGH to "high"
        )
        expected.forEach { (level, value) ->
            val body = adapters.getValue("openai").build(
                request(level), "https://api.example.com/v1", "sk-x"
            ).bodyJson()
            assertEquals("等级 $level 映射错误", value, body.str("reasoning_effort"))
        }
    }

    // ---------- Anthropic ----------

    @Test
    fun anthropic_未开启思考时不下发thinking块() {
        val body = adapters.getValue("anthropic").build(
            request(ThinkingLevel.OFF), "https://api.anthropic.com/v1", "sk-ant-x"
        ).bodyJson()

        assertFalse("OFF 不能出现 thinking 字段", body.containsKey("thinking"))
    }

    @Test
    fun anthropic_思考块结构正确() {
        val body = adapters.getValue("anthropic").build(
            request(ThinkingLevel.MEDIUM), "https://api.anthropic.com/v1", "sk-ant-x"
        ).bodyJson()

        val thinking = body["thinking"]?.jsonObject
        assertNotNull("MEDIUM 应下发 thinking 块", thinking)
        assertEquals("enabled", thinking!!.str("type"))
        assertTrue("budget_tokens 必须是正整数", (thinking["budget_tokens"]?.jsonPrimitive?.int ?: 0) > 0)
    }

    @Test
    fun anthropic_budget必须严格小于maxTokens() {
        // 逐个等级 + 多个 maxTokens 组合验证：这是上游会直接 400 的硬约束
        val maxTokensList = listOf(1024, 2048, 4096, 8192, 16384, 100)
        val levels = listOf(ThinkingLevel.LOW, ThinkingLevel.MEDIUM, ThinkingLevel.HIGH)

        levels.forEach { level ->
            maxTokensList.forEach { maxTokens ->
                val body = adapters.getValue("anthropic").build(
                    request(level, maxTokens), "https://api.anthropic.com/v1", "sk-ant-x"
                ).bodyJson()

                val budget = body["thinking"]?.jsonObject?.get("budget_tokens")?.jsonPrimitive?.int
                if (budget != null) {
                    assertTrue(
                        "budget($budget) 必须 < max_tokens($maxTokens) [level=$level]",
                        budget < maxTokens
                    )
                }
            }
        }
    }

    @Test
    fun anthropic_高等级预算不小于低等级() {
        fun budgetOf(level: ThinkingLevel): Int = adapters.getValue("anthropic").build(
            request(level, 8192), "https://api.anthropic.com/v1", "sk-ant-x"
        ).bodyJson()["thinking"]!!.jsonObject["budget_tokens"]!!.jsonPrimitive.int

        val low = budgetOf(ThinkingLevel.LOW)
        val medium = budgetOf(ThinkingLevel.MEDIUM)
        val high = budgetOf(ThinkingLevel.HIGH)

        assertTrue("LOW($low) <= MEDIUM($medium)", low <= medium)
        assertTrue("MEDIUM($medium) <= HIGH($high)", medium <= high)
    }

    // ---------- Gemini ----------

    @Test
    fun gemini_未开启思考时不下发thinkingConfig() {
        val body = adapters.getValue("gemini").build(
            request(ThinkingLevel.OFF), "https://generativelanguage.googleapis.com", "key"
        ).bodyJson()

        assertFalse(
            "OFF 不能出现 thinkingConfig",
            body["generationConfig"]?.jsonObject?.containsKey("thinkingConfig") == true
        )
    }

    @Test
    fun gemini_思考预算映射正确() {
        val expected = mapOf(
            ThinkingLevel.LOW to 2048,
            ThinkingLevel.MEDIUM to 8192,
            ThinkingLevel.HIGH to 24576
        )
        expected.forEach { (level, value) ->
            val body = adapters.getValue("gemini").build(
                request(level), "https://generativelanguage.googleapis.com", "key"
            ).bodyJson()

            val budget = body["generationConfig"]?.jsonObject
                ?.get("thinkingConfig")?.jsonObject
                ?.get("thinkingBudget")?.jsonPrimitive?.int
            assertEquals("等级 $level 的 thinkingBudget 错误", value, budget)
        }
    }

    // ---------- 共性与多模态共存 ----------

    @Test
    fun 全部等级下请求体都可被三协议正常序列化() {
        ThinkingLevel.entries.forEach { level ->
            // OpenAI / Anthropic：model 在 body、消息在 messages
            listOf("openai", "anthropic").forEach { name ->
                val body = adapters.getValue(name).build(
                    request(level), "https://api.example.com/v1", "key"
                ).bodyJson()
                assertEquals("$name 丢了 model [level=$level]", "test-model", body.str("model"))
                assertNotNull("$name 丢了 messages [level=$level]", body["messages"])
            }
            // Gemini：model 在 URL 路径，消息在 contents
            val geminiRequest = adapters.getValue("gemini").build(
                request(level), "https://generativelanguage.googleapis.com", "key"
            )
            assertTrue(
                "gemini 的 model 必须出现在 URL 里 [level=$level]",
                geminiRequest.url.toString().contains("test-model")
            )
            val geminiBody = geminiRequest.bodyJson()
            assertNotNull("gemini 丢了 contents [level=$level]", geminiBody["contents"])
        }
    }

    @Test
    fun 思考等级与图片可同时生效() {
        val withImage = UnifiedChatRequest(
            model = "vision-model",
            systemPrompt = null,
            messages = listOf(
                UnifiedMessage(
                    role = UnifiedRole.USER,
                    text = "这是什么",
                    images = listOf(ImagePart("image/png", "AAAA"))
                )
            ),
            thinkingLevel = ThinkingLevel.HIGH
        )

        val anthropic = adapters.getValue("anthropic").build(
            withImage, "https://api.anthropic.com/v1", "sk-ant-x"
        ).bodyJson()
        assertNotNull("图片不应挤掉 thinking", anthropic["thinking"])

        val anthropicUser = (anthropic["messages"] as JsonArray).last().jsonObject
        val anthropicBlocks = anthropicUser["content"] as JsonArray
        assertEquals("应同时包含 text 与 image 两个 block", 2, anthropicBlocks.size)

        val openai = adapters.getValue("openai").build(
            withImage, "https://api.example.com/v1", "sk-x"
        ).bodyJson()
        assertEquals("high", openai.str("reasoning_effort"))
        val msgs = openai["messages"] as JsonArray
        val userContent = msgs.last().jsonObject["content"]
        assertTrue("带图消息 content 应为数组", userContent is JsonArray)
    }

    @Test
    fun 图片与思考等级写入的字段名各家正确() {
        val withImage = UnifiedChatRequest(
            model = "vision-model",
            systemPrompt = null,
            messages = listOf(
                UnifiedMessage(
                    role = UnifiedRole.USER,
                    text = null,
                    images = listOf(ImagePart("image/jpeg", "BBBB"))
                )
            ),
            thinkingLevel = ThinkingLevel.LOW
        )

        // Anthropic: source.type = base64
        val anthropicMsg = (adapters.getValue("anthropic").build(
            withImage, "https://api.anthropic.com/v1", "k"
        ).bodyJson()["messages"] as JsonArray).last().jsonObject
        val aBlock = (anthropicMsg["content"] as JsonArray).first().jsonObject
        assertEquals("image", aBlock.str("type"))
        assertEquals("base64", aBlock["source"]!!.jsonObject.str("type"))
        assertEquals("image/jpeg", aBlock["source"]!!.jsonObject.str("media_type"))

        // Gemini: inline_data（snake_case）
        val geminiParts = (adapters.getValue("gemini").build(
            withImage, "https://generativelanguage.googleapis.com", "k"
        ).bodyJson()["contents"] as JsonArray).last().jsonObject["parts"] as JsonArray
        val inlineData = geminiParts.firstOrNull { it.jsonObject.containsKey("inline_data") }
        assertNotNull("Gemini 必须使用 inline_data", inlineData)
        assertEquals("image/jpeg", inlineData!!.jsonObject["inline_data"]!!.jsonObject.str("mime_type"))

        // OpenAI: image_url 用 data URI
        val openaiMsg = (adapters.getValue("openai").build(
            withImage, "https://api.example.com/v1", "k"
        ).bodyJson()["messages"] as JsonArray).last().jsonObject
        val urlPart = (openaiMsg["content"] as JsonArray)
            .first { it.jsonObject.str("type") == "image_url" }.jsonObject
        assertTrue(urlPart["image_url"]!!.jsonObject.str("url")!!.startsWith("data:image/jpeg;base64,"))
    }

    @Test
    fun 默认不传思考等级时等价于关闭() {
        // UnifiedChatRequest.thinkingLevel 默认 OFF —— 老调用点不传参数不应产生任何思考字段
        val implicit = UnifiedChatRequest(
            model = "m",
            systemPrompt = null,
            messages = listOf(UnifiedMessage(role = UnifiedRole.USER, text = "hi"))
        )
        assertEquals(ThinkingLevel.OFF, implicit.thinkingLevel)

        adapters.forEach { (name, adapter) ->
            val body = adapter.build(implicit, "https://api.example.com/v1", "k").bodyJson()
            assertFalse("$name 不应下发思考字段", body.containsKey("thinking"))
            assertFalse("$name 不应下发 reasoning_effort", body.containsKey("reasoning_effort"))
        }
    }

    @Test
    fun 空文本消息不会被序列化成空字符串字段() {
        // 纯图片消息 text 为 null：不应产生空的 text 字段（部分上游会 400）
        val body = adapters.getValue("openai").build(
            UnifiedChatRequest(
                model = "m",
                systemPrompt = null,
                messages = listOf(UnifiedMessage(role = UnifiedRole.USER, text = null))
            ),
            "https://api.example.com/v1", "k"
        ).bodyText()

        assertTrue("不应出现空 text 字段", !body.contains("\"text\":\"\""))
    }

    @Test
    fun 自定义maxTokens会体现在支持该字段的协议上() {
        val req = request(ThinkingLevel.OFF, maxTokens = 777)
        val gemini = adapters.getValue("gemini").build(
            req, "https://generativelanguage.googleapis.com", "k"
        ).bodyJson()
        assertEquals(
            777,
            gemini["generationConfig"]!!.jsonObject["maxOutputTokens"]!!.jsonPrimitive.int
        )

        val anthropic = adapters.getValue("anthropic").build(
            req, "https://api.anthropic.com/v1", "k"
        ).bodyJson()
        assertEquals(777, anthropic["max_tokens"]!!.jsonPrimitive.int)
    }

    @Test
    fun 图片base64原样透传不被转义破坏() {
        // base64 含 + / = 等字符，若被 JSON 转义处理不当，上游解码会失败
        val tricky = "aGVsbG8+/=d29ybGQ="
        val body = adapters.getValue("openai").build(
            UnifiedChatRequest(
                model = "m",
                systemPrompt = null,
                messages = listOf(
                    UnifiedMessage(
                        role = UnifiedRole.USER,
                        text = null,
                        images = listOf(ImagePart("image/png", tricky))
                    )
                )
            ),
            "https://api.example.com/v1", "k"
        ).bodyJson()

        val msgs = body["messages"] as JsonArray
        val url = (msgs.last().jsonObject["content"] as JsonArray)
            .first { it.jsonObject.str("type") == "image_url" }
            .jsonObject["image_url"]!!.jsonObject.str("url")!!
        assertTrue("base64 被破坏：$url", url.endsWith(tricky))
    }

    @Test
    fun OFF等级下Anthropic不因maxTokens极小而产生非法budget() {
        // 边界：maxTokens 小于 1024 时预算公式不能再吐出 "等于 max_tokens" 的非法值
        val body = adapters.getValue("anthropic").build(
            request(ThinkingLevel.HIGH, maxTokens = 1024), "https://api.anthropic.com/v1", "k"
        ).bodyJson()
        val budget = body["thinking"]?.jsonObject?.get("budget_tokens")?.jsonPrimitive?.int
        val maxTokens = body["max_tokens"]!!.jsonPrimitive.int
        if (budget != null) assertTrue(budget < maxTokens)
    }

    @Test
    fun maxTokens过小则整块省略thinking而非发出非法请求() {
        // maxTokens=1024 时连最低预算都放不下 → 必须完全省略 thinking 字段，
        // 退化成普通请求仍可用；若强行下发 budget=1024 会被 Anthropic 直接 400。
        val body = adapters.getValue("anthropic").build(
            request(ThinkingLevel.HIGH, maxTokens = 1024), "https://api.anthropic.com/v1", "k"
        ).bodyJson()

        assertFalse(
            "放不下有效预算时必须省略 thinking 字段（而不是发非法值）",
            body.containsKey("thinking")
        )
        assertEquals("其余字段必须照常存在", 1024, body["max_tokens"]!!.jsonPrimitive.int)
    }

    @Test
    fun maxTokens刚好够用时会下发合法thinking() {
        // 2048 恰好能容纳 1024 的预算下限（1024 给输出留白）
        val body = adapters.getValue("anthropic").build(
            request(ThinkingLevel.HIGH, maxTokens = 2048), "https://api.anthropic.com/v1", "k"
        ).bodyJson()

        val budget = body["thinking"]?.jsonObject?.get("budget_tokens")?.jsonPrimitive?.int
        assertNotNull("2048 应该能容纳思考预算", budget)
        assertTrue("预算必须严格小于 max_tokens", budget!! < 2048)
        assertTrue("预算不得低于 1024 下限", budget >= 1024)
    }

    @Test
    fun 序列化结果可被再次解析为合法JSON() {
        ThinkingLevel.entries.forEach { level ->
            adapters.forEach { (name, adapter) ->
                val text = adapter.build(
                    request(level), "https://api.example.com/v1", "k"
                ).bodyText()
                // 能解析即说明没有产生畸形 JSON
                testJson.parseToJsonElement(text)
            }
        }
    }

    @Test
    fun JsonPrimitive的int读取在三协议思考字段上均可用() {
        // 防御性：确保 budget 字段写的是数字而不是字符串（字符串会被上游拒绝）
        val anthropic = adapters.getValue("anthropic").build(
            request(ThinkingLevel.HIGH), "https://api.anthropic.com/v1", "k"
        ).bodyJson()["thinking"]!!.jsonObject["budget_tokens"]!!
        assertTrue("budget_tokens 必须是 JsonPrimitive 数字", anthropic is JsonPrimitive)
        assertTrue("budget_tokens 不能带引号", !anthropic.toString().contains("\""))

        val gemini = adapters.getValue("gemini").build(
            request(ThinkingLevel.HIGH), "https://generativelanguage.googleapis.com", "k"
        ).bodyJson()["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject["thinkingBudget"]!!
        assertTrue("thinkingBudget 必须无引号", !gemini.toString().contains("\""))
    }
}
