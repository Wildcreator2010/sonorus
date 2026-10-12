package com.wildcreator.sonorus.protocol

import com.wildcreator.sonorus.bodyJson
import com.wildcreator.sonorus.data.remote.protocol.AnthropicAdapter
import com.wildcreator.sonorus.domain.agent.UnifiedChatRequest
import com.wildcreator.sonorus.domain.agent.UnifiedMessage
import com.wildcreator.sonorus.domain.agent.UnifiedRole
import com.wildcreator.sonorus.testJson
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Anthropic 历史自愈：连续同角色消息必须被合并。
 *
 * 场景很常见 —— 用户消息总是先落库再发请求，所以任何一次失败（401、断网、4xx）
 * 都会在库里留下一条没有配对的用户消息。下一次发送就是 `[USER, USER]`，
 * Anthropic 以 "roles must alternate" 直接 400；那条坏行不修就永远发不出去，
 * **整个会话从此卡死**（regenerate / 编辑重发因为会删尾部消息才能自愈）。
 */
class AnthropicHistoryRepairTest {

    private val adapter = AnthropicAdapter(testJson)

    private fun rolesOf(messages: List<UnifiedMessage>): List<String> {
        val payload = adapter.chatRequest(
            "https://api.anthropic.com/v1",
            "sk-ant",
            UnifiedChatRequest(model = "claude-sonnet-4", systemPrompt = "你是助手", messages = messages)
        ).bodyJson()

        return payload["messages"]!!.jsonArray.map { element ->
            element.jsonObject["role"]!!.jsonPrimitive.content
        }
    }

    private fun user(text: String) = UnifiedMessage(role = UnifiedRole.USER, text = text)
    private fun assistant(text: String) = UnifiedMessage(role = UnifiedRole.ASSISTANT, text = text)

    @Test
    fun 连续两条用户消息被合并成一条() {
        assertEquals(
            listOf("user"),
            rolesOf(listOf(user("第一次尝试"), user("重试的那次")))
        )
    }

    @Test
    fun 三条连续用户消息也只留一条() {
        assertEquals(
            listOf("user"),
            rolesOf(listOf(user("a"), user("b"), user("c")))
        )
    }

    @Test
    fun 正常的交替历史不被改动() {
        assertEquals(
            listOf("user", "assistant", "user"),
            rolesOf(listOf(user("问"), assistant("答"), user("再问")))
        )
    }

    @Test
    fun 失败后重试的历史不再触发交替错误() {
        // 真实事故形状：上一轮失败留下两条 user，其后才是正常的交替
        val roles = rolesOf(
            listOf(user("首发"), user("重试"), assistant("回答"), user("继续"))
        )

        // 逐对检查：不允许出现相邻同角色
        roles.zipWithNext().forEach { (left, right) ->
            assertEquals("相邻消息角色必须交替：$roles", true, left != right)
        }
    }

    @Test
    fun 合并后两个文本块都还在没有丢内容() {
        val payload = adapter.chatRequest(
            "https://api.anthropic.com/v1",
            "sk-ant",
            UnifiedChatRequest(
                model = "claude-sonnet-4",
                systemPrompt = "你是助手",
                messages = listOf(user("第一次尝试"), user("重试的那次"))
            )
        ).bodyJson()

        val blocks = payload["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray
        val texts = blocks.map { it.jsonObject["text"]!!.jsonPrimitive.content }

        // 合并是"拼块"而不是"取最后一条"，用户写过的字不能凭空消失
        assertEquals(listOf("第一次尝试", "重试的那次"), texts)
    }
}
