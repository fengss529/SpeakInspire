package com.hatio.speakinspire.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v3 → v4 迁移测试，跑在真实 SQLite 上。
 *
 * ## 为什么必须存在
 *
 * 之前 388 条测试全是内存假 DAO。假 DAO 没有外键、没有 schema 校验，
 * 所以「迁移写错了」这件事**一条测试都挡不住**——
 * 表现是用户更新 App 之后一打开就闪退。
 *
 * ## 它到底在验什么
 *
 * 建一个真实的 v3 库（DDL 抄自 `schemas/…/3.json`），灌进接近真实的数据，
 * 然后用 Room 打开到 v4。**只要 Room 没抛异常，就说明迁移产出的 schema
 * 与实体定义完全一致**（Room 在迁移后会逐列比对名字、类型、NOT NULL、主键、索引）。
 * 剩下的是数据有没有丢。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationV3ToV4Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "migration_v3_v4_test.db"

    private lateinit var room: SpeakInspireDatabase

    @Before
    fun setUp() {
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        if (::room.isInitialized) room.close()
        context.deleteDatabase(dbName)
    }

    /** 造一个真实的 v3 库并灌入数据，然后交给 Room 升级到 v4 */
    private fun seedV3AndUpgrade() {
        val raw = context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null)
        V3_SCHEMA.forEach(raw::execSQL)

        raw.execSQL(
            "INSERT INTO note (id, title, body, createdAt, source, parentId) VALUES " +
                "('n1', '标题一', '第一句话。第二句话很重要。', 1000, 'QUICK', NULL)," +
                "('n2', '标题二', '第二条', 2000, 'QUICK', NULL)"
        )
        raw.execSQL(
            "INSERT INTO deepening (id, noteId, startedAt, finishedAt, estimatedRounds, roundsJson) " +
                "VALUES ('d1', 'n1', 1100, 1200, 5, '[]')"
        )
        raw.execSQL(
            "INSERT INTO article (id, noteId, title, paragraphsJson, writtenAt, edited) " +
                "VALUES ('a1', 'n1', '文章标题', '[]', 1300, 1)"
        )
        raw.execSQL(
            "INSERT INTO script (id, articleId, angle, rationale, sectionsJson, createdAt, shotAt, parked, note) " +
                "VALUES ('s1', 'a1', '角度一', '', '[]', 1400, NULL, 0, '')," +
                "('s2', 'a1', '角度二', '', '[]', 1500, NULL, 0, '')"
        )
        raw.execSQL(
            "INSERT INTO sprout (id, noteId, title, sectionsJson, createdAt) " +
                "VALUES ('sp1', 'n1', '芽', '[]', 1600)"
        )
        raw.execSQL(
            "INSERT INTO trashed (noteId, deletedAt, originalIndex) VALUES ('n2', 1700, 0)"
        )
        raw.version = 3
        raw.close()

        room = Room.databaseBuilder(context, SpeakInspireDatabase::class.java, dbName)
            .addMigrations(SpeakInspireDatabase.MIGRATION_3_4)
            .allowMainThreadQueries()
            .build()
        // 触发迁移：Room 在 onUpgrade 之后会 validateMigration，schema 不一致直接抛
        room.openHelper.writableDatabase
    }

    private fun count(table: String): Int =
        room.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM $table")
            .use { c -> if (c.moveToFirst()) c.getInt(0) else -1 }

    @Test
    fun `迁移后五张表的条数一条不少`() {
        seedV3AndUpgrade()

        assertEquals("note 不该少", 2, count("note"))
        assertEquals("deepening 不该少", 1, count("deepening"))
        assertEquals("article 不该少", 1, count("article"))
        assertEquals("script 不该少——两个都要在", 2, count("script"))
        assertEquals("sprout 不该少", 1, count("sprout"))
        assertEquals("trashed 不该少", 1, count("trashed"))
    }

    @Test
    fun `文章带上了原始记录正文的快照`() = runBlocking {
        seedV3AndUpgrade()

        val article = room.articleDao().findByNote("n1")
        assertNotNull("文章应该还在", article)
        assertEquals("a1", article!!.id)
        assertEquals("n1", article.sourceNoteId)
        assertEquals(
            "快照必须等于迁移那一刻的记录正文——它决定了原记录删掉后文章还能不能自洽",
            "第一句话。第二句话很重要。",
            article.sourceText,
        )
    }

    @Test
    fun `选题带上了文章标题的快照`() = runBlocking {
        seedV3AndUpgrade()

        val scripts = room.scriptDao().findByArticle("a1")
        assertEquals(2, scripts.size)
        assertTrue("每一条都要有标题快照", scripts.all { it.sourceTitle == "文章标题" })
        assertTrue("外键列改名后值要跟着走", scripts.all { it.sourceArticleId == "a1" })
    }

    @Test
    fun `回收站旧数据被标成 note 类型`() = runBlocking {
        seedV3AndUpgrade()

        val rows = room.trashedDao().findAll()
        assertEquals(1, rows.size)
        assertEquals(TrashType.NOTE, rows[0].resourceType)
        assertEquals("n2", rows[0].resourceId)
        assertEquals("主键要重新生成成带类型前缀的形式", "tr_note_n2", rows[0].id)
    }

    @Test
    fun `迁移之后不再有外键违规`() {
        seedV3AndUpgrade()

        room.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { c ->
            assertEquals("不该有任何悬空外键", 0, c.count)
        }
    }

    /**
     * 最重要的一条：迁移完成后，删记录**不再**带走文章和选题。
     *
     * 这是 V0.10 的全部目的。如果哪天有人把外键加回去，这条会红。
     */
    @Test
    fun `升级之后删记录不再连带删文章和选题`() = runBlocking {
        seedV3AndUpgrade()

        room.noteDao().hardDelete("n1")

        // n2 还在——它在回收站里只是被标记，行并没有从 note 表消失
        assertEquals("只有 n1 被物理删除", 1, count("note"))
        assertNull("n1 确实没了", room.noteDao().findById("n1"))
        assertEquals("追问跟着记录走，该删", 0, count("deepening"))
        assertEquals("发芽跟着记录走，该删", 0, count("sprout"))
        assertEquals("文章必须还在", 1, count("article"))
        assertEquals("选题必须还在——这是最容易被级联删光的东西", 2, count("script"))
    }

    private companion object {
        /**
         * v3 的建表语句，逐字抄自 `app/schemas/…/3.json`。
         *
         * 不要「顺手优化」这些 SQL——它要复现的是**用户手机上真实的 v3 库**。
         */
        val V3_SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS `note` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                "`body` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `source` TEXT NOT NULL, " +
                "`parentId` TEXT, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `deepening` (`id` TEXT NOT NULL, `noteId` TEXT NOT NULL, " +
                "`startedAt` INTEGER NOT NULL, `finishedAt` INTEGER, `estimatedRounds` INTEGER NOT NULL, " +
                "`roundsJson` TEXT NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`noteId`) " +
                "REFERENCES `note`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_deepening_noteId` ON `deepening` (`noteId`)",
            "CREATE TABLE IF NOT EXISTS `article` (`id` TEXT NOT NULL, `noteId` TEXT NOT NULL, " +
                "`title` TEXT NOT NULL, `paragraphsJson` TEXT NOT NULL, `writtenAt` INTEGER NOT NULL, " +
                "`edited` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`noteId`) " +
                "REFERENCES `note`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_article_noteId` ON `article` (`noteId`)",
            "CREATE TABLE IF NOT EXISTS `script` (`id` TEXT NOT NULL, `articleId` TEXT NOT NULL, " +
                "`angle` TEXT NOT NULL, `rationale` TEXT NOT NULL, `sectionsJson` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, `shotAt` INTEGER, `parked` INTEGER NOT NULL, " +
                "`note` TEXT NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`articleId`) " +
                "REFERENCES `article`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_script_articleId` ON `script` (`articleId`)",
            "CREATE TABLE IF NOT EXISTS `trashed` (`noteId` TEXT NOT NULL, `deletedAt` INTEGER NOT NULL, " +
                "`originalIndex` INTEGER NOT NULL, PRIMARY KEY(`noteId`))",
            "CREATE TABLE IF NOT EXISTS `provider` (`providerId` TEXT NOT NULL, `baseUrl` TEXT NOT NULL, " +
                "`model` TEXT NOT NULL, `thinkingDisabled` INTEGER NOT NULL, `active` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`providerId`, `model`))",
            "CREATE TABLE IF NOT EXISTS `sprout` (`id` TEXT NOT NULL, `noteId` TEXT NOT NULL, " +
                "`title` TEXT NOT NULL, `sectionsJson` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`), FOREIGN KEY(`noteId`) REFERENCES `note`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_sprout_noteId` ON `sprout` (`noteId`)",
        )
    }
}
