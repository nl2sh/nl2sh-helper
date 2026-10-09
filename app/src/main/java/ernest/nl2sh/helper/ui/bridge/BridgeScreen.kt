package ernest.nl2sh.helper.ui.bridge

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ernest.nl2sh.helper.BridgeAction
import ernest.nl2sh.helper.R
import ernest.nl2sh.helper.ui.HelperActions
import ernest.nl2sh.helper.ui.HelperUiState
import ernest.nl2sh.helper.ui.components.ActionButton
import ernest.nl2sh.helper.ui.components.CardSection

@Composable
internal fun HelperUiState.BridgeScreen(actions: HelperActions) = CardSection("增强 Android 控制能力", 16) {
    bridgeSnapshot?.let { snapshot ->
        if (snapshot.drift) Text("Bridge 版本或协议与建议不一致", color = colorResource(R.color.ui_warning), fontSize = 14.sp)
        Text(snapshot.describe(), color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
    } ?: Text("Android Bridge 状态尚未检查。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
    Text("无障碍与键盘独立启用；助手仅打开设置，不代改系统开关。",
        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
    for ((label, action) in listOf("检查 Bridge" to BridgeAction.INSPECT, "打开 Bridge" to BridgeAction.OPEN_APP,
        "打开无障碍设置" to BridgeAction.ACCESSIBILITY_SETTINGS, "打开键盘设置" to BridgeAction.KEYBOARD_SETTINGS)) {
        ActionButton(label, !busy && connectedRecord != null, Modifier.padding(top = 8.dp)) { actions.manageBridge(action) }
    }
    ActionButton("安装 / 升级 Bridge", !busy && connectedRecord != null, Modifier.padding(top = 8.dp)) { pendingBridgeInstall = true }
}
