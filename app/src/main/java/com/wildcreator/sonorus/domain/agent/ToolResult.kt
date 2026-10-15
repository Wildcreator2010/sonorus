package com.wildcreator.sonorus.domain.agent

/**
 * 工具执行结果。
 *
 * [ok] 是给 [ToolCallGuard] 看的：它决定这次调用算不算一次失败。
 * [text] 是给模型看的：无论成败都要原样回填进对话历史，否则 tool_calls 缺少配对结果。
 */
data class ToolResult(val ok: Boolean, val text: String)
