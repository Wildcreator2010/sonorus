package com.wildcreator.sonorus.domain.agent

import kotlinx.serialization.json.JsonObject

/**
 * 与具体线协议解耦的会话/工具/流模型。
 * data 层的三个 ProtocolAdapter 负责在"统一模型"与"各家 wire format"之间双向转换。
 */

enum class UnifiedRole { USER, ASSISTANT, TOOL }

/**
 * 内嵌图片（仅 user 消息使用）。
 * base64 不含 data: 前缀，mime 由 mimeType 单独携带，便于三协议分别编码。
 */
data class ImagePart(
    val mimeType: String, // image/png / image/jpeg / image/webp / image/gif
    val base64: String
)

data class UnifiedMessage(
    val role: UnifiedRole,
    val text: String? = null,
    /** 用户附带的图片（多模态输入） */
    val images: List<ImagePart>? = null,
    /** assistant 发起的工具调用（可能多个） */
    val toolCalls: List<ToolCall>? = null,
    /** tool 结果：归属的调用 id */
    val toolCallId: String? = null,
    /** tool 结果：工具名（Gemini 的 functionResponse 需要） */
    val toolName: String? = null
)

data class UnifiedTool(
    val name: String,
    val description: String,
    /** JSON Schema */
    val schema: JsonObject
)

/**
 * 思考（推理）等级。
 *
 * 三套协议的表达方式完全不同，统一成一个四档枚举后由各适配器自行映射：
 * - OpenAI 兼容：`reasoning_effort`（low / medium / high）
 * - Anthropic：`thinking.budget_tokens`（关闭时不发该字段）
 * - Gemini：`generationConfig.thinkingConfig.thinkingBudget`
 *
 * OFF 表示不下发任何思考参数 —— 这样不支持思考的模型/网关不会被塞入未知字段。
 */
enum class ThinkingLevel(val label: String, val description: String) {
    OFF("关闭", "不启用思考，响应最快、最省 token"),
    LOW("低", "轻量思考，适合简单问答"),
    MEDIUM("中", "平衡模式，适合日常任务"),
    HIGH("高", "深度思考，适合复杂推理与代码")
}

data class UnifiedChatRequest(
    val model: String,
    val systemPrompt: String?,
    val messages: List<UnifiedMessage>,
    /** 工具声明；null 或空表示本轮不启用工具 */
    val tools: List<UnifiedTool>? = null,
    val maxTokens: Int = 4096,
    /** 思考等级；OFF 时三协议都不下发思考相关参数 */
    val thinkingLevel: ThinkingLevel = ThinkingLevel.OFF
)

/**
 * 上游本轮的收尾原因。
 * LENGTH / BLOCKED 意味着"这段文本不是完整答案"，调用方绝不能当正常回答落库。
 */
enum class FinishReason { STOP, TOOL_CALLS, LENGTH, BLOCKED }

/**
 * 工具调用增量。
 * - nameSet / argsSet：完整值，直接覆盖（Gemini）；
 * - nameChunk / argsChunk：分片，追加（OpenAI / Anthropic）。
 */
data class ToolCallDelta(
    val index: Int,
    val id: String? = null,
    val nameSet: String? = null,
    val nameChunk: String? = null,
    val argsSet: String? = null,
    val argsChunk: String? = null
)

data class StreamDelta(
    val text: String? = null,
    /**
     * 模型推理（思考）增量。各家字段名不同，适配器负责翻译到这一个字段：
     * OpenAI 兼容 = delta.reasoning_content / delta.reasoning；
     * Anthropic = content_block_delta 的 thinking_delta.thinking；
     * Gemini = parts[].thought == true 的 text。
     */
    val reasoning: String? = null,
    val toolCalls: List<ToolCallDelta>? = null,
    val finish: FinishReason? = null,
    /** 流内错误（部分提供商把报错放在 SSE data 里），非空时客户端转为 Error 事件 */
    val error: String? = null
)

data class RemoteModel(
    val id: String,
    val ownedBy: String? = null
)
