package com.wildcreator.sonorus.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * Room v1 -> v2 迁移验证（用纯 JDBC SQLite 执行迁移 SQL，不需要设备/Robolectric）。
 *
 * 覆盖两个真实风险点：
 * 1) 既有数据必须保留（不能因加列而丢会话/消息）；
 * 2) 新增列默认值正确 —— titleAuto 必须默认 1，
 *    否则既有会话会被判定为"用户已手动命名"，AI 自动命名永久失效。
 */
class MigrationTest {

    private fun connect(): Connection {
        Class.forName("org.sqlite.JDBC")
        return DriverManager.getConnection("jdbc:sqlite::memory:")
    }

    /** 建 v1 结构并塞入一条会话 + 两条消息 */
    private fun Connection.seedV1() {
        createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE conversations (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    title TEXT NOT NULL,
                    providerId INTEGER NOT NULL,
                    modelId TEXT NOT NULL,
                    systemPrompt TEXT NOT NULL,
                    toolsEnabled INTEGER NOT NULL,
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
                    toolCallsJson TEXT,
                    toolCallId TEXT,
                    toolName TEXT,
                    createdAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
            st.executeUpdate(
                "INSERT INTO conversations (title, providerId, modelId, systemPrompt, toolsEnabled, createdAt, updatedAt) " +
                    "VALUES ('历史会话', 1, 'gpt-4o', '你是助手', 1, 100, 200)"
            )
            st.executeUpdate(
                "INSERT INTO messages (conversationId, role, content, createdAt) VALUES (1, 'user', '你好', 100)"
            )
            st.executeUpdate(
                "INSERT INTO messages (conversationId, role, content, createdAt) VALUES (1, 'assistant', '在的', 200)"
            )
        }
    }

    /** 与 AppDatabase.MIGRATION_1_2 完全一致的 SQL */
    private fun Connection.runMigration1To2() {
        createStatement().use { st ->
            st.executeUpdate("ALTER TABLE conversations ADD COLUMN titleAuto INTEGER NOT NULL DEFAULT 1")
            st.executeUpdate("ALTER TABLE messages ADD COLUMN imagesJson TEXT")
        }
    }

    @Test
    fun 迁移保留既有会话与消息() {
        connect().use { conn ->
            conn.seedV1()
            conn.runMigration1To2()

            conn.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM conversations").use {
                    it.next()
                    assertEquals(1, it.getInt(1))
                }
                st.executeQuery("SELECT COUNT(*) FROM messages").use {
                    it.next()
                    assertEquals(2, it.getInt(1))
                }
                st.executeQuery("SELECT title FROM conversations WHERE id = 1").use {
                    it.next()
                    assertEquals("历史会话", it.getString(1))
                }
            }
        }
    }

    @Test
    fun 既有会话的titleAuto默认为true() {
        connect().use { conn ->
            conn.seedV1()
            conn.runMigration1To2()

            conn.createStatement().use { st ->
                st.executeQuery("SELECT titleAuto FROM conversations WHERE id = 1").use {
                    it.next()
                    // 1 = true：老会话仍可被 AI 自动命名，不会被误判为手动命名
                    assertEquals(1, it.getInt(1))
                }
            }
        }
    }

    @Test
    fun 既有消息的imagesJson为null() {
        connect().use { conn ->
            conn.seedV1()
            conn.runMigration1To2()

            conn.createStatement().use { st ->
                st.executeQuery("SELECT imagesJson FROM messages ORDER BY id").use {
                    it.next()
                    it.getString(1).let { v -> assertTrue(v == null) }
                }
            }
        }
    }

    @Test
    fun 迁移后新插入的图片字段可正常读写() {
        connect().use { conn ->
            conn.seedV1()
            conn.runMigration1To2()

            val json = """[{"mimeType":"image/png","base64":"AAAA"}]"""
            conn.prepareStatement(
                "INSERT INTO messages (conversationId, role, content, imagesJson, createdAt) VALUES (1,'user','看图',?,300)"
            ).use { ps ->
                ps.setString(1, json)
                ps.executeUpdate()
            }

            conn.prepareStatement("SELECT imagesJson FROM messages WHERE createdAt = 300").use { ps ->
                ps.executeQuery().use {
                    it.next()
                    assertEquals(json, it.getString(1))
                }
            }
        }
    }

    // ---------- v2 -> v3 ----------

    /** 与 AppDatabase.MIGRATION_2_3 完全一致的 SQL */
    private fun Connection.runMigration2To3() {
        createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS `request_stats` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `providerId` INTEGER NOT NULL,
                    `protocol` TEXT NOT NULL,
                    `modelId` TEXT NOT NULL,
                    `success` INTEGER NOT NULL,
                    `errorKind` TEXT,
                    `durationMs` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            st.executeUpdate(
                "CREATE INDEX IF NOT EXISTS `index_request_stats_createdAt` ON `request_stats` (`createdAt`)"
            )
            st.executeUpdate(
                "CREATE INDEX IF NOT EXISTS `index_request_stats_providerId` ON `request_stats` (`providerId`)"
            )
            st.executeUpdate("ALTER TABLE conversations ADD COLUMN thinkingLevel TEXT")
        }
    }

    /** 建到 v2 状态（v1 结构 + 1->2 迁移） */
    private fun Connection.seedV2() {
        seedV1()
        runMigration1To2()
    }

    @Test
    fun 迁移到v3后request_stats表可用且既有数据保留() {
        connect().use { conn ->
            conn.seedV2()
            conn.runMigration2To3()

            conn.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM conversations").use {
                    it.next()
                    assertEquals(1, it.getInt(1))
                }
                st.executeQuery("SELECT COUNT(*) FROM messages").use {
                    it.next()
                    assertEquals(2, it.getInt(1))
                }
                // 新表存在且为空
                st.executeQuery("SELECT COUNT(*) FROM request_stats").use {
                    it.next()
                    assertEquals(0, it.getInt(1))
                }
            }
        }
    }

    @Test
    fun 既有会话的thinkingLevel为null表示跟随全局() {
        connect().use { conn ->
            conn.seedV2()
            conn.runMigration2To3()

            conn.createStatement().use { st ->
                st.executeQuery("SELECT thinkingLevel FROM conversations WHERE id = 1").use {
                    it.next()
                    val value = it.getString(1)
                    assertTrue("老会话必须为 NULL 才能跟随全局默认", value == null)
                }
            }
        }
    }

    @Test
    fun 统计表能正确聚合成功率() {
        connect().use { conn ->
            conn.seedV2()
            conn.runMigration2To3()

            conn.createStatement().use { st ->
                // 4 次成功、1 次失败
                st.executeUpdate(
                    "INSERT INTO request_stats (providerId, protocol, modelId, success, errorKind, durationMs, createdAt) VALUES " +
                        "(1,'OPENAI_COMPAT','gpt-4o',1,NULL,100,1000)," +
                        "(1,'OPENAI_COMPAT','gpt-4o',1,NULL,110,1100)," +
                        "(1,'OPENAI_COMPAT','gpt-4o',0,'HTTP 401',90,1200)," +
                        "(2,'ANTHROPIC','claude-sonnet-4-20250514',1,NULL,200,1300)," +
                        "(2,'ANTHROPIC','claude-sonnet-4-20250514',1,NULL,210,1400)"
                )
                st.executeQuery(
                    "SELECT COUNT(*) AS total, " +
                        "SUM(CASE WHEN success = 1 THEN 1 ELSE 0 END) AS successes " +
                        "FROM request_stats WHERE createdAt >= 0"
                ).use {
                    it.next()
                    assertEquals(5, it.getInt("total"))
                    assertEquals(4, it.getInt("successes"))
                }
                // 按提供商分组：provider 1 共 3 次、成功 2 次
                st.executeQuery(
                    "SELECT providerId, COUNT(*) AS total, " +
                        "SUM(CASE WHEN success = 1 THEN 1 ELSE 0 END) AS successes " +
                        "FROM request_stats GROUP BY providerId ORDER BY providerId"
                ).use {
                    it.next()
                    assertEquals(1L, it.getLong("providerId"))
                    assertEquals(3, it.getInt("total"))
                    assertEquals(2, it.getInt("successes"))
                    it.next()
                    assertEquals(2L, it.getLong("providerId"))
                    assertEquals(2, it.getInt("total"))
                    assertEquals(2, it.getInt("successes"))
                }
            }
        }
    }

    @Test
    fun 时间窗过滤只统计重置之后的请求() {
        connect().use { conn ->
            conn.seedV2()
            conn.runMigration2To3()

            conn.createStatement().use { st ->
                st.executeUpdate(
                    "INSERT INTO request_stats (providerId, protocol, modelId, success, errorKind, durationMs, createdAt) VALUES " +
                        "(1,'OPENAI_COMPAT','m',1,NULL,1,100)," +
                        "(1,'OPENAI_COMPAT','m',0,'boom',1,200)," +
                        "(1,'OPENAI_COMPAT','m',1,NULL,1,5000)"
                )
                // 模拟用户在第 1000 ms 重置统计
                st.executeQuery(
                    "SELECT COUNT(*) AS total, " +
                        "SUM(CASE WHEN success = 1 THEN 1 ELSE 0 END) AS successes " +
                        "FROM request_stats WHERE createdAt >= 1000"
                ).use {
                    it.next()
                    assertEquals(1, it.getInt("total"))
                    assertEquals(1, it.getInt("successes"))
                }
            }
        }
    }
}
