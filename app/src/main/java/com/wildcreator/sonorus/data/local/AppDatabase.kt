package com.wildcreator.sonorus.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ProviderEntity::class,
        ModelEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        RequestStatEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun providerDao(): ProviderDao
    abstract fun modelDao(): ModelDao
    abstract fun chatDao(): ChatDao
    abstract fun requestStatDao(): RequestStatDao

    companion object {
        /**
         * v1 -> v2：conversations 加 titleAuto（区分 AI 自动命名与用户手动命名），
         * messages 加 imagesJson（多模态图片）。旧数据全量保留，已有会话默认 titleAuto=1。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE conversations ADD COLUMN titleAuto INTEGER NOT NULL DEFAULT 1"
                )
                db.execSQL("ALTER TABLE messages ADD COLUMN imagesJson TEXT")
            }
        }

        /**
         * v2 -> v3：新增 request_stats 表（请求成功率统计），
         * conversations 加 thinkingLevel（会话级思考等级覆盖，null = 跟随全局）。
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
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
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_request_stats_createdAt` ON `request_stats` (`createdAt`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_request_stats_providerId` ON `request_stats` (`providerId`)"
                )
                // 既有会话 thinkingLevel 保持 NULL，即跟随设置页全局默认
                db.execSQL("ALTER TABLE conversations ADD COLUMN thinkingLevel TEXT")
            }
        }

        /**
         * v3 -> v4：搜索优化——为 conversations 增加标题索引，提升 LIKE 查询性能。
         *
         * 注意：
         * - 不改动 schema 结构，仅添加索引；
         * - 对于已安装的大量会话用户，首次迁移可能耗时数秒，建议异步执行避免卡顿。
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_conversations_title` ON `conversations` (`title`)"
                )
            }
        }

        /**
         * v4 -> v5：messages 加 versionsJson / activeVersion（回答多版本与批注修订），
         * 推理文本不单独开列 —— 它随版本存在 versionsJson 的每个条目里，
         * 因为「换一版答案」同时意味着「换一版推理」，拆开存会错位。
         * 老数据两列都取默认值，等价于"只有一个版本、它就是当前版本"。
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN versionsJson TEXT")
                db.execSQL(
                    "ALTER TABLE messages ADD COLUMN activeVersion INTEGER NOT NULL DEFAULT 0"
                )
            }
        }
    }
}
