package com.wildcreator.sonorus.domain.agent.tools

import com.wildcreator.sonorus.domain.agent.AgentTool
import com.wildcreator.sonorus.domain.agent.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TimeTool : AgentTool {
    override val name = "get_current_time"
    override val description = "获取设备本地的当前日期、时间与星期"
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { })
    }

    override suspend fun execute(argumentsJson: String): ToolResult {
        // java.time 要 API 26，本工程最低 API 23，只能用 SimpleDateFormat
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss EEEE (z)", Locale.getDefault()).format(Date())
        return ToolResult(true, now)
    }
}
