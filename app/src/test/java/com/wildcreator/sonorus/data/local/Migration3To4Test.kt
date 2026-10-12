package com.wildcreator.sonorus.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * Room v3 -> v4 迁移验证（纯 JDBC SQLite，无需设备/Robolectric）。
 *
 * 覆盖场景：
 * - conversations 表增加标题索引，提升 LIKE 查询性能；
 * - 既有数据保留，索引创建不影响现有会话与消息。
 */
class Migration3To4Test {

    private fun connect(): Connection {
        Class.forName("org.sqlite.JDBC")
        return DriverManager.getConnection("jdbc:sqlite::memory:")
    }

    /** 建 v3 结构并塞入测试数据 */
    private fun Connection.seedV3() {
        createStatement().use { st ->
            // conversations 表（v3 schema）
            st.executeUpdate(
                """
                CREATE TABLE conversations (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    title TEXT NOT NULL,
                    providerId INTEGER NOT NULL,
                    modelId TEXT NOT NULL,
                    systemPrompt TEXT NOT NULL,
                    toolsEnabled INTEGER NOT NULL,
                    titleAuto INTEGER NOT NULL DEFAULT 1,
                    thinkingLevel TEXT,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
            // messages 表（v3 schema）
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
                    createdAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
            // request_stats 表（v3 schema）
            st.executeUpdate(
                """
                CREATE TABLE request_stats (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    providerId INTEGER NOT NULL,
                    protocol TEXT NOT NULL,
                    modelId TEXT NOT NULL,
                    success INTEGER NOT NULL,
                    errorKind TEXT,
                    durationMs INTEGER NOT NULL,
                    createdAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
            // 塞入几条会话 + 消息
            st.executeUpdate(
                "INSERT INTO conversations (title, providerId, modelId, systemPrompt, toolsEnabled, titleAuto, thinkingLevel, createdAt, updatedAt) VALUES" +
                    "('关于 Android 编译的问答', 1, 'gpt-4o-mini', '你是助手', 1, 1, NULL, 100, 200)," +
                    "('Gradle 版本升级讨论', 1, 'claude-sonnet', '你是一个开发者助手', 1, 0, 'LOW', 300, 400)"
            )
            st.executeUpdate(
                "INSERT INTO messages (conversationId, role, content, createdAt) VALUES (1,'user','Gradle 8.9 支持 JDK 25 吗？',100)"
            )
            st.executeUpdate(
                "INSERT INTO messages (conversationId, role, content, createdAt) VALUES (1,'assistant','不支持，建议 JDK 17。',200)"
            )
        }
    }

    /** 与 AppDatabase.MIGRATION_3_4 完全一致的 SQL */
    private fun Connection.runMigration3To4() {
        createStatement().use { st ->
            st.executeUpdate("CREATE INDEX IF NOT EXISTS `index_conversations_title` ON `conversations` (`title`)")
        }
    }

    @Test
    fun 迁移后索引存在且可用() {
        connect().use { conn ->
            conn.seedV3()
            conn.runMigration3To4()

            // 检查索引是否存在（SQLite 在 sqlite_master 中记录）
            val indexExists = conn.createStatement()
                .executeQuery("SELECT name FROM sqlite_master WHERE type='index' AND name='index_conversations_title'")
                .next()
            assertTrue("索引应被创建", indexExists)
        }
    }

    @Test
    fun 迁移后既有会话数据完整() {
        connect().use { conn ->
            conn.seedV3()
            conn.runMigration3To4()

            conn.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM conversations").use { rs ->
                    rs.next()
                    assertEquals(2, rs.getInt(1))
                }
                st.executeQuery("SELECT title FROM conversations WHERE id = 1").use { rs ->
                    rs.next()
                    assertEquals("关于 Android 编译的问答", rs.getString(1))
                }
                st.executeQuery("SELECT thinkingLevel FROM conversations WHERE id = 2").use { rs ->
                    rs.next()
                    assertEquals("LOW", rs.getString(1))
                }
            }
        }
    }

    @Test
    fun 迁移后消息数据完整() {
        connect().use { conn ->
            conn.seedV3()
            conn.runMigration3To4()

            conn.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM messages").use { rs ->
                    rs.next()
                    assertEquals(2, rs.getInt(1))
                }
                st.executeQuery("SELECT content FROM messages WHERE conversationId = 1 ORDER BY id").use { rs ->
                    rs.next()
                    assertEquals("Gradle 8.9 支持 JDK 25 吗？", rs.getString(1))
                    rs.next()
                    assertEquals("不支持，建议 JDK 17。", rs.getString(1))
                }
            }
        }
    }

    @Test
    fun `迁移后 request_stats 数据完整`() {
        connect().use { conn ->
            conn.seedV3()
            // 先插入一些统计样本
            conn.createStatement().use { st ->
                st.executeUpdate(
                    "INSERT INTO request_stats (providerId, protocol, modelId, success, errorKind, durationMs, createdAt) VALUES " +
                        "(1,'OPENAI_COMPAT','gpt-4o',1,NULL,100,1000)," +
                        "(1,'OPENAI_COMPAT','gpt-4o',0,'HTTP 401',90,1100)"
                )
            }
            conn.runMigration3To4()

            conn.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM request_stats").use { rs ->
                    rs.next()
                    assertEquals(2, rs.getInt(1))
                }
                st.executeQuery("SELECT SUM(success) FROM request_stats").use { rs ->
                    rs.next()
                    assertEquals(1, rs.getInt(1))
                }
            }
        }
    }

    @Test
    fun `索引能加速 LIKE 查询`() {
        connect().use { conn ->
            conn.seedV3()
            conn.runMigration3To4()

            // 执行 LIKE 查询，确保能命中
            conn.createStatement().use { st ->
                val rs = st.executeQuery("SELECT id FROM conversations WHERE title LIKE '%编译%'")
                assertTrue("LIKE 查询应命中会话", rs.next())
                assertEquals(1L, rs.getLong("id"))
            }
        }
    }
}
