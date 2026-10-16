package com.hatio.speakinspire.di

import android.content.Context
import androidx.room.Room
import com.hatio.speakinspire.data.local.ArticleDao
import com.hatio.speakinspire.data.local.DeepeningDao
import com.hatio.speakinspire.data.local.NoteDao
import com.hatio.speakinspire.data.local.ProviderDao
import com.hatio.speakinspire.data.local.ScriptDao
import com.hatio.speakinspire.data.local.SpeakInspireDatabase
import com.hatio.speakinspire.data.model.ProviderConfig
import com.hatio.speakinspire.data.remote.HttpTransport
import com.hatio.speakinspire.data.remote.OkHttpTransport
import com.hatio.speakinspire.data.remote.Providers
import com.hatio.speakinspire.data.repository.IdeaRepository
import com.hatio.speakinspire.data.repository.ProviderRepository
import com.hatio.speakinspire.service.AiService
import com.hatio.speakinspire.service.RealAiService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import javax.inject.Singleton

/** 数据库模块 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SpeakInspireDatabase =
        Room.databaseBuilder(context, SpeakInspireDatabase::class.java, SpeakInspireDatabase.NAME)
            // 外键约束默认关闭，删note 时级联清理要手动开
            .also { builder ->
                // 不用 fallbackToDestructiveMigration——
                // 数据是用户的思考资产，静默清空不可接受。
                // 加字段时必须写 Migration。
            }
            .build()

    @Provides fun provideNoteDao(db: SpeakInspireDatabase): NoteDao = db.noteDao()
    @Provides fun provideDeepeningDao(db: SpeakInspireDatabase): DeepeningDao = db.deepeningDao()
    @Provides fun provideArticleDao(db: SpeakInspireDatabase): ArticleDao = db.articleDao()
    @Provides fun provideScriptDao(db: SpeakInspireDatabase): ScriptDao = db.scriptDao()
    @Provides fun provideProviderDao(db: SpeakInspireDatabase): ProviderDao = db.providerDao()

    @Provides
    @Singleton
    fun provideIdeaRepository(
        noteDao: NoteDao,
        deepeningDao: DeepeningDao,
        articleDao: ArticleDao,
        scriptDao: ScriptDao,
    ): IdeaRepository = IdeaRepository(noteDao, deepeningDao, articleDao, scriptDao)
}

/** 网络模块 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpTransport.defaultClient()

    @Provides
    @Singleton
    fun provideHttpTransport(client: OkHttpClient): HttpTransport = OkHttpTransport(client)
}

/**
 * AI 服务绑定。
 *
 * **这里是最需要盯的一处**：如果为了「方便测试」把 RealAiService
 * 换成 FakeAiService 打进 release 包，整个产品的核心价值就没了——
 * 追问会变成固定的话术。
 *
 * 所以绑的是真实实现。测试里直接构造 FakeAiService 对象，
 * 不走 Hilt 替换——这样不存在「测试环境跑得好、线上跑偏」的可能。
 */
@Module
@InstallIn(SingletonComponent::class)
object ServiceModule {

    @Provides
    @Singleton
    fun provideAiService(
        transport: HttpTransport,
        providerRepository: ProviderRepository,
    ): AiService = RealAiService(transport) { currentConfig(providerRepository) }

    /**
     * 取当前配置。
     *
     * AiService 的接口是同步的，而配置存在 Room 里是 suspend 读的，
     * 构造时拿不到——所以延迟到发请求那一刻再读。
     * 只在发请求前调用一次，配置通常已在内存里，阻塞时间极短。
     */
    private fun currentConfig(providerRepository: ProviderRepository): ProviderConfig =
        runBlocking {
            providerRepository.activeConfig()
                // 没有配置时给 DeepSeek 默认值（key 为空），
                // 调用失败会在连接测试里给出可执行的提示
                ?: Providers.DEEPSEEK.copy(apiKey = "")
        }
}