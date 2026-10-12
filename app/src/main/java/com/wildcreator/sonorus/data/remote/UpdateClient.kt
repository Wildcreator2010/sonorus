package com.wildcreator.sonorus.data.remote

import android.content.Context
import com.wildcreator.sonorus.domain.model.ReleaseParser
import com.wildcreator.sonorus.domain.model.ReleaseUpdate
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 应用内更新：读取 GitHub Releases 的最新版，下载 APK 并做 sha256 校验。
 *
 * 安全约定（这是本工程里唯一会引入"外部可执行文件"的路径，标准要严于聊天请求）：
 * - 只走 https，且只认 ReleaseParser 白名单里的 GitHub 主机；
 * - **发布正文必须带 sha256**，缺失或比对不通过一律拒绝，绝不降级成"不校验也装"；
 * - 校验失败的临时文件立刻删除，不留给下一次误用；
 * - 不发送任何凭据（公开 releases 接口无需 token），因此不存在密钥外带。
 */
@Singleton
class UpdateClient @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
    @ApplicationContext private val context: Context
) {

    /** 设置页展示"在查哪个仓库"用 */
    val repository: String get() = "$OWNER/$REPO"

    private val downloadClient by lazy {
        // GitHub 的 browser_download_url 会 302 到 objects.githubusercontent.com，
        // 聊天用的共享客户端刻意关了重定向，这里单独开（不带任何凭据）。
        client.newBuilder().followRedirects(true).build()
    }

    /** 下载目录：应用私有 cache，外部 App 读不到，卸载即清 */
    private fun apkDir(): File = File(context.cacheDir, "apk").apply { if (!exists()) mkdirs() }

    fun fileNameFor(update: ReleaseUpdate): String = "sonorus-${update.versionName}.apk"

    fun currentVersionName(): String = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrDefault("0").ifBlank { "0" }

    /** 拉取最新 release；无 apk 资源 / 链接不合法时返回失败并附原因 */
    suspend fun fetchLatest(): Result<ReleaseUpdate> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(LATEST_RELEASE_URL)
                .header("Accept", "application/vnd.github+json")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw java.io.IOException("发布页返回 HTTP ${response.code}")
                val root = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                ReleaseParser.parse(root) ?: throw java.io.IOException("最新发行版里没有可安装的 APK")
            }
        }
    }

    /**
     * 下载到私有缓存目录，返回校验通过的文件。
     * @param onProgress 0..100；总长未知时回调 -1 表示"下载中"
     */
    suspend fun download(
        update: ReleaseUpdate,
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val target = File(apkDir(), fileNameFor(update))
        runCatching {
            val expected = update.sha256
                ?: throw IllegalStateException("该发布未提供 sha256，已拒绝安装")
            // 清掉上一轮残留，避免复用同名文件
            apkDir().listFiles()?.forEach { it.delete() }

            val request = Request.Builder().url(update.apkUrl).build()
            val digest = MessageDigest.getInstance("SHA-256")
            downloadClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw java.io.IOException("下载失败：HTTP ${response.code}")
                val body = response.body ?: throw java.io.IOException("响应没有内容")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    DigestInputStream(input, digest).use { signed ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var done = 0L
                            while (true) {
                                val read = signed.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                done += read
                                onProgress(if (total > 0) ((done * 100) / total).toInt() else -1)
                            }
                        }
                    }
                }
            }

            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(expected.lowercase(), ignoreCase = true)) {
                throw IllegalStateException("sha256 校验不通过（实际 $actual）")
            }
            onProgress(100)
            target
        }.onFailure {
            // 任何失败路径都不能把未校验的包留在磁盘上
            runCatching { target.delete() }
        }
    }

    private companion object {
        const val OWNER = "Wildcreator2010"
        const val REPO = "Sonorus"
        const val LATEST_RELEASE_URL = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    }
}
