package ernest.nl2sh.helper.ui.connection

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ernest.nl2sh.helper.ConnectionMode
import ernest.nl2sh.helper.ui.HelperActions
import ernest.nl2sh.helper.ui.HelperUiState
import ernest.nl2sh.helper.ui.components.ActionButton
import ernest.nl2sh.helper.ui.components.CardSection

@Composable
internal fun HelperUiState.ConnectionScreen(actions: HelperActions) = CardSection("连接设备", 20) {
    ConnectionModeSelector(actions)
    when (mode) {
        ConnectionMode.LOCAL -> {
            Text("通过本机无线调试以 shell 权限安装并启动 nl2sh。需要 Android 11+。首次在无线调试中选择使用配对码配对设备；可使用配对浮窗，无需分屏。以后可直接启动。", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp))
            ActionButton("打开开发者选项", !busy, Modifier.padding(top = 8.dp)) {
                actions.openDeveloperOptions()
            }
            ActionButton("打开配对浮窗", !busy, Modifier.padding(top = 8.dp)) { actions.openPairingOverlay() }
            Text("首次需允许显示在其他应用上层。若系统设置隐藏浮窗，可在配对通知中输入“端口 六位码”；需允许通知。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Field("临时配对端口", localPairingPort, { localPairingPort = it }, KeyboardType.Number)
            Field("六位配对码", localPairingCode, { localPairingCode = it }, KeyboardType.NumberPassword, true)
            Field("连接端口（可选）", localConnectionPort, { localConnectionPort = it }, KeyboardType.Number)
            Text("连接端口位于无线调试主页，与临时配对端口不同。已配对时留空配对端口和配对码。Web 使用 127.0.0.1。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
        ConnectionMode.TCP -> EndpointForm("在目标设备开启 TCP ADB，首次连接时批准 RSA 授权。", "目标设备地址", "ADB 端口")
        ConnectionMode.WIRELESS_CODE -> {
            EndpointForm("在目标设备的“开发者选项 → 无线调试”中选择“使用配对码配对设备”。输入临时配对地址、端口和六位配对码。", "配对地址", "临时配对端口")
            Field("配对码", pairingCode, { pairingCode = it }, KeyboardType.NumberPassword, true)
        }
        ConnectionMode.WIRELESS_QR -> Text("两台设备连接同一 Wi-Fi。在目标设备的“无线调试 → 使用二维码配对设备”中扫描下方二维码。",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.padding(top = 14.dp))
    }
    ActionButton(if (busy) "正在处理…" else when (mode) {
        ConnectionMode.LOCAL -> "启动 / 首次安装 nl2sh"; ConnectionMode.TCP -> "连接 / 首次安装"; ConnectionMode.WIRELESS_CODE -> "配对并连接"; ConnectionMode.WIRELESS_QR -> "显示配对二维码"
    }, !busy, Modifier.padding(top = 16.dp), onClick = actions.connect)
}

@Composable
private fun HelperUiState.EndpointForm(description: String, hostLabel: String, portLabel: String) {
    Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.padding(top = 14.dp))
    Field(hostLabel, host, { host = it }, KeyboardType.Uri)
    Field(portLabel, port, { port = it }, KeyboardType.Number)
}

@Composable
private fun HelperUiState.Field(label: String, value: String, onChange: (String) -> Unit,
                              keyboardType: KeyboardType, password: Boolean = false) {
    OutlinedTextField(value, onChange, label = { Text(label) }, enabled = !busy, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface))
}
