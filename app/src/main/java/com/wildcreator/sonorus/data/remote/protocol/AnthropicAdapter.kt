package com.wildcreator.sonorus.data.remote.protocol

import com.wildcreator.sonorus.domain.agent.FinishReason
import com.wildcreator.sonorus.domain.agent.RemoteModel
import com.wildcreator.sonorus.domain.agent.StreamDelta
import com.wildcreator.sonorus.domain.agent.ToolCallDelta
import com.wildcreator.sonorus.domain.agent.UnifiedChatRequest
import com.wildcreator.sonorus.domain.agent.UnifiedRole
import com.wildcreator.sonorus.domain.model.ProviderProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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
 * Anthropic Messages API：
 * - POST {base}/messages，鉴权头 x-api-key + anthropic-version
 * - system 独立字段；消息 content 为 blocks 数组
 * - 工具：声明用 input_schema；调用为 tool_use block；结果必须合并为单条 user 消息的 tool_result blocks
 * - 流式：event 区分 content_block_start / content_block_delta / message_delta
 */
@Singleton
class AnthropicAdapter @Inject constructor(
    private val json: Json
) : ProtocolAdapter {

    override val protocol: ProviderProtocol = ProviderProtocol.ANTHROPIC

    override fun normalizeBaseUrl(raw: String): String = withDefaultPath(raw, "/v1")

    override fun modelsRequest(baseUrl: String, apiKey: String): Request = Request.Builder()
        .url(baseUrl.trimEnd('/') + "/models")
        .header("x-api-key", apiKey)
        .header("anthropic-version", ANTHROPIC_VERSION)
        .get()
        .build()

    override fun parseModels(body: String): List<RemoteModel> {
        val root = json.parseToJsonElement(body).jsonObject
        val data = root["data"]?.jsonArray ?: return emptyList()
        return data.mapNotNull { element ->
            val obj = element.jsonObject
            val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            RemoteModel(id = id, ownedBy = obj["display_name"]?.jsonPrimitive?.content)
        }
    }

    override fun chatRequest(
        baseUrl: String,
        apiKey: String,
        request: UnifiedChatRequest
    ): Request {
        val payload = buildJsonObject {
            put("model", request.model)
            put("max_tokens", request.maxTokens)
            put("stream", true)
            request.systemPrompt?.takeIf { it.isNotBlank() }?.let { put("system", it) }
            // 思考预算必须小于 max_tokens（API 硬约束），由 anthropicBudget 保证
            request.thinkingLevel.anthropicBudget(request.maxTokens)?.let { budget ->
                putJsonObject("thinking") {
                    put("type", "enabled")
                    put("budget_tokens", budget)
                }
            }
            putJsonArray("messages") { request.toAnthropicMessages().forEach { add(it) } }
            request.tools?.takeIf { it.isNotEmpty() }?.let { tools ->
                putJsonArray("tools") {
                    tools.forEach { tool ->
                        add(buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("input_schema", tool.schema)
                        })
                    }
                }
            }
        }
        return Request.Builder()
            .url(baseUrl.trimEnd('/') + "/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .header("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
    }

    override fun parseStream(event: String?, data: String): StreamDelta? {
        val root = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return null
        val type = root["type"].asText() ?: event

        return when (type) {
            "message_start" -> StreamDelta(inputTokens = root.obj("message")?.obj("usage")?.get("input_tokens").asIntOrNull())
            "content_block_start" -> {
                val block = root.obj("content_block") ?: return null
                if (block["type"].asText() != "tool_use") return null
                StreamDelta(
                    toolCalls = listOf(
                        ToolCallDelta(
                            index = root["index"].asInt(),
                            id = block["id"].asText(),
                            nameSet = block["name"].asText()
                        )
                    )
                )
            }
            "content_block_delta" -> {
                val delta = root.obj("delta") ?: return null
                when (delta["type"].asText()) {
                    "text_delta" -> StreamDelta(text = delta["text"].asText())
                    // 思考块增量；signature_delta 是签名校验用，与可读推理无关，忽略
                    "thinking_delta" -> StreamDelta(
                        reasoning = delta["thinking"].asText()?.takeIf { it.isNotEmpty() }
                    )
                    "input_json_delta" -> StreamDelta(
                        toolCalls = listOf(
                            ToolCallDelta(
                                index = root["index"].asInt(),
                                argsChunk = delta["partial_json"].asText()
                            )
                        )
                    )
                    else -> null
                }
            }
            "message_delta" -> {
                val stop = root.obj("delta")?.get("stop_reason").asText()
                StreamDelta(
                    // max_tokens 收尾意味着答案被截断，不能当正常说完
                    finish = when (stop) {
                        "tool_use" -> FinishReason.TOOL_CALLS
                        "max_tokens" -> FinishReason.LENGTH
                        "refusal" -> FinishReason.BLOCKED
                        else -> FinishReason.STOP
                    }
                )
            }
            "error" -> StreamDelta(
                error = root.obj("error")?.get("message").asText() ?: "Anthropic 返回错误"
            )
            else -> null
        }
    }

    /** 完整（非流式）响应：content 是 blocks 数组，与 SSE 的事件形态完全不同 */
    override fun parseNonStream(body: String): StreamDelta? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        root.obj("error")?.let {
            return StreamDelta(error = it.get("message").asText() ?: "Anthropic 返回错误")
        }
        val blocks = root.arr("content") ?: return null
        val text = blocks.mapNotNull { block ->
            val obj = block.asObject() ?: return@mapNotNull null
            if (obj["type"].asText() == "text") obj["text"].asText() else null
        }.joinToString("").takeIf { it.isNotEmpty() }
        val toolCalls = blocks.mapIndexedNotNull { index, block ->
            val obj = block.asObject() ?: return@mapIndexedNotNull null
            if (obj["type"].asText() != "tool_use") return@mapIndexedNotNull null
            ToolCallDelta(
                index = index,
                id = obj["id"].asText(),
                nameSet = obj["name"].asText(),
                argsSet = obj["input"]?.toString() ?: "{}"
            )
        }.takeIf { it.isNotEmpty() }
        val finish = when (root["stop_reason"].asText()) {
            "tool_use" -> FinishReason.TOOL_CALLS
            "max_tokens" -> FinishReason.LENGTH
            "refusal" -> FinishReason.BLOCKED
            null -> null
            else -> FinishReason.STOP
        }
        return if (text == null && toolCalls == null && finish == null) null
        else StreamDelta(text = text, toolCalls = toolCalls, finish = finish)
    }

    /**
     * 统一消息 -> Anthropic messages：
     * 连续的 tool 结果必须合并进同一条 user 消息（Anthropic 强制要求）。
     */
    private fun UnifiedChatRequest.toAnthropicMessages(): List<JsonObject> {
        val result = mutableListOf<JsonObject>()
        var index = 0
        while (index < messages.size) {
            val message = messages[index]
            when (message.role) {
                UnifiedRole.TOOL -> {
                    val group = mutableListOf<JsonElement>()
                    while (index < messages.size && messages[index].role == UnifiedRole.TOOL) {
                        group += buildJsonObject {
                            put("type", "tool_result")
                            put("tool_use_id", messages[index].toolCallId ?: "")
                            put("content", messages[index].text ?: "")
                        }
                        index++
                    }
                    result += buildJsonObject {
                        put("role", "user")
                        putJsonArray("content") { group.forEach { add(it) } }
                    }
                }
                UnifiedRole.ASSISTANT -> {
                    result += buildJsonObject {
                        put("role", "assistant")
                        putJsonArray("content") {
                            message.text?.takeIf { it.isNotBlank() }?.let {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", it)
                                })
                            }
                            message.toolCalls?.forEach { call ->
                                add(buildJsonObject {
                                    put("type", "tool_use")
                                    put("id", call.id)
                                    put("name", call.name)
                                    put("input", parseInput(call.arguments))
                                })
                            }
                        }
                    }
                    index++
                }
                UnifiedRole.USER -> {
                    result += buildJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            // Anthropic 图片 block：source.base64 + media_type
                            message.images?.takeIf { it.isNotEmpty() }?.forEach { image ->
                                add(buildJsonObject {
                                    put("type", "image")
                                    putJsonObject("source") {
                                        put("type", "base64")
                                        put("media_type", image.mimeType)
                                        put("data", image.base64)
                                    }
                                })
                            }
                            // 空 text block 会被 Anthropic 拒绝；纯图片消息只留 image block
                            message.text?.takeIf { it.isNotBlank() }?.let {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", it)
                                })
                            }
                        }
                    }
                    index++
                }
            }
        }
        if (result.isEmpty()) {
            result += buildJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    add(buildJsonObject { put("type", "text"); put("text", "你好") })
                }
            }
        }
        // 空 content 数组 Anthropic 会直接 400（模型只出推理不出正文时就会出现），先滤掉再合并
        val nonEmpty = result.filter {
            (it["content"] as? kotlinx.serialization.json.JsonArray)?.isNotEmpty() == true
        }
        return mergeAdjacentRoles(nonEmpty)
    }

    /**
     * Anthropic 强制 user / assistant 交替出现。
     *
     * 一次请求失败后库里会留下没有配对的用户消息（用户消息总是先落库），下一次发送就是
     * 连续两条 user —— 上游直接 400，而那条坏行不修就永远发不出去，**整个会话永久卡死**。
     * 这里把相邻同角色消息的 content 块合并成一条，让坏历史自愈。
     */
    private fun mergeAdjacentRoles(list: List<JsonObject>): List<JsonObject> {
        if (list.size < 2) return list
        val merged = mutableListOf<JsonObject>()

        for (message in list) {
            val previous = merged.lastOrNull()
            val previousRole = previous?.get("role").asText()
            val role = message["role"].asText()

            if (previous != null && role != null && previousRole == role) {
                val blocks = mutableListOf<kotlinx.serialization.json.JsonElement>()
                (previous["content"] as? kotlinx.serialization.json.JsonArray)?.let { blocks.addAll(it) }
                (message["content"] as? kotlinx.serialization.json.JsonArray)?.let { blocks.addAll(it) }
                merged[merged.lastIndex] = buildJsonObject {
                    put("role", role)
                    putJsonArray("content") { blocks.forEach { add(it) } }
                }
            } else {
                merged += message
            }
        }
        return merged
    }

    private fun parseInput(arguments: String): JsonElement =
        if (arguments.isBlank()) buildJsonObject { }
        else runCatching { json.parseToJsonElement(arguments) }.getOrDefault(buildJsonObject { })

    private companion object {
        const val ANTHROPIC_VERSION = "2023-06-01"
        val JSON_MEDIA = "application/json".toMediaType()
    }
}
