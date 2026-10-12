package com.wildcreator.sonorus.data.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 敏感凭据（API Key）存储。
 *
 * 实现约束：
 * - 主密钥由 Android Keystore 生成（AES256-GCM），不可导出；
 * - 键值分别使用 AES256-SIV / AES256-GCM 加密（androidx.security:security-crypto）；
 * - 明文不落数据库、不进备份（见 res/xml/backup_rules.xml）、不写日志；
 * - 所有 IO 在 Dispatchers.IO 执行，写入串行化避免并发覆盖。
 */
interface SecretStore {
    suspend fun put(alias: String, value: String)
    suspend fun get(alias: String): String?
    suspend fun remove(alias: String)
}

@Singleton
class KeystoreSecretStore @Inject constructor(
    @ApplicationContext private val context: Context
) : SecretStore {

    private val initMutex = Mutex()
    private val writeMutex = Mutex()

    @Volatile
    private var prefs: SharedPreferences? = null

    private suspend fun prefs(): SharedPreferences = prefs ?: initMutex.withLock {
        prefs ?: withContext(Dispatchers.IO) {
            try {
                EncryptedSharedPreferences.create(
                    context,
                    FILE_NAME,
                    MasterKey.Builder(context)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build(),
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                // 不吞异常、不降级到明文：设备 Keystore 不可用时应明确失败
                throw IllegalStateException("安全存储初始化失败（Keystore 不可用）：${e.message}", e)
            }
        }.also { prefs = it }
    }

    override suspend fun put(alias: String, value: String) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            prefs().edit().putString(alias, value).apply()
        }
    }

    override suspend fun get(alias: String): String? = withContext(Dispatchers.IO) {
        prefs().getString(alias, null)
    }

    override suspend fun remove(alias: String) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            prefs().edit().remove(alias).apply()
        }
    }

    private companion object {
        const val FILE_NAME = "sonorus_secrets"
    }
}

/** 密钥展示掩码：仅保留末 4 位，用于 UI 提示"已保存"而不泄露全文 */
fun maskSecret(value: String): String {
    val tail = value.takeLast(4)
    return "•".repeat(12) + tail
}
