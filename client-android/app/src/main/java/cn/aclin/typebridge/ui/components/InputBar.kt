package cn.aclin.typebridge.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 撑满剩余高度的大文本域 + 底部「发送时按回车」开关、快捷输入入口与「发送」按钮。
 */
@Composable
fun InputBar(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    targetName: String = "电脑",
    value: String,
    onValueChange: (String) -> Unit,
    sendEnter: Boolean,
    onSendEnterChange: (Boolean) -> Unit,
    onQuickInput: () -> Unit,
    onSend: () -> Unit
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            enabled = enabled,
            placeholder = {
                Text(
                    if (enabled) {
                        "输入要发送到 ${targetName.ifBlank { "电脑" }} 的文字…"
                    } else {
                        "未连接"
                    }
                )
            },
            maxLines = Int.MAX_VALUE,
            textStyle = MaterialTheme.typography.bodyLarge
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Switch(
                checked = sendEnter,
                onCheckedChange = onSendEnterChange,
                enabled = enabled
            )
            Spacer(Modifier.width(8.dp))
            Text("发送时按回车", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onQuickInput) {
                Icon(Icons.Default.Bolt, contentDescription = "快捷输入")
            }
            Button(
                onClick = onSend,
                enabled = enabled && value.isNotBlank()
            ) {
                Text("发送")
            }
        }
    }
}
