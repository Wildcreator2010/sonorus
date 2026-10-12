package com.wildcreator.sonorus.domain.model

import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 备份文件的加密容器：口令经 PBKDF2-HMAC-SHA256 派生密钥，正文用 AES-256-GCM 加密。
 *
 * 容器布局：magic(11 字节 ASCII) | version(1) | salt(16) | iv(12) | GCM 密文（含 16 字节认证标签）
 *
 * 为什么必须有认证标签：备份里含 API Key。GCM 让「口令错」与「文件被改」都必然解密失败，
 * 于是恢复只可能是"要么完整、要么拒绝"，不会出现解出一半就当正常备份导入的情况。
 *
 * 口令本身任何时候都不落盘；忘记口令等于这份备份永久解不开 —— 这是设计代价，不是缺陷。
 */
object BackupCipher {

    /** 容器指纹，同时兼作版本协商：换算法就换结尾字符 */
    const val MAGIC = "SONORUSBAK1"

    private const val VERSION = 1
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256

    /** OWASP 当前对 PBKDF2-HMAC-SHA256 的推荐轮次下界 */
    private const val PBKDF2_ROUNDS = 210_000

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_ALGORITHM = "AES"
    private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"

    /** 容器结构本身不合法：不是本应用导出的文件，或被截断 */
    class MalformedBackup(message: String) : Exception(message)

    /**
     * 解密失败。口令错误与文件被篡改在 GCM 下无法区分，也不该区分 ——
     * 对用户统一表述为「口令不正确，或文件已被修改」。
     */
    class DecryptionFailed(message: String) : Exception(message)

    private val random = SecureRandom()

    fun encrypt(plaintext: ByteArray, passphrase: String): ByteArray {
        require(passphrase.isNotEmpty()) { "口令不能为空" }

        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)

        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(TAG_BITS, iv))
        }
        val body = cipher.doFinal(plaintext)

        return ByteArrayOutputStream(MAGIC.length + 1 + SALT_BYTES + IV_BYTES + body.size).use { out ->
            out.write(MAGIC.toByteArray(Charsets.US_ASCII))
            out.write(VERSION)
            out.write(salt)
            out.write(iv)
            out.write(body)
            out.toByteArray()
        }
    }

    fun decrypt(container: ByteArray, passphrase: String): ByteArray {
        val headerSize = MAGIC.length + 1 + SALT_BYTES + IV_BYTES
        if (container.size <= headerSize) throw MalformedBackup("文件过小，不是有效的备份容器")

        val magic = String(container, 0, MAGIC.length, Charsets.US_ASCII)
        if (magic != MAGIC) throw MalformedBackup("文件头不匹配：这份备份不是 Sonorus 导出的")

        val version = container[MAGIC.length].toInt()
        if (version != VERSION) throw MalformedBackup("备份格式版本 $version 与本应用不兼容")

        val salt = container.copyOfRange(MAGIC.length + 1, MAGIC.length + 1 + SALT_BYTES)
        val iv = container.copyOfRange(MAGIC.length + 1 + SALT_BYTES, headerSize)
        val body = container.copyOfRange(headerSize, container.size)

        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(TAG_BITS, iv))
        }
        return try {
            cipher.doFinal(body)
        } catch (e: AEADBadTagException) {
            throw DecryptionFailed("口令不正确，或文件已被修改")
        } catch (e: GeneralSecurityException) {
            throw DecryptionFailed("解密失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun deriveKey(passphrase: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ROUNDS, KEY_BITS)
        val key = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM).generateSecret(spec)
        return SecretKeySpec(key.encoded, KEY_ALGORITHM)
    }
}
