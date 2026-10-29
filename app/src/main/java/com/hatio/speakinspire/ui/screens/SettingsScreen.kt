package com.hatio.speakinspire.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hatio.speakinspire.BuildConfig
import com.hatio.speakinspire.R
import com.hatio.speakinspire.data.model.ProviderConfig
import com.hatio.speakinspire.service.DraftStyle
import com.hatio.speakinspire.service.ProbeMethod
import com.hatio.speakinspire.service.Prompts
import com.hatio.speakinspire.ui.settings.SettingsUiState
import com.hatio.speakinspire.ui.settings.SettingsViewModel
import com.hatio.speakinspire.ui.settings.TestResult
import com.hatio.speakinspire.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * 设置页。
 *
 * ## 为什么「测试连接」放在保存之前
 *
 * 填错 key 是常态。如果必须先存才能测，用户要来回改几轮——
 * 而「填 key」这一步的心智负担本来就高，够劝退的了。
 * 所以这里的主按钮顺序是：测试 → 保存。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onSelectProvider: (String) -> Unit,
    onSelectModel: (String) -> Unit,
    onAddModel: () -> Unit,
    /** 自定义模型名确认后回调 */
    onCommitModel: (String) -> Unit,
    onCancelAddModel: () -> Unit,
    onBaseUrl: (String) -> Unit,
    onModel: (String) -> Unit,
    onApiKey: (String) -> Unit,
    onThinking: (Boolean) -> Unit,
    /** 选追问方法。立刻生效，不需要点保存 */
    onSelectProbeMethod: (ProbeMethod) -> Unit,
    /** 保存当前方法的问法。传空白 = 恢复默认 */
    onSaveProbeInstruction: (String) -> Unit = {},
    /** 选成文默认风格。立刻生效 */
    onSelectDraftStyle: (DraftStyle) -> Unit = {},
    /** 保存补充写作要求。传空白 = 清掉 */
    onSaveDraftStyleNote: (String) -> Unit = {},
    onTest: () -> Unit,
    onSave: () -> Unit,
    /** 提示弹完后回调，把 saved 标志清掉 */
    onSavedConsumed: () -> Unit = {},
    /** 导出全部内容到 下载/随手记/ */
    onExportAll: () -> Unit = {},
    /** 打开回收站 */
    onOpenTrash: () -> Unit = {},
    onExportMessageConsumed: () -> Unit = {},
    onExportDoneConsumed: () -> Unit = {},
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Android 9 及以下往公共目录写文件要权限。权限批下来就接着导——
    // 不重导的话用户点完「允许」什么也不会发生，只能再点一次按钮
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            onExportAll()
        } else {
            scope.launch {
                snackbar.showSnackbar("没有存储权限，文件写不到下载目录")
            }
        }
    }

    // 保存成功后**留在设置页**，只提示一下。
    //
    // 之前是保存完自动退回上一页，结果用户反馈
    // 「填完 key 后打不开设置页」——
    // 其实是保存后被弹回首页，再点设置时页面在别的 tab，
    // 看起来像打不开。
    //
    // 设置页不是表单提交页，保存完留在原地更符合预期：
    // 用户可能还想确认「选的是哪个模型」「Key 存上了没」。
    LaunchedEffect(state.saved) {
        if (state.saved) {
            snackbar.showSnackbar("已保存")
            onSavedConsumed()
        }
    }
    LaunchedEffect(state.saveError) {
        state.saveError?.let { snackbar.showSnackbar(it) }
    }
    LaunchedEffect(state.exportMessage) {
        val msg = state.exportMessage ?: return@LaunchedEffect
        onExportMessageConsumed()
        // 需要存储权限时不是"提示"而是"去申请"。
        // 标记比对放在文案之外，改文案不会让这条路径失效
        if (msg == SettingsViewModel.EXPORT_NEED_PERMISSION) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                snackbar.showSnackbar("导出没能完成，再试一次。")
            }
        } else {
            snackbar.showSnackbar(msg)
        }
    }

    // 导出成功用对话框：里面有个路径，提示条一行就滚过去了，
    // 用户回头找不到文件
    state.exportDone?.let { done ->
        AlertDialog(
            onDismissRequest = onExportDoneConsumed,
            title = { Text("导出完成") },
            text = { Text(done, fontSize = 13.sp, lineHeight = 20.sp) },
            confirmButton = {
                TextButton(onClick = onExportDoneConsumed) { Text("知道了") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = { Text("设置", style = MaterialTheme.typography.titleMedium) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = Spacing.l),
        ) {
            Spacer(Modifier.height(Spacing.m))

            SectionLabel("用哪个服务")
            Spacer(Modifier.height(Spacing.s))

            Column(Modifier.selectableGroup()) {
                ProviderRow(
                    name = "DeepSeek",
                    desc = "便宜、快。国内直连不用梯子。",
                    selected = state.selectedProvider == ProviderConfig.DEEPSEEK,
                    onClick = { onSelectProvider(ProviderConfig.DEEPSEEK) },
                )
                ProviderRow(
                    name = "小米 MiMo",
                    desc = "同样便宜。注意它的思考模式默认开着。",
                    selected = state.selectedProvider == ProviderConfig.MIMO,
                    onClick = { onSelectProvider(ProviderConfig.MIMO) },
                )
                ProviderRow(
                    name = "自定义",
                    desc = "OpenAI 兼容的都行，填地址和模型名。",
                    selected = state.selectedProvider == ProviderConfig.CUSTOM,
                    onClick = { onSelectProvider(ProviderConfig.CUSTOM) },
                )
            }

            Spacer(Modifier.height(Spacing.xl))

            // key
            SectionLabel("API Key")
            Spacer(Modifier.height(Spacing.s))
            InputField(
                value = state.apiKey,
                onChange = onApiKey,
                placeholder = if (state.keyAlreadySaved) "已保存，留空表示不修改" else state.keyHint,
                isPassword = true,
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = "Key 只存在本机加密存储里，不会上传到任何地方。",
                fontSize = 11.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.outline,
            )

            // 候选模型。让用户手动切换，而不是替他做决定
            if (state.modelChoices.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.xl))

                SectionLabel("用哪个模型")
                Spacer(Modifier.height(Spacing.s))

                state.modelChoices.forEach { choice ->
                    ModelRow(
                        label = choice.label,
                        note = choice.note,
                        modelName = choice.model,
                        selected = state.model == choice.model,
                        onClick = { onSelectModel(choice.model) },
                    )
                }

                // 服务商上了列表里没有的新模型时，明确显示当前用的哪个
                if (state.isCustomModelName) {
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        text = "当前用的是 ${state.model}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }

                TextButton(onClick = onAddModel) {
                    Text("填其他模型名", fontSize = 12.sp)
                }

                // 点了「填其他模型名」得真的出现输入框。
                // 之前这里只把 addingModel 置了 true，界面上什么都不渲染，
                // 按钮点了没反应——和「先留着」是同一类死按钮。
                if (state.addingModel) {
                    var newModel by remember(state.addingModel) { mutableStateOf("") }
                    Spacer(Modifier.height(Spacing.s))
                    InputField(
                        value = newModel,
                        onChange = { newModel = it },
                        placeholder = "模型名，如 deepseek-chat",
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        Button(
                            onClick = { onCommitModel(newModel) },
                            enabled = newModel.isNotBlank(),
                        ) {
                            Text("用这个模型")
                        }
                        TextButton(onClick = onCancelAddModel) {
                            Text("取消")
                        }
                    }
                }
            }

            // ---------- 追问方式 ----------
            //
            // 放在模型之后、接口地址之前：先决定「用哪个模型」，
            // 再决定「怎么问」，最后才是接线细节。
            //
            // 换的是提问手法，不是权力边界——四种方法都只能问不能答。
            // 这一点在设置页里也要说清楚，否则用户会以为切到某种方法
            // 就能拿到建议。
            Spacer(Modifier.height(Spacing.xl))

            SectionLabel("用哪种追问方式")
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = "只换问法，不换规矩：三种方式都只提问、不给答案。",
                fontSize = 11.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(Spacing.s))

            Column(Modifier.selectableGroup()) {
                ProbeMethod.entries.forEach { method ->
                    ProbeMethodRow(
                        method = method,
                        selected = state.probeMethod == method,
                        onClick = { onSelectProbeMethod(method) },
                    )
                }
            }

            ProbeInstructionEditor(
                method = state.probeMethod,
                instruction = state.probeInstruction,
                customized = state.probeInstructionCustomized,
                onSave = onSaveProbeInstruction,
                onReset = { onSaveProbeInstruction("") },
            )

            // ---------- 成文风格 ----------
            //
            // 放在追问方式之后：先把「怎么问」定下来，再定「怎么写」。
            // 这两个是同一层的东西（都在调 AI 的手法），
            // 所以摆在一起；服务商配置在它们前面，因为那是接线。
            Spacer(Modifier.height(Spacing.xl))

            SectionLabel("写成什么风格")
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = "这是以后写文章时的默认风格。成文那一页也能临时换一种，" +
                    "换了不影响这里。",
                fontSize = 11.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(Spacing.s))

            Column(Modifier.selectableGroup()) {
                DraftStyle.entries.forEach { style ->
                    StyleRow(
                        style = style,
                        selected = state.draftStyle == style,
                        onClick = { onSelectDraftStyle(style) },
                    )
                }
            }

            DraftStyleNoteEditor(
                note = state.draftStyleNote,
                onSave = onSaveDraftStyleNote,
            )

            Spacer(Modifier.height(Spacing.xl))

            // 自定义才要填地址和模型
            if (state.needsManualInput) {
                Spacer(Modifier.height(Spacing.xl))

                SectionLabel("接口地址")
                Spacer(Modifier.height(Spacing.s))
                InputField(value = state.baseUrl, onChange = onBaseUrl, placeholder = "https://...")

                Spacer(Modifier.height(Spacing.l))

                SectionLabel("模型名")
                Spacer(Modifier.height(Spacing.s))
                InputField(value = state.model, onChange = onModel, placeholder = "模型名")

                Spacer(Modifier.height(Spacing.l))
            }

            // 思考模式。DeepSeek 也要显示——实测 deepseek-flash
            // 不显式关掉时只返回推理过程、正文为空，
            // 表现就是追问一直转圈却一个字都不出。
            // 之前只给 MiMo 显示，DeepSeek 用户遇到时无从下手。
            if (state.selectedProvider != ProviderConfig.CUSTOM) {
                Spacer(Modifier.height(Spacing.l))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(
                        checked = state.thinkingDisabled,
                        onCheckedChange = onThinking,
                    )
                    Column(Modifier.padding(start = Spacing.s)) {
                        Text("关掉思考模式（只影响成文）", fontSize = 14.sp)
                        Text(
                            "追问永远不思考——思考模式开着时，思维链会先把输出额度吃光，" +
                                "正文一个字都出不来，问不出问题。所以这一步不能再交给设置。" +
                                "这里的开关只管写文章那一步。",
                            fontSize = 11.sp,
                            lineHeight = 17.sp,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }

            Spacer(Modifier.height(Spacing.xl))

            // 测试结果
            state.testResult?.let { result ->
                TestResultBanner(result)
                Spacer(Modifier.height(Spacing.m))
            }

            // 按钮：测试在前，保存在后
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                OutlinedButton(
                    onClick = onTest,
                    enabled = !state.testing,
                    modifier = Modifier.weight(1f),
                ) {
                    if (state.testing) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.height(Spacing.xs))
                    } else {
                        Text("测试连接")
                    }
                }
                Button(
                    onClick = onSave,
                    enabled = !state.testing && state.apiKey.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("保存")
                }
            }

            Spacer(Modifier.height(Spacing.m))

            Text(
                text = "先测再存：填错了当场能发现，不用反复保存试错。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(Spacing.xxl))

            // 数据。放在服务商配置之后、页脚之前——
            // 它是「我把东西存在这儿，安全吗」的答案，
            // 而不是一个功能入口，所以不必抢在前面
            SectionLabel("你的数据")
            Spacer(Modifier.height(Spacing.s))
            Text(
                text = "所有记录只存在这台手机上，不上传任何服务器。" +
                    "导出会往「下载/随手记」里放两个文件：data.json 是完整备份，" +
                    "md 是纯文本——任何编辑器都打得开，十年后也还在。",
                fontSize = 11.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(Spacing.m))
            OutlinedButton(
                onClick = onExportAll,
                enabled = !state.exporting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.exporting) "正在整理…" else "导出全部到「下载/随手记」")
            }
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = "每条记录的追问、文章、发芽报告和选题骨架都会一起导出来，不是摘要。",
                fontSize = 11.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.outline,
            )

            Spacer(Modifier.height(Spacing.m))
            OutlinedButton(onClick = onOpenTrash, modifier = Modifier.fillMaxWidth()) {
                Text("回收站")
            }
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = "删掉的记录、文章、选题都在这里，30 天内可以恢复。",
                fontSize = 11.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.outline,
            )

            Spacer(Modifier.height(Spacing.xxl))

            // 版本号 + 版权放在页脚。
            // 版本号是必要的——用户报 bug 时能说清是哪个版本，
            // 否则只能靠猜「是不是最新版」。
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.version_label, BuildConfig.VERSION_NAME),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.app_copyright),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            Spacer(Modifier.height(Spacing.m))
        }
    }
}

@Composable
private fun ProviderRow(
    name: String,
    desc: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(start = Spacing.s)) {
            Text(name, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(
                desc,
                fontSize = 11.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/**
 * 候选模型行。
 *
 * [note] 写的是「什么时候该选它」而不是参数说明——
 * 用户不需要知道 reasoner 是什么，需要知道它会变慢。
 */
@Composable
private fun ModelRow(
    label: String,
    note: String,
    modelName: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(start = Spacing.s)) {
            Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
            if (note.isNotBlank()) {
                Text(
                    note,
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Text(
                modelName,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
            )
        }
    }
}

/**
 * 追问方法行。
 *
 * [ProbeMethod.bestFor] 是这一行的重点——用户不需要理解
 * 「苏格拉底式」是什么，他只需要知道「我记的是什么事，该选哪个」。
 * 只写方法名会让他四个都不敢选。
 */
@Composable
private fun ProbeMethodRow(
    method: ProbeMethod,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(start = Spacing.s)) {
            Text(method.label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(
                method.blurb,
                fontSize = 11.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.outline,
            )
            Text(
                "适合：${method.bestFor}",
                fontSize = 10.sp,
                lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
            )
        }
    }
}

/**
 * 问法编辑区。
 *
 * ## 为什么允许用户改问法
 *
 * 「怎么问」是很私人的事：有人嫌苏格拉底式太绕、总在抠字眼，
 * 有人嫌反方质询太冲、像在吵架。这些偏好没法用几个预设覆盖，
 * 而预设越多，用户越要先读一遍说明才能选。
 *
 * ## 为什么不是空白框
 *
 * 空白框会让人不知道从哪写起。所以默认**预填当前方法那份能用的问法**——
 * 他改几个字就是自己的了，改坏了还有「恢复默认」。
 * 这就是「外壳 + 引导式填空」：给一个能跑起来的骨架，而不是一张白纸。
 *
 * ## 为什么禁区是灰字只读
 *
 * 用户在这里改的只有手法。真正决定「AI 不会给答案」的那几条
 * 不在这个框里，它们由 `Prompts.probeSystem` 固定拼在 prompt 末尾。
 *
 * 把禁区摆在下面不是装饰：用户改完问法会担心「我这么写会不会把它的分寸改坏了」。
 * 看见那几行还在，他才敢改。反过来，如果禁区也能编辑，
 * 这个 App 的核心承诺就成了一句可选项。
 */
@Composable
private fun ProbeInstructionEditor(
    method: ProbeMethod,
    instruction: String,
    customized: Boolean,
    onSave: (String) -> Unit,
    onReset: () -> Unit,
) {
    // 草稿跟着「方法 + 已生效的问法」走。
    //
    // 只 key 方法 id 是不够的：设置页的数据是异步读出来的，
    // 首帧拿到的 probeInstruction 是空串，编辑框会先画成一个空盒子；
    // 等真正的那段问法到达时，因为 key 没变，草稿不会被重置——
    // 用户看到的就是一个空框，还以为是默认问法是空的。
    // 加上 instruction 之后，数据到位就自动填进来。
    // 打字过程中 instruction 不变，所以不会打断输入。
    var draft by remember(method.id, instruction) { mutableStateOf(instruction) }

    Spacer(Modifier.height(Spacing.m))

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "「${method.label}」具体怎么问",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (customized) {
            Text(
                text = "已改过",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
    Spacer(Modifier.height(Spacing.xs))
    Text(
        text = "只写「怎么问」。不用写别说结论、别给建议——那几条在下面，改不掉。",
        fontSize = 10.sp,
        lineHeight = 16.sp,
        color = MaterialTheme.colorScheme.outline,
    )
    Spacer(Modifier.height(Spacing.s))

    androidx.compose.foundation.layout.Box(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(horizontal = Spacing.m, vertical = Spacing.m),
    ) {
        BasicTextField(
            value = draft,
            onValueChange = { draft = it },
            textStyle = MaterialTheme.typography.bodySmall.copy(
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 19.sp,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp),
        )
    }

    Spacer(Modifier.height(Spacing.s))
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (customized) {
            TextButton(onClick = { draft = ""; onReset() }) {
                Text("恢复默认", fontSize = 12.sp)
            }
        }
        Spacer(Modifier.weight(1f))
        TextButton(
            onClick = { onSave(draft) },
            // 没改就不让点：这个按钮点不出任何变化，能点只会让人怀疑
            enabled = draft != instruction,
        ) {
            Text("保存问法", fontSize = 12.sp)
        }
    }

    // ---------- 禁区：只读 ----------
    Spacer(Modifier.height(Spacing.s))
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
            .padding(Spacing.m),
    ) {
        Text(
            text = "无论怎么改，这几条不变",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.xs))
        // 直接读 Prompts 里那一份。界面另写一份的话，
        // 改 prompt 时忘了改界面，用户看到的承诺就不是实际发出去的东西
        Prompts.FORBIDDEN_RULES.forEach { rule ->
            Text(
                text = rule.removePrefix("- "),
                fontSize = 10.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/**
 * 风格行。
 *
 * [DraftStyle.blurb] 是这一行的重点——「观点文」三个字对不同的人
 * 意味着不同的东西，写出来是什么样才是他判断的依据。
 * [DraftStyle.bestFor] 再补一句「什么时候该用」，
 * 因为用户往往是先有场景（要发出去 / 要照着拍），再挑风格。
 */
@Composable
private fun StyleRow(
    style: DraftStyle,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(start = Spacing.s)) {
            Text(style.label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(
                style.blurb,
                fontSize = 11.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.outline,
            )
            Text(
                "适合：${style.bestFor}",
                fontSize = 10.sp,
                lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
            )
        }
    }
}

/**
 * 风格补充要求。
 *
 * ## 为什么这里默认是空的，而追问那边预填
 *
 * 追问那边用户改的是**手法本身**，预设被改掉就不再生效，所以要预填，
 * 让他在一份能跑的文字上改。
 *
 * 风格这边预设**一直在生效**，这个框是「还想额外加点什么」
 * （再口语一点、别用感叹号、多举一个例子）。
 * 预填会把预设复制一遍，用户改一处就变成两处打架——
 * 改了框里的，预设那段还在，到底听谁的说不清。
 */
@Composable
private fun DraftStyleNoteEditor(
    note: String,
    onSave: (String) -> Unit,
) {
    // 草稿跟着已保存的值走：数据是异步读出来的，
    // 首帧拿到空串，等真值到了要能填进来
    var draft by remember(note) { mutableStateOf(note) }

    Spacer(Modifier.height(Spacing.m))

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "还想再加点什么（可留空）",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (note.isNotBlank()) {
            Text(
                text = "已加过",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
    Spacer(Modifier.height(Spacing.xs))
    Text(
        text = "这段是附加在风格之上的，不会替换掉它的结构。" +
            "比如「再口语一点」「别用感叹号」。",
        fontSize = 10.sp,
        lineHeight = 16.sp,
        color = MaterialTheme.colorScheme.outline,
    )
    Spacer(Modifier.height(Spacing.s))

    androidx.compose.foundation.layout.Box(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(horizontal = Spacing.m, vertical = Spacing.m),
    ) {
        if (draft.isEmpty()) {
            Text(
                text = "例：多用短句，别用感叹号，别下结论。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        BasicTextField(
            value = draft,
            onValueChange = { draft = it },
            textStyle = MaterialTheme.typography.bodySmall.copy(
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 19.sp,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp),
        )
    }

    Spacer(Modifier.height(Spacing.s))
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (note.isNotBlank()) {
            TextButton(onClick = { draft = ""; onSave("") }) {
                Text("清掉", fontSize = 12.sp)
            }
        }
        Spacer(Modifier.weight(1f))
        TextButton(
            onClick = { onSave(draft) },
            // 没改就不让点：点不出变化，能点只会让人怀疑存没存上
            enabled = draft.trim() != note.trim(),
        ) {
            Text("保存", fontSize = 12.sp)
        }
    }

    // ---------- 禁区：只读 ----------
    Spacer(Modifier.height(Spacing.s))
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
            .padding(Spacing.m),
    ) {
        Text(
            text = "无论选哪种风格、加什么要求，这几条不变",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.xs))
        // 和追问那边同一套做法：直接读 prompt 里那一份。
        // 界面另写一份的话，改 prompt 时忘了改界面，
        // 用户看到的承诺就不是实际发出去的东西
        Prompts.DRAFT_FORBIDDEN.forEach { rule ->
            Text(
                text = rule.removePrefix("- "),
                fontSize = 10.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.outline,
    )
}

@Composable
private fun InputField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    isPassword: Boolean = false,
) {
    BoxedField(
        value = value,
        onChange = onChange,
        placeholder = placeholder,
        isPasswordField = isPassword,
        visualTransformation = if (isPassword) PasswordVisualTransformation()
        else VisualTransformation.None,
    )
}

@Composable
private fun BoxedField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    isPasswordField: Boolean,
    visualTransformation: VisualTransformation,
) {
    androidx.compose.foundation.layout.Box(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(horizontal = Spacing.m, vertical = Spacing.m),
    ) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            visualTransformation = visualTransformation,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (isPasswordField) KeyboardType.Password
                else KeyboardType.Uri
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun TestResultBanner(result: TestResult) {
    val ok = result is TestResult.Ok
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(
                if (ok) MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
            )
            .padding(Spacing.m),
    ) {
        Text(
            text = when (result) {
                is TestResult.Ok -> "连上了 · ${result.latencyMs} ms"
                is TestResult.Failed -> "没连上"
            },
            fontSize = 13.sp,
            color = if (ok) MaterialTheme.colorScheme.onTertiaryContainer
            else MaterialTheme.colorScheme.onErrorContainer,
        )
        if (result is TestResult.Failed) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = result.reason,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}