package com.wildcreator.sonorus.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

@Serializable
data class TextAttachment(
    val name: String,
    val mimeType: String,
    val text: String
)

class TextAttachmentException(message: String) : IllegalArgumentException(message)

object TextAttachmentParser {
    const val MAX_FILE_BYTES = 256 * 1024
    const val MAX_FILE_COUNT = 4
    const val MAX_TOTAL_CHARS = 512 * 1024

    private val supportedExtensions = setOf(
        "txt", "md", "markdown", "csv", "json", "jsonl", "log", "xml", "yaml", "yml", "toml",
        "ini", "cfg", "conf", "properties", "java", "kt", "kts", "js", "ts", "jsx", "tsx", "py",
        "go", "rs", "c", "h", "cpp", "hpp", "cs", "swift", "sh", "bash", "zsh", "bat", "cmd",
        "ps1", "sql", "html", "htm", "css", "scss", "less"
    )
    private val supportedMimeTypes = setOf(
        "application/json", "application/ld+json", "application/xml", "application/javascript",
        "application/x-javascript", "application/x-yaml", "application/toml"
    )

    fun parse(rawName: String?, rawMimeType: String?, bytes: ByteArray): TextAttachment {
        val name = normalizeName(rawName)
        val mimeType = rawMimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension !in supportedExtensions && !mimeType.startsWith("text/") && mimeType !in supportedMimeTypes) {
            throw TextAttachmentException("不支持该文件类型：$name")
        }
        if (bytes.isEmpty()) throw TextAttachmentException("文件内容为空：$name")
        if (bytes.size > MAX_FILE_BYTES) {
            throw TextAttachmentException("文件过大：单个文本附件不能超过 ${MAX_FILE_BYTES / 1024} KB")
        }
        val text = decode(bytes).removePrefix("\uFEFF")
        if (text.isBlank()) throw TextAttachmentException("文件没有可读取的文本：$name")
        val controls = text.count { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }
        if (controls > maxOf(1, text.length / 50)) {
            throw TextAttachmentException("文件不是可读取的文本：$name")
        }
        return TextAttachment(name, mimeType.ifBlank { "text/plain" }, text)
    }

    private fun normalizeName(raw: String?): String = raw.orEmpty()
        .replace(Regex("[\\r\\n\\t\\u0000-\\u001F]"), "")
        .trim()
        .replace(Regex("\\s+"), " ")
        .take(120)
        .ifBlank { "附件.txt" }

    private fun decode(bytes: ByteArray): String = when {
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
            decodeStrict(bytes.copyOfRange(3, bytes.size), StandardCharsets.UTF_8)
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
            decodeStrict(bytes.copyOfRange(2, bytes.size), StandardCharsets.UTF_16LE)
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
            decodeStrict(bytes.copyOfRange(2, bytes.size), StandardCharsets.UTF_16BE)
        bytes.any { it == 0.toByte() } -> throw TextAttachmentException("文件不是可读取的文本")
        else -> runCatching { decodeStrict(bytes, StandardCharsets.UTF_8) }
            .getOrElse {
                runCatching { decodeStrict(bytes, charset("GB18030")) }
                    .getOrElse { throw TextAttachmentException("文件编码不受支持，请转换为 UTF-8") }
            }
    }

    private fun decodeStrict(bytes: ByteArray, charset: java.nio.charset.Charset): String = charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
}

private val textAttachmentJson = Json { ignoreUnknownKeys = true }

fun List<TextAttachment>.encodeTextAttachments(): String? = takeIf { it.isNotEmpty() }?.let {
    textAttachmentJson.encodeToString(ListSerializer(TextAttachment.serializer()), it)
}

fun String?.decodeTextAttachments(): List<TextAttachment> = if (isNullOrBlank()) emptyList() else runCatching {
    textAttachmentJson.decodeFromString(ListSerializer(TextAttachment.serializer()), this)
}.getOrDefault(emptyList())

fun String.withTextAttachments(files: List<TextAttachment>): String {
    if (files.isEmpty()) return this
    return buildString {
        if (this@withTextAttachments.isNotBlank()) append(this@withTextAttachments.trim())
        else append("请阅读并处理以下附件。")
        append("\n\n以下是用户选择的文本附件内容：")
        files.forEachIndexed { index, file ->
            append("\n\n----- 附件 ").append(index + 1).append('：').append(file.name)
                .append("（").append(file.mimeType).append("） -----\n")
            append(file.text)
            append("\n----- 附件结束 -----")
        }
    }
}
