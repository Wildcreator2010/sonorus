package com.wildcreator.sonorus.data.repository

import com.wildcreator.sonorus.data.local.ConversationEntity
import com.wildcreator.sonorus.data.local.FakeChatDao
import com.wildcreator.sonorus.domain.agent.ThinkingLevel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「改会话设置」不能碰上下文摘要列。
 *
 * `contextSummary` / `summaryThroughMessageId` 由 [com.wildcreator.sonorus.domain.agent.AgentExecutor]
 * 在压缩历史时写入，域模型 Conversation 不承载它们（UI 不需要，也不该被 UI 改写）。
 * 因此凡是把 Conversation 映射成实体再整行 REPLACE 的写法，都会把这两列静默清零 ——
 * 已摘要掉的旧消息重新进入上下文，摘要不可恢复。
 */
class ConversationSettingsUpdateTest {

    private val dao = FakeChatDao()
    private val repository = ChatRepositoryImpl(dao)

    private fun seedSummary() {
        dao.put(
            ConversationEntity(
                id = 1L,
                title = "会话",
                providerId = 7L,
                modelId = "gpt-4o-mini",
                systemPrompt = "老人格",
                toolsEnabled = true,
                contextSummary = "已压缩前 40 条的摘要",
                summaryThroughMessageId = 42L
            )
        )
    }

    @Test
    fun `改 systemPrompt 后摘要与压缩水位仍在原位`() = runTest {
        seedSummary()
        val conversation = repository.getConversation(1L)!!

        repository.updateConversation(conversation.copy(systemPrompt = "新人格"))

        val stored = dao.conversations.getValue(1L)
        assertEquals("新人格", stored.systemPrompt)
        assertEquals("摘要被整行 REPLACE 抹掉了", "已压缩前 40 条的摘要", stored.contextSummary)
        assertEquals("压缩水位被清零了", 42L, stored.summaryThroughMessageId)
    }

    @Test
    fun `改思考等级与工具开关同样不碰摘要列`() = runTest {
        seedSummary()

        // 每次保存前重新读取会话 —— 与 ChatViewModel 的三个入口一致
        val withLevel = repository.getConversation(1L)!!.copy(thinkingLevel = ThinkingLevel.HIGH)
        repository.updateConversation(withLevel)
        val withTools = repository.getConversation(1L)!!.copy(toolsEnabled = false)
        repository.updateConversation(withTools)

        val stored = dao.conversations.getValue(1L)
        assertEquals("HIGH", stored.thinkingLevel)
        assertEquals(false, stored.toolsEnabled)
        assertEquals("已压缩前 40 条的摘要", stored.contextSummary)
        assertEquals(42L, stored.summaryThroughMessageId)
    }
}
