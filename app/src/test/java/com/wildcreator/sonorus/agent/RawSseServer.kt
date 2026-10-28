package com.wildcreator.sonorus.agent

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * 最小 SSE 服务：发一帧后保持连接不结束。
 * MockWebServer 的 throttleBody 会推迟首帧，测不出"取消是否立刻中断读取"。
 */
class RawSseServer(private val frame: String = FRAME) : Closeable {
    private val socket = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
    val baseUrl: String = "http://127.0.0.1:${socket.localPort}/v1"

    private val worker = thread(isDaemon = true, name = "raw-sse-server") {
        try {
            socket.accept().use { client ->
                val input = client.getInputStream().bufferedReader()
                while (true) {
                    val line = input.readLine() ?: return@use
                    if (line.isEmpty()) break
                }
                client.getOutputStream().apply {
                    write(
                        ("HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/event-stream\r\n" +
                            "Cache-Control: no-cache\r\n\r\n" + frame).toByteArray()
                    )
                    flush()
                }
                // 保持连接直到客户端取消：这里就是之前阻塞 readLine 的场景
                while (input.read() != -1) Unit
            }
        } catch (_: Exception) {
            // 端口关闭/客户端取消都是预期路径
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        const val FRAME = "data: {\"choices\":[{\"delta\":{\"content\":\"首段\"}}]}\n\n"
    }
}
