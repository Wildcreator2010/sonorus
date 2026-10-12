package com.wildcreator.sonorus.data.remote.protocol

import com.wildcreator.sonorus.domain.agent.FinishReason
import com.wildcreator.sonorus.domain.agent.RemoteModel
import com.wildcreator.sonorus.domain.agent.StreamDelta
import com.wildcreator.sonorus.domain.agent.ToolCallDelta
import com.wildcreator.sonorus.domain.agent.UnifiedChatRequest
import com.wildcreator.sonorus.domain.agent.UnifiedRole
import com.wildcreator.sonorus.domain.model.ProviderProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Google Gemini (Generative Language API)：
 * - 鉴权头 x-goog-api-key（避免把密钥写进 URL query 被日志/代理记录）
 * - 端点 {base}/models/{model}:streamGenerateContent?alt=sse
 * - systemInstruction 独立字段；内容结构 contents[].parts[]
 * - 工具：functionDeclarations / functionCall / functionResponse
 * - 模型 id 形如 models/gemini-1.5-pro，落库时去掉前缀
 */
@Singleton
class GeminiAdapter @Inject constructor(
    private val json: Json
) : ProtocolAdapter {

    override val protocol: ProviderProtocol = ProviderProtocol.GEMINI

    override fun normalizeBaseUrl(raw: String): String = withDefaultPath(raw, "/v1beta")

    override fun modelsRequest(baseUrl: String, apiKey: String): Request = Request.Builder()
        .url(baseUrl.trimEnd('/') + "/models")
        .header("x-goog-api-key", apiKey)
        .get()
        .build()

    override fun parseModels(body: String): List<RemoteModel> {
        val root = json.parseToJsonElement(body).jsonObject
        val models = root["models"]?.jsonArray ?: return emptyList()
        return models.mapNotNull { element ->
            val obj = element.jsonObject
            val methods = obj["supportedGenerationMethods"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.content }
                .orEmpty()
            // 只保留可用于聊天的模型，过滤 embedding / tuning 等
            if (methods.isNotEmpty() && "generateContent" !in methods) return@mapNotNull null
            val rawId = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            RemoteModel(
                id = rawId.removePrefix("models/"),
                ownedBy = obj["displayName"]?.jsonPrimitive?.content
            )
        }
    }

    override fun chatRequest(
        baseUrl: String,
        apiKey: String,
        request: UnifiedChatRequest
    ): Request {
        val modelId = request.model.removePrefix("models/")
        val payload = buildJsonObject {
            request.systemPrompt?.takeIf { it.isNotBlank() }?.let {
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") {
                        add(buildJsonObject { put("text", it) })
                    }
                }
            }
            putJsonArray("contents") { request.toGeminiContents().forEach { add(it) } }
            request.tools?.takeIf { it.isNotEmpty() }?.let { tools ->
                putJsonArray("tools") {
                    add(buildJsonObject {
                        putJsonArray("functionDeclarations") {
                            tools.forEach { tool ->
                                add(buildJsonObject {
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    put("parameters", tool.schema)
                                })
                            }
                        }
                    })
                }
            }
            putJsonObject("generationConfig") {
                put("maxOutputTokens", request.maxTokens)
                // 思考预算：Gemini 2.5 系列认 thinkingBudget，OFF 时不发该字段
                request.thinkingLevel.geminiBudget()?.let { budget ->
                    putJsonObject("thinkingConfig") {
                        put("thinkingBudget", budget)
                        // 必须显式要，否则上游根本不回带 thought=true 的分片，
                        // 下面的推理分支就成了永远进不去的死代码（默认只返回最终输出）
                        put("includeThoughts", true)
                    }
                }
            }
        }
        return Request.Builder()
            .url(baseUrl.trimEnd('/') + "/models/$modelId:streamGenerateContent?alt=sse")
            .header("x-goog-api-key", apiKey)
            .header("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
    }

    override fun parseStream(event: String?, data: String): StreamDelta? {
        val root = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return null

        root.obj("error")?.let {
            return StreamDelta(error = it["message"].asText() ?: "Gemini 返回错误")
        }

        val candidate = root.arr("candidates")?.firstOrNull().asObject() ?: return null
        // 末帧经常只有 finishReason 而没有 parts。这里若早退，收尾原因就整帧丢失 ——
        // "被 SAFETY 拦截"和"正常说完"将无法区分，半截答案会被当成完整回答。
        val parts = candidate.obj("content")?.arr("parts")

        val text = StringBuilder()
        val reasoning = StringBuilder()
        val toolCalls = mutableListOf<ToolCallDelta>()
        parts?.forEach { element ->
            val part = element.asObject() ?: return@forEach
            // Gemini 2.5 在 part 上打 thought=true 标记推理文本，正文与推理共用 text 字段
            val piece = part["text"].asText()
            if (piece != null) {
                if (part["thought"].asBoolean() == true) reasoning.append(piece) else text.append(piece)
            }
            part.obj("functionCall")?.let { call ->
                toolCalls += ToolCallDelta(
                    index = toolCalls.size,
                    id = "gemini_call_${UUID.randomUUID()}",
                    nameSet = call["name"].asText(),
                    argsSet = (call["args"] ?: buildJsonObject { }).toString()
                )
            }
        }

        // Gemini 无 tool_use 停止原因，用"本帧是否带 functionCall"判定；
        // MAX_TOKENS / 各类安全拦截必须映射成不完整收尾，不能一律算 STOP
        val finish = candidate["finishReason"].asText()?.let { reason ->
            when {
                toolCalls.isNotEmpty() -> FinishReason.TOOL_CALLS
                reason == "MAX_TOKENS" -> FinishReason.LENGTH
                reason in setOf("SAFETY", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "RECITATION") ->
                    FinishReason.BLOCKED
                else -> FinishReason.STOP
            }
        }

        val resultText = text.toString().takeIf { it.isNotEmpty() }
        val resultReasoning = reasoning.toString().takeIf { it.isNotEmpty() }
        return if (resultText == null && resultReasoning == null && toolCalls.isEmpty() && finish == null) null
        else StreamDelta(
            text = resultText,
            reasoning = resultReasoning,
            toolCalls = toolCalls.takeIf { it.isNotEmpty() },
            finish = finish
        )
    }

    private fun UnifiedChatRequest.toGeminiContents(): List<JsonObject> {
        val contents = mutableListOf<JsonObject>()
        messages.forEach { message ->
            when (message.role) {
                UnifiedRole.USER -> contents += content("user") {
                    // Gemini 图片 part：inline_data.mime_type + base64（无 data: 前缀）
                    message.images?.takeIf { it.isNotEmpty() }?.forEach { image ->
                        add(buildJsonObject {
                            putJsonObject("inline_data") {
                                put("mime_type", image.mimeType)
                                put("data", image.base64)
                            }
                        })
                    }
                    add(buildJsonObject { put("text", message.text ?: "") })
                }
                UnifiedRole.ASSISTANT -> contents += content("model") {
                    message.text?.takeIf { it.isNotBlank() }?.let {
                        add(buildJsonObject { put("text", it) })
                    }
                    message.toolCalls?.forEach { call ->
                        add(buildJsonObject {
                            putJsonObject("functionCall") {
                                put("name", call.name)
                                put("args", json.parseOrEmptyObject(call.arguments))
                            }
                        })
                    }
                }
                UnifiedRole.TOOL -> contents += content("user") {
                    add(buildJsonObject {
                        putJsonObject("functionResponse") {
                            put("name", message.toolName ?: "tool")
                            putJsonObject("response") {
                                put("result", message.text ?: "")
                            }
                        }
                    })
                }
            }
        }
        if (contents.isEmpty()) {
            contents += content("user") { add(buildJsonObject { put("text", "你好") }) }
        }
        return contents
    }

    private fun content(role: String, parts: kotlinx.serialization.json.JsonArrayBuilder.() -> Unit) =
        buildJsonObject {
            put("role", role)
            putJsonArray("parts") { parts() }
        }

    private fun Json.parseOrEmptyObject(raw: String): JsonObject =
        if (raw.isBlank()) buildJsonObject { }
        else runCatching { parseToJsonElement(raw).jsonObject }.getOrDefault(buildJsonObject { })

    private companion object {
        val JSON_MEDIA = "application/json".toMediaType()
    }
}
