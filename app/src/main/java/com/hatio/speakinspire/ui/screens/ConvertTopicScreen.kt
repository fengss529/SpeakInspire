package com.hatio.speakinspire.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hatio.speakinspire.data.model.Angle
import com.hatio.speakinspire.data.model.Section
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
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = { if (state.step == TopicStep.OUTLINE) onBackToAngles() else onBack() },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = {
                    Text(
                        text = if (state.step == TopicStep.ANGLES) "从哪讲起" else "拍摄骨架",
                        style = MaterialTheme.typography.titleMedium,
                    )
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
                        onPark = {
                            onPark()
                            scope.launch {
                                val r = snackbar.showSnackbar(
                                    message = "已移回思考库",
                                    actionLabel = "撤销",
                                    duration = SnackbarDuration.Short,
                                )
                                if (r == SnackbarResult.ActionPerformed) onUndoShot()
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
 * 核心是「我拍好了」这个标记 —— **不是打开相机**。
 * 用户在别的 app 拍完，回来点一下。
 */
@Composable
private fun ExistingScript(
    state: TopicUiState,
    onMarkShot: () -> Unit,
    onPark: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.l),
    ) {
        Spacer(Modifier.height(Spacing.m))

        state.displayAngle?.let {
            Text(
                text = it,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Spacer(Modifier.height(Spacing.l))

        state.displaySections.forEach { section ->
            SavedSectionCard(section)
            Spacer(Modifier.height(Spacing.m))
        }

        if (state.displaySections.isNotEmpty()) {
            Text(
                text = "共 ${state.displaySections.size} 段 · 大约 ${state.totalSeconds} 秒",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(Spacing.xl))

        // 已拍摄状态
        if (state.isShot) {
            ShotDoneBlock()
        } else {
            // 措辞很重要：「我拍好了」而不是「开始拍摄」
            // 前者承认拍摄发生在别处，后者会让人以为要在这个 App 里拍
            Button(
                onClick = onMarkShot,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("我拍好了")
            }
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = "用相机或其他 app 拍完，回来点一下就行。",
                fontSize = 11.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        Spacer(Modifier.height(Spacing.l))

        // 左滑的等价操作：先留着
        if (!state.parked) {
            OutlinedButton(
                onClick = onPark,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("这个角度先不拍")
            }
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = "文章会留着，选题只是移回思考库。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        Spacer(Modifier.height(Spacing.xxl))
    }
}

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
    }
}

/** 已拍摄。诚实显示，不加任何评分 */
@Composable
private fun ShotDoneBlock() {
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