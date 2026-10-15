package com.wildcreator.sonorus.data.local

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「编辑重发」的删尾与插新必须在一次 DAO 调用里完成。
 *
 * 分两步（先 `deleteMessagesFrom` 再 `insertMessage`）会留下一个会话**没有任何消息**的空窗：
 * 此时用户退回会话列表触发 `deleteEmptyConversations`，整条会话被删；
 * 随后插入的消息因为没有外键约束，成为挂在已消失会话上的孤儿行。
 * 合成一次 @Transaction 调用后，任何观察者看到的都是"旧尾巴没了、新消息已经在"。
 */
class ChatDaoEditResendTest {

    private val dao = FakeChatDao()

    /** 会话 10：user(1) assistant(2) user(3) assistant(4)；返回会话 id 与被编辑的那条 user 消息 id */
    private fun seed(): Pair<Long, Long> {
        dao.put(ConversationEntity(id = 10L, title = "会话", providerId = 1L, modelId = "m", systemPrompt = "p"))
        dao.put(MessageEntity(id = 1L, conversationId = 10L, role = "user", content = "第一问"))
        dao.put(MessageEntity(id = 2L, conversationId = 10L, role = "assistant", content = "第一答"))
        val edited = dao.put(MessageEntity(id = 3L, conversationId = 10L, role = "user", content = "第二问"))
        dao.put(MessageEntity(id = 4L, conversationId = 10L, role = "assistant", content = "第二答"))
        return 10L to edited.id
    }

    private fun newUser(conversationId: Long, text: String) =
        MessageEntity(conversationId = conversationId, role = "user", content = text)

    @Test
    fun `一次调用内删尾并插入新消息`() = runTest {
        val (conversationId, anchorId) = seed()

        val newId = dao.replaceTailWithUserMessage(conversationId, anchorId, newUser(conversationId, "改过的第二问"))

        assertNotNull("新消息必须已在同一次调用里落库", dao.getMessage(newId))
        assertEquals("改过的第二问", dao.getMessage(newId)?.content)
        assertEquals(listOf(1L, 2L, newId), dao.getMessages(conversationId).map { it.id })
    }

    @Test
    fun `替换尾巴之后清理空会话不会碰到这条会话`() = runTest {
        val (conversationId, anchorId) = seed()

        dao.replaceTailWithUserMessage(conversationId, anchorId, newUser(conversationId, "改过的第二问"))
        val purged = dao.deleteEmptyConversations()

        assertEquals(0, purged)
        assertNotNull(dao.getConversation(conversationId))
    }

    @Test
    fun `fromId 取上界时等价于纯插入不删任何行`() = runTest {
        val (conversationId, _) = seed()

        val newId = dao.replaceTailWithUserMessage(
            conversationId, Long.MAX_VALUE, newUser(conversationId, "普通发送")
        )

        assertEquals("一条都不该被删掉", 5, dao.countMessages(conversationId))
        assertEquals(listOf(1L, 2L, 3L, 4L, newId), dao.getMessages(conversationId).map { it.id })
        assertEquals("普通发送", dao.getMessage(newId)?.content)
    }

    @Test
    fun `只删锚点及其之后的行锚点之前原样保留`() = runTest {
        val (conversationId, anchorId) = seed()

        dao.replaceTailWithUserMessage(conversationId, anchorId, newUser(conversationId, "新问"))

        assertEquals("第一问", dao.getMessage(1L)?.content)
        assertEquals("第一答", dao.getMessage(2L)?.content)
        assertNull(dao.getMessage(3L))
        assertNull(dao.getMessage(4L))
    }

    @Test
    fun `编辑最后一条提问时会话仍有消息不会被清掉`() = runTest {
        dao.put(ConversationEntity(id = 11L, title = "单轮会话", providerId = 1L, modelId = "m", systemPrompt = "p"))
        val only = dao.put(MessageEntity(id = 5L, conversationId = 11L, role = "user", content = "唯一一问"))

        dao.replaceTailWithUserMessage(11L, only.id, newUser(11L, "改过的一问"))
        val purged = dao.deleteEmptyConversations()

        assertEquals("会话不该在编辑重发后被清理掉", 0, purged)
        assertEquals(1, dao.countMessages(11L))
    }
}
