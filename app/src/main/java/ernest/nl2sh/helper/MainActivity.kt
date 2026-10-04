package ernest.nl2sh.helper

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ernest.ascrcpy.adb.AdbEndpoint
import ernest.ascrcpy.adb.DefaultAdbClient
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val history by lazy { ConnectionHistory(this) }
    private var mode by mutableStateOf(ConnectionMode.TCP)
    private var host by mutableStateOf("")
    private var port by mutableStateOf("5555")
    private var pairingCode by mutableStateOf("")
    private var status by mutableStateOf(UiStatus(StatusTone.IDLE, ""))
    private var records by mutableStateOf(emptyList<ConnectionRecord>())
    private var webUrl by mutableStateOf<String?>(null)
    private var busy by mutableStateOf(false)
    private var qrBitmap by mutableStateOf<Bitmap?>(null)
    private var activeJob: Job? = null
    private var qrDiscovery: QrPairingDiscovery? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("connection", MODE_PRIVATE)
        webUrl = prefs.getString("web_url", null)
        host = prefs.getString("host", "") ?: ""
        port = prefs.getInt("port", 5555).toString()
        mode = savedInstanceState?.getString("mode")?.let { saved ->
            ConnectionMode.entries.firstOrNull { it.name == saved }
        } ?: ConnectionMode.TCP
        showStatus(webUrl?.let { "已保存上次地址：$it\n可打开浏览器；目标服务当前是否在线尚未检查。" }
            ?: "选择连接方式，填写目标设备信息后开始。", StatusTone.IDLE)
        refreshHistory()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContent { Nl2shTheme { HelperScreen() } }
    }

    @Composable private fun HelperScreen() {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding(),
            contentAlignment = Alignment.TopCenter) {
            Column(Modifier.fillMaxWidth().widthIn(max = 640.dp).verticalScroll(rememberScrollState())
                .imePadding().padding(horizontal = 20.dp, vertical = 24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.ic_launcher_art), null, Modifier.size(44.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(getString(R.string.app_name), color = MaterialTheme.colorScheme.primary,
                        fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
                Text("连接 Android 设备，部署 nl2sh 并打开 Web 界面。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp,
                    modifier = Modifier.padding(top = 12.dp))
                ConnectionCard()
                StatusCard()
                HistoryCard()
                ActionButton("在浏览器中打开 nl2sh", webUrl != null, Modifier.padding(top = 16.dp)) {
                    webUrl?.let { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
                }
                Text("网络提示", color = colorResource(R.color.ui_warning), fontSize = 13.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp))
                Text("目标 Web 界面无需登录，请仅在可信网络使用。模型服务可在 Web 界面内配置。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
                    modifier = Modifier.padding(top = 6.dp))
            }
        }
        qrBitmap?.let { QrDialog(it) }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable private fun ConnectionCard() = CardSection("连接设备", 20) {
        val configuration = LocalConfiguration.current
        val stackedModes = configuration.screenWidthDp < 480 && configuration.fontScale >= 1.3f
        FlowRow(Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
            maxItemsInEachRow = if (stackedModes) 1 else 3) {
            ConnectionMode.entries.forEach { choice ->
                val title = when (choice) { ConnectionMode.TCP -> "TCP"; ConnectionMode.WIRELESS_CODE -> "配对码"; ConnectionMode.WIRELESS_QR -> "二维码" }
                val selected = choice == mode
                OutlinedButton({ mode = choice; refreshHistory() }, enabled = !busy,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics {
                        contentDescription = if (selected) "$title，当前连接方式" else "$title，切换连接方式"
                    }, border = ButtonDefaults.outlinedButtonBorder(!busy).copy(
                        width = if (selected) 2.dp else 1.dp,
                        brush = SolidColor(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        disabledContainerColor = MaterialTheme.colorScheme.surface,
                        contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
                    Text(title, fontSize = 14.sp)
                }
            }
        }
        when (mode) {
            ConnectionMode.TCP -> EndpointForm("在目标设备开启 TCP ADB，首次连接时批准 RSA 授权。", "目标设备地址", "ADB 端口")
            ConnectionMode.WIRELESS_CODE -> {
                EndpointForm("在目标设备的“开发者选项 → 无线调试”中选择“使用配对码配对设备”。输入临时配对地址、端口和六位配对码。", "配对地址", "临时配对端口")
                Field("配对码", pairingCode, { pairingCode = it }, KeyboardType.NumberPassword, true)
            }
            ConnectionMode.WIRELESS_QR -> Text("两台设备连接同一 Wi-Fi。在目标设备的“无线调试 → 使用二维码配对设备”中扫描下方二维码。",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.padding(top = 14.dp))
        }
        ActionButton(if (busy) "正在处理…" else when (mode) {
            ConnectionMode.TCP -> "安装并启动"; ConnectionMode.WIRELESS_CODE -> "配对、安装并启动"; ConnectionMode.WIRELESS_QR -> "显示配对二维码"
        }, !busy, Modifier.padding(top = 16.dp), onClick = ::startSelectedAction)
    }

    @Composable private fun EndpointForm(description: String, hostLabel: String, portLabel: String) {
        Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.padding(top = 14.dp))
        Field(hostLabel, host, { host = it }, KeyboardType.Uri)
        Field(portLabel, port, { port = it }, KeyboardType.Number)
    }

    @Composable private fun Field(label: String, value: String, onChange: (String) -> Unit,
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

    @Composable private fun StatusCard() {
        val toneColor = when (status.tone) {
            StatusTone.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
            StatusTone.WORKING -> MaterialTheme.colorScheme.primary
            StatusTone.SUCCESS -> colorResource(R.color.ui_success)
            StatusTone.WARNING -> colorResource(R.color.ui_warning)
            StatusTone.ERROR -> MaterialTheme.colorScheme.error
        }
        Column(Modifier.fillMaxWidth().padding(top = 16.dp).clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, toneColor, RoundedCornerShape(8.dp)).padding(16.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(status.tone.label, color = toneColor, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (status.tone == StatusTone.WORKING) CircularProgressIndicator(Modifier.size(20.dp), color = toneColor, strokeWidth = 2.dp)
            }
            Text(status.message, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }

    @Composable private fun HistoryCard() = CardSection("历史设备", 16) {
        if (records.isEmpty()) Text("暂无历史设备。", color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
        records.forEach { record ->
            Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Text("${record.host}:${record.port}", fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                if (record.guid.isNotEmpty()) Text(record.guid, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("连接", !busy, Modifier.weight(1f)) { connectHistory(record) }
                    ActionButton("删除记录", !busy, Modifier.weight(1f), true) { history.remove(record); refreshHistory() }
                }
            }
        }
    }

    @Composable private fun CardSection(title: String, top: Int, content: @Composable () -> Unit) {
        Column(Modifier.fillMaxWidth().padding(top = top.dp).clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)).padding(16.dp)) {
            Text(title, color = MaterialTheme.colorScheme.secondary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            content()
        }
    }

    @Composable private fun ActionButton(text: String, enabled: Boolean, modifier: Modifier = Modifier,
                                         destructive: Boolean = false, onClick: () -> Unit) {
        val active = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        OutlinedButton(onClick, modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = enabled,
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = MaterialTheme.colorScheme.surface,
                disabledContainerColor = MaterialTheme.colorScheme.surface,
                contentColor = active,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant),
            border = ButtonDefaults.outlinedButtonBorder(enabled).copy(brush = SolidColor(if (enabled) active else MaterialTheme.colorScheme.outline))) {
            Text(text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }

    @Composable private fun QrDialog(bitmap: Bitmap) {
        AlertDialog(onDismissRequest = ::cancelQrPairing, title = { Text("无线调试二维码配对") },
            text = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Image(bitmap.asImageBitmap(), "无线调试配对二维码",
                    Modifier.fillMaxWidth().heightIn(max = 320.dp).background(androidx.compose.ui.graphics.Color.White).padding(16.dp))
            } }, confirmButton = {}, dismissButton = { TextButton(onClick = ::cancelQrPairing) { Text("取消") } },
            containerColor = MaterialTheme.colorScheme.surface)
    }

    private fun refreshHistory() { records = history.list(mode) }
    private fun showStatus(message: String, tone: StatusTone) { status = UiStatus(tone, message) }

    private fun startSelectedAction() {
        when (mode) {
            ConnectionMode.TCP -> {
                val endpoint = inputEndpoint() ?: return
                getSharedPreferences("connection", MODE_PRIVATE).edit().putString("host", endpoint.host).putInt("port", endpoint.port).apply()
                runAction { install(ConnectionRecord(mode, endpoint.host, endpoint.port)) }
            }
            ConnectionMode.WIRELESS_CODE -> {
                val endpoint = inputEndpoint() ?: return
                val code = pairingCode.trim()
                if (!code.matches(Regex("[0-9]{6}"))) { showStatus("请输入六位配对码。", StatusTone.ERROR); return }
                runAction {
                    showStatus("正在与 ${endpoint.host}:${endpoint.port} 配对…", StatusTone.WORKING)
                    val guid = pair(endpoint, code); pairingCode = ""
                    showStatus("已配对；正在查找无线连接服务…", StatusTone.WORKING)
                    val service = discoverConnection(guid, 30_000)
                    if (service == null) showStatus("已配对，但未发现连接服务。请确认无线调试仍已开启，然后重试。", StatusTone.WARNING)
                    else install(ConnectionRecord(ConnectionMode.WIRELESS_CODE,
                        if (endpoint.host.isTailscaleAddress()) endpoint.host else service.host, service.port, guid))
                }
            }
            ConnectionMode.WIRELESS_QR -> startQrPairing()
        }
    }

    private fun inputEndpoint(): AdbEndpoint? {
        val cleanHost = host.trim(); val cleanPort = port.toIntOrNull()
        if (!validHost(cleanHost) || cleanPort == null || cleanPort !in 1..65535) {
            showStatus("请输入有效的 IP/主机名和 1–65535 端口。", StatusTone.ERROR); return null
        }
        return AdbEndpoint(cleanHost, cleanPort)
    }

    private suspend fun pair(endpoint: AdbEndpoint, code: String): String = withContext(Dispatchers.IO) {
        val client = DefaultAdbClient.factory(applicationContext).create()
        try { client.pairWireless(endpoint, code) } finally { client.close() }
    }

    private fun startQrPairing() {
        if (busy || qrBitmap != null) return
        val qr = QrPairing(); val connections = Channel<QrPairingDiscovery.ResolvedService>(Channel.UNLIMITED); var started = false
        val discovery = QrPairingDiscovery(this, qr.serviceName, onPairing = { service ->
            if (!started) { started = true; runAction {
                try {
                    showStatus("已扫码，正在与 ${service.host}:${service.port} 配对…", StatusTone.WORKING)
                    val guid = pair(AdbEndpoint(service.host, service.port), qr.password)
                    showStatus("已配对；正在查找无线连接服务…", StatusTone.WORKING)
                    val connection = withTimeoutOrNull(30_000) {
                        while (true) { val candidate = connections.receive(); if (candidate.name.contains(guid, true) || candidate.host == service.host) return@withTimeoutOrNull candidate }
                        @Suppress("UNREACHABLE_CODE") null
                    }
                    if (connection == null) showStatus("已配对，但未发现无线连接服务。", StatusTone.WARNING)
                    else install(ConnectionRecord(ConnectionMode.WIRELESS_QR, connection.host, connection.port, guid))
                } finally { stopQrPairing(false) }
            } }
        }, onConnection = { connections.trySend(it) }, onError = { error ->
            Log.e("Nl2shHelper", "NSD discovery failed: $error"); showStatus("网络服务发现失败。请检查 Wi-Fi 连接。", StatusTone.ERROR); stopQrPairing()
        })
        qrDiscovery = discovery; qrBitmap = qr.bitmap()
        try { discovery.start(); showStatus("等待目标设备扫描二维码…", StatusTone.WORKING) }
        catch (error: Exception) { stopQrPairing(); report(error) }
    }

    private fun cancelQrPairing() { stopQrPairing(); showStatus("已取消二维码配对。", StatusTone.IDLE) }
    private fun stopQrPairing(cancelJob: Boolean = true) {
        val hadSession = qrDiscovery != null; qrDiscovery?.stop(); qrDiscovery = null; qrBitmap = null
        if (cancelJob && hadSession && busy) activeJob?.cancel()
    }

    private fun connectHistory(record: ConnectionRecord) = runAction {
        val current = if (record.mode == ConnectionMode.TCP) record else {
            showStatus("正在查找 ${record.guid} 的无线连接服务…", StatusTone.WORKING)
            discoverConnection(record.guid, 10_000)?.let { record.copy(host = if (record.host.isTailscaleAddress()) record.host else it.host, port = it.port) } ?: record
        }
        install(current)
    }

    private suspend fun discoverConnection(guid: String, timeout: Long): QrPairingDiscovery.ResolvedService? {
        val connections = Channel<QrPairingDiscovery.ResolvedService>(Channel.UNLIMITED)
        val discovery = QrPairingDiscovery(this, null, {}, { connections.trySend(it) }, { Log.e("Nl2shHelper", "NSD discovery failed: $it") })
        return try { discovery.start(); withTimeoutOrNull(timeout) {
            while (true) { val service = connections.receive(); if (service.name.contains(guid, true)) return@withTimeoutOrNull service }
            @Suppress("UNREACHABLE_CODE") null
        } } finally { discovery.stop(); connections.close() }
    }

    private suspend fun install(record: ConnectionRecord) {
        webUrl = DeviceInstaller(applicationContext).install(record.host, record.port, record.mode != ConnectionMode.TCP) { message ->
            withContext(Dispatchers.Main) { showStatus(message, StatusTone.WORKING) }
        }
        history.save(record); getSharedPreferences("connection", MODE_PRIVATE).edit().putString("web_url", webUrl).apply()
        showStatus("已启动。点击按钮在系统浏览器访问 $webUrl", StatusTone.SUCCESS); refreshHistory()
    }

    private fun runAction(action: suspend () -> Unit): Job? {
        if (busy) return null
        busy = true; showStatus("正在处理连接请求…", StatusTone.WORKING)
        return scope.launch {
            try { action() }
            catch (error: CancellationException) { showStatus("操作已取消。", StatusTone.IDLE); throw error }
            catch (error: Exception) { report(error) }
            finally { busy = false; activeJob = null }
        }.also { activeJob = it }
    }

    private fun report(error: Throwable) { Log.e("Nl2shHelper", "ADB operation failed", error); showStatus(error.message ?: error.javaClass.simpleName, StatusTone.ERROR) }
    private fun validHost(value: String) = value.length in 1..253 && value.matches(Regex("[A-Za-z0-9.-]+")) && !value.startsWith('-') && !value.endsWith('-') && !value.contains("..")
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("mode", mode.name); super.onSaveInstanceState(outState) }
    override fun onDestroy() { stopQrPairing(); scope.cancel(); super.onDestroy() }
}

internal data class UiStatus(val tone: StatusTone, val message: String)
internal enum class StatusTone(val label: String) { IDLE("就绪"), WORKING("处理中"), SUCCESS("已启动"), WARNING("需注意"), ERROR("失败") }
internal fun String.isTailscaleAddress(): Boolean {
    val normalized = trim().removePrefix("[").removeSuffix("]").lowercase()
    if (normalized.startsWith("fd7a:115c:a1e0:")) return true
    val octets = normalized.split('.').mapNotNull(String::toIntOrNull)
    return octets.size == 4 && octets.all { it in 0..255 } && octets[0] == 100 && octets[1] in 64..127
}
