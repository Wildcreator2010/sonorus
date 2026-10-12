package com.wildcreator.sonorus.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * v4 -> v5 迁移：messages 加 versionsJson / activeVersion（回答多版本与批注修订）。
 *
 * 关键风险是老数据必须解读为"只有一个版本，且它就是当前版本"：
 * activeVersion 默认值一旦不是 0，升级后所有历史回答都会指向一个不存在的版本。
 */
class Migration4To5Test {

    private fun connect(): Connection {
        Class.forName("org.sqlite.JDBC")
        return DriverManager.getConnection("jdbc:sqlite::memory:")
    }

    /** v4 的 messages 表结构（含 v2 的 imagesJson） */
    private fun Connection.seedV4() {
        createStatement().use { st ->
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
            st.executeUpdate(
                "INSERT INTO messages (conversationId, role, content, createdAt) VALUES (1,'user','你好',100)"
            )
            st.executeUpdate(
                "INSERT INTO messages (conversationId, role, content, createdAt) VALUES (1,'assistant','在的',200)"
            )
        }
    }

    /** 与 AppDatabase.MIGRATION_4_5 完全一致的 SQL */
    private fun Connection.runMigration4To5() {
        createStatement().use { st ->
            st.executeUpdate("ALTER TABLE messages ADD COLUMN versionsJson TEXT")
            st.executeUpdate("ALTER TABLE messages ADD COLUMN activeVersion INTEGER NOT NULL DEFAULT 0")
        }
    }

    @Test
    fun 迁移保留既有消息() {
        connect().use { conn ->
            conn.seedV4()
            conn.runMigration4To5()
            conn.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM messages").use {
                    it.next()
                    assertEquals(2, it.getInt(1))
                }
            }
        }
    }

    @Test
    fun 老数据解读为单版本当前版() {
        connect().use { conn ->
            conn.seedV4()
            conn.runMigration4To5()
            conn.createStatement().use { st ->
                st.executeQuery(
                    "SELECT content, versionsJson, activeVersion FROM messages WHERE role = 'assistant'"
                ).use { rs ->
                    assertTrue(rs.next())
                    assertEquals("在的", rs.getString(1))
                    assertNull("单版本不写 JSON", rs.getString(2))
                    assertEquals(0, rs.getInt(3))
                    // 与实体的 toDomain 逻辑一致：versionsJson 为空即回退成单版本
                    val versions = com.wildcreator.sonorus.domain.model.MessageVariants.decode(
                        rs.getString(2),
                        com.wildcreator.sonorus.domain.model.AnswerVersion(content = rs.getString(1))
                    )
                    assertEquals(1, versions.size)
                    assertEquals("在的", versions[rs.getInt(3)].content)
                }
            }
        }
    }

    @Test
    fun 写入多版本后能读回() {
        connect().use { conn ->
            conn.seedV4()
            conn.runMigration4To5()
            val versions = listOf(
                com.wildcreator.sonorus.domain.model.AnswerVersion(content = "第一版", createdAt = 1L),
                com.wildcreator.sonorus.domain.model.AnswerVersion(content = "第二版", note = "太长了", createdAt = 2L)
            )
            val (mirror, json, active) =
                com.wildcreator.sonorus.domain.model.MessageVariants.entry(versions, 1)
            conn.prepareStatement(
                "UPDATE messages SET content = ?, versionsJson = ?, activeVersion = ? WHERE role = 'assistant'"
            ).use { ps ->
                ps.setString(1, mirror)
                ps.setString(2, json)
                ps.setInt(3, active)
                assertEquals(1, ps.executeUpdate())
            }
            conn.createStatement().use { st ->
                st.executeQuery("SELECT content, versionsJson, activeVersion FROM messages WHERE role = 'assistant'").use { rs ->
                    rs.next()
                    assertEquals("第二版", rs.getString(1))
                    assertEquals(1, rs.getInt(3))
                    val decoded = com.wildcreator.sonorus.domain.model.MessageVariants.decode(
                        rs.getString(2),
                        com.wildcreator.sonorus.domain.model.AnswerVersion(content = rs.getString(1))
                    )
                    assertEquals(2, decoded.size)
                    assertEquals("太长了", decoded[1].note)
                }
            }
        }
    }
}
