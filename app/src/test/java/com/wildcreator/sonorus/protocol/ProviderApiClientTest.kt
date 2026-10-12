package com.wildcreator.sonorus.data.remote

import com.wildcreator.sonorus.data.local.ProviderRequestSummary
import com.wildcreator.sonorus.data.local.RequestStatDao
import com.wildcreator.sonorus.data.local.RequestStatEntity
import com.wildcreator.sonorus.data.local.RequestSummary
import com.wildcreator.sonorus.data.remote.protocol.AnthropicAdapter
import com.wildcreator.sonorus.data.remote.protocol.OpenAiCompatAdapter
import com.wildcreator.sonorus.domain.agent.UnifiedChatRequest
import com.wildcreator.sonorus.domain.agent.UnifiedMessage
import com.wildcreator.sonorus.domain.agent.UnifiedRole
import com.wildcreator.sonorus.domain.model.ProviderCredentials
import com.wildcreator.sonorus.domain.model.ProviderProtocol
import com.wildcreator.sonorus.testJson
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * 网络出入口的端到端单测：用 MockWebServer 喂真实 SSE 字节流，
 * 验证事件切分（event:/data: 行、空行分隔、[DONE] 哨兵）与 HTTP 错误处理。
 */
class ProviderApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: ProviderApiClient
    private lateinit var statDao: RecordingStatDao

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        statDao = RecordingStatDao()
        client = ProviderApiClient(
            OkHttpClient(),
            mapOf(
                ProviderProtocol.OPENAI to OpenAiCompatAdapter(testJson),
                ProviderProtocol.ANTHROPIC to AnthropicAdapter(testJson)
            ),
            statDao
        )
    }

    /**
     * 内存版统计 DAO：既满足依赖注入，又让"埋点确实被调用"成为可断言的事实。
     * 只实现 ProviderApiClient 用到的方法，其余抛异常以免被误用。
     */
    private class RecordingStatDao : RequestStatDao {
        val inserted = mutableListOf<RequestStatEntity>()

        override suspend fun insert(stat: RequestStatEntity) {
            inserted += stat
        }

        override suspend fun summary(sinceEpochMs: Long) = RequestSummary(0, 0)
        override suspend fun summaryForProvider(providerId: Long, sinceEpochMs: Long) =
            RequestSummary(0, 0)

        override fun observeByProvider(sinceEpochMs: Long): kotlinx.coroutines.flow.Flow<List<ProviderRequestSummary>> =
            kotlinx.coroutines.flow.flowOf(emptyList())

        override suspend fun clearAll() = Unit
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun fetchModels_解析模型列表() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"data":[{"id":"gpt-4o","owned_by":"openai"}]}""")
        )

        val models = client.fetchModels(credentials(ProviderProtocol.OPENAI, "/v1"))
        assertEquals(1, models.size)
        assertEquals("gpt-4o", models[0].id)

        val recorded = server.takeRequest()
        assertEquals("/v1/models", recorded.path)
        assertEquals("Bearer sk-test", recorded.getHeader("Authorization"))
    }

    @Test(expected = IOException::class)
    fun fetchModels_非2xx抛出IO异常() {
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
            client.fetchModels(credentials(ProviderProtocol.OPENAI, "/v1"))
        }
    }

    @Test
    fun streamChat_解析OpenAI风格SSE() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """
                    data: {"choices":[{"delta":{"content":"你"}}]}

                    data: {"choices":[{"delta":{"content":"好"}}]}

                    data: {"choices":[{"finish_reason":"stop"}]}

                    data: [DONE]

                    """.trimIndent()
                )
        )

        val events = withTimeout(10_000) {
            client.streamChat(credentials(ProviderProtocol.OPENAI, "/v1"), chatRequest()).toList()
        }

        val deltas = events.filterIsInstance<ProviderApiClient.StreamEvent.Delta>()
            .mapNotNull { it.delta.text }
        assertEquals(listOf("你", "好"), deltas)
        assertTrue(events.any { it is ProviderApiClient.StreamEvent.Delta && it.delta.finish != null })
        assertTrue(events.last() is ProviderApiClient.StreamEvent.Done)
        // [DONE] 哨兵不应产生额外事件
        assertEquals(4, events.size)
    }

    @Test
    fun streamChat_识别Anthropic的event行() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """
                    event: content_block_delta
                    data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi"}}

                    event: message_delta
                    data: {"type":"message_delta","delta":{"stop_reason":"end_turn"}}

                    """.trimIndent()
                )
        )

        val events = withTimeout(10_000) {
            client.streamChat(credentials(ProviderProtocol.ANTHROPIC, "/v1"), chatRequest()).toList()
        }

        val first = events.first() as ProviderApiClient.StreamEvent.Delta
        assertEquals("Hi", first.delta.text)
        val last = events.filterIsInstance<ProviderApiClient.StreamEvent.Delta>().last()
        assertNotNull(last.delta.finish)

        val recorded = server.takeRequest()
        assertEquals("/v1/messages", recorded.path)
        assertEquals("sk-test", recorded.getHeader("x-api-key"))
    }

    @Test
    fun streamChat_非2xx下发Error事件() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))

        val events = withTimeout(10_000) {
            client.streamChat(credentials(ProviderProtocol.OPENAI, "/v1"), chatRequest()).toList()
        }

        val error = events.first() as ProviderApiClient.StreamEvent.Error
        assertTrue(error.message.contains("401"))
    }

    @Test
    fun streamChat_流内错误对象转为Error事件() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("""data: {"error":{"message":"context length exceeded"}}""" + "\n\n")
        )

        val events = withTimeout(10_000) {
            client.streamChat(credentials(ProviderProtocol.OPENAI, "/v1"), chatRequest()).toList()
        }

        val error = events.first() as ProviderApiClient.StreamEvent.Error
        assertEquals("context length exceeded", error.message)
    }

    // ---------- 请求统计埋点 ----------

    @Test
    fun 成功请求记为一条success样本() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"data":[{"id":"gpt-4o"}]}""")
        )

        client.fetchModels(credentials(ProviderProtocol.OPENAI, "/v1"))

        assertEquals("每次请求恰好记一条", 1, statDao.inserted.size)
        val stat = statDao.inserted.single()
        assertTrue("成功请求必须标记为 success", stat.success)
        assertEquals("提供商 id 必须归属正确", 1L, stat.providerId)
        assertEquals("协议名必须落库", ProviderProtocol.OPENAI.name, stat.protocol)
        assertTrue("耗时不应为负", stat.durationMs >= 0)
    }

    @Test
    fun HTTP失败记为一条failure样本且带错误摘要() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"bad key"}"""))

        runCatching { client.fetchModels(credentials(ProviderProtocol.OPENAI, "/v1")) }

        val stat = statDao.inserted.single()
        assertTrue("401 必须记为失败", !stat.success)
        assertNotNull("失败样本要带错误摘要便于排查", stat.errorKind)
        assertTrue("错误摘要应包含状态码", stat.errorKind!!.contains("401"))
    }

    @Test
    fun 流式请求失败也会计数且不重复() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        runCatching {
            client.streamChat(credentials(ProviderProtocol.OPENAI, "/v1"), chatRequest()).toList()
        }

        assertEquals("同一次请求只能记一条，不能重复计数", 1, statDao.inserted.size)
        assertTrue(!statDao.inserted.single().success)
    }

    @Test
    fun 统计异常绝不影响主流程() = runBlocking {
        // 用会抛异常的 DAO 模拟"写库失败"：请求结果必须照常返回
        val brokenDao = object : RequestStatDao by statDao {
            override suspend fun insert(stat: RequestStatEntity) {
                throw IllegalStateException("database is locked")
            }
        }
        val resilientClient = ProviderApiClient(
            OkHttpClient(),
            mapOf(ProviderProtocol.OPENAI to OpenAiCompatAdapter(testJson)),
            brokenDao
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[{"id":"m"}]}"""))

        val models = resilientClient.fetchModels(credentials(ProviderProtocol.OPENAI, "/v1"))

        assertEquals("统计写库失败不能拖垮模型拉取", 1, models.size)
    }

    @Test
    fun 带finish的流式请求记为成功样本() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """
                    data: {"choices":[{"delta":{"content":"你好"},"finish_reason":"stop"}]}

                    data: [DONE]

                    """.trimIndent()
                )
        )

        client.streamChat(credentials(ProviderProtocol.OPENAI, "/v1"), chatRequest()).toList()

        val stat = statDao.inserted.single()
        assertTrue("正常收尾的流必须记成功", stat.success)
        assertEquals(null, stat.errorKind)
    }

    @Test
    fun 没收到finish的流记为失败并写明截断() = runBlocking {
        // 上游半路结束（网关超时、进程被杀）时，chunked body 也能"干净地"读完：
        // HTTP 层完全看不出问题，只有 finish 缺失能识别，这类样本不能算成功
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """
                    data: {"choices":[{"delta":{"content":"写到一半"}}]}

                    data: [DONE]

                    """.trimIndent()
                )
        )

        val events = withTimeout(10_000) {
            client.streamChat(credentials(ProviderProtocol.OPENAI, "/v1"), chatRequest()).toList()
        }
        assertTrue("界面侧行为不变，仍收到 Done", events.last() is ProviderApiClient.StreamEvent.Done)

        val stat = statDao.inserted.single()
        assertTrue("没有 finish 的流要记为失败", !stat.success)
        assertTrue("失败原因要写明疑似截断", stat.errorKind!!.contains("截断"))
    }

    private fun credentials(protocol: ProviderProtocol, path: String) = ProviderCredentials(
        providerId = 1L,
        protocol = protocol,
        baseUrl = server.url(path).toString().trimEnd('/'),
        apiKey = "sk-test"
    )

    private fun chatRequest() = UnifiedChatRequest(
        model = "gpt-4o",
        systemPrompt = null,
        messages = listOf(UnifiedMessage(role = UnifiedRole.USER, text = "你好")),
        tools = null,
        maxTokens = 1024
    )
}
