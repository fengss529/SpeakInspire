package com.hatio.speakinspire.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hatio.speakinspire.ui.theme.Spacing

/**
 * 五个 tab：记录 / 思考 / [＋] / 选题 / 统计
 *
 * ## 加号为什么居中
 *
 * 「随手记」是这个App 最高频的动作——想到就记，摩擦必须归零，
 * 否则习惯第3 天就断了。放在正中第3 位，两个拇指都能轻松够到。
 */
enum class MainTab(val label: String, val icon: String) {
    RECORD("记录", "✎"),
    THINK("思考", "❐"),
    TOPIC("选题", "◎"),
    STAT("统计", "▦"),
    ;

    val index: Int get() = ordinal
}

@Composable
fun MainBottomBar(
    current: MainTab?,
    onSelect: (MainTab) -> Unit,
    onCreate: () -> Unit,
    recordBadge: Int = 0,
    topicBadge: Int = 0,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(64.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            TabItem(MainTab.RECORD, current, onSelect, recordBadge)
            TabItem(MainTab.THINK, current, onSelect, 0)

            CreateButton(onCreate)

            TabItem(MainTab.TOPIC, current, onSelect, topicBadge)
            TabItem(MainTab.STAT, current, onSelect, 0)
        }
    }
}

@Composable
private fun TabItem(
    tab: MainTab,
    current: MainTab?,
    onSelect: (MainTab) -> Unit,
    badge: Int,
) {
    val active = current == tab
    val color = if (active) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onSelect(tab) }
            .padding(horizontal = Spacing.m, vertical = Spacing.s),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box {
            Text(
                text = tab.icon,
                fontSize = 19.sp,
                color = color,
            )
            if (badge > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 7.dp, y = (-4).dp)
                        .size(15.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (badge > 9) "9+" else badge.toString(),
                        fontSize = 9.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = tab.label,
            fontSize = 11.sp,
            color = color,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

/** 居中的大加号。视觉权重最高的按钮。 */
@Composable
private fun CreateButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .shadow(6.dp, CircleShape)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "+",
            fontSize = 30.sp,
            color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.Light,
        )
    }
}