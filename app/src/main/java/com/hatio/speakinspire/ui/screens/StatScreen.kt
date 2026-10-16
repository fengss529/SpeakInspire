package com.hatio.speakinspire.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hatio.speakinspire.ui.Stats
import com.hatio.speakinspire.ui.components.EmptyHint
import com.hatio.speakinspire.ui.theme.Spacing
import com.hatio.speakinspire.ui.theme.StatusColors

/**
 * 统计页。
 *
 * ## 为什么不显示「追问轮数」
 *
 * 停留时长受打字速度影响，是伪指标。真正有意义的是转化率——
 * 有多少条记录真的走到了深化。
 *
 *## 中间态的诚实
 *
 * 「已深化但未成文章」的人数单独标出来。
 * 这一环流失最多，知道它流失比数字好看更重要。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatScreen(
    stats: Stats,
    pendingCount: Int,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("统计", style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        if (stats.recorded == 0) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                EmptyHint(
                    icon = "▦",
                    title = "还没有数据",
                    hint = "记几条想法之后，这里会显示转化情况。",
                )
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = Spacing.l),
        ) {
            Spacer(Modifier.height(Spacing.l))

            // 三个大数字
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                BigNumber(stats.recorded, "记录", StatusColors.wait)
                BigNumber(stats.deepened, "已深化", StatusColors.ongoing)
                BigNumber(stats.articlEd, "已成文章", StatusColors.articlEd)
            }

            Spacer(Modifier.height(Spacing.xl))

            Text(
                text = "转化漏斗",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(Spacing.m))

            // 全局口径的漏斗——每一步相对「记录总数」
            FunnelRow("已深化", stats.deepened, stats.recorded, StatusColors.ongoing)
            FunnelRow("已成文章", stats.articlEd, stats.recorded, StatusColors.articlEd)
            FunnelRow("已拍摄", stats.shot, stats.recorded, StatusColors.shot)

            if (pendingCount > 0) {
                Spacer(Modifier.height(Spacing.m))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(StatusColors.aiFill)
                        .padding(Spacing.m),
                ) {
                    Text(
                        text = "另 $pendingCount 条等你决定要不要讲",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            Spacer(Modifier.height(Spacing.xl))

            Text(
                text = "口径说明",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(Spacing.s))
            Text(
                text = "漏斗以「记录总数」为分母。中间态（已深化但还没成文章）" +
                    "单独标出，因为这一环流失最多——看数字好看不如知道它卡在哪。",
                fontSize = 12.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BigNumber(value: Int, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value.toString(),
            fontSize = 32.sp,
            fontWeight = FontWeight.Light,
            color = color,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FunnelRow(label: String, value: Int, total: Int, color: Color) {
    val pct = if (total == 0) 0 else value * 100 / total
    Column(Modifier.padding(vertical = Spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "$value",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "  $pct%",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(end = Spacing.l),
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            // 0% 时不画条——画一小截会让人误以为「有一点点」
            if (pct > 0) {
                Box(
                    Modifier
                        .fillMaxWidth((pct.coerceIn(2, 100)) / 100f)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(color),
                )
            }
        }
    }
}