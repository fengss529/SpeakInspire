package com.hatio.speakinspire.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 冒烟测试：证明 Robolectric 能提供真实的 Android SQLite。
 *
 * 它本身没断言什么业务规则，作用是**先把地基试出来**——
 * 万一 Robolectric 在这个项目上跑不起来（SDK 版本、资源、依赖冲突），
 * 后面四个真正的迁移/级联测试就全白写了。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomSmokeTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        SpeakInspireDatabase::class.java,
    ).allowMainThreadQueries().build()

    @After
    fun tearDown() = db.close()

    @Test
    fun `真实 Room 能打开并读写`() = runBlocking {
        db.noteDao().upsert(
            NoteEntity(
                id = "n1",
                title = "标题",
                body = "正文",
                createdAt = 1L,
                source = "manual",
                parentId = null,
            ),
        )

        assertEquals("正文", db.noteDao().findById("n1")?.body)
        Unit
    }
}
