package com.wildcreator.sonorus.agent

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.wildcreator.sonorus.data.local.*
import com.wildcreator.sonorus.data.prefs.SettingsManager
import com.wildcreator.sonorus.data.remote.ProviderApiClient
import com.wildcreator.sonorus.data.remote.protocol.OpenAiCompatAdapter
import com.wildcreator.sonorus.data.repository.ChatRepositoryImpl
import com.wildcreator.sonorus.domain.agent.AgentExecutor
import com.wildcreator.sonorus.domain.agent.ToolRegistry
import com.wildcreator.sonorus.domain.model.*
import com.wildcreator.sonorus.domain.repository.ProviderRepository
import com.wildcreator.sonorus.testJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class AgentLifecycleTest {
    @Test
    fun 删除正在回答的会话后新会话可以发送() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.useSlow()
            val job = launch { fixture.executor.send(1, "旧问题") }
            withTimeout(3_000) { fixture.executor.state.first { it.streamingContent.isNotEmpty() } }
            val deleted = withTimeoutOrNull(1_000) {
                fixture.executor.cancelRun(1)
                ChatRepositoryImpl(fixture.dao).deleteConversation(1)
                job.join()
                true
            } ?: false
            assertTrue("删除必须等待取消完成并及时释放槽位", deleted)
            assertFalse(fixture.executor.state.value.running)
            assertNull(fixture.dao.getConversation(1))
            assertTrue(fixture.dao.getMessages(1).isEmpty())
            fixture.useMock()
            fixture.server.enqueue(answer("新回答"))
            withTimeout(3_000) { fixture.executor.send(2, "新问题") }
            assertEquals("新回答", fixture.dao.getLastAnswerMessage(2)?.content)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun 取消时保存失败也必须释放发送状态() = runBlocking {
        val fixture = Fixture(failAnswerInsert = true)
        try {
            fixture.useSlow()
            val job = launch { runCatching { fixture.executor.send(1, "旧问题") } }
            withTimeout(3_000) { fixture.executor.state.first { it.streamingContent.isNotEmpty() } }
            fixture.executor.stop()
            withTimeout(7_000) { job.join() }
            assertFalse("取消清理抛错不能留下全局 running", fixture.executor.state.value.running)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun 删除其他会话不会取消当前回答() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.useSlow()
            val job = launch { fixture.executor.send(1, "旧问题") }
            withTimeout(3_000) { fixture.executor.state.first { it.streamingContent.isNotEmpty() } }
            fixture.executor.cancelRun(2)
            ChatRepositoryImpl(fixture.dao).deleteConversation(2)
            assertTrue(fixture.executor.state.value.running)
            assertEquals(1L, fixture.executor.state.value.conversationId)
            assertTrue(job.isActive)
            fixture.executor.stop()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun 首条消息写入失败也必须释放发送状态() = runBlocking {
        val fixture = Fixture(failUserInsert = true)
        try {
            runCatching { fixture.executor.send(1, "写不进去") }
            assertFalse("写库失败不能把 running 留在 true", fixture.executor.state.value.running)
            assertNotNull("失败原因要交给界面", fixture.executor.state.value.error)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun 首条消息尚在写入时也不能启动第二个回合() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fixture = Fixture(beforeFirstInsert = {
            entered.complete(Unit)
            release.await()
        })
        try {
            fixture.server.enqueue(answer("第一轮"))
            fixture.server.enqueue(answer("不应发起"))
            val first = launch { fixture.executor.send(1, "第一问") }
            withTimeout(3_000) { entered.await() }
            assertTrue("写入第一条消息前就应占用槽位", fixture.executor.state.value.running)
            fixture.executor.send(2, "第二问")
            assertTrue("被拒绝的并发发送不能写入消息", fixture.dao.getMessages(2).isEmpty())
            release.complete(Unit)
            withTimeout(3_000) { first.join() }
            assertFalse(fixture.executor.state.value.running)
        } finally {
            release.complete(Unit)
            fixture.close()
        }
    }

    private class Fixture(
        failAnswerInsert: Boolean = false,
        failUserInsert: Boolean = false,
        beforeFirstInsert: (suspend () -> Unit)? = null
    ) {
        val server = MockWebServer().apply { start() }
        private val raw = RawSseServer()
        @Volatile
        private var baseUrl = server.url("/v1").toString()
        private val http = OkHttpClient()
        private val stored = FakeChatDao().apply {
            put(ConversationEntity(id = 1, title = "旧会话", providerId = 1, modelId = "model", toolsEnabled = false))
            put(ConversationEntity(id = 2, title = "新会话", providerId = 1, modelId = "model", toolsEnabled = false))
        }
        val dao = object : ChatDao by stored {
            override suspend fun insertMessage(message: MessageEntity): Long {
                if (message.role == "assistant" && failAnswerInsert) error("disk full")
                if (message.role == "user" && failUserInsert) error("disk full")
                if (message.conversationId == 1L && message.role == "user") beforeFirstInsert?.invoke()
                return stored.insertMessage(message)
            }

            override suspend fun insertMessageIfConversationExists(message: MessageEntity): Boolean {
                if (stored.getConversation(message.conversationId) == null) return false
                insertMessage(message)
                return true
            }
        }
        private val settings = SettingsManager(
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
                produceFile = { Files.createTempDirectory("sonorus-settings").toFile().resolve("settings.preferences_pb") }
            )
        )
        private val provider = object : ProviderRepository {
            override suspend fun credentials(id: Long) = ProviderCredentials(id, ProviderProtocol.OPENAI, baseUrl, "test")
            override fun observeProviders(): Flow<List<Provider>> = error("unused")
            override suspend fun getProvider(id: Long): Provider? = error("unused")
            override suspend fun saveProvider(draft: ProviderDraft): Long = error("unused")
            override suspend fun deleteProvider(id: Long): Unit = error("unused")
            override suspend fun conversationCountFor(providerId: Long): Int = error("unused")
            override suspend fun maskedApiKey(id: Long): String? = error("unused")
            override suspend fun testConnection(draft: ProviderDraft): Result<Int> = error("unused")
            override fun observeModels(providerId: Long): Flow<List<ModelInfo>> = error("unused")
            override suspend fun refreshModels(providerId: Long): Result<Int> = error("unused")
        }
        private val stats = object : RequestStatDao {
            override suspend fun insert(stat: RequestStatEntity) = Unit
            override suspend fun summary(sinceEpochMs: Long) = RequestSummary(0, 0)
            override suspend fun summaryForProvider(providerId: Long, sinceEpochMs: Long) = RequestSummary(0, 0)
            override fun observeByProvider(sinceEpochMs: Long): Flow<List<ProviderRequestSummary>> = flowOf(emptyList())
            override suspend fun clearAll() = Unit
        }
        val executor = AgentExecutor(
            dao, provider,
            ProviderApiClient(http, mapOf(ProviderProtocol.OPENAI to OpenAiCompatAdapter(testJson)), stats),
            ToolRegistry(), settings, testJson
        )

        fun useSlow() {
            baseUrl = raw.baseUrl
        }

        fun useMock() {
            baseUrl = server.url("/v1").toString()
        }

        fun close() {
            executor.stop()
            raw.close()
            http.dispatcher.cancelAll()
            server.shutdown()
        }
    }

    companion object {
        private fun answer(text: String) = MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"$text\"},\"finish_reason\":\"stop\"}]}\n\n")
    }
}
