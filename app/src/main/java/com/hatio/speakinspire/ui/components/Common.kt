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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hatio.speakinspire.domain.NoteState
import com.hatio.speakinspire.domain.TopicState
import com.hatio.speakinspire.ui.theme.Spacing
import com.hatio.speakinspire.ui.theme.StatusColors

/** 一张卡片右上角的小标签 */
data class ChipSpec(val label: String, val color: Color)

/**
 * 记录状态的标签。
 *
 * **默认状态不显示**——「记录」这种标签没必要，
 * 用户扫一眼列表就知道哪些没聊过。标签只在非默认状态出现，
 * 这样卡片才不会被 chip 淹没。
 */
fun NoteState.toChip(): ChipSpec? = when (this) {
    NoteState.RECORD -> null
    NoteState.ONGOING -> ChipSpec(label, StatusColors.ongoing)
    NoteState.DEEPENED -> ChipSpec(label, StatusColors.deepened)
    NoteState.COMPOSED -> ChipSpec(label, StatusColors.articlEd)
    NoteState.DERIVED -> ChipSpec(label, StatusColors.derived)
}

/**
 * 选题状态的标签。同上，「待开拍」是默认态，不显示。
 *
 * 只有「已拍摄」和「搁置」需要标出来——这两条是用户在列表里
 * 真正想一眼区分的东西。
 */
fun TopicState.toChip(): ChipSpec? = when (this) {
    TopicState.TODO -> null
    TopicState.SHOT -> ChipSpec(label, StatusColors.shot)
    TopicState.PARKED -> ChipSpec(label, StatusColors.parked)
}

@Composable
fun StateChip(chip: ChipSpec, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(chip.color.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = chip.label,
            fontSize = 10.sp,
            color = chip.color,
        )
    }
}

/** 卡片右上角 ⋮ 里的一个动作 */
data class CardAction(val label: String, val onClick: () -> Unit)

/** 卡片。「记录」「思考」「选题」三个 tab 共用 */
@Composable
fun IdeaCard(
    title: String,
    preview: String,
    timeLabel: String,
    modifier: Modifier = Modifier,
    chip: ChipSpec? = null,
    tagLine: String? = null,
    actions: List<CardAction> = emptyList(),
    onClick: () -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }

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
                if (chip != null) StateChip(chip)
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
                if (actions.isNotEmpty()) {
                    Box {
                        IconButton(
                            onClick = { menuOpen = true },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = "更多",
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            actions.forEach { a ->
                                DropdownMenuItem(
                                    text = { Text(a.label, fontSize = 13.sp) },
                                    onClick = {
                                        menuOpen = false
                                        a.onClick()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 删除确认。
 *
 * 三种资源共用同一套话术：说清楚「不是真删」「多久能恢复」
 * 「会不会连累别的东西」。第三句最重要——用户被级联删除坑过一次，
 * 现在必须明确告诉他这次不会。
 */
@Composable
fun ConfirmDeleteDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = "删除",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除「$title」？", fontSize = 16.sp) },
        text = {
            Text(
                text = message,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
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