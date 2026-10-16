package com.hatio.speakinspire.debug

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.hatio.speakinspire.data.remote.Providers
import com.hatio.speakinspire.data.repository.ProviderRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch

/**
 * debug 专用的 key 注入入口。
 *
 * ## 为什么需要
 *
 * 设置页要手动点，而 adb 的触摸注入对 Compose 语义节点无效
 * （底部导航能点，卡片和 IconButton 点不动）。
 * 所以自动化跑完整流程时进不去设置页。
 *
 * ## 用法
 *
 * ```
 * adb shell am start -n com.hatio.speakinspire/.debug.KeySetupActivity -e key sk-xxx
 * ```
 *
 * ## 安全边界
 *
 * 位于 `src/debug` 源集 —— **release 包里这个类不存在**，
 * manifest 里也没有这条声明。正式功能一律走设置页。
 */
class KeySetupActivity : Activity() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ProviderEntryPoint {
        fun providerRepository(): ProviderRepository
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate reached")

        val key = intent?.getStringExtra("key").orEmpty()
        Log.i(TAG, "key length = ${key.length}")
        if (key.isBlank()) {
            Log.w(TAG, "no key given")
            finish()
            return
        }

        val repo = EntryPointAccessors.fromApplication(
            applicationContext,
            ProviderEntryPoint::class.java,
        ).providerRepository()

        val noteId = intent?.getStringExtra("note").orEmpty()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                repo.saveConfig(Providers.DEEPSEEK.copy(apiKey = key))
                Log.i(TAG, "KEY_INJECTED_OK")

                // 顺带做一次真实连通性验证——比注入成功更有意义
                val cfg = repo.activeConfig()
                Log.i(TAG, "config loaded: ${cfg?.model}, key len=${cfg?.apiKey?.length}")

                if (noteId.isNotBlank()) {
                    val service = com.hatio.speakinspire.service.RealAiService(
                        com.hatio.speakinspire.data.remote.OkHttpTransport(),
                    ) { cfg!! }
                    val ctx = com.hatio.speakinspire.service.DeepeningContext(
                        noteId = noteId,
                        originalNote = "会上想说话但说不出来",
                        rounds = emptyList(),
                        roundIndex = 0,
                        maxRounds = 5,
                    )
                    val text = service.streamQuestion(ctx)
                        .toList()
                        .joinToString("")
                    Log.i(TAG, "AI_REPLY_LEN=" + text.length)
                    Log.i(TAG, "AI_REPLY=" + text)
                }
            } catch (e: Exception) {
                Log.e(TAG, "failed: ${'$'}{e.message}", e)
            } finally {
                finish()
            }
        }
    }

    companion object {
        private const val TAG = "KeySetup"
    }
}