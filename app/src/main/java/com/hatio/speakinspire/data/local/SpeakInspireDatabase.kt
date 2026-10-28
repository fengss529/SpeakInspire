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
    version = 4,
    exportSchema = true,
)
abstract class SpeakInspireDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun deepeningDao(): DeepeningDao
    abstract fun articleDao(): ArticleDao
    abstract fun scriptDao(): ScriptDao
    abstract fun providerDao(): ProviderDao
    abstract fun sproutDao(): SproutDao
    abstract fun trashedDao(): TrashedDao

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

        /**
         * v3 → v4：三种资源解耦。
         *
         * article 去外键（改存 sourceNoteId + sourceText 快照），
         * script 去外键（改存 sourceArticleId + sourceTitle 快照），
         * trashed 从「只能装记录」扩成「能装三种资源」。
         *
         * ## ⚠️ 三条顺序不能换
         *
         * SQLite 的 `DROP TABLE` 在开启外键时**会触发级联动作**。
         * 若先重建 article，`DROP TABLE article` 会把 script 全部删掉，
         * 而且**不报任何错**——用户会以为选题凭空消失。
         *
         * 所以必须：
         * 1. 先重建 script（此时它已不指向 article）
         * 2. 再重建 article（此时已无子表指向它）
         * 3. 最后 trashed
         *
         * ## 列类型必须与实体完全一致
         *
         * Room 在迁移后会跑 `validateMigration` 逐列比对（名字、类型、NOT NULL、主键、索引）。
         * 少一个索引、把 INTEGER 写成 TEXT，启动时直接抛
         * `Migration didn't properly handle`。
         * Boolean → INTEGER，Long → INTEGER。
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // ① script：去掉指向 article 的外键
                db.execSQL(
                    """
                    CREATE TABLE script_new (
                        id TEXT NOT NULL,
                        sourceArticleId TEXT,
                        sourceTitle TEXT NOT NULL,
                        angle TEXT NOT NULL,
                        rationale TEXT NOT NULL,
                        sectionsJson TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        shotAt INTEGER,
                        parked INTEGER NOT NULL,
                        note TEXT NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent(),
                )
                // 外键还在时把标题快照带过去——这一步之后再 join 就查不到了
                db.execSQL(
                    """
                    INSERT INTO script_new
                        (id, sourceArticleId, sourceTitle, angle, rationale,
                         sectionsJson, createdAt, shotAt, parked, note)
                    SELECT s.id, s.articleId, COALESCE(a.title, ''), s.angle, s.rationale,
                           s.sectionsJson, s.createdAt, s.shotAt, s.parked, s.note
                    FROM script s LEFT JOIN article a ON a.id = s.articleId
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE script")
                db.execSQL("ALTER TABLE script_new RENAME TO script")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_script_sourceArticleId ON script(sourceArticleId)"
                )

                // ② article：去掉指向 note 的外键，带一份原文快照
                db.execSQL(
                    """
                    CREATE TABLE article_new (
                        id TEXT NOT NULL,
                        sourceNoteId TEXT,
                        sourceText TEXT NOT NULL,
                        title TEXT NOT NULL,
                        paragraphsJson TEXT NOT NULL,
                        writtenAt INTEGER NOT NULL,
                        edited INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO article_new
                        (id, sourceNoteId, sourceText, title, paragraphsJson, writtenAt, edited)
                    SELECT a.id, a.noteId, COALESCE(n.body, ''), a.title,
                           a.paragraphsJson, a.writtenAt, a.edited
                    FROM article a LEFT JOIN note n ON n.id = a.noteId
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE article")
                db.execSQL("ALTER TABLE article_new RENAME TO article")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_article_sourceNoteId ON article(sourceNoteId)"
                )

                // ③ trashed：扩成能装三种资源。旧的都当记录处理
                db.execSQL(
                    """
                    CREATE TABLE trashed_new (
                        id TEXT NOT NULL,
                        resourceType TEXT NOT NULL,
                        resourceId TEXT NOT NULL,
                        deletedAt INTEGER NOT NULL,
                        originalIndex INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent(),
                )
                // id 规则必须与 IdeaRepository.moveToTrash 的 "tr_${'$'}{type}_${'$'}{resourceId}" 一致，
                // 否则迁移出来的旧行和新建的行长得不一样，将来按 id 查会漏
                db.execSQL(
                    """
                    INSERT INTO trashed_new (id, resourceType, resourceId, deletedAt, originalIndex)
                    SELECT 'tr_note_' || noteId, 'note', noteId, deletedAt, originalIndex FROM trashed
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE trashed")
                db.execSQL("ALTER TABLE trashed_new RENAME TO trashed")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_trashed_resourceType_resourceId " +
                        "ON trashed(resourceType, resourceId)"
                )
            }
        }

        val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
    }
}