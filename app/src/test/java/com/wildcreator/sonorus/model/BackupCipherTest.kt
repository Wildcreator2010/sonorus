package com.wildcreator.sonorus.model

import com.wildcreator.sonorus.domain.model.BackupCipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 备份容器的加密/认证行为。
 *
 * 这里最要紧的不是"能加密"，而是**失败模式**：口令错、文件被改、文件根本不是本应用导出的，
 * 三种都必须抛异常，绝不能返回空内容或半截内容 —— 备份里含 API Key，静默失败等于让用户以为恢复成功了。
 */
class BackupCipherTest {

    private val plaintext = """{"schema":"sonorus.backup/v1","providers":[{"apiKey":"sk-test-123"}]}"""
        .toByteArray(Charsets.UTF_8)

    @Test
    fun 口令正确时往返内容一致() {
        val container = BackupCipher.encrypt(plaintext, "correct horse")

        val restored = BackupCipher.decrypt(container, "correct horse")

        assertArrayEqualsSafe(plaintext, restored)
    }

    @Test
    fun 口令错误必须抛异常而不是返回内容() {
        val container = BackupCipher.encrypt(plaintext, "right passphrase")

        try {
            val leaked = BackupCipher.decrypt(container, "wrong passphrase")
            fail("错误口令竟然解出了内容：${leaked.size} 字节")
        } catch (e: BackupCipher.DecryptionFailed) {
            assertTrue(e.message!!.contains("口令"))
        }
    }

    @Test
    fun 翻转密文任意一位都会被认证标签挡住() {
        val container = BackupCipher.encrypt(plaintext, "pw")
        // 正文起始位置 = magic + version + salt + iv
        val bodyStart = BackupCipher.MAGIC.length + 1 + 16 + 12

        for (offset in listOf(0, 5, bodyStart, container.size - 1)) {
            val tampered = container.copyOf().also { it[offset] = (it[offset].toInt() xor 0xFF).toByte() }
            try {
                BackupCipher.decrypt(tampered, "pw")
                fail("第 $offset 字节被篡改却仍解密成功")
            } catch (e: BackupCipher.DecryptionFailed) {
                // 预期：GCM 标签校验失败
            } catch (e: BackupCipher.MalformedBackup) {
                // 篡改落在头部时也可能被判成非法容器，同样属于拒绝
            }
        }
    }

    @Test
    fun 同样的明文两次导出得到不同容器() {
        val a = BackupCipher.encrypt(plaintext, "pw")
        val b = BackupCipher.encrypt(plaintext, "pw")

        // salt 与 iv 都是随机的：容器必须不同，否则文件可被比对
        assertNotEquals(b, a)
        assertArrayEqualsSafe(BackupCipher.decrypt(a, "pw"), BackupCipher.decrypt(b, "pw"))
    }

    @Test
    fun 非本应用导出的文件被识别为非法容器() {
        // 长度必须越过"头部 + 至少 1 字节正文"，否则会先被截断检查挡住，测不到文件头比对
        val foreign = ("NOTSONUS!" + "x".repeat(80)).toByteArray(Charsets.UTF_8)

        try {
            BackupCipher.decrypt(foreign, "pw")
            fail("外部文件应当被拒绝")
        } catch (e: BackupCipher.MalformedBackup) {
            assertTrue("报错应指向文件头，实际：${e.message}", e.message!!.contains("文件头不匹配"))
        }
    }

    @Test
    fun 容器被截断时报非法而不是崩溃() {
        val container = BackupCipher.encrypt(plaintext, "pw")
        val truncated = container.copyOf(container.size / 2)

        try {
            BackupCipher.decrypt(truncated, "pw")
            fail("截断的文件不应被接受")
        } catch (e: Exception) {
            assertTrue(
                "期望 MalformedBackup 或 DecryptionFailed，实际 ${e.javaClass.simpleName}",
                e is BackupCipher.MalformedBackup || e is BackupCipher.DecryptionFailed
            )
        }
    }

    @Test
    fun 空口令直接被拒绝() {
        try {
            BackupCipher.encrypt(plaintext, "")
            fail("空口令不该能生成备份")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("口令"))
        }
    }

    @Test
    fun 空文件内容也能安全往返() {
        val empty = ByteArray(0)
        val container = BackupCipher.encrypt(empty, "pw")

        assertEquals(0, BackupCipher.decrypt(container, "pw").size)
        assertFalse("容器不该是空壳", container.isEmpty())
    }

    @Test
    fun 兜底PBKDF2与平台实现逐字节一致() {
        // 单测的 JVM 一定有 PBKDF2WithHmacSHA256，所以走不到兜底分支；
        // 这里直接调兜底函数，拿平台实现当参照物 —— 两者必须一模一样，否则 Android 6/7 导出的备份在新机器上打不开。
        val cases = listOf(
            DeriveCase("correct horse battery staple", 1, 256),
            DeriveCase("口令含中文与 emoji 🙂", 2, 256),
            DeriveCase("pw", 10_000, 256),
            // 48 字节跨两个分块，验证分块序号与拼接
            DeriveCase("multi-block", 64, 384)
        )
        val salt = ByteArray(16) { (it * 7 + 3).toByte() }

        for (c in cases) {
            val fromPlatform = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(PBEKeySpec(c.passphrase.toCharArray(), salt, c.rounds, c.keyBits))
                .encoded
            val fromFallback = BackupCipher.pbkdf2HmacSha256(
                c.passphrase.toByteArray(Charsets.UTF_8), salt, c.rounds, c.keyBits / 8
            )

            assertArrayEqualsSafe(
                "rounds=${c.rounds} keyBits=${c.keyBits} 口令=${c.passphrase}",
                fromPlatform, fromFallback
            )
        }
    }

    private data class DeriveCase(val passphrase: String, val rounds: Int, val keyBits: Int)

    private fun assertArrayEqualsSafe(expected: ByteArray, actual: ByteArray) {
        org.junit.Assert.assertArrayEquals(expected, actual)
    }

    private fun assertArrayEqualsSafe(message: String, expected: ByteArray, actual: ByteArray) {
        org.junit.Assert.assertArrayEquals(message, expected, actual)
    }
}
