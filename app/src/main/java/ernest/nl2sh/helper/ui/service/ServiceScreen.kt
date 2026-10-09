package ernest.nl2sh.helper.ui.service

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ernest.nl2sh.helper.R
import ernest.nl2sh.helper.DeviceAction
import ernest.nl2sh.helper.ui.HelperActions
import ernest.nl2sh.helper.ui.HelperUiState
import ernest.nl2sh.helper.ui.components.ActionButton
import ernest.nl2sh.helper.ui.components.CardSection

@Composable
internal fun HelperUiState.ServiceScreen(actions: HelperActions) {
    Column {
        ServiceCard()
        ActionButton("在浏览器中打开 nl2sh", webUrl != null,
            Modifier.padding(top = 16.dp), onClick = actions.openBrowser)
        Text("网络提示", color = colorResource(R.color.ui_warning),
            fontSize = 13.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 20.dp))
        Text("目标 Web 界面无需登录，请仅在可信网络使用。模型服务可在 Web 界面内配置。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun HelperUiState.ServiceCard() = CardSection("服务管理", 16) {
    Text(connectedRecord?.let { "当前目标：${it.host}:${it.port}" } ?: "先连接设备以管理服务。",
        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
    Text("连接复用健康服务；升级、重启和停止由独立动作发起。",
        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
    for ((label, action) in listOf("检查更新" to DeviceAction.UPDATE, "重启服务" to DeviceAction.RESTART, "停止服务" to DeviceAction.STOP)) {
        ActionButton(label, !busy && connectedRecord != null, Modifier.padding(top = 8.dp),
            action == DeviceAction.STOP) { pendingDeviceAction = action }
    }
}
