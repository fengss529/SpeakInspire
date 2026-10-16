package com.hatio.speakinspire.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        NoteEntity::class,
        DeepeningEntity::class,
        ArticleEntity::class,
        ScriptEntity::class,
        TrashedEntity::class,
        ProviderEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class SpeakInspireDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun deepeningDao(): DeepeningDao
    abstract fun articleDao(): ArticleDao
    abstract fun scriptDao(): ScriptDao
    abstract fun providerDao(): ProviderDao

    companion object {
        const val NAME = "speakinspire.db"

        /** 回收站保留期：30 天后自动清除 */
        const val TRASH_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    }
}