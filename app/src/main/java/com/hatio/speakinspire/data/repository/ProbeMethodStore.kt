package com.hatio.speakinspire.data.repository

import android.content.Context
import com.hatio.speakinspire.service.ProbeMethod
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 追问方法的选择，以及每种方法被用户改过的「手法」。
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
 *
 * ## 手法可改，但只改手法
 *
 * [instructionOf] 返回的是**注入 prompt 的那一段问法**。
 * 用户改了它就改了 AI 怎么问——但改不了 AI 能不能给答案：
 * 禁区那一段不在这个方法里，它由 [com.hatio.speakinspire.service.Prompts.probeSystem]
 * 固定拼在末尾，界面只读展示。
 *
 * 这是整个功能的成立条件。如果手法段能夹带「顺便告诉用户该怎么做」，
 * 那用户（或者别的什么人）写一句进去，追问阶段就变成了咨询。
 */
interface ProbeMethodStore {

    /** 当前选中的方法。从没选过时返回默认 */
    fun current(): ProbeMethod

    fun select(method: ProbeMethod)

    /**
     * 这个方法实际生效的手法段。
     *
     * 用户改过就返回改过的，没改过（或清空了）返回 [ProbeMethod.instruction]。
     * **空白等于恢复默认**——比留一个「恢复默认」按钮更好懂：
     * 用户把框清空，就已经在说「我不要自己写的那套了」。
     */
    fun instructionOf(method: ProbeMethod): String

    /** 保存自定义手法。传空白表示恢复默认 */
    fun saveInstruction(method: ProbeMethod, text: String)

    /** 这个方法是否被改过。界面据此显示「已自定义 · 恢复默认」 */
    fun isCustomized(method: ProbeMethod): Boolean
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

    override fun instructionOf(method: ProbeMethod): String =
        prefs.getString(instructionKey(method), null)
            ?.takeIf { it.isNotBlank() }
            ?: method.instruction

    override fun saveInstruction(method: ProbeMethod, text: String) {
        prefs.edit().apply {
            if (text.isBlank()) remove(instructionKey(method))
            else putString(instructionKey(method), text)
        }.apply()
    }

    override fun isCustomized(method: ProbeMethod): Boolean =
        !prefs.getString(instructionKey(method), null).isNullOrBlank()

    private fun instructionKey(method: ProbeMethod) = "$KEY_INSTRUCTION_PREFIX${method.id}"

    private companion object {
        const val FILE = "probe_settings"
        const val KEY = "probe_method"
        const val KEY_INSTRUCTION_PREFIX = "probe_instruction_"
    }
}

/** 内存实现。测试与降级用 */
class InMemoryProbeMethodStore(
    private var method: ProbeMethod = ProbeMethod.DEFAULT,
    private val instructions: MutableMap<String, String> = mutableMapOf(),
) : ProbeMethodStore {

    override fun current(): ProbeMethod = method

    override fun select(method: ProbeMethod) {
        this.method = method
    }

    override fun instructionOf(method: ProbeMethod): String =
        instructions[method.id]?.takeIf { it.isNotBlank() } ?: method.instruction

    override fun saveInstruction(method: ProbeMethod, text: String) {
        if (text.isBlank()) instructions.remove(method.id) else instructions[method.id] = text
    }

    override fun isCustomized(method: ProbeMethod): Boolean =
        !instructions[method.id].isNullOrBlank()
}
