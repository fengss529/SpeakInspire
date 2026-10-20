package com.hatio.speakinspire

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 写策略守卫：**有子表的实体不能用 `INSERT OR REPLACE`**。
 *
 * ## 这个 bug 为什么只能靠读源码守
 *
 * 它在 SQLite 层，内存假 DAO 永远复现不了——
 * `FakeNoteDao.upsert` 只是替换列表里的元素，
 * 没有外键，也不可能级联删除。用它写的测试会全绿，
 * 而真机上用户在丢数据。
 *
 * 真实链路是这样的（已在本机 sqlite3 上复现过）：
 *
 * ```
 * Room 生成的 _Impl 在 onOpen 里执行 PRAGMA foreign_keys = ON
 * INSERT OR REPLACE 的语义是 **先 DELETE 再 INSERT**
 *   → 触发子表的 ON DELETE CASCADE
 *   → 父行改一个字，子表整个清空
 * ```
 *
 * 实测后果（真机反馈的三条 bug 其实是同一件事）：
 *
 * | 用户动作 | 被删掉的子表 | 看到的现象 |
 * |---|---|---|
 * | 点「编辑」改原始记录 | `deepening` | 追问历史全丢，再打开像没聊过 |
 * | 再点「编辑」 | `article` | 写好的文章消失 |
 * | 重新成文保存（id 固定 `a_$noteId`） | `script` | 选题凭空不见了 |
 *
 * `@Upsert` 走 `INSERT ... ON CONFLICT DO UPDATE`，全程不删行，不触发级联。
 */
class DaoWriteStrategyTest {

    /** 从测试工作目录往上找出 Daos.kt（Gradle 的 cwd 可能是根目录或 app/） */
    private fun daosSource(): File {
        val relative = "src/main/java/com/hatio/speakinspire/data/local/Daos.kt"
        val candidates = listOf(
            File("app/$relative"),
            File(relative),
            File("../app/$relative"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: error(
                "找不到 Daos.kt，检查过：\n" +
                    candidates.joinToString("\n") { "  ${it.absolutePath}" }
            )
    }

    private fun source(): String = daosSource().readText()

    @Test
    fun `五个实体 DAO 的写入必须是 Upsert`() {
        val src = source()
        val upserts = Regex("@Upsert\\s+suspend fun upsert").findAll(src).count()
        assertEquals(
            "有子表的实体（note / deepening / article / script）和 provider " +
                "都必须用 @Upsert。实际找到 $upserts 处。",
            5,
            upserts,
        )
    }

    @Test
    fun `REPLACE 只允许出现在没有子表的 trashed 上`() {
        val src = source()
        val replaceCount = Regex("OnConflictStrategy\\.REPLACE").findAll(src).count()
        assertEquals(
            "OnConflictStrategy.REPLACE 只该出现 1 次（note 表的 trash）。" +
                "多出来的每一处都会在真机上静默删除子表数据。",
            1,
            replaceCount,
        )
        // 那一处确实是回收站
        val trashBlock = src.substringAfter("suspend fun trash", "")
        assertTrue(
            "唯一的 REPLACE 必须属于回收站写入",
            trashBlock.take(120).isNotBlank(),
        )
    }

    @Test
    fun `note 表的 upsert 不能再是 Insert`() {
        val src = source()
        val noteDao = src.substringAfter("interface NoteDao").substringBefore("interface DeepeningDao")
        assertTrue(
            "note 表改名走的就是这个 upsert——REPLACE 会连追问、文章、选题一起删",
            noteDao.contains("@Upsert"),
        )
        assertTrue(noteDao.contains("suspend fun upsert(note: NoteEntity)"))
    }
}
