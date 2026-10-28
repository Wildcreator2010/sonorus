package com.wildcreator.sonorus.data.remote

import com.wildcreator.sonorus.data.local.RequestStatDao
import com.wildcreator.sonorus.data.local.RequestStatEntity
import com.wildcreator.sonorus.data.remote.protocol.ProtocolAdapter
import com.wildcreator.sonorus.domain.agent.RemoteModel
import com.wildcreator.sonorus.domain.agent.UnifiedChatRequest
import com.wildcreator.sonorus.domain.model.ProviderCredentials
import com.wildcreator.sonorus.domain.model.ProviderProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 同聊天流：异常文本可能回显带密钥的 URL 或认证头，抛给界面前先脱敏
                val message = redactSecrets(e.message ?: "拉取模型失败")
                record(credentials, null, success = false, error = message, startedAt = startedAt)
                throw java.io.IOException(message, e)
            }
        }

    fun streamChat(
        credentials: ProviderCredentials,
        chatRequest: UnifiedChatRequest
    ): Flow<StreamEvent> = callbackFlow {
        val protocolAdapter = adapter(credentials.protocol)
        val request = protocolAdapter.chatRequest(credentials.baseUrl, credentials.apiKey, chatRequest)
        val call = client.newCall(request)
        /*
         * 单独的子协程负责"收到取消就掐断 socket"。
         * 生产者阻塞在 readLine() 上时没有任何挂起点，取消标记要等下一帧数据到达才被看见；
         * 只写 awaitClose 更晚 —— 它要等生产者退出才注册得上。
         * 结果就是：用户点了停止/删了会话，回合仍要挂到读超时才释放，期间所有会话都显示"正在回答"。
         */
        val cancelWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        val startedAt = System.currentTimeMillis()
        // 只在首次产生实质错误时记录，避免同一请求重复计数
        var recorded = false
        // 各家协议都在最后一个增量上带 finish；没带就是上游半路结束
        var sawFinish = false
        val errorMessage = "上游返回了无法识别的响应（可能是协议或端点不匹配）"

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val message = "HTTP ${response.code}：${response.errorBodyText()}"
                    send(StreamEvent.Error(message))
                    record(credentials, chatRequest.model, success = false, error = message, startedAt = startedAt)
                    recorded = true
                    close()
                    return@use
                }
                val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))
                var rawFirstLine = reader.readLine()
                // 有些网关在完整 JSON 前会补空行或 UTF-8 BOM；先剥掉再判断协议形态
                while (rawFirstLine != null && rawFirstLine.isBlank()) rawFirstLine = reader.readLine()
                val firstLine = rawFirstLine?.removePrefix("\uFEFF")
                if (firstLine == null) {
                    send(StreamEvent.Error("上游返回了空响应（HTTP ${response.code}）"))
                    record(
                        credentials, chatRequest.model, false,
                        "上游返回空响应", startedAt
                    )
                    recorded = true
                    send(StreamEvent.Done)
                    close()
                    return@use
                }

                // 网关可能忽略 stream=true 直接回完整 JSON；这种响应没有任何 data: 行，
                // 按 SSE 读会一行都匹配不上，界面就只剩"模型返回空内容"。
                val trimmed = firstLine.trimStart('\uFEFF', ' ', '\t')
                if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                    val body = buildString {
                        append(firstLine)
                        while (true) {
                            val line = reader.readLine() ?: break
                            append('\n').append(line)
                        }
                    }
                    val delta = protocolAdapter.parseNonStream(body)
                    if (delta?.error != null) {
                        val safeErr = redactSecrets(delta.error!!)
                        send(StreamEvent.Error(safeErr))
                        record(credentials, chatRequest.model, false, safeErr, startedAt)
                    } else if (delta == null) {
                        send(StreamEvent.Error(errorMessage))
                        record(credentials, chatRequest.model, false, errorMessage, startedAt)
                    } else {
                        // 完整响应天然是收尾的：没有 finish 字段也算成功
                        sawFinish = true
                        send(StreamEvent.Delta(delta))
                        record(credentials, chatRequest.model, true, null, startedAt)
                    }
                    recorded = true
                    send(StreamEvent.Done)
                    close()
                    return@use
                }

                var eventName: String? = null
                val data = StringBuilder()

                suspend fun dispatch() {
                    if (data.isEmpty()) {
                        eventName = null
                        return
                    }
                    val payload = data.toString()
                    data.setLength(0)
                    val name = eventName
                    eventName = null
                    protocolAdapter.parseStream(name, payload)?.let { delta ->
                        delta.error?.let { err ->
                            val safeErr = redactSecrets(err)
                            send(StreamEvent.Error(safeErr))
                            if (!recorded) {
                                record(credentials, chatRequest.model, false, safeErr, startedAt)
                                recorded = true
                            }
                        } ?: run {
                            if (delta.finish != null) sawFinish = true
                            send(StreamEvent.Delta(delta))
                        }
                    }
                }

                suspend fun handleLine(line: String) {
                    when {
                        line.isBlank() -> dispatch() // SSE 事件分隔
                        line.startsWith(":") -> Unit // 注释/心跳
                        line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
                        line.startsWith("data:") -> {
                            if (data.isNotEmpty()) data.append('\n')
                            data.append(line.removePrefix("data:").trimStart())
                        }
                    }
                }

                handleLine(firstLine)
                while (true) {
                    val line = reader.readLine() ?: break
                    handleLine(line)
                }
                dispatch()
                if (!recorded) {
                    // 没收到 finish 就记失败：上游超时/网关掐断时 chunked body 也能"干净地"结束，
                    // 这种截断在 HTTP 层完全看不出来，不认就永远统计成功。
                    // 界面侧仍按已有内容展示（不为不合规网关新增报错），只有统计变严格。
                    record(
                        credentials, chatRequest.model,
                        success = sawFinish,
                        error = if (sawFinish) null else "流未以 finish 结束（疑似被截断）",
                        startedAt = startedAt
                    )
                    recorded = true
                }
                send(StreamEvent.Done)
                close()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 传输层异常文本可能回显完整 URL（带 query 里的密钥）或 Authorization 头
            val message = redactSecrets(e.message ?: "网络请求失败")
            send(StreamEvent.Error(message))
            if (!recorded) {
                record(credentials, chatRequest.model, success = false, error = message, startedAt = startedAt)
                recorded = true
            }
            close()
        } finally {
            cancelWatcher.cancel()
        }
        awaitClose { cancelWatcher.cancel(); call.cancel() }
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
