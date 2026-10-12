package com.wildcreator.sonorus.agent

import com.wildcreator.sonorus.domain.agent.AgentExecutor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AgentExecutor.AgentState.forConversation] 的归属过滤。
 *
 * AgentExecutor 是进程级单例、只有一份 state：回答在用户离开聊天页后继续在后台续写时，
 * 另一个会话的页面必须把这份状态看成空闲，否则会显示别人的流式文本。
 */
class AgentStateVisibilityTest {

    private val runningElsewhere = AgentExecutor.AgentState(
        running = true,
        phase = AgentExecutor.RunPhase.ANSWERING,
        conversationId = 7L,
        streamingContent = "半截回答"
    )

    @Test
    fun 别的会话的进行中状态显示为空闲() {
        val shown = runningElsewhere.forConversation(conversationId = 42L)

        assertFalse(shown.running)
        assertEquals("", shown.streamingContent)
        assertNull(shown.conversationId)
    }

    @Test
    fun 本会话的进行中状态原样保留() {
        val shown = runningElsewhere.forConversation(conversationId = 7L)

        assertTrue(shown.running)
        assertEquals("半截回答", shown.streamingContent)
        assertEquals(7L, shown.conversationId)
    }

    @Test
    fun 错误信息同样按会话隔离() {
        val failed = AgentExecutor.AgentState(conversationId = 7L, error = "上游 500")

        assertNull(failed.forConversation(42L).error)
        assertEquals("上游 500", failed.forConversation(7L).error)
    }

    @Test
    fun 归属未知的空闲状态谁都能看到() {
        // 复位后的状态（conversationId = null）不含任何内容，不需要屏蔽
        val idle = AgentExecutor.AgentState()

        assertEquals(idle, idle.forConversation(42L))
    }
}
