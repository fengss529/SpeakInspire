package com.hatio.speakinspire.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.hatio.speakinspire.data.repository.ProviderRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.hatio.speakinspire.data.remote.Providers

/**
 * 仅用于自动化测试：注入 API Key。
 *
 * ## 为什么需要它
 *
 * 设置页要手动点，而 adb 的触摸注入对 Compose 语义节点无效
 * （底部导航能点，卡片点不动）。所以自动化跑流程时进不去设置页。
 *
 * ## 安全边界
 *
 * - **只在 debug 版注册**（见 DebugManifest.xml），release 包里不存在这个类
 * - 不打印 key 内容到日志
 * - 仅为开发期工具，正式功能走设置页
 */
class KeyInjectReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ProviderEntryPoint {
        fun providerRepository(): ProviderRepository
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SET_KEY) return

        val key = intent.getStringExtra(EXTRA_KEY).orEmpty()
        if (key.isBlank()) {
            Log.w(TAG, "key is empty")
            return
        }

        val repo = EntryPointAccessors.fromApplication(
            context.applicationContext,
            ProviderEntryPoint::class.java,
        ).providerRepository()

        // BroadcastReceiver 的 onReceive 里不能阻塞等待
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                repo.saveConfig(
                    Providers.DEEPSEEK.copy(apiKey = key)
                )
                Log.i(TAG, "key injected ok")
                context.sendBroadcast(
                    Intent(ACTION_DONE).setPackage(context.packageName)
                )
            } catch (e: Exception) {
                Log.e(TAG, "inject failed: ${e.message}")
                context.sendBroadcast(
                    Intent(ACTION_DONE).setPackage(context.packageName)
                )
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "KeyInject"
        const val ACTION_SET_KEY = "com.hatio.speakinspire.SET_KEY"
        const val ACTION_DONE = "com.hatio.speakinspire.KEY_INJECTED"
        const val EXTRA_KEY = "key"
    }
}