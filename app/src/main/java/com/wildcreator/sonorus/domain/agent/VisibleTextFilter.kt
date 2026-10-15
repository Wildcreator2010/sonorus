package com.wildcreator.sonorus.domain.agent

/**
 * 用户可见文本过滤器：剥离模型混在正文里的思维链标签块。
 *
 * 为什么必须是状态机而不是一条正则：
 * SSE 按任意边界切分，`<thou` 与 `ght>` 可能落在两个 chunk 里。对单个 chunk 做正则替换匹配不到
 * 完整标签，于是残缺标签被当成正文吐给用户；等后续 chunk 到达时，内容早已显示出去了。
 * 这里跨 chunk 保留可能构成标签的尾巴，只在确认不是标签时才放行。
 *
 * 为什么必须有代码兜底：
 * 三家协议都有原生 reasoning 字段（见 [StreamDelta.reasoning]），但自建网关经常把推理文本塞进
 * content。Prompt 只能降低概率；"哪些字符可以展示给用户"必须由代码决定。
 *
 * 识别不到的形态（例如没有标签、纯自然语言的推理）不在这里处理 —— 靠 [SystemPromptGuard]
 * 约束模型，而不是靠猜。
 */
class VisibleTextFilter {

    private val buffer = StringBuilder()
    private var insideHidden = false

    /** 是否至少剥离过一个思维链块（供调用方判断"空答复"是模型真的没说话，还是全被过滤掉了） */
    var strippedAnything: Boolean = false
        private set

    /**
     * 喂入一个流式分片，返回本次可安全展示的文本（可能为空字符串）。
     * 返回空不代表丢弃 —— 可能是被隐藏，也可能只是缓冲起来等后续分片补全标签。
     */
    fun accept(chunk: String): String {
        if (chunk.isEmpty()) return ""
        buffer.append(chunk)
        val visible = StringBuilder()

        while (true) {
            if (!insideHidden) {
                val open = findTag(buffer, OPEN_TAGS)
                if (open == null) {
                    // 只保留可能构成标签前缀的尾巴，其余立即放行
                    val keep = partialTagSuffixLength(buffer, OPEN_TAGS)
                    val flush = buffer.length - keep
                    if (flush > 0) {
                        visible.append(buffer, 0, flush)
                        buffer.delete(0, flush)
                    }
                    break
                }
                visible.append(buffer, 0, open.first)
                buffer.delete(0, open.first + open.second.length)
                insideHidden = true
                strippedAnything = true
            } else {
                val close = findTag(buffer, CLOSE_TAGS)
                if (close == null) {
                    // 隐藏区内的内容一律丢弃，只留可能的闭合标签前缀，避免缓冲无限增长
                    val keep = partialTagSuffixLength(buffer, CLOSE_TAGS)
                    buffer.delete(0, buffer.length - keep)
                    break
                }
                buffer.delete(0, close.first + close.second.length)
                insideHidden = false
            }
        }
        return visible.toString()
    }

    /**
     * 流结束时调用，吐出仍可展示的尾部缓冲。
     * 隐藏区未闭合时返回空 —— 宁可少显示，也不能把没说完的思维链倒给用户。
     * 缓冲里剩下的是标签前缀（断流正好切在 `<thought` 中间）时同样返回空：
     * 那半截标签交出去就是用户看到的乱码尾巴。
     */
    fun finish(): String {
        val tail = buffer.toString()
        buffer.setLength(0)
        if (insideHidden) return ""
        return if (isTagPrefix(tail)) "" else tail
    }

    private fun isTagPrefix(text: String): Boolean =
        text.isNotEmpty() && ALL_TAGS.any {
            it.length > text.length && it.startsWith(text, ignoreCase = true)
        }

    /** 命中标签：first = 起始下标，second = 标签字面量 */
    private fun findTag(text: StringBuilder, tags: List<String>): Pair<Int, String>? {
        var bestIndex = -1
        var bestTag: String? = null
        tags.forEach { tag ->
            val index = indexOfIgnoreCase(text, tag)
            if (index >= 0 && (bestIndex < 0 || index < bestIndex)) {
                bestIndex = index
                bestTag = tag
            }
        }
        val tag = bestTag ?: return null
        return bestIndex to tag
    }

    private fun indexOfIgnoreCase(text: StringBuilder, needle: String): Int {
        if (needle.isEmpty() || text.length < needle.length) return -1
        val last = text.length - needle.length
        for (start in 0..last) {
            var matched = true
            for (offset in needle.indices) {
                if (text[start + offset].lowercaseChar() != needle[offset].lowercaseChar()) {
                    matched = false
                    break
                }
            }
            if (matched) return start
        }
        return -1
    }

    /** 缓冲区末尾有多少字符可能是某个标签的前缀（不含完整标签本身） */
    private fun partialTagSuffixLength(text: StringBuilder, tags: List<String>): Int {
        if (text.isEmpty()) return 0
        val maxLength = (tags.maxOf { it.length } - 1).coerceAtLeast(0)
        val upperBound = minOf(maxLength, text.length)
        for (length in upperBound downTo 1) {
            val suffix = text.substring(text.length - length)
            if (tags.any { it.length > length && it.startsWith(suffix, ignoreCase = true) }) {
                return length
            }
        }
        return 0
    }

    private companion object {
        /** 只收录真实模型会输出的标签名，避免把正文里的 < 误判成标签 */
        val OPEN_TAGS = listOf("<thought>", "<thinking>", "<analysis>", "<reasoning>")
        val CLOSE_TAGS = listOf("</thought>", "</thinking>", "</analysis>", "</reasoning>")
        val ALL_TAGS = OPEN_TAGS + CLOSE_TAGS
    }
}
