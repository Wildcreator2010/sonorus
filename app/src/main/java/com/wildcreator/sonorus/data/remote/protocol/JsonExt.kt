package com.wildcreator.sonorus.data.remote.protocol

import com.wildcreator.sonorus.domain.agent.ThinkingLevel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI

internal fun JsonElement?.asText(): String? = (this as? JsonPrimitive)?.contentOrNull

internal fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

/**
 * 数组取值一律走这里，不要用 `element.jsonArray`：
 * 后者遇到 `"delta": null` / `"error": null`（网关末帧常见）会抛 IllegalArgumentException，
 * 把整条已经正常流出的回答连累成"网络错误"。JsonNull 在这里安静地返回 null。
 */
internal fun JsonElement?.asArray(): JsonArray? = this as? JsonArray

internal fun JsonElement?.asInt(fallback: Int = 0): Int =
    (this as? JsonPrimitive)?.intOrNull ?: fallback

/** 布尔字段（Gemini 的 part.thought）；非布尔原样返回 null */
internal fun JsonElement?.asBoolean(): Boolean? =
    (this as? JsonPrimitive)?.booleanOrNull

internal fun JsonElement?.asTextOrNull(): String? =
    (this as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.text(key: String): String? = this[key].asText()

internal fun JsonObject.obj(key: String): JsonObject? = this[key].asObject()

internal fun JsonObject.arr(key: String): JsonArray? = this[key].asArray()

internal fun JsonElement.asPrimitiveText(): String? = jsonPrimitive.contentOrNull

// ---------- 思考等级 -> 各家参数映射 ----------

/** OpenAI 系：reasoning_effort。OFF 返回 null 表示不下发 */
internal fun ThinkingLevel.reasoningEffort(): String? = when (this) {
    ThinkingLevel.OFF -> null
    ThinkingLevel.LOW -> "low"
    ThinkingLevel.MEDIUM -> "medium"
    ThinkingLevel.HIGH -> "high"
}

/**
 * Anthropic 系：thinking.budget_tokens 必须小于 max_tokens，否则 API 直接报错。
 *
 * 返回 null 表示不下发 thinking 块，两种情况：
 * 1) 等级为 OFF；
 * 2) max_tokens 太小，塞不下任何有意义的思考预算（此时宁可退化为普通请求，
 *    也不能发出 budget >= max_tokens 的非法请求 —— 那会被上游直接 400）。
 */
internal fun ThinkingLevel.anthropicBudget(maxTokens: Int): Int? {
    if (this == ThinkingLevel.OFF) return null
    // Anthropic 要求预算至少 1024，且必须为最终回答留出空间
    val floor = ANTHROPIC_MIN_BUDGET
    val headroom = maxTokens - floor
    if (headroom < floor) return null

    val desired = maxTokens / budgetDivisor()
    val budget = desired.coerceAtMost(headroom).coerceAtLeast(floor)
    // 兜底：无论公式如何演变，都不允许产出违反上游约束的值
    return budget.takeIf { it < maxTokens }
}

/** Anthropic 对 thinking budget 的下限要求 */
private const val ANTHROPIC_MIN_BUDGET = 1024

private fun ThinkingLevel.budgetDivisor(): Int = when (this) {
    ThinkingLevel.OFF -> 1
    ThinkingLevel.LOW -> 6
    ThinkingLevel.MEDIUM -> 4
    ThinkingLevel.HIGH -> 2
}

/** Gemini 系：thinkingConfig.thinkingBudget（-1 = 动态，0 = 关闭） */
internal fun ThinkingLevel.geminiBudget(): Int? = when (this) {
    ThinkingLevel.OFF -> null
    ThinkingLevel.LOW -> 2048
    ThinkingLevel.MEDIUM -> 8192
    ThinkingLevel.HIGH -> 24576
}

/**
 * baseUrl 规整：去掉尾部斜杠；若用户只填了域名（无路径）则补上协议默认路径。
 * 例：https://api.openai.com -> https://api.openai.com/v1
 *     https://gw.example.com/v1 -> 保持原样（用户显式指定）
 */
internal fun withDefaultPath(raw: String, fallbackPath: String): String {
    val url = raw.trim().trimEnd('/')
    if (url.isEmpty()) return url
    return try {
        val path = URI(url).path
        if (path.isNullOrBlank() || path == "/") url + fallbackPath else url
    } catch (e: Exception) {
        url
    }
}
