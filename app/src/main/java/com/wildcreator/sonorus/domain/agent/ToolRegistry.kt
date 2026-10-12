package com.wildcreator.sonorus.domain.agent

import com.wildcreator.sonorus.domain.agent.tools.CalculatorTool
import com.wildcreator.sonorus.domain.agent.tools.TimeTool
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ToolRegistry @Inject constructor() {

    private val tools: Map<String, AgentTool> = listOf(
        TimeTool(),
        CalculatorTool()
    ).associateBy { it.name }

    fun all(): List<AgentTool> = tools.values.toList()

    fun find(name: String): AgentTool? = tools[name]

    /** 与协议无关的工具清单，由各 ProtocolAdapter 转换为各自的 wire format */
    fun specs(): List<UnifiedTool> = all().map { tool ->
        UnifiedTool(
            name = tool.name,
            description = tool.description,
            schema = tool.parameters
        )
    }
}
