package ernest.nl2sh.helper.ui.connection

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ernest.nl2sh.helper.ConnectionMode
import ernest.nl2sh.helper.ui.HelperActions
import ernest.nl2sh.helper.ui.HelperUiState

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HelperUiState.ConnectionModeSelector(actions: HelperActions) {
    val configuration = LocalConfiguration.current
    val stackedModes = configuration.screenWidthDp < 480 && configuration.fontScale >= 1.3f
    FlowRow(Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
        maxItemsInEachRow = if (stackedModes) 1 else 2) {
        ConnectionMode.entries.forEach { choice ->
            val title = when (choice) { ConnectionMode.LOCAL -> "本机"; ConnectionMode.TCP -> "TCP"; ConnectionMode.WIRELESS_CODE -> "配对码"; ConnectionMode.WIRELESS_QR -> "二维码" }
            val selected = choice == mode
            OutlinedButton({ actions.selectMode(choice) }, enabled = !busy, shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics {
                    this.selected = selected
                    val spokenTitle = if (choice == ConnectionMode.TCP) "TCP ADB" else title
                    contentDescription = if (selected) "$spokenTitle，当前连接方式" else "$spokenTitle，切换连接方式"
                }, border = ButtonDefaults.outlinedButtonBorder(!busy).copy(
                    width = if (selected) 2.dp else 1.dp,
                    brush = SolidColor(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    disabledContainerColor = MaterialTheme.colorScheme.surface,
                    contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
                Text(if (selected) "✓ $title" else title, fontSize = 14.sp)
            }
        }
    }
}
