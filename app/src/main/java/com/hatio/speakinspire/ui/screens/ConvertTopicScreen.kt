package com.hatio.speakinspire.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hatio.speakinspire.data.model.Angle
import com.hatio.speakinspire.data.model.Material
import com.hatio.speakinspire.data.model.MaterialKind
import com.hatio.speakinspire.data.model.Section
import com.hatio.speakinspire.domain.MaterialKit
import com.hatio.speakinspire.ui.components.EditField
import com.hatio.speakinspire.ui.theme.Spacing
import com.hatio.speakinspire.ui.theme.StatusColors
import com.hatio.speakinspire.ui.topic.TopicStep
import com.hatio.speakinspire.ui.topic.TopicUiState
import kotlinx.coroutines.launch

/**
 * 转选题页。
 *
 * ## 「已拍摄」只是一个标记
 *
 * **这个 App 不负责拍摄。** 用户用手机里的相机或其他短视频 app 拍完之后，
 * 回来点一下「拍过了」就完了。
 *
 * 所以这里没有取景框、没有录制按钮、没有「拍摄中」的中间态——
 * 那些会让用户以为要在这个 App 里拍完，那这个标记就没人点了。
 *
 * 文案也据此设计：不是「开始拍摄」，是「我拍好了」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConvertTopicScreen(
    state: TopicUiState,
    onBack: () -> Unit,
    onDerive: () -> Unit,
    onChooseAngle: (Angle) -> Unit,
    onBackToAngles: () -> Unit,
    onSave: () -> Unit,
    onMarkShot: () -> Unit,
    onUndoShot: () -> Unit,
    onPark: () -> Unit,
    onUnpark: () -> Unit = {},
    onEnterEdit: () -> Unit = {},
    onCancelEdit: () -> Unit = {},
    onSaveEdit: () -> Unit = {},
    onDraftAngle: (String) -> Unit = {},
    onSectionTitle: (Int, String) -> Unit = { _, _ -> },
    onPhrase: (Int, Int, String) -> Unit = { _, _, _ -> },
    onAddPhrase: (Int) -> Unit = {},
    onRemovePhrase: (Int, Int) -> Unit = { _, _ -> },
    onMaterialText: (Int, String) -> Unit = { _, _ -> },
    onMaterialSource: (Int, String) -> Unit = { _, _ -> },
    onAddMaterial: () -> Unit = {},
    onRemoveMaterial: (Int) -> Unit = {},
    onSettings: () -> Unit = {},
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = {
                            // 返回键退的是「最里面那一层」：
                            // 编辑态先退出编辑（草稿在不确认时丢掉，
                            // 所以要有这一步拦住，别直接离页）
                            when {
                                state.editing -> onCancelEdit()
                                state.step == TopicStep.OUTLINE -> onBackToAngles()
                                else -> onBack()
                            }
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = {
                    // 已有一条选题时，这一页就是「拍摄骨架」。
                    // 曾经只看 step 判断，于是从选题 tab 点进来会显示
                    // 「从哪讲起」——而这一页根本没有让你挑角度的地方
                    Text(
                        text = when {
                            state.editing -> "编辑骨架"
                            state.hasExisting -> "拍摄骨架"
                            state.step == TopicStep.ANGLES -> "从哪讲起"
                            else -> "拍摄骨架"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                actions = {
                    // 设置入口全局化：任何一页都能进，返回回到原处
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
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
                .padding(padding),
        ) {
            Box(Modifier.weight(1f)) {
                if (state.hasExisting) {
                    ExistingScript(
                        state = state,
                        onMarkShot = onMarkShot,
                        onUndoShot = onUndoShot,
                        onUnpark = onUnpark,
                        onEnterEdit = onEnterEdit,
                        onCancelEdit = onCancelEdit,
                        onSaveEdit = onSaveEdit,
                        onDraftAngle = onDraftAngle,
                        onSectionTitle = onSectionTitle,
                        onPhrase = onPhrase,
                        onAddPhrase = onAddPhrase,
                        onRemovePhrase = onRemovePhrase,
                        onMaterialText = onMaterialText,
                        onMaterialSource = onMaterialSource,
                        onAddMaterial = onAddMaterial,
                        onRemoveMaterial = onRemoveMaterial,
                        onPark = {
                            onPark()
                            scope.launch {
                                val r = snackbar.showSnackbar(
                                    message = "已移回思考库",
                                    actionLabel = "撤销",
                                    duration = SnackbarDuration.Short,
                                )
                                if (r == SnackbarResult.ActionPerformed) onUnpark()
                            }
                        },
                    )
                } else if (state.step == TopicStep.ANGLES) {
                    AnglePicker(
                        state = state,
                        onDerive = onDerive,
                        onChoose = onChooseAngle,
                    )
                } else {
                    OutlinePreview(state = state, onSave = onSave)
                }
            }
        }
    }
}

/**
 * 角度选择。
 *
 * 三个角度，每个带一句「为什么这个角度适合讲」。
 * 那句话是**帮用户判断**，不是给答案。
 */
@Composable
private fun AnglePicker(
    state: TopicUiState,
    onDerive: () -> Unit,
    onChoose: (Angle) -> Unit,
) {
    if (state.loadingAngles) {
        CenterBox {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp)
                Spacer(Modifier.height(Spacing.l))
                Text(
                    "找几个能讲的角度…",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    if (state.angles.isEmpty()) {
        CenterBox {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "没想出角度",
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(Spacing.s))
                Text(
                    "先把这篇文章写成，再回来挑角度。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.xl))
                Button(onClick = onDerive) {
                    Text("再试一次")
                }
            }
        }
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.l),
    ) {
        Spacer(Modifier.height(Spacing.m))
        Text(
            text = "同一篇文章可以讲好几个角度。挑一个你现在最想说的。",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(Spacing.l))

        state.angles.forEach { angle ->
            AngleCard(angle) { onChoose(angle) }
            Spacer(Modifier.height(Spacing.m))
        }

        Spacer(Modifier.height(Spacing.xxl))
    }
}

@Composable
private fun AngleCard(angle: Angle, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .clickable(onClick = onClick)
            .padding(Spacing.l),
    ) {
        if (angle.kind.isNotBlank()) {
            Text(
                text = angle.kind,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(3.dp))
        }
        Text(
            text = angle.title,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (angle.rationale.isNotBlank()) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = angle.rationale,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 骨架预览。
 *
 * ## 展示的是意群短语，不是逐字稿
 *
 * 「我明明准备过稿子」这样的短语，用户能据此讲出自己的话。
 * 如果给「我明明准备过稿子，但是一上台脑子就空了」这样的逐字稿，
 * 用户会变成朗读——而朗读出来的短视频没有「他在讲自己的事」的感觉。
 */
@Composable
private fun OutlinePreview(state: TopicUiState, onSave: () -> Unit) {
    if (state.loadingOutline) {
        CenterBox {
            CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp)
        }
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.l),
    ) {
        Spacer(Modifier.height(Spacing.m))

        state.chosenAngle?.let {
            Text(
                text = it.title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = "这是骨架，不是稿子。按这些短语讲你自己的话。",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        Spacer(Modifier.height(Spacing.l))

        state.outline?.sections?.forEach { section ->
            OutlineSectionCard(section)
            Spacer(Modifier.height(Spacing.m))
        }

        // 总时长
        if (state.outline != null) {
            Spacer(Modifier.height(Spacing.s))
            Text(
                text = "共 ${state.outline.sections.size} 段 · 大约 ${state.outline.totalSeconds} 秒",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(Spacing.l))

        // 存之前也先让他看见素材。存完才发现「料不多」，
        // 就只能重拆一次角度了
        MaterialBlock(
            materials = state.displayMaterials,
            editing = false,
            onText = { _, _ -> },
            onSource = { _, _ -> },
            onAdd = {},
            onRemove = {},
        )

        Spacer(Modifier.height(Spacing.xl))

        Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
            Text("存成选题")
        }

        Spacer(Modifier.height(Spacing.xxl))
    }
}

@Composable
private fun OutlineSectionCard(section: com.hatio.speakinspire.data.model.OutlineSection) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(Spacing.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = section.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${section.seconds} 秒",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        if (section.goal.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = section.goal,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(Spacing.s))

        // 意群短语。用 chip 形状区分于普通文字——
        // 视觉上就传达「这是提示，不是稿子」
        section.phrases.forEach { phrase ->
            Row(
                Modifier.padding(vertical = 3.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    Modifier
                        .padding(top = 6.dp)
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
                Spacer(Modifier.width(Spacing.s))
                Text(
                    text = phrase,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        // 镜头提示
        if (section.cues.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.s))
            Row(
                Modifier
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(StatusColors.aiFill.copy(alpha = 0.5f))
                    .padding(horizontal = Spacing.s, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                section.cues.forEach { cue ->
                    Text(
                        text = "${cue.icon} ${cue.text}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 已保存的选题。
 *
 * 核心是「标记为已拍摄」这个动作 —— **不是打开相机**。
 * 用户在别的 app 拍完，回来点一下。所以这里没有取景框、
 * 没有录制按钮、没有「拍摄中」的中间态：那些会让用户
 * 以为要在这个 App 里拍完，那这个标记就永远没人点。
 *
 * ## 为什么「编辑」在这里而不是列表里
 *
 * 骨架是一份要照着讲的东西，改它得先看清楚整段。
 * 在列表上给个编辑入口，用户得在看不见内容的情况下进编辑态。
 */
@Composable
private fun ExistingScript(
    state: TopicUiState,
    onMarkShot: () -> Unit,
    onUndoShot: () -> Unit,
    onUnpark: () -> Unit,
    onEnterEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onSaveEdit: () -> Unit,
    onDraftAngle: (String) -> Unit,
    onSectionTitle: (Int, String) -> Unit,
    onPhrase: (Int, Int, String) -> Unit,
    onAddPhrase: (Int) -> Unit,
    onRemovePhrase: (Int, Int) -> Unit,
    onMaterialText: (Int, String) -> Unit,
    onMaterialSource: (Int, String) -> Unit,
    onAddMaterial: () -> Unit,
    onRemoveMaterial: (Int) -> Unit,
    onPark: () -> Unit,
) {
    val sections = if (state.editing) state.draftSections else state.displaySections
    val seconds = sections.sumOf { it.seconds }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.l),
    ) {
        Spacer(Modifier.height(Spacing.m))

        if (state.editing) {
            Text(
                text = "角度",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(2.dp))
            EditField(
                value = state.draftAngle,
                onValueChange = onDraftAngle,
                placeholder = "这个角度一句话说清",
                style = TextStyle(
                    fontSize = 18.sp,
                    lineHeight = 26.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = "改这一句，卡片标题也会跟着变。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.outline,
            )
        } else {
            state.displayAngle?.let {
                Text(
                    text = it,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        Spacer(Modifier.height(Spacing.l))

        sections.forEachIndexed { index, section ->
            if (state.editing) {
                EditableSectionCard(
                    section = section,
                    index = index,
                    onTitle = { onSectionTitle(index, it) },
                    onPhrase = { phraseIndex, text -> onPhrase(index, phraseIndex, text) },
                    onAddPhrase = { onAddPhrase(index) },
                    onRemovePhrase = { phraseIndex -> onRemovePhrase(index, phraseIndex) },
                )
            } else {
                SavedSectionCard(section)
            }
            Spacer(Modifier.height(Spacing.m))
        }

        if (sections.isNotEmpty()) {
            Text(
                text = "共 ${sections.size} 段 · 大约 $seconds 秒",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(Spacing.l))

        // 手边的料。
        //
        // 位置在骨架之后：先看「按什么顺序讲」，再看「手边有什么」。
        // 反过来会让人以为素材是正文，然后就照着念了
        MaterialBlock(
            materials = state.displayMaterials,
            editing = state.editing,
            onText = onMaterialText,
            onSource = onMaterialSource,
            onAdd = onAddMaterial,
            onRemove = onRemoveMaterial,
        )

        Spacer(Modifier.height(Spacing.l))

        if (state.editing) {
            Button(onClick = onSaveEdit, modifier = Modifier.fillMaxWidth()) {
                Text("存好")
            }
            Spacer(Modifier.height(Spacing.xs))
            TextButton(onClick = onCancelEdit, modifier = Modifier.fillMaxWidth()) {
                Text("不改了", fontSize = 13.sp)
            }
        } else {
            if (state.isShot) {
                ShotDoneBlock(onUndo = onUndoShot)
            } else {
                // 措辞很重要：承认拍摄发生在别处。
                // 写「开始拍摄」会让人以为要在这个 App 里拍
                Button(
                    onClick = onMarkShot,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("标记为已拍摄")
                }
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = "用相机或其他 app 拍完，回来点一下就行。",
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            Spacer(Modifier.height(Spacing.m))

            OutlinedButton(onClick = onEnterEdit, modifier = Modifier.fillMaxWidth()) {
                Text("编辑骨架")
            }

            Spacer(Modifier.height(Spacing.m))

            if (state.parked) {
                OutlinedButton(onClick = onUnpark, modifier = Modifier.fillMaxWidth()) {
                    Text("取消搁置")
                }
            } else {
                OutlinedButton(onClick = onPark, modifier = Modifier.fillMaxWidth()) {
                    Text("这个角度先不拍")
                }
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = "文章会留着，选题只是移回思考库。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }

        Spacer(Modifier.height(Spacing.xxl))
    }
}

/**
 * 可编辑的一段骨架。
 *
 * 时长不显示成输入框而是灰字：它不是可改的东西（改了也不影响实际节奏），
 * 但得在里面——用户需要知道这一段大概讲多久。
 * 镜头提示同理，改成纯展示。
 */
@Composable
private fun EditableSectionCard(
    section: Section,
    index: Int,
    onTitle: (String) -> Unit,
    onPhrase: (Int, String) -> Unit,
    onAddPhrase: () -> Unit,
    onRemovePhrase: (Int) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(Spacing.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "第 ${index + 1} 段",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${section.seconds} 秒",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        EditField(
            value = section.title,
            onValueChange = onTitle,
            placeholder = "这一段叫什么",
            style = TextStyle(
                fontSize = 14.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            ),
        )

        Spacer(Modifier.height(Spacing.s))

        section.phrases.forEachIndexed { phraseIndex, phrase ->
            Row(
                Modifier.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
                Spacer(Modifier.width(Spacing.s))
                EditField(
                    value = phrase,
                    onValueChange = { onPhrase(phraseIndex, it) },
                    placeholder = "一句半截的话",
                    style = TextStyle(
                        fontSize = 15.sp,
                        lineHeight = 23.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { onRemovePhrase(phraseIndex) },
                    modifier = Modifier.size(28.dp),
                ) {
                    Text("×", fontSize = 15.sp, color = MaterialTheme.colorScheme.outline)
                }
            }
        }

        TextButton(onClick = onAddPhrase) {
            Text("＋ 加一句", fontSize = 12.sp)
        }

        if (section.cues.isNotEmpty()) {
            Text(
                text = section.cues.joinToString("　") { "${it.icon} ${it.text}" },
                fontSize = 11.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 已保存的一段骨架（只读）。
 *
 * 短语用圆点而不是 chip：视觉上传达「这是提示，不是稿子」。
 * 加了边框的 chip 会被读成按钮，而它按不动——按不动的按钮
 * 比没有按钮更让人恼火。
 */
@Composable
private fun SavedSectionCard(section: Section) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(Spacing.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = section.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${section.seconds} 秒",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Spacer(Modifier.height(Spacing.s))
        section.phrases.forEach { phrase ->
            Row(
                Modifier.padding(vertical = 3.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    Modifier
                        .padding(top = 6.dp)
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
                Spacer(Modifier.width(Spacing.s))
                Text(
                    text = phrase,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        if (section.cues.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.s))
            Row(
                Modifier
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(StatusColors.aiFill.copy(alpha = 0.5f))
                    .padding(horizontal = Spacing.s, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                section.cues.forEach { cue ->
                    Text(
                        text = "${cue.icon} ${cue.text}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 手边的料。
 *
 * ## 为什么它不是「正文」
 *
 * 每条都是独立的短句，中间没有连接词，也**不成段**。
 * 一旦成段，用户就会照着念——而「照着念就不练口语了」是这个 App 的前提。
 * 素材的作用是让人开口时不空，不是替他把话说完。
 *
 * ## 为什么出处必须显示出来
 *
 * 数据、金句、引用只有在带出处时才被收下（见 MaterialKit）。
 * 既然收下的都有出处，展示时就不能把它藏起来——
 * 用户要能一眼看见「这句是谁说的」，才敢在镜头前引用它。
 * 藏着出处，等于把「有据可查」变成「听我说」。
 */
@Composable
private fun MaterialBlock(
    materials: List<Material>,
    editing: Boolean,
    onText: (Int, String) -> Unit,
    onSource: (Int, String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "手边的料",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            if (materials.isNotEmpty()) {
                Text(
                    text = "${materials.size} 条",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = "不成句，也不用念。讲的时候扫一眼，用自己的话接上去。",
            fontSize = 11.sp,
            lineHeight = 17.sp,
            color = MaterialTheme.colorScheme.outline,
        )
        // 编辑态必须把准入规则写出来。
        //
        // 数据 / 金句 / 引用一旦把出处删空，存的时候整条会被丢掉
        // （见 MaterialKit.sanitize）。不提前说的话，用户看到的是
        // 「我明明写了一条，存完就没了」——这种事说不清楚，也没法撤销。
        if (editing) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = MaterialKit.RULE_HINT,
                fontSize = 11.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(Spacing.s))

        if (materials.isEmpty() && !editing) {
            // 空着就直说空着。编一句「暂无素材」毫无信息，
            // 而这句话解释了为什么空：没把握的东西它不写
            Text(
                text = "这次没找到能给的材料。没把握出处的数据和引用，它不会写。",
                fontSize = 11.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        materials.forEachIndexed { index, m ->
            if (editing) {
                EditableMaterialRow(
                    material = m,
                    onText = { onText(index, it) },
                    onSource = { onSource(index, it) },
                    onRemove = { onRemove(index) },
                )
            } else {
                MaterialRow(m)
            }
        }

        if (editing) {
            TextButton(onClick = onAdd) {
                Text("＋ 加一条", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun MaterialRow(m: Material) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .padding(top = 2.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                .padding(horizontal = 5.dp, vertical = 1.dp),
        ) {
            Text(
                text = m.kind.label,
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(Spacing.s))
        Column(Modifier.weight(1f)) {
            Text(
                text = m.text,
                fontSize = 14.sp,
                lineHeight = 21.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            // 有出处才写。没出处就是没有——不替它补一个
            if (m.hasSource) {
                Text(
                    text = "—— ${m.source}",
                    fontSize = 10.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun EditableMaterialRow(
    material: Material,
    onText: (String) -> Unit,
    onSource: (String) -> Unit,
    onRemove: () -> Unit,
) {
    Column(Modifier.padding(vertical = Spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 种类不给改：它决定了「要不要出处」。把例子改成金句却没有出处，
            // 保存时这条会被准入规则丢掉——用户会看着刚写的东西凭空消失
            Box(
                Modifier
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            ) {
                Text(
                    text = material.kind.label,
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onRemove, modifier = Modifier.size(26.dp)) {
                Text("×", fontSize = 14.sp, color = MaterialTheme.colorScheme.outline)
            }
        }
        EditField(
            value = material.text,
            onValueChange = onText,
            placeholder = "一句话，别写成段",
            style = TextStyle(
                fontSize = 14.sp,
                lineHeight = 21.sp,
                color = MaterialTheme.colorScheme.onSurface,
            ),
        )
        if (material.kind.needsSource) {
            EditField(
                value = material.source,
                onValueChange = onSource,
                // 这一类没有出处就存不下来，得提前说清楚，
                // 不然用户会以为自己写完就保存了
                placeholder = "出处（这一类必须填，留空会被丢掉）",
                style = TextStyle(
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        } else {
            EditField(
                value = material.source,
                onValueChange = onSource,
                placeholder = "出处（可以不填）",
                style = TextStyle(
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

/** 已拍摄。诚实显示，不加任何评分 */
@Composable
private fun ShotDoneBlock(onUndo: () -> Unit = {}) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(StatusColors.shot.copy(alpha = 0.15f))
            .padding(Spacing.l),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "✓",
                fontSize = 18.sp,
                color = StatusColors.shot,
            )
            Spacer(Modifier.width(Spacing.s))
            Text(
                text = "已拍摄",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = "这条已经在你的连续记录里了。",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 点错了要有退路。标错日期不会毁掉什么，
        // 但一直错着会让人不信任这个勾
        TextButton(
            onClick = onUndo,
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
        ) {
            Text("撤销标记", fontSize = 12.sp)
        }
    }
}

@Composable
private fun CenterBox(content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().padding(Spacing.xl),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}