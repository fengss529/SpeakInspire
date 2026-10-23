package com.hatio.speakinspire.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hatio.speakinspire.data.export.MarkdownExporter
import com.hatio.speakinspire.data.export.MarkdownShare
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
     * 导出全部记录为一个 Markdown 文件。
     *
     * ## 为什么这件事必须做
     *
     * 用户把想法记在这里，最底层的担心是「哪天这个 App 不做了，
     * 我说过的话就没了」。导出的存在，是这份担心的解药——
     * 所以导出必须是**完整**的：追问、文章、发芽、选题一个不少。
     * 少一样，他下次还得回来找，那这次导出就没意义。
     */
    fun exportAll() {
        if (_state.value.exporting) return
        _state.value = _state.value.copy(exporting = true, exportMessage = null)
        viewModelScope.launch {
            val views = ideaRepo.exportSnapshot()
            if (views.isEmpty()) {
                _state.value = _state.value.copy(
                    exporting = false,
                    exportMessage = "还没有记录可以导出。",
                )
                return@launch
            }
            val md = MarkdownExporter.all(views)
            val name = MarkdownExporter.fileName("随手记-全部")
            val ok = MarkdownShare.share(context, name, md)
            _state.value = _state.value.copy(
                exporting = false,
                exportMessage = if (ok) {
                    "已生成 ${views.size} 条记录的 Markdown，选一个 App 存下来。"
                } else {
                    "没能生成导出文件，再试一次。"
                },
            )
        }
    }

    fun consumeExportMessage() {
        _state.value = _state.value.copy(exportMessage = null)
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
    /** 导出结果提示。null = 没有要提示的 */
    val exportMessage: String? = null,
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