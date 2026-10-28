package com.hatio.speakinspire.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import com.hatio.speakinspire.ui.components.ConfirmDeleteDialog
import com.hatio.speakinspire.ui.components.EmptyHint
import com.hatio.speakinspire.ui.theme.Spacing
import com.hatio.speakinspire.ui.trash.TrashItem

/**
 * 回收站。
 *
 * ## 这页之前根本不存在
 *
 * `trashed` 表和 `moveToTrash` 一直在，但没有任何入口——
 * 等于「删除」这个能力从来没真正上线过，按下就是永久删除。
 * 三种资源独立之后每类都能删，这个洞必须补上。
 *
 * ## 为什么会话里写「彻底删除不碰别的」
 *
 * 用户被级联删除坑过一次（删一条记录，文章和选题全没了）。
 * 现在要明确告诉他：删这个，就只删这个。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    items: List<TrashItem>,
    onBack: () -> Unit,
    onRestore: (TrashItem) -> Unit,
    onPurge: (TrashItem) -> Unit,
) {
    var pendingPurge by remember { mutableStateOf<TrashItem?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = { Text("回收站", style = MaterialTheme.typography.titleMedium) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        if (items.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                EmptyHint(
                    icon = "♲",
                    title = "回收站是空的",
                    hint = "删掉的东西会先放这里，30 天内都能恢复。",
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(
                    start = Spacing.l,
                    end = Spacing.l,
                    top = Spacing.s,
                    bottom = Spacing.xl,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                items(items, key = { "${it.type}_${it.resourceId}" }) { item ->
                    TrashRow(
                        item = item,
                        onRestore = { onRestore(item) },
                        onPurge = { pendingPurge = item },
                    )
                }
            }
        }
    }

    pendingPurge?.let { item ->
        ConfirmDeleteDialog(
            title = item.title,
            message = "这次是真的删除，恢复不了。\n只删这一条${item.typeLabel}，别的东西不动。",
            confirmLabel = "彻底删除",
            onConfirm = {
                onPurge(item)
                pendingPurge = null
            },
            onDismiss = { pendingPurge = null },
        )
    }
}

@Composable
private fun TrashRow(
    item: TrashItem,
    onRestore: () -> Unit,
    onPurge: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ),
    ) {
        Column(Modifier.padding(Spacing.l)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.typeLabel,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = Spacing.s),
                )
                Text(
                    text = item.title,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onRestore) { Text("恢复", fontSize = 13.sp) }
                TextButton(onClick = onPurge) {
                    Text("彻底删除", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
