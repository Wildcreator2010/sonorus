package com.wildcreator.sonorus.domain.agent.tools

import com.wildcreator.sonorus.domain.agent.AgentTool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class TimeTool : AgentTool {
    override val name = "get_current_time"
    override val description = "获取设备本地的当前日期、时间与星期"
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { })
    }

    override suspend fun execute(argumentsJson: String): String {
        val now = ZonedDateTime.now()
        return now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss EEEE (z)"))
    }
}
