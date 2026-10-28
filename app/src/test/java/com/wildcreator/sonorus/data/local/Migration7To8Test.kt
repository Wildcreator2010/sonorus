package com.wildcreator.sonorus.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.sql.DriverManager

class Migration7To8Test {
    @Test
    fun 迁移增加文本附件列且保留旧消息() {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            conn.createStatement().use { st ->
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
                st.executeUpdate("INSERT INTO messages (conversationId,role,content,createdAt) VALUES (1,'user','旧消息',1)")
                st.executeUpdate(AppDatabase.MIGRATION_7_8_SQL)
                st.executeQuery("SELECT content, filesJson FROM messages").use { rs ->
                    rs.next()
                    assertEquals("旧消息", rs.getString(1))
                    assertNull(rs.getString(2))
                }
            }
        }
    }
}
