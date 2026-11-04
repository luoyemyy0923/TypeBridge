package cn.aclin.typebridge.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.aclin.typebridge.model.HistoryItem
import cn.aclin.typebridge.ui.components.HistoryList

/** 带 AppBar 的全屏 BottomSheet：历史记录（支持删除单条）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorySheet(
    items: List<HistoryItem>,
    onDismiss: () -> Unit,
    onResend: (HistoryItem) -> Unit,
    onEdit: (HistoryItem) -> Unit,
    onDelete: (HistoryItem) -> Unit,
    onDeleteSelected: (List<HistoryItem>) -> Unit,
    onClear: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showClearConfirm by remember { mutableStateOf(false) }

    fun exitSelection() {
        selectionMode = false
        selectedIds = emptySet()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = null,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
        ) {
            TopAppBar(
                title = {
                    Text(
                        if (selectionMode) "已选择 ${selectedIds.size} 项"
                        else "历史记录 (${items.size})"
                    )
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
                actions = {
                    if (selectionMode) {
                        TextButton(onClick = { exitSelection() }) {
                            Text("取消")
                        }
                        TextButton(
                            enabled = selectedIds.isNotEmpty(),
                            onClick = {
                                onDeleteSelected(items.filter { it.id in selectedIds })
                                exitSelection()
                            }
                        ) {
                            Text("删除")
                        }
                    } else {
                        IconButton(
                            onClick = { selectionMode = true },
                            enabled = items.isNotEmpty()
                        ) {
                            Icon(Icons.Default.Checklist, contentDescription = "多选")
                        }
                        IconButton(
                            onClick = { showClearConfirm = true },
                            enabled = items.isNotEmpty()
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "清空")
                        }
                    }
                }
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                HistoryList(
                    items = items,
                    onResend = onResend,
                    onEdit = onEdit,
                    onDelete = onDelete,
                    selectionMode = selectionMode,
                    selectedIds = selectedIds,
                    onToggleSelect = { item ->
                        selectedIds = if (item.id in selectedIds) {
                            selectedIds - item.id
                        } else {
                            selectedIds + item.id
                        }
                    }
                )
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空历史记录") },
            text = { Text("确定清空全部 ${items.size} 条历史记录吗？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    onClear()
                }) {
                    Text("清空")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            }
        )
    }
}
