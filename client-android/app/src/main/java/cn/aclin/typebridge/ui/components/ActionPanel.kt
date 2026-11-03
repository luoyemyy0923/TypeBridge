package cn.aclin.typebridge.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.aclin.typebridge.model.ActionItem

@Composable
fun ActionPanel(
    actions: List<ActionItem>,
    inputText: String,
    sourceName: String = "电脑",
    onExec: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("指令库", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            Text(
                "由 ${sourceName.ifBlank { "电脑" }} 下发 · ${actions.size} 条",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 2.dp)
        ) {
            items(actions, key = { it.id }) { action ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(108.dp)
                ) {
                    FilledTonalButton(
                        onClick = { onExec(action.id) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        Icon(
                            imageVector = if (action.isTextEnter) {
                                Icons.AutoMirrored.Filled.Send
                            } else {
                                Icons.Default.Edit
                            },
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(action.name, maxLines = 1, fontSize = 12.sp, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        action.subtitle,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (inputText.isBlank()) {
            Text(
                "提示：固定文本为空的指令会使用上方输入框里的实时文本",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
