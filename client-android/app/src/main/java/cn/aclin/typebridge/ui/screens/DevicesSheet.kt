package cn.aclin.typebridge.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.aclin.typebridge.model.Device
import cn.aclin.typebridge.ui.components.DeviceList

/** 带 AppBar 的全屏 BottomSheet：已配对设备。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesSheet(
    devices: List<Device>,
    current: Device?,
    connectedDeviceId: String?,
    onDismiss: () -> Unit,
    onAdd: () -> Unit,
    onSelect: (Device) -> Unit,
    onRemove: (Device) -> Unit
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
                title = { Text("已配对设备") },
                windowInsets = WindowInsets(0, 0, 0, 0),
                actions = {
                    IconButton(onClick = onAdd) {
                        Icon(Icons.Default.Add, contentDescription = "扫码添加")
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
                DeviceList(
                    devices = devices,
                    current = current,
                    connectedDeviceId = connectedDeviceId,
                    onSelect = onSelect,
                    onRemove = onRemove
                )
            }
        }
    }
}
