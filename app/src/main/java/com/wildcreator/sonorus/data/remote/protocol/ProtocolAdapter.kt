package com.wildcreator.sonorus.data.remote.protocol

import com.wildcreator.sonorus.domain.agent.RemoteModel
import com.wildcreator.sonorus.domain.agent.StreamDelta
import com.wildcreator.sonorus.domain.agent.UnifiedChatRequest
import com.wildcreator.sonorus.domain.model.ProviderProtocol
import okhttp3.Request

/**
 * 线协议适配器：把"统一会话模型"翻译为各家 wire format，并把各家响应翻译回统一模型。
 *
 * 新增一家协议 = 实现本接口 + 在 ProtocolAdapterFactory 注册，domain / ui 层无需改动。
 */
interface ProtocolAdapter {

    val protocol: ProviderProtocol

    /** 规整用户填写的 baseUrl（补默认路径、去尾斜杠） */
    fun normalizeBaseUrl(raw: String): String

    /** 拉取模型列表的请求 */
    fun modelsRequest(baseUrl: String, apiKey: String): Request

    /** 解析模型列表响应体 */
    fun parseModels(body: String): List<RemoteModel>

    /** 流式聊天请求 */
    fun chatRequest(baseUrl: String, apiKey: String, request: UnifiedChatRequest): Request

    /**
     * 解析一行 SSE 数据。
     * @param event SSE `event:` 字段（Anthropic 依赖它区分事件类型，OpenAI/Gemini 为 null）
     * @param data  SSE `data:` 字段的 JSON 文本
     * @return null 表示该行无需处理（心跳、未知事件、非 JSON）
     */
    fun parseStream(event: String?, data: String): StreamDelta?

    /** 网关忽略 stream=true、直接返回完整 JSON 时的兜底解析；null = 该协议不支持 */
    fun parseNonStream(body: String): StreamDelta? = null
}
