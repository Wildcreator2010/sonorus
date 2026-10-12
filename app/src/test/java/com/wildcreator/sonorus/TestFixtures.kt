package com.wildcreator.sonorus

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Buffer

/** 单元测试用 Json：与 AppModule 生产配置保持一致 */
val testJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/** 读取 OkHttp 请求体文本（OkHttp 的 body 只能写一次，测试里直接写进 Buffer） */
fun okhttp3.Request.bodyText(): String {
    val buffer = Buffer()
    checkNotNull(body) { "请求没有 body" }.writeTo(buffer)
    return buffer.readUtf8()
}

/** 把请求体文本解析为 JsonObject */
fun okhttp3.Request.bodyJson(): JsonObject = testJson.parseToJsonElement(bodyText()).jsonObject

fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.content
