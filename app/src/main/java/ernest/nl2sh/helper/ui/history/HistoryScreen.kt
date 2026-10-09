package ernest.nl2sh.helper.ui.history

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ernest.nl2sh.helper.ui.*
import ernest.nl2sh.helper.ui.components.*
import ernest.nl2sh.helper.ui.connection.ConnectionModeSelector

@Composable
internal fun HelperUiState.HistoryScreen(actions: HelperActions) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 640.dp).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                CardSection("历史设备", 0) { ConnectionModeSelector(actions) }
            }
            item { StatusCard() }
            if (records.isEmpty()) item {
                Text("暂无历史设备。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            }
            items(records, key = { it.identity }) { record ->
                CardSection("已保存设备", 0) {
                    Text("${record.host}:${record.port}", color = MaterialTheme.colorScheme.onSurface,
                        fontFamily = FontFamily.Monospace, fontSize = 14.sp,
                        modifier = Modifier.padding(top = 8.dp))
                    if (record.guid.isNotEmpty()) Text(record.guid,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionButton("连接", !busy, Modifier.weight(1f)) { actions.connectHistory(record) }
                        ActionButton("删除记录", !busy, Modifier.weight(1f), true) { actions.removeHistory(record) }
                    }
                }
            }
        }
    }
}
