package ernest.nl2sh.helper

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ernest.ascrcpy.adb.AdbEndpoint
import ernest.ascrcpy.adb.DefaultAdbClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val history by lazy { ConnectionHistory(this) }
    private var mode = ConnectionMode.TCP
    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private lateinit var codeInput: EditText
    private lateinit var statusView: TextView
    private lateinit var actionButton: Button
    private lateinit var openButton: Button
    private lateinit var form: LinearLayout
    private lateinit var historyList: LinearLayout
    private lateinit var modeButtons: Map<ConnectionMode, Button>
    private var activeJob: Job? = null
    private var qrDiscovery: QrPairingDiscovery? = null
    private var qrDialog: AlertDialog? = null
    private var webUrl: String? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("connection", MODE_PRIVATE)
        webUrl = prefs.getString("web_url", null)
        mode = savedInstanceState?.getString("mode")?.let { name ->
            ConnectionMode.entries.firstOrNull { it.name == name }
        } ?: ConnectionMode.TCP
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
            setPadding(dp(24), dp(28), dp(24), dp(28))
            setBackgroundColor(Color.rgb(247, 249, 251))
        }
        setContentView(ScrollView(this).apply { addView(root) })
        root.addView(label("nl2sh 助手", 27, Color.rgb(25, 54, 80), 0))
        root.addView(label("连接目标 Android 设备，自动安装最新 nl2sh 并启动 Web 界面。", 16, Color.DKGRAY, 12))
        root.addView(label("选择连接方式", 20, Color.rgb(25, 54, 80), 28))
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        modeButtons = ConnectionMode.entries.associateWith { connectionMode ->
            Button(this).apply {
                text = when (connectionMode) {
                    ConnectionMode.TCP -> "TCP ADB"
                    ConnectionMode.WIRELESS_CODE -> "无线调试 · 配对码"
                    ConnectionMode.WIRELESS_QR -> "无线调试 · 二维码"
                }
                setOnClickListener { mode = connectionMode; renderMode() }
                modeRow.addView(this)
            }
        }
        root.addView(modeRow, marginTop(12))
        form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(form)
        hostInput = EditText(this).apply {
            hint = "例如 192.168.1.20"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.getString("host", ""))
        }
        portInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setText(prefs.getInt("port", 5555).toString())
        }
        codeInput = EditText(this).apply {
            hint = "六位配对码"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setSingleLine(true)
        }
        actionButton = Button(this).apply { setOnClickListener { startSelectedAction() } }
        root.addView(label("历史设备", 20, Color.rgb(25, 54, 80), 26))
        historyList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(historyList)
        openButton = Button(this).apply {
            text = "在浏览器中打开 nl2sh"
            isEnabled = webUrl != null
            setOnClickListener { webUrl?.let { url -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
        }
        root.addView(openButton, marginTop(18))
        statusView = label(webUrl?.let { "上次启动地址：$it" } ?: "等待连接。", 15, Color.DKGRAY, 18)
        root.addView(statusView)
        root.addView(label("目标 Web 界面当前无需登录，仅应在可信网络中使用。模型服务可在 Web 界面内配置。", 13, Color.GRAY, 24))
        renderMode()
        root.requestFocus()
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
    }

    private fun renderMode() {
        modeButtons.forEach { (choice, button) -> button.isEnabled = !busy && choice != mode }
        form.removeAllViews()
        when (mode) {
            ConnectionMode.TCP -> {
                form.addView(label("在目标设备开启 TCP ADB，首次连接时批准 RSA 授权。", 15, Color.DKGRAY, 14))
                form.addView(label("目标设备地址", 16, Color.rgb(25, 54, 80), 18))
                form.addView(hostInput)
                form.addView(label("ADB 端口", 16, Color.rgb(25, 54, 80), 12))
                form.addView(portInput)
                actionButton.text = "安装并启动"
            }
            ConnectionMode.WIRELESS_CODE -> {
                form.addView(label("在目标设备的“开发者选项 → 无线调试”中选择“使用配对码配对设备”。输入临时配对地址、端口和六位配对码。", 15, Color.DKGRAY, 14))
                form.addView(label("配对地址", 16, Color.rgb(25, 54, 80), 18))
                form.addView(hostInput)
                form.addView(label("临时配对端口", 16, Color.rgb(25, 54, 80), 12))
                form.addView(portInput)
                form.addView(codeInput)
                actionButton.text = "配对、安装并启动"
            }
            ConnectionMode.WIRELESS_QR -> {
                form.addView(label("两台设备连接同一 Wi-Fi。在目标设备的“无线调试 → 使用二维码配对设备”中扫描下方二维码。", 15, Color.DKGRAY, 14))
                actionButton.text = "显示配对二维码"
            }
        }
        form.addView(actionButton, marginTop(16))
        actionButton.isEnabled = !busy
        renderHistory()
    }

    private fun renderHistory() {
        historyList.removeAllViews()
        val records = history.list(mode)
        if (records.isEmpty()) historyList.addView(label("暂无历史设备。", 14, Color.GRAY, 8))
        records.forEach { record ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val detail = if (record.guid.isEmpty()) "${record.host}:${record.port}" else
                "${record.host}:${record.port}\n${record.guid}"
            row.addView(label(detail, 14, Color.DKGRAY, 0), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(Button(this).apply {
                text = "连接"
                isEnabled = !busy
                setOnClickListener { connectHistory(record) }
            })
            row.addView(Button(this).apply {
                text = "删除"
                isEnabled = !busy
                setOnClickListener { history.remove(record); renderHistory() }
            })
            historyList.addView(row, marginTop(8))
        }
    }

    private fun startSelectedAction() {
        when (mode) {
            ConnectionMode.TCP -> {
                val endpoint = inputEndpoint() ?: return
                getSharedPreferences("connection", MODE_PRIVATE).edit()
                    .putString("host", endpoint.host).putInt("port", endpoint.port).apply()
                runAction { install(ConnectionRecord(mode, endpoint.host, endpoint.port)) }
            }
            ConnectionMode.WIRELESS_CODE -> {
                val endpoint = inputEndpoint() ?: return
                val code = codeInput.text.toString().trim()
                if (!code.matches(Regex("[0-9]{6}"))) {
                    statusView.text = "请输入六位配对码。"; return
                }
                runAction {
                    statusView.text = "正在与 ${endpoint.host}:${endpoint.port} 配对…"
                    val guid = pair(endpoint, code)
                    codeInput.text.clear()
                    statusView.text = "已配对；正在查找无线连接服务…"
                    val service = discoverConnection(guid, 30_000)
                    if (service == null) {
                        statusView.text = "已配对，但未发现连接服务。请确认无线调试仍已开启，然后重试。"
                    } else {
                        val host = if (endpoint.host.isTailscaleAddress()) endpoint.host else service.host
                        install(ConnectionRecord(ConnectionMode.WIRELESS_CODE, host, service.port, guid))
                    }
                }
            }
            ConnectionMode.WIRELESS_QR -> startQrPairing()
        }
    }

    private fun inputEndpoint(): AdbEndpoint? {
        val host = hostInput.text.toString().trim()
        val port = portInput.text.toString().toIntOrNull()
        if (!validHost(host) || port == null || port !in 1..65535) {
            statusView.text = "请输入有效的 IP/主机名和 1–65535 端口。"
            return null
        }
        return AdbEndpoint(host, port)
    }

    private suspend fun pair(endpoint: AdbEndpoint, code: String): String = withContext(Dispatchers.IO) {
        val client = DefaultAdbClient.factory(applicationContext).create()
        try { client.pairWireless(endpoint, code) } finally { client.close() }
    }

    private fun startQrPairing() {
        if (busy || qrDialog != null) return
        val qr = QrPairing()
        val connections = Channel<QrPairingDiscovery.ResolvedService>(Channel.UNLIMITED)
        var started = false
        val discovery = QrPairingDiscovery(this, qr.serviceName,
            onPairing = { service ->
                if (!started) {
                    started = true
                    runAction {
                        try {
                            statusView.text = "已扫码，正在与 ${service.host}:${service.port} 配对…"
                            val guid = pair(AdbEndpoint(service.host, service.port), qr.password)
                            statusView.text = "已配对；正在查找无线连接服务…"
                            val connection = withTimeoutOrNull(30_000) {
                                while (true) {
                                    val candidate = connections.receive()
                                    if (candidate.name.contains(guid, ignoreCase = true) || candidate.host == service.host)
                                        return@withTimeoutOrNull candidate
                                }
                                @Suppress("UNREACHABLE_CODE") null
                            }
                            if (connection == null) statusView.text = "已配对，但未发现无线连接服务。"
                            else install(ConnectionRecord(ConnectionMode.WIRELESS_QR,
                                connection.host, connection.port, guid))
                        } finally { stopQrPairing(cancelJob = false) }
                    }
                }
            }, onConnection = { connections.trySend(it) }, onError = { error ->
                Log.e("Nl2shHelper", "NSD discovery failed: $error")
                statusView.text = "网络服务发现失败。请检查 Wi-Fi 连接。"
                stopQrPairing()
            })
        qrDiscovery = discovery
        val image = ImageView(this).apply {
            setImageBitmap(qr.bitmap())
            contentDescription = "无线调试配对二维码"
            adjustViewBounds = true
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        qrDialog = AlertDialog.Builder(this)
            .setTitle("无线调试二维码配对")
            .setView(image)
            .setNegativeButton("取消") { _, _ -> stopQrPairing() }
            .setOnCancelListener { stopQrPairing() }
            .create().also { it.show() }
        try {
            discovery.start()
            statusView.text = "等待目标设备扫描二维码…"
        } catch (error: Exception) {
            stopQrPairing()
            report(error)
        }
    }

    private fun stopQrPairing(cancelJob: Boolean = true) {
        val hadSession = qrDiscovery != null
        qrDiscovery?.stop()
        qrDiscovery = null
        qrDialog?.setOnCancelListener(null)
        qrDialog?.dismiss()
        qrDialog = null
        if (cancelJob && hadSession && busy) activeJob?.cancel()
    }

    private fun connectHistory(record: ConnectionRecord) = runAction {
        val current = if (record.mode == ConnectionMode.TCP) record else {
            statusView.text = "正在查找 ${record.guid} 的无线连接服务…"
            val service = discoverConnection(record.guid, 10_000)
            if (service == null) record else record.copy(
                host = if (record.host.isTailscaleAddress()) record.host else service.host,
                port = service.port)
        }
        install(current)
    }

    private suspend fun discoverConnection(guid: String, timeout: Long): QrPairingDiscovery.ResolvedService? {
        val connections = Channel<QrPairingDiscovery.ResolvedService>(Channel.UNLIMITED)
        val discovery = QrPairingDiscovery(this, null, {}, { connections.trySend(it) },
            { code -> Log.e("Nl2shHelper", "NSD discovery failed: $code") })
        return try {
            discovery.start()
            withTimeoutOrNull(timeout) {
                while (true) {
                    val service = connections.receive()
                    if (service.name.contains(guid, ignoreCase = true)) return@withTimeoutOrNull service
                }
                @Suppress("UNREACHABLE_CODE") null
            }
        } finally { discovery.stop(); connections.close() }
    }

    private suspend fun install(record: ConnectionRecord) {
        webUrl = DeviceInstaller(applicationContext).install(record.host, record.port,
            record.mode != ConnectionMode.TCP) { message ->
            withContext(Dispatchers.Main) { statusView.text = message }
        }
        history.save(record)
        getSharedPreferences("connection", MODE_PRIVATE).edit().putString("web_url", webUrl).apply()
        statusView.text = "已启动。点击按钮在系统浏览器访问 $webUrl"
        openButton.isEnabled = true
        renderHistory()
    }

    private fun runAction(action: suspend () -> Unit): Job? {
        if (busy) return null
        busy = true
        renderMode()
        return scope.launch {
            try { action() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { report(error) }
            finally { busy = false; activeJob = null; renderMode() }
        }.also { activeJob = it }
    }

    private fun report(error: Throwable) {
        Log.e("Nl2shHelper", "ADB operation failed", error)
        statusView.text = "失败：${error.message ?: error.javaClass.simpleName}"
    }

    private fun validHost(value: String): Boolean =
        value.length in 1..253 && value.matches(Regex("[A-Za-z0-9.-]+")) &&
            !value.startsWith('-') && !value.endsWith('-') && !value.contains("..")

    private fun label(text: String, size: Int, color: Int, top: Int): TextView = TextView(this).apply {
        this.text = text
        textSize = size.toFloat()
        setTextColor(color)
        gravity = Gravity.START
        layoutParams = marginTop(top)
    }

    private fun marginTop(top: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("mode", mode.name)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        stopQrPairing()
        scope.cancel()
        super.onDestroy()
    }
}

internal fun String.isTailscaleAddress(): Boolean {
    val normalized = trim().removePrefix("[").removeSuffix("]").lowercase()
    if (normalized.startsWith("fd7a:115c:a1e0:")) return true
    val octets = normalized.split('.').mapNotNull(String::toIntOrNull)
    return octets.size == 4 && octets.all { it in 0..255 } &&
        octets[0] == 100 && octets[1] in 64..127
}
