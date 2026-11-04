package cn.aclin.typebridge.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.aclin.typebridge.model.QuickInput
import cn.aclin.typebridge.model.QuickInputGroup
import cn.aclin.typebridge.model.maskText

/**
 * 带 AppBar 的全屏 BottomSheet：快捷输入。
 * [manage] = false 时只提供发送，不显示任何管理操作。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickInputSheet(
    items: List<QuickInput>,
    groups: List<QuickInputGroup>,
    canSend: Boolean,
    manage: Boolean = true,
    collapsedGroupIds: Set<Long> = emptySet(),
    onDismiss: () -> Unit,
    onSend: (QuickInput) -> Unit,
    onSave: (QuickInput) -> Unit,
    onDelete: (QuickInput) -> Unit,
    onSaveGroup: (QuickInputGroup) -> Unit = {},
    onDeleteGroup: (QuickInputGroup) -> Unit = {},
    onMoveGroup: (QuickInputGroup, Boolean) -> Unit = { _, _ -> },
    onMoveItem: (QuickInput, Boolean) -> Unit = { _, _ -> },
    onSaveMeta: (QuickInput) -> Unit = {},
    onToggleGroup: (QuickInputGroup) -> Unit = {},
    onTidy: () -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var creatingItem by remember { mutableStateOf(false) }
    var editingItem by remember { mutableStateOf<QuickInput?>(null) }
    var creatingGroup by remember { mutableStateOf(false) }
    var editingGroup by remember { mutableStateOf<QuickInputGroup?>(null) }
    var deletingGroup by remember { mutableStateOf<QuickInputGroup?>(null) }

    val defaultGroupId = groups.firstOrNull { it.isDefault }?.id ?: groups.firstOrNull()?.id
    val knownIds = groups.map { it.id }.toSet()
    val grouped = items.groupBy { item ->
        item.groupId.takeIf { it in knownIds } ?: defaultGroupId ?: 0L
    }
    val visibleGroups = if (manage) {
        groups
    } else {
        groups.filter { grouped[it.id].orEmpty().isNotEmpty() }
    }
    val sortableGroups = groups

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
                        IconButton(onClick = { creatingGroup = true }) {
                            Icon(Icons.Default.CreateNewFolder, contentDescription = "新建分组")
                        }
                        IconButton(onClick = { creatingItem = true }) {
                            Icon(Icons.Default.Add, contentDescription = "新增快捷输入")
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
                if (items.isEmpty() && groups.isEmpty()) {
                    Text(
                        if (manage) "还没有快捷输入，点右上角 + 添加" else "还没有快捷输入",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (groups.isEmpty()) {
                    // 分组尚未加载完成时的兜底：先平铺展示
                    items.forEach { item ->
                        QuickInputRow(
                            item = item,
                            canSend = canSend,
                            manage = manage,
                            canMoveUp = false,
                            canMoveDown = false,
                            onSend = { onSend(item) },
                            onEdit = { editingItem = item },
                            onDelete = { onDelete(item) },
                            onMoveUp = {},
                            onMoveDown = {}
                        )
                    }
                } else {
                    visibleGroups.forEach { group ->
                        val groupItems = grouped[group.id].orEmpty()
                        val groupIndex = sortableGroups.indexOfFirst { it.id == group.id }
                        val expanded = group.id !in collapsedGroupIds
                        GroupHeader(
                            group = group,
                            count = groupItems.size,
                            expanded = expanded,
                            manage = manage,
                            canMoveUp = groupIndex > 0,
                            canMoveDown = groupIndex >= 0 && groupIndex < sortableGroups.lastIndex,
                            onToggle = { onToggleGroup(group) },
                            onMoveUp = { onMoveGroup(group, true) },
                            onMoveDown = { onMoveGroup(group, false) },
                            onRename = { editingGroup = group },
                            onDelete = { deletingGroup = group },
                            onTidy = onTidy
                        )

                        if (!expanded) {
                            // 折叠状态不展示组内条目
                        } else if (groupItems.isEmpty()) {
                            if (manage) {
                                Text(
                                    "暂无条目",
                                    modifier = Modifier.padding(start = 38.dp, top = 2.dp, bottom = 2.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            groupItems.forEachIndexed { index, item ->
                                QuickInputRow(
                                    item = item,
                                    canSend = canSend,
                                    manage = manage,
                                    canMoveUp = index > 0,
                                    canMoveDown = index < groupItems.lastIndex,
                                    onSend = { onSend(item) },
                                    onEdit = { editingItem = item },
                                    onDelete = { onDelete(item) },
                                    onMoveUp = { onMoveItem(item, true) },
                                    onMoveDown = { onMoveItem(item, false) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    val editingEncrypted = editingItem
    if (editingEncrypted != null && editingEncrypted.encrypted) {
        // 加密条目：内容与加密标记不可改，其余（描述 / 回车 / 分组）可改
        EncryptedMetaDialog(
            item = editingEncrypted,
            groups = groups,
            defaultGroupId = defaultGroupId ?: 0L,
            onDismiss = { editingItem = null },
            onConfirm = { updated ->
                editingItem = null
                onSaveMeta(updated)
            }
        )
    } else if (creatingItem || editingEncrypted != null) {
        QuickInputEditorDialog(
            initial = editingEncrypted,
            groups = groups,
            defaultGroupId = defaultGroupId ?: 0L,
            onDismiss = {
                creatingItem = false
                editingItem = null
            },
            onConfirm = { input ->
                creatingItem = false
                editingItem = null
                onSave(input)
            }
        )
    }

    if (creatingGroup || editingGroup != null) {
        GroupEditorDialog(
            initial = editingGroup,
            onDismiss = {
                creatingGroup = false
                editingGroup = null
            },
            onConfirm = { group ->
                creatingGroup = false
                editingGroup = null
                onSaveGroup(group)
            }
        )
    }

    deletingGroup?.let { group ->
        val count = grouped[group.id].orEmpty().size
        AlertDialog(
            onDismissRequest = { deletingGroup = null },
            title = { Text("删除分组") },
            text = {
                Text(
                    if (count > 0) {
                        "「${group.name}」中的 $count 条快捷输入将移动到默认分组。"
                    } else {
                        "确定删除分组「${group.name}」吗？"
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteGroup(group)
                    deletingGroup = null
                }) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingGroup = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun GroupHeader(
    group: QuickInputGroup,
    count: Int,
    expanded: Boolean,
    manage: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggle: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onTidy: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(
            thickness = 0.5.dp,
            modifier = Modifier.padding(top = 4.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onToggle,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    if (expanded) {
                        Icons.Default.KeyboardArrowDown
                    } else {
                        Icons.AutoMirrored.Filled.KeyboardArrowRight
                    },
                    contentDescription = if (expanded) "折叠分组" else "展开分组",
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                group.name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Box(
                modifier = Modifier.size(36.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    count.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (manage) {
                CompactIcon(Icons.Default.KeyboardArrowUp, "上移分组", canMoveUp, onMoveUp)
                CompactIcon(Icons.Default.KeyboardArrowDown, "下移分组", canMoveDown, onMoveDown)
                CompactIcon(Icons.Default.Edit, "重命名分组", true, onRename)
                if (group.isDefault) {
                    CompactIcon(Icons.Default.AutoFixHigh, "整理数据", true, onTidy)
                } else {
                    CompactIcon(Icons.Default.Delete, "删除分组", true, onDelete)
                }
            }
        }
    }
}

@Composable
private fun QuickInputRow(
    item: QuickInput,
    canSend: Boolean,
    manage: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onSend: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 与分组名称左对齐（折叠箭头 32dp + 间距 6dp）
            .padding(start = 38.dp, top = 2.dp, bottom = 2.dp),
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
            if (manage) {
                CompactIcon(Icons.Default.KeyboardArrowUp, "上移", canMoveUp, onMoveUp)
                CompactIcon(Icons.Default.KeyboardArrowDown, "下移", canMoveDown, onMoveDown)
            }
            CompactIcon(Icons.AutoMirrored.Filled.Send, "发送", canSend, onSend)
            if (manage) {
                // 加密条目可编辑描述 / 回车 / 分组（内容与加密标记只读）
                CompactIcon(Icons.Default.Edit, "编辑", true, onEdit)
                CompactIcon(Icons.Default.Delete, "删除", true, onDelete)
            }
        }
    }
}

@Composable
private fun CompactIcon(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(36.dp)
    ) {
        Icon(
            icon,
            contentDescription = description,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun QuickInputEditorDialog(
    initial: QuickInput?,
    groups: List<QuickInputGroup>,
    defaultGroupId: Long,
    onDismiss: () -> Unit,
    onConfirm: (QuickInput) -> Unit
) {
    var description by remember(initial) { mutableStateOf(initial?.description.orEmpty()) }
    var text by remember(initial) { mutableStateOf(initial?.text.orEmpty()) }
    var encrypted by remember(initial) { mutableStateOf(initial?.encrypted ?: false) }
    var enter by remember(initial) { mutableStateOf(initial?.enter ?: false) }
    var groupId by remember(initial) {
        mutableStateOf(initial?.groupId?.takeIf { it != 0L } ?: defaultGroupId)
    }
    var groupMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新增快捷输入" else "编辑快捷输入") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
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
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "分组",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Box {
                        TextButton(onClick = { groupMenu = true }) {
                            Text(
                                groups.firstOrNull { it.id == groupId }?.name ?: "默认分组"
                            )
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(
                            expanded = groupMenu,
                            onDismissRequest = { groupMenu = false }
                        ) {
                            groups.forEach { group ->
                                DropdownMenuItem(
                                    text = { Text(group.name) },
                                    onClick = {
                                        groupId = group.id
                                        groupMenu = false
                                    }
                                )
                            }
                        }
                    }
                }

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
                            enter = enter,
                            groupId = groupId
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

@Composable
private fun EncryptedMetaDialog(
    item: QuickInput,
    groups: List<QuickInputGroup>,
    defaultGroupId: Long,
    onDismiss: () -> Unit,
    onConfirm: (QuickInput) -> Unit
) {
    var description by remember(item) { mutableStateOf(item.description) }
    var enter by remember(item) { mutableStateOf(item.enter) }
    var groupId by remember(item) {
        mutableStateOf(item.groupId.takeIf { it != 0L } ?: defaultGroupId)
    }
    var groupMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑快捷输入") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("描述") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "分组",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Box {
                        TextButton(onClick = { groupMenu = true }) {
                            Text(groups.firstOrNull { it.id == groupId }?.name ?: "默认分组")
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(
                            expanded = groupMenu,
                            onDismissRequest = { groupMenu = false }
                        ) {
                            groups.forEach { group ->
                                DropdownMenuItem(
                                    text = { Text(group.name) },
                                    onClick = {
                                        groupId = group.id
                                        groupMenu = false
                                    }
                                )
                            }
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "发送时按回车",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(checked = enter, onCheckedChange = { enter = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(
                    item.copy(
                        description = description.trim(),
                        enter = enter,
                        groupId = groupId
                    )
                )
            }) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun GroupEditorDialog(
    initial: QuickInputGroup?,
    onDismiss: () -> Unit,
    onConfirm: (QuickInputGroup) -> Unit
) {
    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新建分组" else "重命名分组") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("分组名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onConfirm(
                        QuickInputGroup(
                            id = initial?.id ?: 0L,
                            name = name.trim(),
                            sortOrder = initial?.sortOrder ?: 0,
                            isDefault = initial?.isDefault ?: false
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
