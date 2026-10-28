package com.wildcreator.sonorus.model

import com.wildcreator.sonorus.domain.model.TextAttachment
import com.wildcreator.sonorus.domain.model.TextAttachmentException
import com.wildcreator.sonorus.domain.model.TextAttachmentParser
import com.wildcreator.sonorus.domain.model.decodeTextAttachments
import com.wildcreator.sonorus.domain.model.encodeTextAttachments
import com.wildcreator.sonorus.domain.model.withTextAttachments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TextAttachmentTest {
    @Test
    fun UTF8文本文件解析并规范文件名() {
        val attachment = TextAttachmentParser.parse(" 说明\n.txt ", "text/plain", "第一行\n第二行".toByteArray())
        assertEquals("说明.txt", attachment.name)
        assertEquals("text/plain", attachment.mimeType)
        assertEquals("第一行\n第二行", attachment.text)
    }

    @Test
    fun 带BOM的UTF16文本可读取() {
        val body = "中文".toByteArray(Charsets.UTF_16LE)
        val attachment = TextAttachmentParser.parse("note.txt", "text/plain", byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + body)
        assertEquals("中文", attachment.text)
    }

    @Test
    fun 二进制内容会被拒绝() {
        try {
            TextAttachmentParser.parse("fake.txt", "text/plain", byteArrayOf(1, 0, 2, 0, 3))
            fail("含 NUL 的二进制文件不能作为文本提交")
        } catch (e: TextAttachmentException) {
            assertTrue(e.message!!.contains("文本"))
        }
    }

    @Test
    fun 单文件超过上限会被拒绝() {
        try {
            TextAttachmentParser.parse("huge.txt", "text/plain", ByteArray(TextAttachmentParser.MAX_FILE_BYTES + 1) { 'a'.code.toByte() })
            fail("超大文件不应读入会话")
        } catch (e: TextAttachmentException) {
            assertTrue(e.message!!.contains("过大"))
        }
    }

    @Test
    fun 不支持的文件类型会被拒绝() {
        try {
            TextAttachmentParser.parse("archive.zip", "application/zip", "not zip".toByteArray())
            fail("压缩包不属于文本附件")
        } catch (e: TextAttachmentException) {
            assertTrue(e.message!!.contains("类型"))
        }
    }

    @Test
    fun 附件序列化往返保留内容() {
        val attachments = listOf(TextAttachment("a.md", "text/markdown", "# 标题"))
        assertEquals(attachments, attachments.encodeTextAttachments().decodeTextAttachments())
    }

    @Test
    fun 发送给模型时正文与附件边界清楚() {
        val rendered = "请总结".withTextAttachments(listOf(TextAttachment("a.md", "text/markdown", "# 标题")))
        assertTrue(rendered.startsWith("请总结"))
        assertTrue(rendered.contains("附件 1：a.md"))
        assertTrue(rendered.contains("# 标题"))
        assertTrue(rendered.contains("附件结束"))
    }

    @Test
    fun 无附件时不改变原消息() {
        assertEquals("原文", "原文".withTextAttachments(emptyList()))
    }
}
