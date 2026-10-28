package com.wildcreator.sonorus.data.remote.protocol

import com.wildcreator.sonorus.domain.agent.FinishReason
import com.wildcreator.sonorus.domain.agent.RemoteModel
import com.wildcreator.sonorus.domain.agent.StreamDelta
import com.wildcreator.sonorus.domain.agent.ToolCallDelta
import com.wildcreator.sonorus.domain.agent.UnifiedChatRequest
import com.wildcreator.sonorus.domain.agent.UnifiedMessage
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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OpenAI 兼容协议：/models、/chat/completions、Bearer 鉴权、delta 流式。
 * 适用于 OpenAI、DeepSeek、Moonshot、通义、Groq、vLLM / One-API / New-API 等兼容网关。
 */
@Singleton
class OpenAiCompatAdapter @Inject constructor(
    private val json: Json
) : ProtocolAdapter {

    override val protocol: ProviderProtocol = ProviderProtocol.OPENAI

    override fun normalizeBaseUrl(raw: String): String = withDefaultPath(raw, "/v1")

    override fun modelsRequest(baseUrl: String, apiKey: String): Request = Request.Builder()
        .url(baseUrl.trimEnd('/') + "/models")
        .header("Authorization", "Bearer $apiKey")
        .get()
        .build()

    override fun parseModels(body: String): List<RemoteModel> {
        val root = json.parseToJsonElement(body).jsonObject
        val data = root["data"]?.jsonArray ?: return emptyList()
        return data.mapNotNull { element ->
            val obj = element.jsonObject
            val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            RemoteModel(id = id, ownedBy = obj["owned_by"]?.jsonPrimitive?.content)
        }
    }

    override fun chatRequest(
        baseUrl: String,
        apiKey: String,
        request: UnifiedChatRequest
    ): Request {
        val payload = buildJsonObject {
            put("model", request.model)
            putJsonArray("messages") {
                request.systemPrompt?.takeIf { it.isNotBlank() }?.let { sys ->
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", sys)
                    })
                }
                request.messages.forEach { add(it.toWire()) }
            }
            request.tools?.takeIf { it.isNotEmpty() }?.let { tools ->
                putJsonArray("tools") {
                    tools.forEach { tool ->
                        add(buildJsonObject {
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("parameters", tool.schema)
                            }
                        })
                    }
                }
            }
            put("stream", true)
            // Usage is emitted in the final SSE frame when requested.
            putJsonObject("stream_options") { put("include_usage", true) }
            put("max_tokens", request.maxTokens)
            // 思考等级：OpenAI 系用 reasoning_effort；OFF 时不发该字段，
            // 避免不支持推理的模型/网关因未知参数报 400
            request.thinkingLevel.reasoningEffort()?.let { put("reasoning_effort", it) }
        }
        return Request.Builder()
            .url(baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
    }

    override fun parseStream(event: String?, data: String): StreamDelta? {
        if (data == "[DONE]") return null
        val root = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return null

        // 全部走安全取值：网关末帧常见 "error": null / "delta": null，
        // 用 jsonObject/jsonArray 会抛异常把整条已流出的回答连累成"网络错误"
        root.obj("error")?.let {
            return StreamDelta(error = it.text("message") ?: "上游返回错误")
        }

        val inputTokens = root.obj("usage")?.get("prompt_tokens").asIntOrNull() ?: root.obj("usage")?.get("input_tokens").asIntOrNull()
        val choice = root.arr("choices")?.firstOrNull().asObject()
        if (choice == null) return inputTokens?.let { StreamDelta(inputTokens = it) }
        // 有的网关把完整 message 塞进一帧 data: 里，而不是逐 token 的 delta；
        // 少数兼容层还会走 completions 形态，正文在 choice.text
        val payload = choice.obj("delta") ?: choice.obj("message")
        // 拒绝回答是给用户看的正文，不是"空内容"；部分网关只回 refusal
        val text = payload?.get("content").asContentText()
            ?: payload?.get("refusal").asText()
            ?: choice["text"].asContentText()
        // 推理增量：DeepSeek / 通义 / Qwen 等用 reasoning_content，部分网关（xAI、OpenRouter）用 reasoning
        val reasoning = (payload?.get("reasoning_content").asContentText() ?: payload?.get("reasoning").asContentText())
            ?.takeIf { it.isNotEmpty() }

        val toolCalls = payload?.arr("tool_calls")?.mapNotNull { element ->
            val obj = element.asObject() ?: return@mapNotNull null
            val fn = obj.obj("function")
            val hasAny = obj["id"] != null || fn != null
            if (!hasAny) return@mapNotNull null
            ToolCallDelta(
                index = obj["index"].asInt(),
                id = obj["id"].asText(),
                nameChunk = fn?.get("name").asText(),
                argsChunk = fn?.get("arguments").asText()
            )
        }?.takeIf { it.isNotEmpty() }

        val finish = choice.finishReason()

        return if (text == null && reasoning == null && toolCalls == null && finish == null && inputTokens == null) null
        else StreamDelta(text = text, reasoning = reasoning, toolCalls = toolCalls, finish = finish, inputTokens = inputTokens)
    }

    /** 完整（非流式）响应：网关忽略 stream=true 时不能把它当成空回答 */
    override fun parseNonStream(body: String): StreamDelta? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        root.obj("error")?.let {
            return StreamDelta(error = it.text("message") ?: "上游返回错误")
        }
        val choice = root.arr("choices")?.firstOrNull().asObject() ?: return null
        val message = choice.obj("message")
        val text = message?.get("content").asContentText()
            ?: message?.get("refusal").asText()
            ?: choice["text"].asContentText()
        val reasoning = (message?.get("reasoning_content").asContentText()
            ?: message?.get("reasoning").asContentText())?.takeIf { it.isNotEmpty() }
        val toolCalls = message?.arr("tool_calls")?.mapIndexedNotNull { index, element ->
            val obj = element.asObject() ?: return@mapIndexedNotNull null
            val fn = obj.obj("function") ?: return@mapIndexedNotNull null
            ToolCallDelta(
                index = index,
                id = obj.text("id"),
                nameSet = fn.text("name"),
                argsSet = fn.text("arguments")
            )
        }?.takeIf { it.isNotEmpty() }
        val finish = choice.finishReason()
        return if (text == null && reasoning == null && toolCalls == null && finish == null) null
        else StreamDelta(text = text, reasoning = reasoning, toolCalls = toolCalls, finish = finish)
    }

    private fun kotlinx.serialization.json.JsonObject.finishReason(): FinishReason? =
        this["finish_reason"].asText()?.let { reason ->
            when (reason) {
                "tool_calls", "function_call" -> FinishReason.TOOL_CALLS
                // 被长度上限截断：内容不完整，不能当正常收尾
                "length" -> FinishReason.LENGTH
                "content_filter" -> FinishReason.BLOCKED
                else -> FinishReason.STOP
            }
        }

    private fun UnifiedMessage.toWire(): JsonObject = buildJsonObject {
        when (role) {
            UnifiedRole.USER -> {
                put("role", "user")
                val images = images?.takeIf { it.isNotEmpty() }
                if (images == null) {
                    put("content", text ?: "")
                } else {
                    // 多模态形态：content 为 [text?, image_url...] 数组
                    putJsonArray("content") {
                        text?.takeIf { it.isNotEmpty() }?.let {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", it)
                            })
                        }
                        images.forEach { image ->
                            add(buildJsonObject {
                                put("type", "image_url")
                                putJsonObject("image_url") {
                                    put("url", "data:${image.mimeType};base64,${image.base64}")
                                }
                            })
                        }
                    }
                }
            }
            UnifiedRole.ASSISTANT -> {
                put("role", "assistant")
                text?.let { put("content", it) }
                toolCalls?.takeIf { it.isNotEmpty() }?.let { calls ->
                    putJsonArray("tool_calls") {
                        calls.forEach { call ->
                            add(buildJsonObject {
                                put("id", call.id)
                                put("type", "function")
                                putJsonObject("function") {
                                    put("name", call.name)
                                    put("arguments", call.arguments)
                                }
                            })
                        }
                    }
                }
            }
            UnifiedRole.TOOL -> {
                put("role", "tool")
                put("content", text ?: "")
                toolCallId?.let { put("tool_call_id", it) }
            }
        }
    }

    private companion object {
        val JSON_MEDIA = "application/json".toMediaType()
    }
}
