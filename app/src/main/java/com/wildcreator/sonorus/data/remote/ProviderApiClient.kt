package com.wildcreator.sonorus.data.remote

import com.wildcreator.sonorus.data.local.RequestStatDao
import com.wildcreator.sonorus.data.local.RequestStatEntity
import com.wildcreator.sonorus.data.remote.protocol.ProtocolAdapter
import com.wildcreator.sonorus.domain.agent.RemoteModel
import com.wildcreator.sonorus.domain.agent.UnifiedChatRequest
import com.wildcreator.sonorus.domain.model.ProviderCredentials
import com.wildcreator.sonorus.domain.model.ProviderProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlin.jvm.JvmSuppressWildcards
import okhttp3.OkHttpClient
import java.io.BufferedReader
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 三协议统一的网络出入口：模型拉取 + SSE 流式聊天。
 * 所有请求由对应 ProtocolAdapter 构造，本类只负责传输与事件切分。
 *
 * 这里同时是唯一的请求统计埋点位置 —— 只要上游请求都经过本类，成功率统计就不会漏记。
 */
@Singleton
class ProviderApiClient @Inject constructor(
    private val client: OkHttpClient,
    private val adapters: Map<ProviderProtocol, @JvmSuppressWildcards ProtocolAdapter>,
    private val statDao: RequestStatDao
) {

    sealed interface StreamEvent {
        data class Delta(val delta: com.wildcreator.sonorus.domain.agent.StreamDelta) : StreamEvent
        data class Error(val message: String) : StreamEvent
        data object Done : StreamEvent
    }

    private fun adapter(protocol: ProviderProtocol): ProtocolAdapter =
        adapters[protocol] ?: error("未注册的协议适配器：$protocol")

    suspend fun fetchModels(credentials: ProviderCredentials): List<RemoteModel> =
        withContext(Dispatchers.IO) {
            val request = adapter(credentials.protocol).modelsRequest(credentials.baseUrl, credentials.apiKey)
            val startedAt = System.currentTimeMillis()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw java.io.IOException("HTTP ${response.code}：${response.errorBodyText()}")
                    }
                    val body = response.body?.string().orEmpty()
                    val models = adapter(credentials.protocol).parseModels(body)
                    record(credentials, null, success = true, error = null, startedAt = startedAt)
                    models
                }
            } catch (e: Exception) {
                record(credentials, null, success = false, error = e.message, startedAt = startedAt)
                throw e
            }
        }

    fun streamChat(
        credentials: ProviderCredentials,
        chatRequest: UnifiedChatRequest
    ): Flow<StreamEvent> = callbackFlow {
        val protocolAdapter = adapter(credentials.protocol)
        val request = protocolAdapter.chatRequest(credentials.baseUrl, credentials.apiKey, chatRequest)
        val call = client.newCall(request)
        // 取消挂在协程 job 结束上，不能只靠下面的 awaitClose：
        // 生产者阻塞在 readLine()，awaitClose 要等整个流读完才注册得上，
        // 于是"停止"只是界面不再显示 token，上游仍在继续生成并计费。
        currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        val startedAt = System.currentTimeMillis()
        // 只在首次产生实质错误时记录，避免同一请求重复计数
        var recorded = false

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val message = "HTTP ${response.code}：${response.errorBodyText()}"
                    trySend(StreamEvent.Error(message))
                    record(credentials, chatRequest.model, success = false, error = message, startedAt = startedAt)
                    recorded = true
                    close()
                    return@use
                }
                val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))
                var eventName: String? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) { eventName = null; continue } // SSE 事件分隔
                    when {
                        line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
                        line.startsWith("data:") -> {
                            val payload = line.removePrefix("data:").trim()
                            if (payload.isEmpty()) continue
                            protocolAdapter.parseStream(eventName, payload)?.let { delta ->
                                delta.error?.let { err ->
                                    val safeErr = redactSecrets(err)
                                    trySend(StreamEvent.Error(safeErr))
                                    if (!recorded) {
                                        record(credentials, chatRequest.model, false, safeErr, startedAt)
                                        recorded = true
                                    }
                                } ?: trySend(StreamEvent.Delta(delta))
                            }
                        }
                    }
                }
                if (!recorded) {
                    record(credentials, chatRequest.model, success = true, error = null, startedAt = startedAt)
                    recorded = true
                }
                trySend(StreamEvent.Done)
                close()
            }
        } catch (e: Exception) {
            val message = e.message ?: "网络请求失败"
            trySend(StreamEvent.Error(message))
            if (!recorded) {
                record(credentials, chatRequest.model, success = false, error = message, startedAt = startedAt)
                recorded = true
            }
            close()
        }
        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)

    /**
     * 记录一次请求结果。统计失败绝不能影响主流程 ——
     * 写库异常直接吞掉，宁可丢样本也不能让聊天报错。
     */
    private suspend fun record(
        credentials: ProviderCredentials,
        modelId: String?,
        success: Boolean,
        error: String?,
        startedAt: Long
    ) {
        runCatching {
            statDao.insert(
                RequestStatEntity(
                    providerId = credentials.providerId,
                    protocol = credentials.protocol.name,
                    modelId = modelId ?: "-",
                    success = success,
                    // 入库前先擦除密钥痕迹：异常网关可能在错误里回显 Authorization / api key
                    errorKind = error?.let { redactSecrets(it) }?.take(120)?.replace(Regex("\\s+"), " "),
                    durationMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0)
                )
            )
        }
    }

    /** 错误体截断，避免把上游长文本（含可能的敏感回显）塞进 UI */
    private fun okhttp3.Response.errorBodyText(): String =
        redactSecrets(body?.string().orEmpty()).take(300)
}

// ---------- 错误文本密钥擦除 ----------

private val bearerRegex = Regex("(?i)Bearer\\s+[A-Za-z0-9._+/=\\-]+")
private val secretKeyRegex = Regex(
    "(?i)([\"']?(?:api[_-]?key|x-api-key|x-goog-api-key|authorization|access[_-]?token|secret|token)[\"']?\\s*[:=]\\s*[\"']?)" +
        "(?!(?:Bearer\\b|\\[redacted\\]))[^\"'\\s,}]{4,}"
)
private val skTokenRegex = Regex("sk-[A-Za-z0-9_\\-]{10,}")

/**
 * 把错误文本里可能携带的凭据痕迹替换为 [redacted]：
 * - `Bearer xxx` 认证头回显；
 * - JSON / kv 形式的 api_key / authorization / token / secret 字段值
 *   （负向前瞻跳过已擦除占位与裸 “Bearer”，避免二次误伤）；
 * - OpenAI 风格 `sk-...` 令牌。
 * 宁可误擦不可漏擦：只处理错误摘要，不碰正常响应正文。
 */
internal fun redactSecrets(text: String): String = text
    .replace(bearerRegex) { "Bearer [redacted]" }
    .replace(secretKeyRegex) { m -> m.groupValues[1] + "[redacted]" }
    .replace(skTokenRegex) { "sk-[redacted]" }
