package com.hatio.speakinspire.ui.text

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hatio.speakinspire.ui.theme.Spacing

/**
 * 文字选择。
 *
 * ## 两种不同的需要
 *
 * 1. **只是想复制**——「这段话我想发到微信」。
 *    交给系统原生：长按选词，弹出复制/全选。
 * 2. **想把其中一句捡起来用**——「我第三轮说的那句挺好，
 *    下一轮接着这句往下说」。
 *
 * 第 1 种用 [SelectableText] 就够了：原生菜单不好看，但不用学。
 * 第 2 种必须自己拿到选中的文字，见 [PickableText]。
 *
 * ## 为什么不是一套组件打天下
 *
 * Compose 的 `SelectionContainer` 只给「选中」这个状态，
 * **不把选中的字符串交出来**（TextToolbar 能拿到的只有位置）。
 * 想拿到内容，唯一的办法是把文字放进一个只读输入框，
 * 从它的 selection 区间里切——代价是它长得不像 Text，样式要对齐。
 *
 * 所以只在真正需要内容的地方付这个代价，其余一律用原生那套。
 */

/**
 * 只读文本，长按可选中，菜单是系统原生的复制/全选。
 *
 * 不再挂额外的自定义按钮：要「分享」走页面右上角那个图标，
 * 要「捡一句用」用 [PickableText]。让同一段文字挂两套菜单，
 * 用户会分不清哪个才是对的。
 */
@Composable
fun SelectableText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    SelectionContainer {
        Text(
            text = text,
            style = style,
            modifier = modifier,
            maxLines = maxLines,
            overflow = overflow,
        )
    }
}

/**
 * 可选中、且能把选中的那句交给调用方的文本。
 *
 * ## 为什么用只读输入框而不是 SelectionContainer
 *
 * 因为要拿到「选中了哪几个字」。这件事在 Compose 里只有一条路：
 * 把文字放进 `BasicTextField` 并 `readOnly = true`，
 * 选区会通过 `onValueChange` 回报，于是能切出子串。
 * 顺带白得原生的复制/全选菜单——那本来就是输入框的默认行为。
 *
 * 代价是样式要对齐：输入框有自己的内边距与基线，所以要显式传 [style]；
 * 还要把光标画成透明——只读文本里闪一个光标，用户会以为能点进去改。
 *
 * ## 为什么是回调而不是固定动作
 *
 * 不同页面「捡起一句话」之后的去处不一样：追问页是收进回答，
 * 将来可能是「拿这句发芽」「拿这句起一条新记录」。
 * 这里只负责「选中 → 交出去」，做什么由调用方决定。
 * **这就是自定义选句的口子。**
 */
@Composable
fun PickableText(
    text: String,
    style: TextStyle,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** 动作的名字。不同页面做的事不同，话就得跟着改 */
    pickLabel: String = "收进回答",
) {
    // 选区留在本地：只读文本不会真被改动，
    // 这里接住的只有「用户拖了两个手柄」这一件事。
    // 用 text 当 key——正文被换掉（换了记录）时选区必须作废，
    // 否则旧区间会越界
    var value by remember(text) {
        mutableStateOf(TextFieldValue(text = text, selection = TextRange.Zero))
    }

    Column(modifier) {
        BasicTextField(
            value = value,
            onValueChange = { value = it },
            readOnly = true,
            textStyle = style,
            cursorBrush = SolidColor(Color.Transparent),
            modifier = Modifier.fillMaxWidth(),
        )

        val picked = pickedText(text, value.selection)
        if (picked.isNotBlank()) {
            Spacer(Modifier.height(Spacing.xs))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 把「将要收进去的那句」原样念出来，而不是只报字数。
                //
                // 字数是个不可核对的信息：用户看到「已选 4 字」，
                // 没法判断是不是自己划的那四个字，点下去才发现收错了。
                // 这段文字是他自己写的，一眼就能认出对不对——
                // 显示出来，等于把核对权交回给他。
                Text(
                    text = "「${preview(picked)}」",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = pickLabel,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onPick(picked) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/**
 * 预览用的短句。
 *
 * 太长的选区（用户一拖拖了半屏）如果把整段念出来，
 * 这一行会变成正文的另一份，反而看不清要点。
 * 掐头留尾，两头都是他认得的部分。
 */
private fun preview(s: String, limit: Int = 16): String {
    val flat = s.replace('\n', ' ').trim()
    if (flat.length <= limit) return flat
    return flat.take(limit) + "…"
}

/**
 * 从选区里切出文字。
 *
 * 单独抽成纯函数是因为它有三个容易错的边界：
 * 拖反了方向（start > end）、根本没选（折叠区间）、
 * 区间越界（文字在选中过程中被换掉了，比如切了记录）。
 * 这三种都不能把异常扔给用户。
 *
 * 全部落在「返回空串」上：空串就是「什么都没选中」，
 * 界面据此不显示那个按钮。**不显示，好过显示一个点了没反应的按钮。**
 */
fun pickedText(text: String, selection: TextRange): String {
    if (text.isEmpty()) return ""
    val start = selection.min.coerceIn(0, text.length)
    val end = selection.max.coerceIn(0, text.length)
    if (start == end) return ""
    return text.substring(start, end)
}
