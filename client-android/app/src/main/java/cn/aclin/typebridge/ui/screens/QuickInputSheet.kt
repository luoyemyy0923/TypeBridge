package cn.aclin.typebridge.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.aclin.typebridge.model.QuickInput
import cn.aclin.typebridge.model.maskText

/** 带 AppBar 的全屏 BottomSheet：快捷输入。manage = false 时只提供发送。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickInputSheet(
    items: List<QuickInput>,
    canSend: Boolean,
    manage: Boolean = true,
    onDismiss: () -> Unit,
    onSend: (QuickInput) -> Unit,
    onSave: (QuickInput) -> Unit,
    onDelete: (QuickInput) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var editing by remember { mutableStateOf<QuickInput?>(null) }
    var creating by remember { mutableStateOf(false) }

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
                title = { Text("快捷输入 (${items.size})") },
                windowInsets = WindowInsets(0, 0, 0, 0),
                actions = {
                    if (manage) {
                        IconButton(onClick = { creating = true }) {
                            Icon(Icons.Default.Add, contentDescription = "新增快捷输入")
                        }
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
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (items.isEmpty()) {
                    Text(
                        if (manage) "还没有快捷输入，点右上角 + 添加" else "还没有快捷输入",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                items.forEach { item ->
                    QuickInputRow(
                        item = item,
                        canSend = canSend,
                        manage = manage,
                        onSend = { onSend(item) },
                        onEdit = { editing = item },
                        onDelete = { onDelete(item) }
                    )
                }
            }
        }
    }

    if (creating || editing != null) {
        val target = editing
        QuickInputEditorDialog(
            initial = target,
            onDismiss = {
                creating = false
                editing = null
            },
            onConfirm = { input ->
                creating = false
                editing = null
                onSave(input)
            }
        )
    }
}

@Composable
private fun QuickInputRow(
    item: QuickInput,
    canSend: Boolean,
    manage: Boolean,
    onSend: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.encrypted) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = "已加密",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    item.description.ifBlank { "未命名" },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 描述与内容相同时（只填了描述），只显示一行
            val sameContent = item.text == item.description ||
                (item.encrypted && item.text == maskText(item.description))
            if (!sameContent) {
                Text(
                    item.text,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onSend, enabled = canSend) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
            }
            if (manage) {
                // 加密记录不进入编辑器：内存中没有明文，只能删除后重新添加
                if (!item.encrypted) {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "编辑")
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "删除")
                }
            }
        }
    }
}

@Composable
private fun QuickInputEditorDialog(
    initial: QuickInput?,
    onDismiss: () -> Unit,
    onConfirm: (QuickInput) -> Unit
) {
    var description by remember(initial) { mutableStateOf(initial?.description.orEmpty()) }
    var text by remember(initial) { mutableStateOf(initial?.text.orEmpty()) }
    var encrypted by remember(initial) { mutableStateOf(initial?.encrypted ?: false) }
    var enter by remember(initial) { mutableStateOf(initial?.enter ?: false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新增快捷输入" else "编辑快捷输入") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("描述") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("文本") },
                    modifier = Modifier
                        .fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "发送时按回车",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(checked = enter, onCheckedChange = { enter = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "加密保存",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(checked = encrypted, onCheckedChange = { encrypted = it })
                }
                Text(
                    "开启后，文本在数据库中加密存储（密钥保存在加密偏好设置）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank() || description.isNotBlank(),
                onClick = {
                    val desc = description.trim()
                    onConfirm(
                        QuickInput(
                            id = initial?.id ?: 0L,
                            description = desc,
                            // 只填了描述时，自动把描述同步为内容
                            text = text.ifBlank { desc },
                            encrypted = encrypted,
                            enter = enter
                        )
                    )
                }
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
