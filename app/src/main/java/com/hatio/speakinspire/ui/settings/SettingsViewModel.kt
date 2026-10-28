package com.hatio.speakinspire.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hatio.speakinspire.BuildConfig
import com.hatio.speakinspire.data.export.DownloadStore
import com.hatio.speakinspire.data.export.LibraryJson
import com.hatio.speakinspire.data.export.MarkdownExporter
import com.hatio.speakinspire.data.model.ProviderConfig
import com.hatio.speakinspire.data.remote.ModelChoice
import com.hatio.speakinspire.data.remote.Providers
import com.hatio.speakinspire.data.repository.IdeaRepository
import com.hatio.speakinspire.data.repository.ProbeMethodStore
import com.hatio.speakinspire.data.repository.ProviderRepository
import com.hatio.speakinspire.service.AiService
import com.hatio.speakinspire.service.ConnectionResult
import com.hatio.speakinspire.service.ProbeMethod
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import android.content.Context
import javax.inject.Inject

/**
 * 设置页ViewModel。
 *
 * ## 「测试连接」为什么必须能先测再存
 *
 * 用户填错 key 是常态（少个空格、复制时带上前缀）。
 * 如果必须先保存才能测，填错了要来回改几轮——
 * 而「填 key」这一步的心智负担本来就高。
 * 所以：拿当前输入的临时配置去测，测过了再让用户决定存不存。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: ProviderRepository,
    private val aiService: AiService,
    private val probeStore: ProbeMethodStore,
    /** 导出要用整库快照。这里不叫 repo，是为了不和 ProviderRepository 撞名 */
    private val ideaRepo: IdeaRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val selected = repo.selectedProviderId()
            val cfg = selected?.let { repo.configForTest(it) } ?: Providers.DEEPSEEK
            val savedKey = selected?.let { repo.readKey(it) }.orEmpty()
            _state.value = SettingsUiState(
                selectedProvider = cfg.providerId,
                baseUrl = cfg.baseUrl,
                model = cfg.model,
                apiKey = savedKey,
                thinkingDisabled = cfg.thinkingDisabled,
                keyAlreadySaved = savedKey.isNotBlank(),
                probeMethod = probeStore.current(),
            )
        }
    }

    /**
     * 换追问方法。
     *
     * **立刻落盘，不等「保存」按钮**——它跟 API Key 不是一类东西：
     * key 需要一个确认动作，问法是随手试的，用户切过去就该生效。
     * 让他为了试试另一种问法先去点保存，是在给一个无风险操作加摩擦。
     */
    fun selectProbeMethod(method: ProbeMethod) {
        probeStore.select(method)
        _state.value = _state.value.copy(probeMethod = method)
    }

    fun selectProvider(providerId: String) {
        val preset = Providers.byId(providerId)
        _state.value = _state.value.copy(
            selectedProvider = providerId,
            baseUrl = preset.baseUrl,
            model = preset.model,
            addingModel = false,
            thinkingDisabled = preset.thinkingDisabled,
            // 先清空，避免把 A 家的 key 发到 B 家
            apiKey = "",
            keyAlreadySaved = false,
            testResult = null,
        )
        // 再把这家**之前存过**的 key 读回来。
        // 不能一律清空——用户切回用过的服务商时，
        // 看到空输入框会以为 key 丢了，然后重新贴一遍。
        viewModelScope.launch {
            val saved = repo.readKey(providerId).orEmpty()
            if (_state.value.selectedProvider == providerId) {
                _state.value = _state.value.copy(
                    apiKey = saved,
                    keyAlreadySaved = saved.isNotBlank(),
                )
            }
        }
    }

    /**
     * 切换到同一个服务商下的另一个模型。
     *
     * 换模型不换key —— 同一个服务商的 key 是一样的，
     * 只需要换模型名。误以为要重填key 会让流程显得很麻烦。
     */
    fun selectModel(model: String) {
        val current = _state.value
        val preset = Providers.byId(current.selectedProvider)
        viewModelScope.launch {
            repo.saveConfig(
                preset.copy(
                    apiKey = current.apiKey,
                    model = model,
                    // 保留用户可能改过的地址，别被预置值覆盖
                    baseUrl = current.baseUrl,
                    thinkingDisabled = current.thinkingDisabled,
                )
            )
            _state.value = current.copy(model = model, testResult = null)
        }
    }

    /** 加一个自定义模型名（服务商自己的新模型上线时用） */
    fun addModel() {
        val current = _state.value
        // 直接跳到添加状态，让用户直接填名字
        _state.value = current.copy(addingModel = true)
    }

    /** 确认新增的自定义模型 */
    fun commitNewModel(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            _state.value = _state.value.copy(addingModel = false)
            return
        }
        val current = _state.value
        viewModelScope.launch {
            repo.saveConfig(
                Providers.byId(current.selectedProvider).copy(
                    apiKey = current.apiKey,
                    model = trimmed,
                    baseUrl = current.baseUrl,
                    thinkingDisabled = current.thinkingDisabled,
                )
            )
            _state.value = current.copy(
                model = trimmed,
                addingModel = false,
                testResult = null,
            )
        }
    }

    fun cancelAddModel() {
        _state.value = _state.value.copy(addingModel = false)
    }

    fun updateBaseUrl(v: String) {
        _state.value = _state.value.copy(baseUrl = v.trim(), testResult = null)
    }

    fun updateModel(v: String) {
        _state.value = _state.value.copy(model = v.trim(), testResult = null)
    }

    fun updateKey(v: String) {
        _state.value = _state.value.copy(apiKey = v.trim(), testResult = null)
    }

    /** [disabled] = 关掉思考模式。字段名叫 thinkingDisabled，别把语义搞反 */
    fun updateThinking(disabled: Boolean) {
        _state.value = _state.value.copy(thinkingDisabled = disabled)
    }

    /** 用当前输入测试，不落库 */
    fun testConnection() {
        val current = _state.value
        if (current.apiKey.isBlank()) {
            _state.value = current.copy(
                testResult = TestResult.Failed("先填 API Key 再测")
            )
            return
        }
        _state.value = current.copy(testing = true, testResult = null)

        viewModelScope.launch {
            val cfg = current.toConfig()
            val result = aiService.testConnection(cfg)
            _state.value = _state.value.copy(
                testing = false,
                testResult = when (result) {
                    is ConnectionResult.Success -> TestResult.Ok(result.latencyMs)
                    is ConnectionResult.Failure -> TestResult.Failed(result.reason)
                    // 无法归因的错误不该显示技术细节，
                    // 但也不能装作成功——给一句诚实的话
                    ConnectionResult.Unknown -> TestResult.Failed(
                        "连上了但响应异常，可能模型名不对。检查一下模型名。"
                    )
                },
            )
        }
    }

    /** 保存。只有测过才允许存——但不强求，允许用户跳过 */
    fun save() {
        val current = _state.value
        if (current.apiKey.isBlank()) {
            _state.value = current.copy(saveError = "API Key 不能为空")
            return
        }
        viewModelScope.launch {
            repo.saveConfig(current.toConfig())
            _state.value = _state.value.copy(
                keyAlreadySaved = true,
                saved = true,
                saveError = null,
            )
        }
    }

    fun consumeSavedFlag() {
        _state.value = _state.value.copy(saved = false)
    }

    /**
     * 导出全部内容到 `下载/随手记/`。
     *
     * ## 为什么一次给两个文件
     *
     * **md 是给人读的，json 是给程序读的。**
     * 两者解决的是不同的担心：
     *
     * - md：十年后随便一个文本编辑器都能打开。用户最底层的担心是
     *   「哪天这个 App 不做了，我说过的话就没了」——纯文本才真正解除它。
     * - json：字段与库里的表一一对应，带 `schemaVersion`。
     *   换手机、重装、将来做导入，靠它把内容原样搬回去。
     *   md 丢了「这是第几轮问答」「这段是 AI 补的」这类结构，搬不动。
     *
     * 所以不要问用户「你要哪种」——他没法在导出那一刻判断。
     * 一次都给，各管一头。
     *
     * ## 写到下载目录而不是拉分享面板
     *
     * 分享面板的语义是「发给别人」，可用户真正的需求是
     * 「留一份在自己手里」。写进下载目录后，文件管理器一打开就看得见，
     * 插上电脑也是一个普通文件夹。
     */
    fun exportAll() {
        if (_state.value.exporting) return
        _state.value = _state.value.copy(exporting = true, exportMessage = null)
        viewModelScope.launch {
            val library = ideaRepo.exportSnapshot()
            if (library.isEmpty) {
                _state.value = _state.value.copy(
                    exporting = false,
                    exportMessage = "还没有记录可以导出。",
                )
                return@launch
            }

            val md = MarkdownExporter.all(library)
            val json = LibraryJson.encode(library, BuildConfig.VERSION_NAME)
            val stem = MarkdownExporter.fileName("随手记-全部").removeSuffix(".md")

            val result = DownloadStore.write(
                context,
                // json 放前面：它是完整备份，md 是顺手给人看的
                listOf(
                    LibraryJson.FILE_NAME to json,
                    "$stem.md" to md,
                ),
            )

            _state.value = _state.value.copy(
                exporting = false,
                exportMessage = when (result) {
                    is DownloadStore.Result.Ok -> null
                    // 只有 Android 9 及以下会走到这里。界面拿到这个标记后去申请权限，
                    // 批下来再调一次 exportAll()
                    DownloadStore.Result.NeedPermission -> EXPORT_NEED_PERMISSION
                    is DownloadStore.Result.Failed -> "没能写入文件：${result.detail}"
                },
                // 成功用对话框而不是提示条：里面有个路径，
                // 用户得能看清并记住它去哪儿了。提示条一行就滚过去了
                exportDone = (result as? DownloadStore.Result.Ok)?.let {
                    "已存到「${it.folder}」\n\n" +
                        it.names.joinToString("\n") +
                        "\n\n里面装的是 ${library.notes.size} 条记录、" +
                        "${library.articles.size} 篇文章、${library.topics.size} 个选题。\n" +
                        "data.json 是完整备份，md 是给你读的。"
                },
            )
        }
    }

    fun consumeExportMessage() {
        _state.value = _state.value.copy(exportMessage = null)
    }

    fun consumeExportDone() {
        _state.value = _state.value.copy(exportDone = null)
    }

    companion object {
        /**
         * 「需要存储权限」不能靠中文字符串比对——那句话随时会改文案。
         * 用这个标记当信号，界面据此弹权限申请。
         */
        const val EXPORT_NEED_PERMISSION = "__need_storage_permission__"
    }
}

data class SettingsUiState(
    val selectedProvider: String = ProviderConfig.DEEPSEEK,
    val baseUrl: String = "",
    val model: String = "",
    /** 正在输入新的自定义模型名 */
    val addingModel: Boolean = false,
    val apiKey: String = "",
    val thinkingDisabled: Boolean = true,
    /** 是否已保存过 key——决定输入框显示占位符还是掩码 */
    val keyAlreadySaved: Boolean = false,
    val testing: Boolean = false,
    val testResult: TestResult? = null,
    val saved: Boolean = false,
    val saveError: String? = null,
    /** 追问方法。与服务商无关，单独存在偏好里 */
    val probeMethod: ProbeMethod = ProbeMethod.DEFAULT,
    /** 正在导出全部 */
    val exporting: Boolean = false,
    /** 失败/提示信息，用底部提示条显示。null = 没有要提示的 */
    val exportMessage: String? = null,
    /** 导出成功后的完整回执（含路径），用对话框显示。null = 不显示 */
    val exportDone: String? = null,
) {
    fun toConfig() = ProviderConfig(
        providerId = selectedProvider,
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = model,
        thinkingDisabled = thinkingDisabled,
    )

    /** 自定义服务商才需要填地址与模型名 */
    val needsManualInput: Boolean get() = selectedProvider == ProviderConfig.CUSTOM

    /** 该服务商的候选模型。空表示要手填 */
    val modelChoices: List<ModelChoice> get() = Providers.candidates(selectedProvider)

    /** 当前模型不在候选列表里——服务商可能上了新模型 */
    val isCustomModelName: Boolean
        get() = modelChoices.isNotEmpty() && modelChoices.none { it.model == model }


    /** 预置服务商的 key 提示。写清楚去哪儿拿，减少用户困惑 */
    val keyHint: String get() = when (selectedProvider) {
        ProviderConfig.DEEPSEEK -> "在 platform.deepseek.com 的「API keys」页面创建"
        ProviderConfig.MIMO -> "在小米 MiMo 开放平台创建，形如 sk- 开头"
        else -> "填入服务商的 API Key"
    }
}

sealed interface TestResult {
    data class Ok(val latencyMs: Long) : TestResult
    data class Failed(val reason: String) : TestResult
}