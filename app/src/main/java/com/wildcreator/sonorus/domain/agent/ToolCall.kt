package com.wildcreator.sonorus.domain.agent

import kotlinx.serialization.Serializable

/** 与协议无关的"工具调用"形态，用于 Agent 循环与 Room 持久化（JSON 字符串） */
@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String
)
