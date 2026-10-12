package com.wildcreator.sonorus.domain.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 一条可安装的版本发布。
 *
 * 供应链约束（都在这层判掉，不留给 UI）：
 * - apkUrl 必须是 https 且落在 github.com / objects.githubusercontent.com；
 * - sha256 为 null 表示发布正文里没写校验值 —— 调用方必须**拒绝安装**（fail closed），
 *   不能"没给就不校验"，否则被中间人替换的包照样能装上。
 */
data class ReleaseUpdate(
    val versionName: String,
    val apkUrl: String,
    val sha256: String?,
    val notes: String
)

object ReleaseParser {

    /** 允许的重定向目标主机：GitHub Releases 会 302 到 objects.githubusercontent.com */
    private val allowedHosts = setOf(
        "github.com",
        "objects.githubusercontent.com",
        "api.github.com",
        "codeload.github.com"
    )

    /** 只接受 apk 资源；contentType 缺失时退回文件名判断 */
    private val apkMime = "application/vnd.android.package-archive"

    /** 正文里的校验值写法：sha256=xxx / SHA-256: xxx（允许反引号与 markdown 列表符号） */
    private val sha256Regex = Regex(
        """(?i)sha[-_ ]?256\s*[:=]\s*[`*_]*([0-9a-f]{64})""",
        RegexOption.IGNORE_CASE
    )

    /**
     * 解析 GitHub `releases/latest` 响应。
     * @return null = 没有可用的 apk 资源或 url 不合法，调用方按"无更新"处理并给出原因
     */
    fun parse(root: JsonObject): ReleaseUpdate? {
        val tag = root["tag_name"]?.jsonPrimitive?.content ?: return null
        val version = stripTagPrefix(tag)
        if (version.isEmpty()) return null

        val assets = root["assets"] as? JsonArray ?: return null
        val apk = assets.firstOrNull { it.isApkAsset() } as? JsonObject ?: return null
        val url = apk["browser_download_url"]?.jsonPrimitive?.content ?: return null
        if (!url.isSafeDownloadUrl()) return null

        val body = root["body"]?.jsonPrimitive?.content.orEmpty()
        return ReleaseUpdate(
            versionName = version,
            apkUrl = url,
            sha256 = sha256Regex.find(body)?.groupValues?.get(1)?.lowercase(),
            notes = root["name"]?.jsonPrimitive?.content ?: version
        )
    }

    private fun Any?.isApkAsset(): Boolean {
        val asset = this as? JsonObject ?: return false
        val mime = asset["content_type"]?.jsonPrimitive?.content
        if (mime == apkMime) return true
        val name = asset["name"]?.jsonPrimitive?.content ?: return false
        return name.endsWith(".apk", ignoreCase = true)
    }

    /** 去掉 v / V / release- 等常见前缀，只留版本号本体 */
    fun stripTagPrefix(tag: String): String =
        tag.trim()
            .removePrefix("release-")
            .removePrefix("Release-")
            .removePrefix("v")
            .removePrefix("V")
            .trim()

    /**
     * 版本比较：按点分段做数字比较，非数字段退回字符串比较。
     * 必须逐段比 —— 用字典序会把 "0.10.0" 判成比 "0.9.0" 旧。
     * @return 正数 = [a] 更新
     */
    fun compare(a: String, b: String): Int {
        val left = stripTagPrefix(a).split('.', '-', '_')
        val right = stripTagPrefix(b).split('.', '-', '_')
        val size = maxOf(left.size, right.size)
        for (i in 0 until size) {
            val l = left.getOrNull(i) ?: "0"
            val r = right.getOrNull(i) ?: "0"
            val ln = l.toIntOrNull()
            val rn = r.toIntOrNull()
            val diff = when {
                ln != null && rn != null -> ln.compareTo(rn)
                ln != null -> 1        // 数字段视为比预发布标记更新（0.2 优于 0.2-beta）
                rn != null -> -1
                else -> l.compareTo(r)
            }
            if (diff != 0) return diff
        }
        return 0
    }

    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0

    /** 下载链接白名单：只允许 https + 已知 GitHub 主机，挡掉被替换成 http/其他域的响应 */
    private fun String.isSafeDownloadUrl(): Boolean =
        runCatching {
            val uri = java.net.URI(this)
            uri.scheme == "https" && uri.host?.lowercase() in allowedHosts
        }.getOrDefault(false)
}
