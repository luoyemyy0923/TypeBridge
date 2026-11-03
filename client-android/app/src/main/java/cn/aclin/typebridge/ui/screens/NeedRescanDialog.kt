package cn.aclin.typebridge.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import cn.aclin.typebridge.network.RescanReason
import cn.aclin.typebridge.ui.components.needRescanTitle

@Composable
fun NeedRescanDialog(
    reason: RescanReason,
    detail: String,
    serverName: String = "电脑",
    onRescan: () -> Unit,
    onDismiss: () -> Unit,
    onCopyDiagnostics: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("需要重新扫码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(needRescanTitle(reason, serverName))
                if (detail.isNotBlank()) {
                    Text(detail, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "请走到 ${serverName.ifBlank { "电脑" }} 前，点击菜单栏的「投字」图标 →「显示二维码配对」，然后扫描。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = { TextButton(onClick = onRescan) { Text("重新扫码") } },
        dismissButton = {
            Row {
                TextButton(onClick = onCopyDiagnostics) { Text("复制诊断") }
                TextButton(onClick = onDismiss) { Text("稍后") }
            }
        }
    )
}
