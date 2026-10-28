package com.wildcreator.sonorus.domain.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Agent 工具循环刹车：识别"模型在原地打转"的调用模式并叫停本轮。
 *
 * 背景：只靠"最大轮次"兜底会把 8 轮全部烧在一个不可能成功的调用上，用户看到的是
 * 一连串空回复 + 最后弹"工具调用轮次超限"。真正需要的是**放弃条件**：
 *
 * - 相同工具 + 相同参数重复调用：第一次已经拿到结果，再调一次不可能得到新信息；
 * - 单个工具在一轮内被反复调用：说明模型没在看结果；
 * - 同一工具反复执行失败：重试只是把失败次数刷满。
 *
 * 与"最大轮次"的分工：这里是业务级放弃条件，轮次上限是最后的保险丝。两者都要有。
 */
class ToolCallGuard(
    /** 同一 (工具名, 参数) 允许出现的次数：给一次瞬时失败重试的机会 */
    private val maxIdenticalCalls: Int = 2,
    /** 单个工具在一轮内的调用次数上限：参数不同也受限 */
    private val maxCallsPerTool: Int = 4,
    /** 单个工具连续执行失败的上限 */
    private val maxFailuresPerTool: Int = 2
) {

    sealed interface Verdict {
        /** 允许执行 */
        object Allow : Verdict

        /** 拒绝执行并终止本轮 */
        data class Stop(val reason: String) : Verdict
    }

    private val identicalCounts = mutableMapOf<String, Int>()
    private val perToolCounts = mutableMapOf<String, Int>()
    private val failureCounts = mutableMapOf<String, Int>()

    /**
     * 执行前登记一次调用，判断它是否已经把本轮变成死循环。
     *
     * `canonicalName` 必须是 [ToolRegistry.resolve] 出来的**注册名**，不能用模型给的名字：
     * 模型每轮换个拼写是线上常态（`get_currrent_time`、`Get_Current_Time`、`get_currenttime`），
     * 按原始名计数时 identical 与 perTool 两个计数器各算各的，这条刹车正好被它要防的行为绕过。
     */
    fun register(call: ToolCall, canonicalName: String): Verdict {
        val toolKey = canonicalName.lowercase()
        val fingerprint = toolKey + '|' + normalizeArguments(call.arguments)

        val identical = (identicalCounts[fingerprint] ?: 0) + 1
        identicalCounts[fingerprint] = identical

        val perTool = (perToolCounts[toolKey] ?: 0) + 1
        perToolCounts[toolKey] = perTool

        return when {
            identical > maxIdenticalCalls -> Verdict.Stop(
                "模型用相同参数重复调用 $canonicalName 已达 $identical 次，且没有产生新信息"
            )
            perTool > maxCallsPerTool -> Verdict.Stop(
                "$canonicalName 在本轮内被调用 $perTool 次，超过上限 $maxCallsPerTool"
            )
            else -> Verdict.Allow
        }
    }

    /**
     * 登记执行结果；连续失败超过上限时同样终止本轮。
     * `failed` 取自 [ToolResult.ok] —— 不能靠错误文案猜，也不能只看有没有抛异常。
     */
    fun recordResult(canonicalName: String, failed: Boolean): Verdict {
        val key = canonicalName.lowercase()
        if (!failed) {
            // 这里是"连续失败"：中间成功一次就重新计数，不是整个回合的累计
            failureCounts.remove(key)
            return Verdict.Allow
        }
        val failures = (failureCounts[key] ?: 0) + 1
        failureCounts[key] = failures
        return if (failures > maxFailuresPerTool) {
            Verdict.Stop("$canonicalName 连续执行失败 $failures 次，已放弃本轮")
        } else {
            Verdict.Allow
        }
    }

    /** 参数里的空白与字段顺序不影响"是不是同一个调用"的判断 */
    private fun normalizeArguments(raw: String): String {
        val element = runCatching { Json.parseToJsonElement(raw) }.getOrNull()
            ?: return raw.trim().replace(WHITESPACE, "")
        return canonical(element).toString()
    }

    /** 递归按 key 排序；数组顺序保留（对调用参数来说顺序有意义） */
    private fun canonical(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.entries.sortedBy { it.key }
                .associate { (key, value) -> key to canonical(value) }
        )
        is JsonArray -> JsonArray(element.map(::canonical))
        else -> element
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
