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

    /** 精确匹配；要把模型给出的名称变成可执行工具，请用 [resolve] */
    fun find(name: String): AgentTool? = tools[name]

    /**
     * 把模型给出的工具名解析成已注册工具。
     *
     * 为什么需要它：模型会自己"造"工具名 —— 线上观测到的 `get_currrent_time`（三个 r）就是实例。
     * 只做精确查找会返回 null，于是每轮都得到"未注册的工具"，模型原样再调一次，直到轮次上限
     * 弹窗报错。这里的原则是：能确定意图就放行，确定不了才让上层终止本轮。
     *
     * 匹配顺序（从严到松）：
     * 1. 原样精确匹配；
     * 2. 忽略大小写；
     * 3. 折叠连续重复字母后相同（`currrent` → `curent`，与 `current` 归一后一致）；
     * 4. 编辑距离 ≤ 1 且没有并列最优 —— 防止两个工具互相误配。
     */
    fun resolve(name: String): AgentTool? {
        val raw = name.trim()
        if (raw.isEmpty()) return null
        tools[raw]?.let { return it }

        val lower = raw.lowercase()
        tools[lower]?.let { return it }

        val collapsed = collapseRepeatedLetters(lower)
        tools.entries
            .firstOrNull { collapseRepeatedLetters(it.key.lowercase()) == collapsed }
            ?.let { return it.value }

        val ranked = tools.entries
            .map { it.value to editDistance(lower, it.key.lowercase()) }
            .sortedBy { it.second }
        val best = ranked.firstOrNull() ?: return null
        val runnerUp = ranked.getOrNull(1)
        return best.first.takeIf {
            best.second <= 1 && (runnerUp == null || runnerUp.second > best.second)
        }
    }

    private fun collapseRepeatedLetters(text: String): String {
        val sb = StringBuilder(text.length)
        var previous: Char? = null
        text.forEach { ch ->
            if (ch != previous) sb.append(ch)
            previous = ch
        }
        return sb.toString()
    }

    /** 标准 Levenshtein 距离；工具只有个位数，朴素实现足够且无依赖 */
    private fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, substitution)
            }
            previous = current
        }
        return previous[b.length]
    }

    /** 与协议无关的工具清单，由各 ProtocolAdapter 转换为各自的 wire format */
    fun specs(): List<UnifiedTool> = all().map { tool ->
        UnifiedTool(
            name = tool.name,
            description = tool.description,
            schema = tool.parameters
        )
    }
}
