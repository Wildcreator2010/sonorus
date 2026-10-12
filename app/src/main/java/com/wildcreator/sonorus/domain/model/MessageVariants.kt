package com.wildcreator.sonorus.domain.model

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 回答多版本的编解码与批注修订指令拼装。
 *
 * 约束：
 * - `MessageEntity.content` 必须永远等于 `versions[activeVersion].content`，
 *   这样历史重建、导出、UI 这三条既有读路径都不用理解版本；
 *   写侧只允许经由 [entry] 生成 entity 字段，避免两处失步；
 * - 单版本不落 versionsJson（返回 null），旧数据零迁移即可读；
 * - 版本数有上限，防止反复「按批注修改」把一行撑到几百 KB。
 */
object MessageVariants {

    /** 每条回答最多保留的版本数；超出后丢弃最旧的一版 */
    const val MAX_VERSIONS = 10

    private val codec = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    /**
     * 解码存下来的版本列表；为空或非法时用 fallback 兜底成单版本。
     *
     * **绝不能在这里过滤空正文版本**：库里存的 `activeVersion` 是按写入时的位置来的，
     * 解码时删掉任何一版都会让后面所有版本前移，于是 `content` 与 `versions[activeVersion]`
     * 指向不同的版本 —— 界面显示 A 版的正文、却带上 B 版的推理和批注。
     * 空正文本身是合法的（模型只出推理、或只发起工具调用）。
     */
    fun decode(json: String?, fallback: AnswerVersion): List<AnswerVersion> {
        if (json.isNullOrBlank()) return listOf(fallback)
        val list = runCatching {
            codec.decodeFromString(ListSerializer(AnswerVersion.serializer()), json)
        }.getOrNull()
        return if (list.isNullOrEmpty()) listOf(fallback) else list
    }

    /**
     * 只有一个"无推理、无批注"的版本时才省略 JSON —— 与旧数据形态一致，省空间；
     * 一旦带了推理或批注就必须落库，否则解码时该信息会丢。
     */
    fun encode(versions: List<AnswerVersion>): String? {
        if (versions.size == 1) {
            val only = versions.first()
            if (only.reasoning == null && only.note == null) return null
        }
        return runCatching {
            codec.encodeToString(ListSerializer(AnswerVersion.serializer()), versions)
        }.getOrNull()
    }

    /** 追加一版并让它成为当前版本，返回裁剪后的完整列表 */
    fun append(existing: List<AnswerVersion>, next: AnswerVersion): List<AnswerVersion> {
        val merged = existing + next
        return if (merged.size > MAX_VERSIONS) merged.takeLast(MAX_VERSIONS) else merged
    }

    /** 一次写入 content / versionsJson / activeVersion，三者的唯一合法构造点 */
    fun entry(versions: List<AnswerVersion>, index: Int = versions.lastIndex): Triple<String, String?, Int> {
        require(versions.isNotEmpty()) { "版本列表不能为空" }
        val safeIndex = index.coerceIn(0, versions.lastIndex)
        return Triple(versions[safeIndex].content, encode(versions), safeIndex)
    }

    /**
     * 生成"按批注重写"的瞬时用户指令。
     * 只用于当次请求，不落库 —— 批注本身存在新版本的 note 里，
     * 落库会让模型在后续轮次里反复看到同一条批注。
     */
    fun revisionInstruction(quote: String?, note: String): String = buildString {
        append("请根据下面的批注修改你上一条回答，")
        append("直接输出修改后的完整回答，不要说明你改了什么，也不要只给片段。\n")
        if (!quote.isNullOrBlank()) {
            append("【批注针对的原文片段】\n").append(quote.trim()).append('\n')
        }
        append("【批注】\n").append(note.trim())
    }
}
