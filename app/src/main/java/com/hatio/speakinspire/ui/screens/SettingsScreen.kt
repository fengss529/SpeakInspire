package com.hatio.speakinspire.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
 * ## 为什么改成「卡片分组」
 *
 * 原来是「小标题 + 一串单选行」竖着排下来，所有东西看起来一样重：
 * 服务商、API Key、追问方式、成文风格都在同一个平面上，
 * 用户扫一遍看不出这是**四件事**，只看到二十来行字。
 *
 * 现在每一组是一张卡片：图标 + 组名 + 一句这组在管什么。
 * 卡片之间的留白比卡片内的行距大得多——
 * **分组靠的是空间，不是又加一条分隔线。**
 *
 * ## 为什么模型和思考模式被去掉了
 *
 * - 模型：换服务商时已经带上了它自己的默认模型，用户手上没有一个
 *   「该选哪个模型」的判断依据（参数说明他不需要，也不该需要）。
 *   选项摆在那里只会让人以为选错会出问题。自定义服务商仍在卡片里手填模型名。
 * - 思考模式：它其实不是一个选项。追问必须关（不关就会被思维链吃光输出额度，
 *   表现为「点了没反应」），成文也必须关（开着时模型在思维链里把话说完，
 *   正文只剩 `{}`）。两条路都在请求层强制关闭，
 *   界面上的开关从来没有真正生效过——**一个不生效的开关比没有更糟**，
 *   用户会以为自己调过它就修好了某个问题。
 *
 * ## 为什么「测试 / 保存」挪进了第一张卡片
 *
 * 它们只作用于服务商配置。原来漂在页面底部，离 API Key 隔着三组设置，
 * 用户填完 key 还要往下找按钮。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onSelectProvider: (String) -> Unit,
    onBaseUrl: (String) -> Unit,
    onModel: (String) -> Unit,
    onApiKey: (String) -> Unit,
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
    // 用户可能还想确认「选的是哪个服务」「Key 存上了没」。
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
        containerColor = PageBg,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = { Text("设置", style = MaterialTheme.typography.titleMedium) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PageBg),
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
            Spacer(Modifier.height(Spacing.s))

            // ---------- 1. 服务商 ----------
            SettingsSection(
                icon = Icons.Outlined.Bolt,
                title = "用哪个服务",
                subtitle = "决定 AI 从哪来。三家都有免费额度，换一家不用重装。",
            ) {
                Column(Modifier.selectableGroup()) {
                    ProviderRow(
                        name = "DeepSeek",
                        desc = "便宜、快。国内直连不用梯子。",
                        selected = state.selectedProvider == ProviderConfig.DEEPSEEK,
                        onClick = { onSelectProvider(ProviderConfig.DEEPSEEK) },
                    )
                    ProviderRow(
                        name = "小米 MiMo",
                        desc = "同样便宜，有免费额度。",
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

                ThinDivider(Modifier.padding(vertical = Spacing.s))

                // 自定义才要填地址和模型
                if (state.needsManualInput) {
                    SubLabel("接口地址")
                    Spacer(Modifier.height(Spacing.xs))
                    InputField(
                        value = state.baseUrl,
                        onChange = onBaseUrl,
                        placeholder = "https://...",
                    )
                    Spacer(Modifier.height(Spacing.m))

                    SubLabel("模型名")
                    Spacer(Modifier.height(Spacing.xs))
                    InputField(value = state.model, onChange = onModel, placeholder = "模型名")
                    Spacer(Modifier.height(Spacing.m))
                }

                SubLabel("API Key")
                Spacer(Modifier.height(Spacing.xs))
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

                Spacer(Modifier.height(Spacing.m))

                // 按钮：测试在前，保存在后。
                // 填错 key 是常态，先测一次能当场发现，不用反复保存试错
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    OutlinedButton(
                        onClick = onTest,
                        enabled = !state.testing,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                    ) {
                        if (state.testing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text("测试连接", fontSize = 13.sp)
                        }
                    }
                    Button(
                        onClick = onSave,
                        enabled = !state.testing && state.apiKey.isNotBlank(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                    ) {
                        Text("保存", fontSize = 13.sp)
                    }
                }

                state.testResult?.let { result ->
                    Spacer(Modifier.height(Spacing.m))
                    TestResultBanner(result)
                }
            }

            Spacer(Modifier.height(Spacing.m))

            // ---------- 2. 追问方式 ----------
            //
            // 换的是提问手法，不是权力边界——三种方法都只能问不能答。
            // 这一点在卡片副标题里说清楚，否则用户会以为切到某种方法
            // 就能拿到建议。
            SettingsSection(
                icon = Icons.Outlined.Forum,
                title = "用哪种追问方式",
                subtitle = "只换问法，不换规矩：三种都只提问、不给答案。",
            ) {
                Column(Modifier.selectableGroup()) {
                    ProbeMethod.entries.forEach { method ->
                        ProbeMethodRow(
                            method = method,
                            selected = state.probeMethod == method,
                            onClick = {
                                onSelectProbeMethod(method)
                                // 这个动作没有「保存」按钮，也没有别的可见结果——
                                // 只有圆点挪了位置。用户会怀疑「是不是还要点保存」。
                                // 一句回执把这个疑问当场消掉，顺便说清它从哪一句开始生效：
                                // **已经在等回答的那一个问题不会变**，那是同一场对话的一部分。
                                scope.launch {
                                    snackbar.showSnackbar(
                                        "下一条问题起，按「${method.shortLabel}」问"
                                    )
                                }
                            },
                        )
                    }
                }

                AdvancedHeader("进阶 · 可以改问法")

                ProbeInstructionEditor(
                    method = state.probeMethod,
                    instruction = state.probeInstruction,
                    customized = state.probeInstructionCustomized,
                    onSave = onSaveProbeInstruction,
                    onReset = { onSaveProbeInstruction("") },
                )
            }

            Spacer(Modifier.height(Spacing.m))

            // ---------- 3. 成文风格 ----------
            //
            // 和追问方式同一层（都在调 AI 的手法），所以排在一起
            SettingsSection(
                icon = Icons.Outlined.Article,
                title = "写成什么风格",
                subtitle = "以后写文章时的默认风格。成文那一页也能临时换，换了不影响这里。",
            ) {
                Column(Modifier.selectableGroup()) {
                    DraftStyle.entries.forEach { style ->
                        StyleRow(
                            style = style,
                            selected = state.draftStyle == style,
                            onClick = { onSelectDraftStyle(style) },
                        )
                    }
                }

                AdvancedHeader("进阶 · 还能加要求")

                DraftStyleNoteEditor(
                    note = state.draftStyleNote,
                    onSave = onSaveDraftStyleNote,
                )
            }

            Spacer(Modifier.height(Spacing.m))

            // ---------- 4. 数据 ----------
            //
            // 它是「我把东西存在这儿，安全吗」的答案，
            // 而不是一个功能入口，所以放在最后一组。
            //
            // 但这一组**没有二级页**：它上面那行「你的数据」是个分组标签，
            // 点不动；能点的是下面两行。用户反馈「这个按钮是干啥的，
            // 没发现用处」——他点的是标签，看到没反应就以为坏了。
            // 副标题现在直接说明「能点的是下面两行」。
            SettingsSection(
                icon = Icons.Outlined.FileDownload,
                title = "你的数据",
                subtitle = "能点的是下面两行。所有内容只存在这台手机上，不上传任何服务器。",
            ) {
                ActionRow(
                    icon = Icons.Outlined.FileDownload,
                    title = if (state.exporting) "正在整理…" else "导出全部内容",
                    subtitle = "data.json 是完整备份，md 是任何编辑器都打得开的纯文本",
                    enabled = !state.exporting,
                    onClick = onExportAll,
                )
                ActionRow(
                    icon = Icons.Outlined.DeleteOutline,
                    title = "回收站",
                    subtitle = "删掉的记录、文章、选题都在这里，30 天内可以恢复",
                    onClick = onOpenTrash,
                )
            }

            Spacer(Modifier.height(Spacing.xl))

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
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.8f),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.app_copyright),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.8f),
                )
            }

            Spacer(Modifier.height(Spacing.xl))
        }
    }
}

// ---------- 视觉基础件 ----------

/** 页面底色。比卡片深一点点，卡片才浮得起来 */
private val PageBg = Color(0xFFF5F3F9)
private val CardBg = Color(0xFFFFFFFF)

/**
 * 一组设置。
 *
 * 图标 + 组名 + 一句这组在管什么，下面是选项。
 * 组名是这一页唯一用 15sp 半粗的地方——
 * 层级靠字号和留白，不靠再画一套框线。
 */
@Composable
private fun SettingsSection(
    icon: ImageVector,
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(CardBg)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.10f),
                shape = RoundedCornerShape(18.dp),
            ),
    ) {
        Row(
            Modifier.padding(
                start = Spacing.l,
                end = Spacing.l,
                top = Spacing.l,
                bottom = Spacing.m,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(Modifier.padding(start = Spacing.m)) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }

        ThinDivider()

        Column(Modifier.padding(Spacing.s)) {
            content()
        }
    }
}

/** 卡片内的小标题，比正文小、比灰字重一点 */
@Composable
private fun SubLabel(text: String) {
    Text(
        text = text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 进阶区的分隔。
 *
 * 「进阶」两个字不是装饰：它告诉用户上面那些选完就能用，
 * 下面这些不动也没关系——**不动也不会错过什么**。
 */
@Composable
private fun AdvancedHeader(text: String) {
    Spacer(Modifier.height(Spacing.m))
    Row(Modifier.padding(horizontal = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
        ThinDivider(Modifier.weight(1f), inset = 0.dp)
        Text(
            text = text,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.9f),
            modifier = Modifier.padding(horizontal = Spacing.s),
        )
        ThinDivider(Modifier.weight(1f), inset = 0.dp)
    }
    Spacer(Modifier.height(Spacing.xs))
}

@Composable
private fun ThinDivider(modifier: Modifier = Modifier, inset: androidx.compose.ui.unit.Dp = 0.dp) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = inset)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.09f)),
    )
}

/**
 * 选中指示器。
 *
 * 不用 M3 的 RadioButton：它自带 48dp 触摸目标，塞在行尾会把行高撑起来，
 * 而这些行整行都可点，不需要那个额外的目标。
 */
@Composable
private fun SelectionDot(selected: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    val idle = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    Box(
        Modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(if (selected) accent else Color.Transparent)
            .border(1.5.dp, if (selected) accent else idle, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(Color.White),
            )
        }
    }
}

/**
 * 一个可选项。
 *
 * 选中时整行染色 + 标题变主色——**两处同时变**，
 * 扫一眼就知道选的是哪一条，不必去找那个小圆点。
 */
@Composable
private fun OptionRow(
    title: String,
    blurb: String,
    footnote: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)
                else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.m, vertical = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = blurb,
                fontSize = 11.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.outline,
            )
            if (!footnote.isNullOrBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = footnote,
                    fontSize = 10.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.75f),
                )
            }
        }
        Spacer(Modifier.size(Spacing.m))
        SelectionDot(selected)
    }
}

/** 一组里的一条动作（导出、回收站），右侧一个箭头表示会跳到别处 */
@Composable
private fun ActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Spacing.m, vertical = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp),
            )
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = Spacing.m),
        ) {
            Text(
                text = title,
                fontSize = 14.sp,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Icon(
            imageVector = Icons.Outlined.ChevronRight,
            contentDescription = null,
            // 箭头用主色而不是灰色：这一组里「能点的是哪些」全靠它指出来。
            // 之前是 50% 灰，远看和分组标题的小图标分不出来，
            // 于是整张卡被当成一个可以点进去的东西
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun ProviderRow(
    name: String,
    desc: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    OptionRow(title = name, blurb = desc, footnote = null, selected = selected, onClick = onClick)
}

/**
 * 追问方法行。
 *
 * [ProbeMethod.bestFor] 是这一行的重点——用户不需要理解
 * 「苏格拉底式」是什么，他只需要知道「我记的是什么事，该选哪个」。
 * 只写方法名会让他三个都不敢选。
 */
@Composable
private fun ProbeMethodRow(
    method: ProbeMethod,
    selected: Boolean,
    onClick: () -> Unit,
) {
    OptionRow(
        title = method.label,
        blurb = method.blurb,
        footnote = "适合：${method.bestFor}",
        selected = selected,
        onClick = onClick,
    )
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
    OptionRow(
        title = style.label,
        blurb = style.blurb,
        footnote = "适合：${style.bestFor}",
        selected = selected,
        onClick = onClick,
    )
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

    Column(Modifier.padding(horizontal = Spacing.s, vertical = Spacing.m)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "「${method.label}」具体怎么问",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
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
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                .padding(horizontal = Spacing.m, vertical = Spacing.m)
                .heightIn(min = 116.dp),
        )

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
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                .padding(Spacing.m),
        ) {
            Text(
                text = "无论怎么改，这几条不变",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
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

    Column(Modifier.padding(horizontal = Spacing.s, vertical = Spacing.m)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "还想再加点什么（可留空）",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
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

        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
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
                    .heightIn(min = 56.dp),
            )
        }

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
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                .padding(Spacing.m),
        ) {
            Text(
                text = "无论选哪种风格、加什么要求，这几条不变",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
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
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(horizontal = Spacing.m, vertical = Spacing.m),
    ) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall.copy(
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
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
            .clip(RoundedCornerShape(12.dp))
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
            fontWeight = FontWeight.Medium,
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
