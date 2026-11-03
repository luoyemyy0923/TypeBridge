package cn.aclin.typebridge.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
    onClear: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
                title = { Text("历史记录 (${items.size})") },
                windowInsets = WindowInsets(0, 0, 0, 0),
                actions = {
                    TextButton(onClick = onClear, enabled = items.isNotEmpty()) {
                        Text("清空")
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "关闭")
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
                    onDelete = onDelete
                )
            }
        }
    }
}
