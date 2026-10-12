package com.wildcreator.sonorus.domain.model

import com.wildcreator.sonorus.testJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新源的解析与版本比较。
 * 这是全工程唯一会引入"外部可执行文件"的路径，
 * 所以 https/主机白名单、sha256 缺失、apk 资源挑选这些都必须断言住，不能靠 UI 兜。
 */
class ReleaseParserTest {

    private fun obj(text: String): JsonObject = testJson.parseToJsonElement(text).jsonObject

    private fun release(
        tag: String = "v0.2.0",
        assets: String = """[{"name":"sonorus-v0.2.0.apk","content_type":"application/vnd.android.package-archive","browser_download_url":"https://github.com/Wildcreator2010/Sonorus/releases/download/v0.2.0/app-release.apk"}]""",
        body: String = "更新说明\nsha256=" + "a".repeat(64)
    ) = obj(
        """{"tag_name":"$tag","name":"$tag","body":${jsonString(body)},"assets":$assets}"""
    )

    /** 手写 JSON 字符串转义，省得在测试里再引一个序列化器 */
    private fun jsonString(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    @Test
    fun 解析出版本与校验值() {
        val update = ReleaseParser.parse(release())
        assertEquals("0.2.0", update?.versionName)
        assertEquals("a".repeat(64), update?.sha256)
        assertTrue(update!!.apkUrl.startsWith("https://"))
    }

    @Test
    fun 优先挑apk资源() {
        val mixed = release(
            assets = """[
                {"name":"notes.txt","content_type":"text/plain","browser_download_url":"https://github.com/x/y/releases/download/v0.2.0/notes.txt"},
                {"name":"app.apk","content_type":"application/vnd.android.package-archive","browser_download_url":"https://github.com/x/y/releases/download/v0.2.0/app.apk"}
            ]"""
        )
        assertTrue(ReleaseParser.parse(mixed)!!.apkUrl.endsWith("app.apk"))
    }

    @Test
    fun 没有apk资源时返回null() {
        val noApk = release(
            assets = """[{"name":"source.zip","content_type":"application/zip","browser_download_url":"https://github.com/x/y/archive.zip"}]"""
        )
        assertNull(ReleaseParser.parse(noApk))
    }

    @Test
    fun 正文缺sha256时校验值为空() {
        val update = ReleaseParser.parse(release(body = "这次忘了写校验值"))
        assertNull(update?.sha256)
    }

    @Test
    fun 校验值支持多种书写格式() {
        // 发布正文是人手写的，等号/冒号/反引号/markdown 列表符号都得容忍
        assertEquals("b".repeat(64), ReleaseParser.parse(release(body = "SHA-256: " + "b".repeat(64)))!!.sha256)
        assertEquals("c".repeat(64), ReleaseParser.parse(release(body = "sha256 = `" + "c".repeat(64) + "`"))!!.sha256)
        assertEquals("d".repeat(64), ReleaseParser.parse(release(body = "- sha256=" + "d".repeat(64)))!!.sha256)
        // 大写十六进制统一转小写，比对时不再区分
        assertEquals("e".repeat(64), ReleaseParser.parse(release(body = "sha256=" + "E".repeat(64)))!!.sha256)
    }

    @Test
    fun 拒绝明文http下载链接() {
        val http = release(
            assets = """[{"name":"app.apk","content_type":"application/vnd.android.package-archive","browser_download_url":"http://github.com/x/app.apk"}]"""
        )
        assertNull("必须挡住被降级成 http 的响应", ReleaseParser.parse(http))
    }

    @Test
    fun 拒绝白名单外的主机() {
        val evil = release(
            assets = """[{"name":"app.apk","content_type":"application/vnd.android.package-archive","browser_download_url":"https://evil.example.com/app.apk"}]"""
        )
        assertNull(ReleaseParser.parse(evil))
    }

    @Test
    fun 缺少必要字段时返回null() {
        assertNull(ReleaseParser.parse(obj("""{"assets":[]}""")))
        assertNull(ReleaseParser.parse(obj("""{"tag_name":"v0.2.0"}""")))
        assertNull(ReleaseParser.parse(obj("""{"tag_name":"","assets":[]}""")))
    }

    @Test
    fun 版本按数字段比较不是字典序() {
        // 字典序会把 0.10.0 判成比 0.9.0 旧
        assertTrue(ReleaseParser.isNewer("0.10.0", "0.9.0"))
        assertTrue(ReleaseParser.isNewer("0.2.0", "0.1.0"))
        assertTrue(ReleaseParser.isNewer("1.0.0", "0.99.99"))
        assertFalse(ReleaseParser.isNewer("0.1.0", "0.1.0"))
        assertFalse(ReleaseParser.isNewer("0.1.0", "0.2.0"))
    }

    @Test
    fun tag前缀不影响比较() {
        assertEquals(0, ReleaseParser.compare("v0.2.0", "0.2.0"))
        assertTrue(ReleaseParser.compare("release-0.3.0", "v0.2.0") > 0)
    }

    @Test
    fun 正式版优先于预发布标记() {
        assertTrue(ReleaseParser.isNewer("0.2.0", "0.2-beta"))
        assertFalse(ReleaseParser.isNewer("0.2-beta", "0.2.0"))
    }

    @Test
    fun 段数不同按补零处理() {
        assertEquals(0, ReleaseParser.compare("1.2", "1.2.0"))
        assertTrue(ReleaseParser.compare("1.2.1", "1.2") > 0)
    }
}
