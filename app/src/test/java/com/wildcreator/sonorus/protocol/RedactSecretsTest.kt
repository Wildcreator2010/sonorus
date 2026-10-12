package com.wildcreator.sonorus.protocol

import com.wildcreator.sonorus.data.remote.redactSecrets
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 错误文本密钥擦除的单测：
 * 上游网关的错误回显绝不允许把凭据痕迹带进 UI 或 request_stats 库。
 */
class RedactSecretsTest {

    @Test
    fun `Bearer 头回显被擦除`() {
        val out = redactSecrets("""invalid apikey in Authorization: Bearer sk-abcDEF123456789._-/=""")
        assertFalse("不应残留 token 主体", out.contains("abcDEF123456789"))
        assertTrue(out.contains("Bearer [redacted]"))
    }

    @Test
    fun `JSON 形式 api_key 字段值被擦除`() {
        val out = redactSecrets("""{"error":"upstream echoed","api_key":"supersecretvalue123"}""")
        assertFalse(out.contains("supersecretvalue123"))
        assertTrue("字段名保留，仅擦除值", out.contains("api_key") && out.contains("[redacted]"))
    }

    @Test
    fun `kv 形式 authorization 被擦除`() {
        val out = redactSecrets("authorization=ABCDEFGH12345678 failed")
        assertFalse(out.contains("ABCDEFGH12345678"))
    }

    @Test
    fun `sk 前缀令牌被擦除`() {
        val out = redactSecrets("rejected key sk-proj-AbCdEf1234567890XYZ")
        assertFalse(out.contains("AbCdEf1234567890"))
        assertTrue(out.contains("sk-[redacted]"))
    }

    @Test
    fun `普通错误文本不受影响`() {
        val plain = """{"error":"bad key"}"""
        assertTrue(redactSecrets(plain) == plain)
    }

    @Test
    fun `擦除是幂等的`() {
        val once = redactSecrets("Bearer TopSecretToken123 leaked")
        assertTrue(redactSecrets(once) == once)
    }
}
