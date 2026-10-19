package com.hatio.speakinspire.data.repository

import android.content.Context
import com.hatio.speakinspire.service.ProbeMethod
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 追问方法的选择。
 *
 * ## 为什么单独抽一个接口
 *
 * 它既不是服务商配置（跟 base_url、key 无关），
 * 也不适合塞进 [com.hatio.speakinspire.data.model.ProviderConfig]——
 * 那是「发给哪家模型」，这是「用什么方式问」。混在一起以后，
 * 换服务商就会莫名其妙地把问法也换掉。
 *
 * 抽成接口的第二个理由是测试：追问方法必须能从
 * ViewModel 一路传到请求体，而这一条只能靠链路测试守。
 * JVM 单测没有 Android Context，所以 ViewModel 依赖接口，
 * 测试塞一个内存实现即可。
 */
interface ProbeMethodStore {

    /** 当前选中的方法。从没选过时返回默认 */
    fun current(): ProbeMethod

    fun select(method: ProbeMethod)
}

/**
 * 落盘实现。
 *
 * 用普通 SharedPreferences 而不是加密存储——它不含敏感信息，
 * 也不值得为它建一个 DataStore。读的是内存缓存，主线程安全。
 */
@Singleton
class SharedPrefsProbeMethodStore @Inject constructor(
    @ApplicationContext context: Context,
) : ProbeMethodStore {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun current(): ProbeMethod =
        ProbeMethod.byId(prefs.getString(KEY, null))

    override fun select(method: ProbeMethod) {
        prefs.edit().putString(KEY, method.id).apply()
    }

    private companion object {
        const val FILE = "probe_settings"
        const val KEY = "probe_method"
    }
}

/** 内存实现。测试与降级用 */
class InMemoryProbeMethodStore(
    private var method: ProbeMethod = ProbeMethod.DEFAULT,
) : ProbeMethodStore {

    override fun current(): ProbeMethod = method

    override fun select(method: ProbeMethod) {
        this.method = method
    }
}
