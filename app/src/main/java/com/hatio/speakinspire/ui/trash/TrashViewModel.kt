package com.hatio.speakinspire.ui.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hatio.speakinspire.data.local.TrashType
import com.hatio.speakinspire.data.repository.IdeaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 回收站。
 *
 * ## 为什么三种资源要共用一个回收站
 *
 * 分成三个回收站会让「我刚才删的那个东西去哪了」变成一道找题。
 * 用户按下删除之后，脑子里想的是「刚才那条」，不是「一个 note 类型」。
 *
 * 表里只存 `resourceType` + `resourceId`，名字是**查出来**的——
 * 把标题冗余进回收站会导致「改了标题，回收站里还是旧的」。
 */
@HiltViewModel
class TrashViewModel @Inject constructor(
    private val repo: IdeaRepository,
) : ViewModel() {

    private val _items = MutableStateFlow<List<TrashItem>>(emptyList())
    val items: StateFlow<List<TrashItem>> = _items.asStateFlow()

    init {
        viewModelScope.launch {
            repo.observeTrashed().collect { rows ->
                // 标题逐条查，不走列表快照——快照会排掉回收站里的东西
                _items.value = rows.map { row ->
                    TrashItem(
                        type = row.resourceType,
                        resourceId = row.resourceId,
                        title = repo.titleOf(row.resourceType, row.resourceId)
                            .ifBlank { "（已不存在）" },
                        typeLabel = labelOf(row.resourceType),
                        deletedAt = row.deletedAt,
                    )
                }
            }
        }
    }

    private fun labelOf(type: String): String = when (type) {
        TrashType.NOTE -> "记录"
        TrashType.ARTICLE -> "文章"
        TrashType.SCRIPT -> "选题"
        else -> "内容"
    }

    fun restore(item: TrashItem) {
        viewModelScope.launch { repo.restoreFromTrash(item.type, item.resourceId) }
    }

    fun purge(item: TrashItem) {
        viewModelScope.launch { repo.purge(item.type, item.resourceId) }
    }
}

data class TrashItem(
    val type: String,
    val resourceId: String,
    val title: String,
    val typeLabel: String,
    val deletedAt: Long,
)
