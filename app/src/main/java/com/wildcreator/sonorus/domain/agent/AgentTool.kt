package com.wildcreator.sonorus.domain.agent

import kotlinx.serialization.json.JsonObject

/**
 * Agent 本地工具抽象。
 * 新工具：实现本接口，并在 ToolRegistry 中注册即可被模型按 function calling 调用。
 */
interface AgentTool {
    /** 工具名，需与暴露给模型的 function name 一致，建议 snake_case */
    val name: String

    /** 给模型看的自然语言描述 */
    val description: String

    /** 参数 JSON Schema 对象 */
    val parameters: JsonObject

    /**
     * 执行工具，argumentsJson 为模型输出的 arguments 字符串。
     *
     * 失败要用 [ToolResult.ok] = false 表达，不要靠抛异常、也不要只返回一句错误文案：
     * 执行器凭这个标志统计"同一工具反复失败"，靠文案猜失败等于这条刹车不存在。
     */
    suspend fun execute(argumentsJson: String): ToolResult
}
