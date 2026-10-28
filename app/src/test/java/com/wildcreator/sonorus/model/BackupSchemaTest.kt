package com.wildcreator.sonorus.model

import com.wildcreator.sonorus.domain.model.BackupCipher
import com.wildcreator.sonorus.domain.model.BackupConversation
import com.wildcreator.sonorus.domain.model.BackupFile
import com.wildcreator.sonorus.domain.model.BackupMessage
import com.wildcreator.sonorus.domain.model.BackupProvider
import com.wildcreator.sonorus.domain.model.BackupSchema
import com.wildcreator.sonorus.domain.model.BackupSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * 备份 JSON 结构的往返保真。
 *
 * 重点盯"容易丢的列"：imagesJson / toolCallsJson / toolCallId / versionsJson / activeVersion。
 * 域模型 ChatMessage 刻意不带 toolCallId 与 toolCallsJson，所以备份必须直接保存实体原始字符串 ——
 * 一旦这里改成走域对象，恢复出来的会话送到 Anthropic 就会因孤立 tool_result 被 400 拒掉。
 */
class BackupSchemaTest {

    private val sample = BackupFile(
        createdAt = 1_700_000_000_000L,
        appVersion = "0.1.1",
        providers = listOf(
            BackupProvider(
                name = "我的网关",
                baseUrl = "https://gateway.example.com/v1",
                protocol = "OPENAI",
                enabled = true,
                apiKey = "sk-abc-123"
            ),
            // 当时本机就没有密钥的提供商：apiKey 为 null，恢复时不该覆盖已有密钥
            BackupProvider("裸号", "https://bare.example.com/v1", "ANTHROPIC", enabled = false, apiKey = null)
        ),
        conversations = listOf(
            BackupConversation(
                title = "工具调用与图片",
                titleAuto = false,
                providerName = "我的网关",
                providerBaseUrl = "https://gateway.example.com/v1",
                modelId = "gpt-4o",
                systemPrompt = "你是助手。\n第二行",
                toolsEnabled = true,
                thinkingLevel = "MEDIUM",
                createdAt = 1_600_000_000_000L,
                updatedAt = 1_600_000_999_999L,
                messages = listOf(
                    BackupMessage(
                        role = "user",
                        content = "看看这张图",
                        imagesJson = """[{"mimeType":"image/png","base64":"AAAA"}]""",
                        createdAt = 1_600_000_000_001L
                    ),
                    BackupMessage(
                        role = "assistant",
                        content = "",
                        toolCallsJson = """[{"id":"call_1","name":"calculator","arguments":"1+1"}]""",
                        createdAt = 1_600_000_000_002L
                    ),
                    BackupMessage(
                        role = "tool",
                        content = "2",
                        toolCallId = "call_1",
                        toolName = "calculator",
                        createdAt = 1_600_000_000_003L
                    ),
                    BackupMessage(
                        role = "assistant",
                        content = "答案是 2",
                        versionsJson = """[{"content":"初版","createdAt":1},{"content":"答案是 2","note":"再算一遍","createdAt":2}]""",
                        activeVersion = 1,
                        createdAt = 1_600_000_000_004L
                    )
                )
            )
        ),
        settings = BackupSettings(
            contextLimit = 20,
            defaultSystemPrompt = "默认设定",
            thinkingLevel = "LOW",
            themeMode = "DARK",
            fontScale = "LARGE"
        )
    )

    @Test
    fun 编解码往返逐字段一致() {
        val back = BackupSchema.decode(BackupSchema.encode(sample))

        assertEquals(sample.schema, back.schema)
        assertEquals(sample.createdAt, back.createdAt)
        assertEquals(sample.appVersion, back.appVersion)
        assertEquals(sample.providers, back.providers)
        assertEquals(sample.settings, back.settings)

        val conv = back.conversations.single()
        assertEquals(sample.conversations.single(), conv)
        assertEquals(4, conv.messages.size)
    }

    @Test
    fun 工具调用链的每一环都保住了() {
        val messages = BackupSchema.decode(BackupSchema.encode(sample)).conversations.single().messages

        assertEquals("""[{"id":"call_1","name":"calculator","arguments":"1+1"}]""", messages[1].toolCallsJson)
        assertEquals("call_1", messages[2].toolCallId)
        assertEquals("calculator", messages[2].toolName)
        assertEquals("2", messages[2].content)
    }

    @Test
    fun 图片原始JSON与多版本原文一字不差() {
        val messages = BackupSchema.decode(BackupSchema.encode(sample)).conversations.single().messages

        assertEquals("""[{"mimeType":"image/png","base64":"AAAA"}]""", messages[0].imagesJson)
        assertEquals(
            """[{"content":"初版","createdAt":1},{"content":"答案是 2","note":"再算一遍","createdAt":2}]""",
            messages[3].versionsJson
        )
        assertEquals(1, messages[3].activeVersion)
        assertNull("单版本消息不该凭空多出 versionsJson", messages[0].versionsJson)
    }

    @Test
    fun 口令加密后再解开仍然是同一份结构() {
        val container = BackupCipher.encrypt(BackupSchema.encode(sample).toByteArray(Charsets.UTF_8), "pw")
        val text = BackupCipher.decrypt(container, "pw").toString(Charsets.UTF_8)

        assertEquals(sample.conversations.single().messages[1].toolCallsJson,
            BackupSchema.decode(text).conversations.single().messages[1].toolCallsJson)
    }

    @Test
    fun 格式标识不符就拒绝() {
        val wrongSchema = BackupSchema.encode(sample).replace(BackupFile.SCHEMA, "wildadaptor.backup/v1")

        try {
            BackupSchema.decode(wrongSchema)
            fail("旧标识的备份应当被拒收，而不是按 v1 硬解")
        } catch (e: BackupCipher.MalformedBackup) {
            assertEquals(true, e.message?.contains("wildadaptor.backup/v1"))
        }
    }

    @Test
    fun 未来新增字段不影响当前读取() {
        // ignoreUnknownKeys 的意义：新版本 App 写出的备份，老版本至少能读现有字段
        val future = BackupSchema.encode(sample)
            .replaceFirst("{", """{"brandNewField":[1,2,3],""")

        val back = BackupSchema.decode(future)

        assertEquals(sample.providers, back.providers)
    }

    @Test
    fun 文本附件备份往返不丢失() {
        val raw = """{"schema":"sonorus.backup/v1","conversations":[{"title":"文件","providerName":"测试","providerBaseUrl":"https://example.com/v1","modelId":"model","systemPrompt":"","createdAt":1,"updatedAt":1,"messages":[{"role":"user","content":"读取附件","filesJson":"[{\"name\":\"note.txt\",\"text\":\"附件内容\"}]","createdAt":1}]}]}"""
        val encoded = BackupSchema.encode(BackupSchema.decode(raw))
        val root = kotlinx.serialization.json.Json.parseToJsonElement(encoded) as kotlinx.serialization.json.JsonObject
        val conversations = root["conversations"] as kotlinx.serialization.json.JsonArray
        val messages = (conversations[0] as kotlinx.serialization.json.JsonObject)["messages"] as kotlinx.serialization.json.JsonArray
        val files = (messages[0] as kotlinx.serialization.json.JsonObject)["filesJson"] as? kotlinx.serialization.json.JsonPrimitive
        assertEquals("""[{"name":"note.txt","text":"附件内容"}]""", files?.content)
    }

    @Test
    fun 非JSON内容报非法容器而不是解析崩溃() {
        try {
            BackupSchema.decode("这不是 json")
            fail("应当抛出可读的非法备份错误")
        } catch (e: BackupCipher.MalformedBackup) {
            // 预期路径
        } catch (e: Exception) {
            fail("期望 MalformedBackup，实际 ${e.javaClass.name}: ${e.message}")
        }
    }

    @Test
    fun 指纹只认创建时间标题与消息数() {
        val a = sample.conversations.single()

        // 标题两端空格不算差异
        assertEquals(a.fingerprint, BackupFile.fingerprint("  ${a.title}  ", a.createdAt, a.messages.size))
        // 消息数变了 = 不同的会话（恢复后又被追加过内容）
        if (a.fingerprint == BackupFile.fingerprint(a.title, a.createdAt, a.messages.size + 1)) {
            fail("消息数不同却被算成同一个指纹")
        }
        // 创建时间变了 = 不同的会话
        if (a.fingerprint == BackupFile.fingerprint(a.title, a.createdAt + 1, a.messages.size)) {
            fail("创建时间不同却被算成同一个指纹")
        }
    }
}
