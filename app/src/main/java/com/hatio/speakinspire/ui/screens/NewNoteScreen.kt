package com.hatio.speakinspire.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hatio.speakinspire.ui.theme.Spacing

/**
 * 新建速记。
 *
 * ## 目标是 10 秒完成，所以只有两栏
 *
 * 没有标签选择、没有来源标注、没有「保存草稿」——
 * 这些都会把10 秒变成 30 秒，而习惯第 3 天就断了。
 *
 * 键盘自动弹出，聚焦在标题上——因为这是「随手记」，
 * 标题通常一句话就想好了，正文可以后面再补。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewNoteScreen(
    onSave: (String, String, () -> Unit) -> Unit,
    onBack: () -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        focus.requestFocus()
    }

    val canSave = title.isNotBlank() || body.isNotBlank()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = {
                    Text(
                        text = "记一下",
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = Spacing.l)
                .imePadding(),
        ) {
            Spacer(Modifier.height(Spacing.m))

            BasicTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
                textStyle = MaterialTheme.typography.titleMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { inner ->
                    if (title.isEmpty()) {
                        Text(
                            text = "一句话概括",
                            fontSize = 17.sp,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    inner()
                },
            )

            Spacer(Modifier.height(Spacing.m))

            BasicTextField(
                value = body,
                onValueChange = { body = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { inner ->
                    if (body.isEmpty()) {
                        Text(
                            text = "展开说说。想到什么写什么，不用整理。",
                            fontSize = 15.sp,
                            lineHeight = 24.sp,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    inner()
                },
            )

            Spacer(Modifier.height(Spacing.m))

            Button(
                onClick = {
                    keyboard?.hide()
                    onSave(title, body) { onBack() }
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("保存", modifier = Modifier.padding(vertical = Spacing.xs))
            }

            Spacer(Modifier.height(Spacing.l))

            Text(
                text = "只保存，不追问。深化是另一件事——想聊的时候再回来。",
                fontSize = 11.sp,
                lineHeight = 17.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}