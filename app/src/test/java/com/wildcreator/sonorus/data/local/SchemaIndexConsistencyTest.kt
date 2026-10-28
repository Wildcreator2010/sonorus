package com.wildcreator.sonorus.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 迁移侧索引 SQL 的自检：索引名必须符合 Room 的 `index_<表名>_<列名>` 约定，
 * 且 MIGRATION_3_4 与 MIGRATION_5_6 必须是同一条语句（幂等，两个入口不能漂移出两种名字）。
 *
 * **这条测试覆盖不到的部分要说清楚**：真正的事故是"迁移建了索引、实体却没声明 `indices`"，
 * 而 `@Entity` / `@Database` 是 class 保留的注解，JVM 单元测试里 `getAnnotation` 恒为 null，
 * 想在单元测试里比对实体声明根本做不到。能真正锁死这类漂移的做法只有两种：
 *   1. `exportSchema = true` 并把 schema JSON 提交进仓库，用一份 JSON 对账迁移 SQL；
 *   2. 设备/Robolectric 上跑一次真实升级（Room 的 TableInfo 校验会当场报错）。
 * 两者都还没做，见项目记忆里的待办。
 */
class SchemaIndexConsistencyTest {

    private data class CreatedIndex(val name: String, val table: String, val column: String)

    private fun parse(sql: String): CreatedIndex {
        val m = Regex(
            "CREATE INDEX (?:IF NOT EXISTS )?`([^`]+)` ON `([^`]+)` \\(`([^`]+)`\\)"
        ).find(sql) ?: error("这段 SQL 不是预期的 CREATE INDEX 形态：$sql")
        return CreatedIndex(m.groupValues[1], m.groupValues[2], m.groupValues[3])
    }

    @Test
    fun 标题索引名符合Room的自动命名约定() {
        val created = parse(AppDatabase.CREATE_INDEX_CONVERSATIONS_TITLE)

        assertEquals("index_${created.table}_${created.column}", created.name)
        assertEquals("conversations", created.table)
        assertEquals("title", created.column)
    }

    @Test
    fun 迁移链从1连续覆盖到当前版本() {
        // 链路断一档，跨档升级的用户就会撞上 "Missing migration"；Room 不会替你猜
        val migrations = listOf(
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_4_5,
            AppDatabase.MIGRATION_5_6,
            AppDatabase.MIGRATION_6_7,
            AppDatabase.MIGRATION_7_8
        )
        var expectedStart = 1

        migrations.forEach { migration ->
            assertEquals("迁移链在 v$expectedStart 之前断开", expectedStart, migration.startVersion)
            expectedStart = migration.endVersion
        }
        assertEquals(8, expectedStart)
    }

    @Test
    fun 统计索引名符合约定且列存在() {
        val createdAt = parse(AppDatabase.CREATE_INDEX_REQUEST_STATS_CREATED_AT)
        val providerId = parse(AppDatabase.CREATE_INDEX_REQUEST_STATS_PROVIDER_ID)

        for (created in listOf(createdAt, providerId)) {
            assertEquals("request_stats", created.table)
            assertEquals("index_${created.table}_${created.column}", created.name)
        }
        assertEquals("createdAt", createdAt.column)
        assertEquals("providerId", providerId.column)
    }
}
