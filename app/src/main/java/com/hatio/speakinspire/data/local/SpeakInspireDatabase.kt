package com.hatio.speakinspire.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        NoteEntity::class,
        DeepeningEntity::class,
        ArticleEntity::class,
        ScriptEntity::class,
        TrashedEntity::class,
        ProviderEntity::class,
        SproutEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class SpeakInspireDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun deepeningDao(): DeepeningDao
    abstract fun articleDao(): ArticleDao
    abstract fun scriptDao(): ScriptDao
    abstract fun providerDao(): ProviderDao
    abstract fun sproutDao(): SproutDao

    companion object {
        const val NAME = "speakinspire.db"

        /** 回收站保留期：30 天后自动清除 */
        const val TRASH_RETENTION_MS = 30L * 24 * 60 * 60 * 1000

        /**
         * v1 → v2：provider 表主键从单列改成 (providerId, model) 复合键。
         *
         * 为什么要改：同一个服务商下要能存多个模型（flash / reasoner /自定义名），
         * 单列主键会让后存的覆盖先存的。
         *
         * 数据要不要保留？**不保留。**
         * provider 表里只有服务商配置（地址、模型名、开关），
         * **API Key 不在这里**——它在 Tink 加密的 SharedPreferences 里。
         * 所以重建这张表只会丢掉「选了哪个服务商」这种可重建的状态，
         * 用户重新点一下即可；key 不会丢。
         *
         * 用重建而不是 ALTER TABLE：SQLite 不支持直接改主键，
         * 只能删表重建。既然数据可重建，就不写迁移 SQL。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS provider")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS provider (
                        providerId TEXT NOT NULL,
                        baseUrl TEXT NOT NULL,
                        model TEXT NOT NULL,
                        thinkingDisabled INTEGER NOT NULL,
                        active INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(providerId, model)
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * v2 → v3：新增发芽报告表。
         *
         * 纯增表，不动任何既有数据——这是加字段/加表时唯一该走的姿势。
         * `fallbackToDestructiveMigration` 在这里等于「用户升级一次，
         * 思考库清空」，所以宁可写 40 行 SQL。
         *
         * 列顺序、类型、NOT NULL 必须与 [SproutEntity] 完全一致，
         * 否则 Room 启动时的 schema 校验会直接抛
         * `Migration didn't properly handle`。
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS sprout (
                        id TEXT NOT NULL,
                        noteId TEXT NOT NULL,
                        title TEXT NOT NULL,
                        sectionsJson TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        PRIMARY KEY(id),
                        FOREIGN KEY(noteId) REFERENCES note(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sprout_noteId ON sprout(noteId)")
            }
        }

        val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
    }
}