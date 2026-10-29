package com.hatio.speakinspire.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle

/**
 * 一个「看起来像文字、点上去能编辑」的输入框。
 *
 * ## 为什么不用 TextField
 *
 * `TextField` 自带下划线、内边距和 56dp 的最小高度。把它摆进正文里，
 * 整页会变成一张张表单——而这里要的是「点哪改哪」：
 * 只读的时候和编辑的时候长得**一模一样**，用户不用先分辨
 * 「现在能不能改」，点上去就能改。
 *
 * ## 为什么长按仍能选词
 *
 * `BasicTextField` 用的是原生文本选择，长按选词、复制、全选
 * 都和只读时一样。换成自己拼的点击手势反而会把这条路堵掉。
 */
@Composable
fun EditField(
    value: String,
    onValueChange: (String) -> Unit,
    style: TextStyle,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = false,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = style,
        singleLine = singleLine,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(
                        text = placeholder,
                        style = style.copy(color = MaterialTheme.colorScheme.outlineVariant),
                    )
                }
                inner()
            }
        },
    )
}
