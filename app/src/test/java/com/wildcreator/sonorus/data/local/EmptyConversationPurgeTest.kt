package com.wildcreator.sonorus.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * 「自动删除空对话」的 SQL 语义验证（纯 JDBC SQLite，无需设备/Robolectric）。
 *
 * [PURGE_SQL] 与 ChatDao.deleteEmptyConversations 的语句逐字一致 —— 改 DAO 里的 SQL 必须同步改这里。
 */
class EmptyConversationPurgeTest {

    private fun connect(): Connection {
        Class.forName("org.sqlite.JDBC")
        return DriverManager.getConnection("jdbc:sqlite::memory:")
    }

    private fun Connection.createSchema() {
        createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE conversations (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    title TEXT NOT NULL,
                    titleAuto INTEGER NOT NULL DEFAULT 1,
                    providerId INTEGER NOT NULL,
                    modelId TEXT NOT NULL,
                    systemPrompt TEXT NOT NULL,
                    toolsEnabled INTEGER NOT NULL,
                    thinkingLevel TEXT,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
            st.executeUpdate(
                """
                CREATE TABLE messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    conversationId INTEGER NOT NULL,
                    role TEXT NOT NULL,
                    content TEXT NOT NULL,
                    imagesJson TEXT,
                    toolCallsJson TEXT,
                    toolCallId TEXT,
                    toolName TEXT,
                    versionsJson TEXT,
                    activeVersion INTEGER NOT NULL DEFAULT 0,
                    createdAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    private fun Connection.addConversation(title: String, titleAuto: Int = 1): Long =
        createStatement().use { st ->
            st.executeUpdate(
                "INSERT INTO conversations (title, titleAuto, providerId, modelId, systemPrompt, toolsEnabled, createdAt, updatedAt) " +
                    "VALUES ('$title', $titleAuto, 1, 'gpt-4o-mini', '你是助手', 1, 100, 200)"
            )
            st.generatedKeys.use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }

    private fun Connection.addMessage(conversationId: Long, role: String, content: String) {
        createStatement().use { st ->
            st.executeUpdate(
                "INSERT INTO messages (conversationId, role, content, createdAt) VALUES ($conversationId, '$role', '$content', 300)"
            )
        }
    }

    private fun Connection.purge(): Int = createStatement().use { it.executeUpdate(PURGE_SQL) }

    private fun Connection.titleOf(id: Long): String? =
        createStatement().use { st ->
            st.executeQuery("SELECT title FROM conversations WHERE id = $id").use { rs ->
                if (rs.next()) rs.getString(1) else null
            }
        }

    private fun Connection.countConversations(): Int =
        createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM conversations").use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }

    private fun Connection.countMessagesOf(conversationId: Long): Int =
        createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM messages WHERE conversationId = $conversationId")
                .use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
        }

    @Test
    fun 只删除没有任何消息的会话() {
        connect().use { conn ->
            conn.createSchema()
            val abandoned = conn.addConversation("新会话")
            val chat = conn.addConversation("关于 Gradle 的问答")
            conn.addMessage(chat, "user", "Gradle 8.9 支持 JDK 25 吗？")
            conn.addMessage(chat, "assistant", "不支持，建议 JDK 17。")

            assertEquals(1, conn.purge())
            assertNull("空会话应被删除", conn.titleOf(abandoned))
            assertEquals("关于 Gradle 的问答", conn.titleOf(chat))
        }
    }

    @Test
    fun 消息表全空时清空所有会话() {
        connect().use { conn ->
            conn.createSchema()
            repeat(3) { conn.addConversation("新会话") }

            // 子查询返回空集时 NOT IN 恒真：所有会话都没有消息，全部删除
            assertEquals(3, conn.purge())
            assertEquals(0, conn.countConversations())
        }
    }

    @Test
    fun 清理不会动任何消息行() {
        connect().use { conn ->
            conn.createSchema()
            conn.addConversation("待清理")
            val kept = conn.addConversation("留下的会话")
            conn.addMessage(kept, "user", "第一条")
            conn.addMessage(kept, "assistant", "第二条")

            conn.purge()

            assertEquals(2, conn.countMessagesOf(kept))
        }
    }

    @Test
    fun 用户手动命名过的空会话同样被删() {
        connect().use { conn ->
            conn.createSchema()
            // titleAuto = 0 表示用户改过名字；"空了就删"不给豁免
            val renamed = conn.addConversation("我起的名字", titleAuto = 0)

            assertEquals(1, conn.purge())
            assertEquals(0, conn.countConversations())
            assertNull(conn.titleOf(renamed))
        }
    }

    @Test
    fun 重复清理是幂等的() {
        connect().use { conn ->
            conn.createSchema()
            conn.addConversation("新会话")
            val chat = conn.addConversation("有内容的会话")
            conn.addMessage(chat, "user", "还在生成中")

            assertEquals(1, conn.purge())
            assertEquals(0, conn.purge())
            assertEquals(1, conn.countConversations())
        }
    }

    private companion object {
        const val PURGE_SQL =
            "DELETE FROM conversations WHERE id NOT IN " +
                "(SELECT DISTINCT conversationId FROM messages)"
    }
}
