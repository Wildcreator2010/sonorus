package com.wildcreator.sonorus.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * 备份文件的明文结构（加密前的 JSON）。
 *
 * 设计原则：**字段与 Room 实体一一对应，且原样保存 JSON 列**。
 * `imagesJson` / `toolCallsJson` / `versionsJson` 存的是数据库里的原始字符串，不先解成域对象再序列化 ——
 * 域模型 `ChatMessage` 刻意不带 `toolCallId` 与 `toolCallsJson`，走域对象往返会丢掉工具调用链，
 * 导回来的会话将无法继续问模型（Anthropic 会因孤立的 tool_result 直接 400）。
 *
 * 与「导出会话」(`ConversationExporter`) 的分工不同，不要混用：
 * 那个是给人在电脑/聊天软件里看的有损文本（图片只留字节数），这个是给机器恢复用的无损容器。
 */
@Serializable
data class BackupFile(
    val schema: String = SCHEMA,
    val createdAt: Long = 0L,
    /** 导出时的 versionName，仅用于排查"哪个版本的 App 写的" */
    val appVersion: String = "",
    val providers: List<BackupProvider> = emptyList(),
    val conversations: List<BackupConversation> = emptyList(),
    val settings: BackupSettings? = null
) {
    companion object {
        const val SCHEMA = "sonorus.backup/v1"

        /** 会话身份指纹：恢复时用它判断"这台机器上已经有了" */
        fun fingerprint(title: String, createdAt: Long, messageCount: Int): String =
            "${createdAt}|${title.trim()}|$messageCount"
    }
}

/**
 * 提供商条目。
 * [apiKey] 可以为空：那表示本机当时就没有该提供商的密钥，恢复时不该覆盖已有密钥。
 * 会话用 (providerName, providerBaseUrl) 而不是数字 id 关联提供商 ——
 * id 是本机数据库自增的，换一台机器就毫无意义。
 */
@Serializable
data class BackupProvider(
    val name: String,
    val baseUrl: String,
    val protocol: String,
    val enabled: Boolean = true,
    val apiKey: String? = null
)

@Serializable
data class BackupConversation(
    val title: String,
    val titleAuto: Boolean = true,
    val providerName: String,
    val providerBaseUrl: String,
    val modelId: String,
    val systemPrompt: String,
    val toolsEnabled: Boolean = true,
    /** 会话级思考等级覆盖；null = 跟随全局 */
    val thinkingLevel: String? = null,
    val contextSummary: String? = null,
    val summaryThroughMessageId: Long = 0,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<BackupMessage> = emptyList()
) {
    val fingerprint: String
        get() = BackupFile.fingerprint(title, createdAt, messages.size)
}

/** 与 messages 表的列一一对应；JSON 列保持原始字符串 */
@Serializable
data class BackupMessage(
    val role: String,
    val content: String,
    val imagesJson: String? = null,
    val toolCallsJson: String? = null,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val versionsJson: String? = null,
    val activeVersion: Int = 0,
    val createdAt: Long
)

/**
 * 全局偏好。
 * 不含「默认提供商 + 模型」：那是本机数字 id，跨机器无意义，恢复后由用户重新点一次即可。
 * 也不含统计起始时间与更新提示 —— 属于本机使用痕迹，不是内容。
 */
@Serializable
data class BackupSettings(
    val contextLimit: Int = 0,
    val defaultSystemPrompt: String = "",
    val thinkingLevel: String = "OFF",
    val themeMode: String = "SYSTEM",
    val fontScale: String = "MEDIUM"
)

object BackupSchema {

    /**
     * 备份专用序列化配置。与生产 Json 分开：
     * 备份文件必须长期可读，不能因为生产端调整 encodeDefaults/explicitNulls 而改变格式。
     */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    /** 单条消息内容上限保护：整个备份走内存，超限就该提示而不是 OOM */
    private const val MAX_FILE_CHARS = 200_000_000

    fun encode(file: BackupFile): String = json.encodeToString(BackupFile.serializer(), file)

    fun decode(text: String): BackupFile {
        if (text.length > MAX_FILE_CHARS) {
            throw BackupCipher.MalformedBackup("备份内容过大（${text.length} 字符），已拒绝解析")
        }
        // 解密后的内容属于外部输入：结构坏了要收敛成同一种"非法备份"异常，
        // UI 只需处理 MalformedBackup / DecryptionFailed 两类，不必认识 kotlinx 的具体异常族
        val parsed = try {
            json.decodeFromString(BackupFile.serializer(), text)
        } catch (e: SerializationException) {
            throw BackupCipher.MalformedBackup("备份内容不是合法的 ${BackupFile.SCHEMA} 结构")
        }
        if (parsed.schema != BackupFile.SCHEMA) {
            throw BackupCipher.MalformedBackup("备份格式为 ${parsed.schema}，本应用只认 ${BackupFile.SCHEMA}")
        }
        return parsed
    }
}
