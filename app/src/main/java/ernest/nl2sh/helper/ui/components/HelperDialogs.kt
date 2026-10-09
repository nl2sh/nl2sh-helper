package ernest.nl2sh.helper.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import ernest.nl2sh.helper.BridgeAction
import ernest.nl2sh.helper.DeviceAction
import ernest.nl2sh.helper.ui.HelperActions
import ernest.nl2sh.helper.ui.HelperUiState

@Composable
internal fun HelperUiState.HelperDialogs(actions: HelperActions) {
    qrBitmap?.let { QrDialog(it, actions.cancelQr) }
    if (pendingBridgeInstall) {
        AlertDialog(onDismissRequest = { pendingBridgeInstall = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("安装 / 升级 Android Bridge") },
            text = { Text("将按原生版本的签名 Manifest 验证并安装 Bridge，然后打开应用。已有签名不兼容时停止，不自动卸载。无障碍与键盘仍需你在目标设备手动启用。", modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { pendingBridgeInstall = false; actions.manageBridge(BridgeAction.INSTALL) }) { Text("确认安装") } },
            dismissButton = { TextButton(onClick = { pendingBridgeInstall = false }) { Text("取消") } })
    }
    pendingDeviceAction?.let { action ->
        val title = when (action) {
            DeviceAction.UPDATE -> "检查更新并升级"
            DeviceAction.RESTART -> "重启服务"
            DeviceAction.STOP -> "停止服务"
            DeviceAction.CONNECT -> "连接服务"
        }
        AlertDialog(onDismissRequest = { pendingDeviceAction = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(title) },
            text = { Text(if (action == DeviceAction.UPDATE)
                "更新会校验暂存程序、保留旧程序并重启。失败时尝试恢复旧服务。配置和会话保留。"
                else "此操作会取消当前任务并结束待决审批。配置和会话保留。", modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = {
                pendingDeviceAction = null
                actions.confirmDeviceAction(action)
            }) { Text("确认") } },
            dismissButton = { TextButton(onClick = { pendingDeviceAction = null }) { Text("取消") } })
    }
}

@Composable
private fun QrDialog(bitmap: Bitmap, cancel: () -> Unit) {
    AlertDialog(onDismissRequest = cancel, title = { Text("无线调试二维码配对") },
        text = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Image(bitmap.asImageBitmap(), "无线调试配对二维码",
                Modifier.fillMaxWidth().heightIn(max = 320.dp).background(androidx.compose.ui.graphics.Color.White).padding(16.dp))
        } }, confirmButton = {}, dismissButton = { TextButton(onClick = cancel) { Text("取消") } },
        containerColor = MaterialTheme.colorScheme.surface)
}
