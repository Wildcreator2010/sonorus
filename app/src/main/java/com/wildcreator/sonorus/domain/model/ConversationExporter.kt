package com.wildcreator.sonorus.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 会话导出器：把 Conversation + messages 序列化为 Markdown / JSON 文本。
 *
 * 设计约束：
 * - 纯函数、无 Android 依赖，方便 JVM 单测直接覆盖；
 * - 不携带任何 API Key、Provider baseUrl 等敏感元数据 ——
 *   导出文件是要走系统分享面板的，一旦混入密钥就是外泄；
 * - 图片以占位符表示（`![image](data:mime;base64,...)` 会让文本膨胀数 MB，
 *   同时很多聊天/邮件客户端会拒绝渲染），保留 mimeType 与字节长度即可。
 */
object ConversationExporter {

    /** 导出用 JSON 序列化器：与生产 Json 分离，避免 encodeDefaults 差异影响可读性 */
    private val exportJson = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun toMarkdown(conversation: Conversation, messages: List<ChatMessage>): String {
        val sb = StringBuilder()
        // 工具调用的 assistant 占位行没有正文字符，导出时跳过：它的信息由紧随其后的 tool 结果承载。
        // 不跳过就会在导出文件里留下一个空的"### 助手"小节。
        // 计数与正文用同一份过滤结果，否则"消息数"和下面的小节数会对不上。
        val rendered = messages.filterNot { it.role == ChatRole.ASSISTANT && it.content.isBlank() }
        sb.append("# ").append(escapeMdHeading(conversation.title.ifBlank { "未命名会话" })).append('\n')
        sb.append('\n')
        sb.append("- 模型：`").append(conversation.modelId).append("`\n")
        sb.append("- 创建：").append(formatEpoch(conversation.createdAt)).append('\n')
        sb.append("- 导出：").append(formatEpoch(System.currentTimeMillis())).append('\n')
        sb.append("- 消息数：").append(rendered.size).append('\n')
        if (conversation.systemPrompt.isNotBlank()) {
            sb.append('\n').append("## System Prompt\n\n")
            // 用围栏代码块保留原始格式；围栏本身出现在 prompt 里时用更长围栏规避
            val fence = pickFence(conversation.systemPrompt)
            sb.append(fence).append('\n')
            sb.append(conversation.systemPrompt).append('\n')
            sb.append(fence).append('\n')
        }
        sb.append('\n').append("## 对话\n")

        rendered.forEach { m ->
            sb.append('\n').append("### ").append(roleLabel(m.role, m.toolName)).append('\n')
            if (m.images.isNotEmpty()) {
                sb.append('\n')
                m.images.forEachIndexed { index, img ->
                    sb.append("> 🖼 图片 ").append(index + 1)
                        .append("（").append(img.mimeType)
                        .append("，base64 ").append(img.base64.length).append(" 字符）\n")
                }
            }
            if (m.files.isNotEmpty()) {
                sb.append('\n')
                m.files.forEachIndexed { index, file ->
                    sb.append("> 📄 附件 ").append(index + 1)
                        .append("（").append(file.name)
                        .append("，").append(file.text.length).append(" 字）\n")
                }
            }
            val reasoning = m.reasoning?.takeIf { it.isNotBlank() }
            if (reasoning != null) {
                sb.append('\n').append("🧠 思考过程\n\n")
                reasoning.trim().lines().forEach { sb.append("> ").append(it).append('\n') }
            }
            // 多版本时逐版输出（含触发修订的批注），单版本时保持原有扁平结构
            if (m.versions.size > 1) {
                m.versions.forEachIndexed { index, version ->
                    sb.append('\n').append("#### 版本 ").append(index + 1)
                        .append(if (index == m.activeVersion) "（当前）" else "").append('\n')
                    version.note?.takeIf { it.isNotBlank() }?.let {
                        sb.append('\n').append("> 📝 批注：")
                            .append(it.replace(Regex("\\s+"), " ").trim()).append('\n')
                    }
                    val body = version.content.trimEnd()
                    if (body.isNotEmpty()) sb.append('\n').append(body).append('\n')
                }
            } else {
                val content = m.content.trimEnd()
                if (content.isNotEmpty()) {
                    sb.append('\n').append(content).append('\n')
                }
            }
        }
        return sb.toString()
    }

    fun toJson(conversation: Conversation, messages: List<ChatMessage>): String {
        // 与 Markdown 共用同一条过滤规则：工具调用的 assistant 占位行没有正文，
        // 导出去只会多一条空消息，还会让两种格式的"消息数"对不上
        val rendered = messages.filterNot { it.role == ChatRole.ASSISTANT && it.content.isBlank() }
        val payload = ExportPayload(
            schema = "sonorus.conversation/v1",
            exportedAt = System.currentTimeMillis(),
            title = conversation.title,
            modelId = conversation.modelId,
            createdAt = conversation.createdAt,
            systemPrompt = conversation.systemPrompt,
            messages = rendered.map { m ->
                ExportMessage(
                    role = m.role.name.lowercase(),
                    content = m.content,
                    toolName = m.toolName,
                    reasoning = m.reasoning?.takeIf { it.isNotBlank() },
                    // 多版本才展开，单版本不重复写一遍正文
                    versions = if (m.versions.size > 1) {
                        m.versions.mapIndexed { index, v ->
                            ExportVersion(
                                index = index,
                                content = v.content,
                                note = v.note,
                                createdAt = v.createdAt
                            )
                        }
                    } else emptyList(),
                    // 图片仅保留元数据，避免 JSON 文件动辄数十 MB
                    images = m.images.map { ExportImage(mimeType = it.mimeType, bytes = it.base64.length) },
                    files = m.files.map { ExportFile(name = it.name, chars = it.text.length) }
                )
            }
        )
        return exportJson.encodeToString(ExportPayload.serializer(), payload)
    }

    /** 生成用于分享/保存的默认文件名（不含扩展名） */
    fun defaultFileName(conversation: Conversation): String {
        val safe = conversation.title.trim()
            .replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_")
            .take(40)
            .ifBlank { "conversation" }
        return "${safe}_${conversation.id}"
    }

    private fun roleLabel(role: ChatRole, toolName: String?): String = when (role) {
        ChatRole.USER -> "🧑 用户"
        ChatRole.ASSISTANT -> "🤖 助手"
        ChatRole.TOOL -> "🔧 工具 · ${toolName ?: "unknown"}"
        ChatRole.SYSTEM -> "⚙️ 系统"
    }

    /** Markdown 标题里不能出现裸换行；其余字符照原样保留以最小化改动 */
    private fun escapeMdHeading(text: String): String =
        text.replace(Regex("[\\r\\n]+"), " ").trim()

    /**
     * 选择足够长的代码围栏，避免 systemPrompt 内含 ``` 时提前闭合。
     * 至少三个反引号；若正文里出现 N 连反引号则用 N+1。
     */
    private fun pickFence(body: String): String {
        var longest = 0
        var current = 0
        for (ch in body) {
            if (ch == '`') {
                current++
                if (current > longest) longest = current
            } else current = 0
        }
        return "`".repeat(maxOf(3, longest + 1))
    }

    /** 极简时间戳格式化：避免为了导出引入 java.time 依赖（minSdk 26 也支持，但用 SimpleDateFormat 更轻） */
    private fun formatEpoch(epochMs: Long): String {
        if (epochMs <= 0L) return "-"
        return runCatching {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
            sdf.format(java.util.Date(epochMs))
        }.getOrDefault(epochMs.toString())
    }

    // ---------- JSON schema ----------

    @Serializable
    private data class ExportPayload(
        val schema: String,
        val exportedAt: Long,
        val title: String,
        val modelId: String,
        val createdAt: Long,
        val systemPrompt: String,
        val messages: List<ExportMessage>
    )

    @Serializable
    private data class ExportMessage(
        val role: String,
        val content: String,
        val toolName: String? = null,
        /** 当前版本的推理文本；无推理或不支持该协议时为 null（不写键，老读者不受影响） */
        val reasoning: String? = null,
        /** 多版本回答的全部版本；单版本恒为空数组 */
        val versions: List<ExportVersion> = emptyList(),
        val images: List<ExportImage> = emptyList(),
        val files: List<ExportFile> = emptyList()
    )

    @Serializable
    private data class ExportVersion(
        val index: Int,
        val content: String,
        /** 触发这次修订的批注 */
        val note: String? = null,
        val createdAt: Long
    )

    @Serializable
    private data class ExportImage(
        val mimeType: String,
        /** base64 字符串长度；不导出实际字节，防止文件体积爆炸 */
        val bytes: Int
    )

    @Serializable
    private data class ExportFile(
        val name: String,
        /** 文本字符数；正文不导出，与图片同样只留元数据 */
        val chars: Int
    )
}
