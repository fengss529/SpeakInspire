package com.hatio.speakinspire.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hatio.speakinspire.domain.IdeaState
import com.hatio.speakinspire.ui.theme.Spacing
import com.hatio.speakinspire.ui.theme.StatusColors

/** 状态对应的颜色 */
fun stateColor(state: IdeaState): Color = when (state) {
    IdeaState.WAIT -> StatusColors.wait
    IdeaState.ONGOING -> StatusColors.ongoing
    IdeaState.DEEPENED -> StatusColors.deepened
    IdeaState.ARTICLED -> StatusColors.articlEd
    IdeaState.TOPIC -> StatusColors.topic
    IdeaState.SHOT -> StatusColors.shot
    IdeaState.PARKED -> StatusColors.parked
    IdeaState.STALE -> StatusColors.stale
}

/**
 * 状态标签。
 *
 * **默认状态不显示**——「待深化」这种标签没必要，
 * 用户扫一眼列表就知道哪些没聊过。标签只在非默认状态出现，
 * 这样卡片才不会被 chip 淹没。
 */
@Composable
fun StateChip(state: IdeaState, modifier: Modifier = Modifier) {
    if (state == IdeaState.WAIT) return
    val color = stateColor(state)
    Box(
        modifier = modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = state.label,
            fontSize = 10.sp,
            color = color,
        )
    }
}

/** 想法卡片。「记录」「思考」两个 tab 共用 */
@Composable
fun IdeaCard(
    title: String,
    preview: String,
    state: IdeaState,
    timeLabel: String,
    modifier: Modifier = Modifier,
    tagLine: String? = null,
    onClick: () -> Unit = {},
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ),
        onClick = onClick,
    ) {
        Column(Modifier.padding(Spacing.l)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.size(Spacing.s))
                StateChip(state)
            }

            Spacer(Modifier.height(Spacing.xs))

            Text(
                text = preview,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(Spacing.s))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                if (tagLine != null) {
                    Text(
                        text = tagLine,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = timeLabel,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/** 空状态。四个 tab 各自的文案不同 */
@Composable
fun EmptyHint(
    icon: String,
    title: String,
    hint: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = Spacing.xl, vertical = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = icon, fontSize = 40.sp, color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(Spacing.m))
        Text(
            text = title,
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = hint,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}