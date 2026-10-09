package ernest.nl2sh.helper

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import ernest.nl2sh.helper.ui.*
import ernest.ascrcpy.adb.AdbEndpoint
import ernest.ascrcpy.adb.DefaultAdbClient
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

class MainActivity : ComponentActivity() {
    private val ui = HelperUiState()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val history by lazy { ConnectionHistory(this) }
    private var mode by ui::mode
    private var localPairingPort by ui::localPairingPort
    private var localConnectionPort by ui::localConnectionPort
    private var localPairingCode by ui::localPairingCode
    private val localConnector by lazy { LocalDeviceConnector(applicationContext) }
    private var host by ui::host
    private var port by ui::port
    private var pairingCode by ui::pairingCode
    private var status by ui::status
    private var records by ui::records
    private var webUrl by ui::webUrl
    private var connectedRecord by ui::connectedRecord
    private var bridgeSnapshot by ui::bridgeSnapshot
    private var busy by ui::busy
    private var qrBitmap by ui::qrBitmap
    private var activeJob: Job? = null
    private var qrDiscovery: QrPairingDiscovery? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("connection", MODE_PRIVATE)
        webUrl = prefs.getString("web_url", null)
        host = savedInstanceState?.getString("host") ?: prefs.getString("host", "").orEmpty()
        port = savedInstanceState?.getString("port") ?: prefs.getInt("port", 5555).toString()
        mode = savedInstanceState?.getString("mode")?.let { saved ->
            ConnectionMode.entries.firstOrNull { it.name == saved }
        } ?: ConnectionMode.LOCAL
        savedInstanceState?.getBundle("selected_device")?.let { selected ->
            val selectedMode = ConnectionMode.entries.firstOrNull { it.name == selected.getString("mode") }
            val selectedHost = selected.getString("host")
            val selectedPort = selected.getInt("port")
            if (selectedMode != null && !selectedHost.isNullOrBlank() && selectedPort in 1..65535)
                connectedRecord = ConnectionRecord(selectedMode, selectedHost, selectedPort, selected.getString("guid").orEmpty())
        }
        showStatus(webUrl?.let { "已保存上次地址：$it\n可打开浏览器；目标服务当前是否在线尚未检查。" }
            ?: "选择连接方式，填写目标设备信息后开始。", StatusTone.IDLE)
        localConnectionPort = savedInstanceState?.getString("local_connection_port").orEmpty()
        refreshHistory()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContent { Nl2shTheme { HelperApp(ui, HelperActions(
            selectMode = { mode = it; refreshHistory() },
            connect = ::startSelectedAction,
            openDeveloperOptions = {
                runCatching { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                    .onFailure { showStatus("请在系统设置中手动打开开发者选项 → 无线调试。", StatusTone.WARNING) }
            },
            openPairingOverlay = ::openPairingOverlay,
            connectHistory = { connectHistory(it) },
            removeHistory = { history.remove(it); refreshHistory() },
            manageBridge = ::manageBridge,
            confirmDeviceAction = { action -> connectedRecord?.let { connectHistory(it, action) } },
            cancelQr = ::cancelQrPairing,
            openBrowser = { webUrl?.let { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } },
        )) } }
        acceptOverlayResult(intent)
    }

    private fun openPairingOverlay() {
        if (android.os.Build.VERSION.SDK_INT < 30) {
            showStatus("配对浮窗需要 Android 11+。", StatusTone.ERROR); return
        }
        if (!android.provider.Settings.canDrawOverlays(this)) {
            showStatus("请允许显示在其他应用上层，返回后再次点击打开配对浮窗。", StatusTone.IDLE)
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
                .onFailure { showStatus("请在系统设置中为助手允许显示在其他应用上层。", StatusTone.WARNING) }
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 41)
            return
        }
        startPairingOverlayService()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 41 && android.provider.Settings.canDrawOverlays(this)) startPairingOverlayService()
    }

    private fun startPairingOverlayService() {
        runCatching { startForegroundService(Intent(this, LocalPairingOverlayService::class.java)) }
            .onFailure { report(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptOverlayResult(intent)
    }

    private fun acceptOverlayResult(intent: Intent) {
        if (intent.getBooleanExtra("close_pairing_overlay", false)) {
            stopService(Intent(this, LocalPairingOverlayService::class.java))
            intent.removeExtra("close_pairing_overlay")
        }
        if (intent.getBooleanExtra("local_pairing_complete", false)) {
            mode = ConnectionMode.LOCAL
            localPairingCode = ""; localPairingPort = ""
            refreshHistory()
            showStatus("本机已配对。点击启动 / 首次安装 nl2sh；发现失败时填写无线调试主页的连接端口。", StatusTone.IDLE)
            intent.removeExtra("local_pairing_complete")
        }
    }

    private fun refreshHistory() { records = history.list(mode) }
    private fun showStatus(message: String, tone: StatusTone) { status = UiStatus(tone, message) }

    private fun startSelectedAction() {
        when (mode) {
            ConnectionMode.LOCAL -> {
                fun parsePort(value: String): Int? = value.trim().takeIf { it.isNotEmpty() }?.let {
                    require(it.toIntOrNull() in 1..65535) { "请输入 1–65535 的本机端口。" }; it.toInt()
                }
                runAction {
                    val pairing = parsePort(localPairingPort)
                    val connection = parsePort(localConnectionPort)
                    val code = localPairingCode.trim()
                    require((pairing == null && code.isEmpty()) || (pairing != null && code.matches(Regex("[0-9]{6}")))) { "首次配对请同时填写临时配对端口和六位码。" }
                    try {
                        val record = localConnector.connect(pairing, code, connection) { showStatus(it, StatusTone.WORKING) }
                        localPairingPort = ""
                        install(record)
                    } finally { localPairingCode = ""; localPairingPort = "" }
                }
            }
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
                    val (guid, service) = pairAndDiscover(endpoint, code); pairingCode = ""
                    showStatus("已配对；正在查找无线连接服务…", StatusTone.WORKING)
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

    private suspend fun pairAndDiscover(
        endpoint: AdbEndpoint,
        code: String,
    ): Pair<String, QrPairingDiscovery.ResolvedService?> {
        val connections = Channel<QrPairingDiscovery.ResolvedService>(Channel.UNLIMITED)
        val discovery = connectionDiscovery(connections)
        return try {
            discovery.start()
            val guid = pair(endpoint, code)
            guid to awaitConnectableConnection(
                connections, guid, 30_000,
                routeHost = endpoint.host.takeIf(String::isTailscaleAddress),
            )
        } finally {
            discovery.stop()
            connections.close()
        }
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
                    val connection = awaitConnectableConnection(connections, guid, 30_000, fallbackHost = service.host)
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

    private fun connectHistory(record: ConnectionRecord, action: DeviceAction = DeviceAction.CONNECT) = runAction {
        val current = if (record.mode == ConnectionMode.LOCAL) localConnector.reconnect(record) { showStatus(it, StatusTone.WORKING) } else if (record.mode == ConnectionMode.TCP) record else {
            showStatus("正在查找 ${record.guid} 的无线连接服务…", StatusTone.WORKING)
            discoverConnection(
                record.guid, 10_000,
                routeHost = record.host.takeIf(String::isTailscaleAddress),
            )?.let { record.copy(host = if (record.host.isTailscaleAddress()) record.host else it.host, port = it.port) } ?: record
        }
        install(current, action)
    }

    private fun manageBridge(action: BridgeAction) = runAction {
        val record = requireNotNull(connectedRecord) { "请先连接目标设备。" }
        val current = if (record.mode == ConnectionMode.LOCAL) localConnector.reconnect(record) { showStatus(it, StatusTone.WORKING) } else if (record.mode == ConnectionMode.TCP) record else
            discoverConnection(record.guid, 10_000, routeHost = record.host.takeIf(String::isTailscaleAddress))
                ?.let { record.copy(host = if (record.host.isTailscaleAddress()) record.host else it.host, port = it.port) } ?: record
        val snapshot = DeviceBridgeManager(applicationContext).perform(current, action) { message ->
            withContext(Dispatchers.Main) { showStatus(message, StatusTone.WORKING) }
        }
        connectedRecord = current
        bridgeSnapshot = snapshot
        showStatus("Bridge 状态已刷新，详情见增强控制卡片。" + (snapshot.note?.let { "\n$it" } ?: ""),
            if (snapshot.drift || snapshot.note != null || !snapshot.installed) StatusTone.WARNING else StatusTone.IDLE)
    }

    private suspend fun discoverConnection(
        guid: String,
        timeout: Long,
        routeHost: String? = null,
    ): QrPairingDiscovery.ResolvedService? {
        val connections = Channel<QrPairingDiscovery.ResolvedService>(Channel.UNLIMITED)
        val discovery = connectionDiscovery(connections)
        return try {
            discovery.start()
            awaitConnectableConnection(connections, guid, timeout, routeHost = routeHost)
        } finally { discovery.stop(); connections.close() }
    }

    private fun connectionDiscovery(connections: Channel<QrPairingDiscovery.ResolvedService>) =
        QrPairingDiscovery(this, null, {}, { connections.trySend(it) }, {
            Log.e("Nl2shHelper", "NSD discovery failed: $it")
        })

    private suspend fun awaitConnectableConnection(
        connections: Channel<QrPairingDiscovery.ResolvedService>,
        guid: String,
        timeout: Long,
        fallbackHost: String? = null,
        routeHost: String? = null,
    ): QrPairingDiscovery.ResolvedService? = withTimeoutOrNull(timeout) {
        firstReachableWirelessService(
            connections,
            matches = { it.name.contains(guid, true) || it.host == fallbackHost },
        ) { candidate ->
            val endpoint = AdbEndpoint(routeHost ?: candidate.host, candidate.port)
            probeWireless(endpoint).also { reachable ->
                if (!reachable) Log.w("Nl2shHelper", "Ignoring unreachable wireless ADB service ${candidate.name} at ${endpoint.serial}")
            }
        }
    }

    private suspend fun probeWireless(endpoint: AdbEndpoint): Boolean = withContext(Dispatchers.IO) {
        val client = DefaultAdbClient.factory(applicationContext).create()
        try {
            withTimeout(10_000) { client.connectWireless(endpoint) }
            true
        } catch (error: Exception) {
            Log.w("Nl2shHelper", "Wireless ADB probe failed for ${endpoint.serial}", error)
            false
        } finally { client.close() }
    }

    private suspend fun install(record: ConnectionRecord, action: DeviceAction = DeviceAction.CONNECT) {
        val result = DeviceInstaller(applicationContext).perform(record.host, record.port,
            record.mode != ConnectionMode.TCP, action) { message ->
            withContext(Dispatchers.Main) { showStatus(message, StatusTone.WORKING) }
        }
        if (connectedRecord != record) bridgeSnapshot = null
        connectedRecord = record
        result.url?.let { webUrl = it }
        history.save(record)
        getSharedPreferences("connection", MODE_PRIVATE).edit().putString("web_url", webUrl).apply()
        when {
            action == DeviceAction.STOP -> showStatus("已停止服务；保存的浏览器地址不代表在线。", StatusTone.IDLE)
            result.legacy -> showStatus("已连接 ${result.version}：$webUrl\n旧版服务使用兼容模式；显式更新可迁移原生服务协议。", StatusTone.WARNING)
            else -> showStatus("${result.version} 已在线：$webUrl", StatusTone.SUCCESS)
        }
        refreshHistory()
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
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("mode", mode.name)
        outState.putString("host", host)
        outState.putString("port", port)
        outState.putString("local_connection_port", localConnectionPort)
        connectedRecord?.let { record -> outState.putBundle("selected_device", Bundle().apply {
            putString("mode", record.mode.name); putString("host", record.host)
            putInt("port", record.port); putString("guid", record.guid)
        }) }
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { stopQrPairing(); scope.cancel(); super.onDestroy() }
}

internal fun String.isTailscaleAddress(): Boolean {
    val normalized = trim().removePrefix("[").removeSuffix("]").lowercase()
    if (normalized.startsWith("fd7a:115c:a1e0:")) return true
    val octets = normalized.split('.').mapNotNull(String::toIntOrNull)
    return octets.size == 4 && octets.all { it in 0..255 } && octets[0] == 100 && octets[1] in 64..127
}
