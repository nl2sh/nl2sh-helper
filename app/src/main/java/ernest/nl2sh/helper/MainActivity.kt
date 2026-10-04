package ernest.nl2sh.helper

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ProgressBar
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
    private lateinit var ui: HelperUi
    private lateinit var statusView: TextView
    private lateinit var statusLabel: TextView
    private lateinit var statusCard: LinearLayout
    private lateinit var progress: ProgressBar
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
        ui = HelperUi(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        val container = FrameLayout(this).apply {
            addView(root, FrameLayout.LayoutParams(-1, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
                val width = minOf(right - left, dp(640))
                if (root.layoutParams.width != width) {
                    root.layoutParams = FrameLayout.LayoutParams(width, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                }
            }
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(ui.background)
            isFillViewport = true
            addView(container)
            setOnApplyWindowInsetsListener { view, insets ->
                applySystemInsets(view, insets)
                insets
            }
        })
        val brand = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        brand.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher_art)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) })
        brand.addView(ui.label(getString(R.string.app_name), 24, ui.accent, bold = true),
            LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(brand)
        root.addView(label("连接 Android 设备，部署 nl2sh 并打开 Web 界面。", 14, ui.secondary, 12))

        val connectionCard = ui.card()
        root.addView(connectionCard, marginTop(20))
        connectionCard.addView(ui.label("连接设备", 16, ui.cyan, bold = true))
        val stackedModes = resources.configuration.fontScale >= 1.3f &&
            resources.configuration.screenWidthDp < 480
        val modeRow = LinearLayout(this).apply {
            orientation = if (stackedModes) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            isBaselineAligned = false
        }
        modeButtons = ConnectionMode.entries.associateWith { connectionMode ->
            ui.button().apply {
                setOnClickListener { mode = connectionMode; renderMode() }
                modeRow.addView(this, LinearLayout.LayoutParams(
                    if (stackedModes) -1 else 0, -2, if (stackedModes) 0f else 1f
                ).apply {
                    if (connectionMode != ConnectionMode.TCP) {
                        if (stackedModes) topMargin = dp(6) else marginStart = dp(6)
                    }
                })
            }
        }
        connectionCard.addView(modeRow, marginTop(12))
        form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        connectionCard.addView(form)
        hostInput = ui.input(EditText(this)).apply {
            contentDescription = "目标设备地址"
            hint = "例如 192.168.1.20"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.getString("host", ""))
        }
        portInput = ui.input(EditText(this)).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setText(prefs.getInt("port", 5555).toString())
        }
        codeInput = ui.input(EditText(this)).apply {
            contentDescription = "六位配对码"
            hint = "六位配对码"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
        }
        actionButton = ui.button(primaryAction = true).apply { setOnClickListener { startSelectedAction() } }

        statusCard = ui.card()
        root.addView(statusCard, marginTop(16))
        val statusHeading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        statusLabel = ui.label("", 14, ui.secondary, bold = true)
        statusHeading.addView(statusLabel, LinearLayout.LayoutParams(0, -2, 1f))
        progress = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(ui.accent)
            visibility = View.GONE
            contentDescription = "正在处理连接请求"
        }
        statusHeading.addView(progress, LinearLayout.LayoutParams(dp(20), dp(20)))
        statusCard.addView(statusHeading)
        statusView = label("", 14, ui.primary, 8)
        statusView.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        statusCard.addView(statusView)
        showStatus(webUrl?.let { "已保存上次地址：$it\n可打开浏览器；目标服务当前是否在线尚未检查。" }
            ?: "选择连接方式，填写目标设备信息后开始。", StatusTone.IDLE)

        val historyCard = ui.card()
        root.addView(historyCard, marginTop(16))
        historyCard.addView(ui.label("历史设备", 16, ui.cyan, bold = true))
        historyList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        historyCard.addView(historyList)
        openButton = ui.button(primaryAction = true).apply {
            text = "在浏览器中打开 nl2sh"
            isEnabled = webUrl != null
            setOnClickListener { webUrl?.let { url -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
        }
        root.addView(openButton, marginTop(16))
        root.addView(ui.label("网络提示", 13, ui.warning, 20, bold = true))
        root.addView(label("目标 Web 界面无需登录，请仅在可信网络使用。模型服务可在 Web 界面内配置。", 13, ui.secondary, 6))
        renderMode()
        root.requestFocus()
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    private fun renderMode() {
        modeButtons.forEach { (choice, button) ->
            button.isEnabled = !busy
            button.isSelected = choice == mode
            val title = when (choice) {
                ConnectionMode.TCP -> "TCP"
                ConnectionMode.WIRELESS_CODE -> "配对码"
                ConnectionMode.WIRELESS_QR -> "二维码"
            }
            button.text = if (choice == mode) "✓ $title" else title
            val description = if (choice == ConnectionMode.TCP) "TCP ADB" else title
            button.contentDescription = if (choice == mode) "$description，当前连接方式" else "$description，切换连接方式"
        }
        portInput.contentDescription = if (mode == ConnectionMode.TCP) "ADB 端口" else "临时配对端口"
        listOf(hostInput, portInput, codeInput).forEach { it.isEnabled = !busy }
        form.removeAllViews()
        when (mode) {
            ConnectionMode.TCP -> {
                form.addView(label("在目标设备开启 TCP ADB，首次连接时批准 RSA 授权。", 14, ui.secondary, 14))
                form.addView(label("目标设备地址", 14, ui.secondary, 18))
                form.addView(hostInput)
                form.addView(label("ADB 端口", 14, ui.secondary, 12))
                form.addView(portInput)
                actionButton.text = "安装并启动"
            }
            ConnectionMode.WIRELESS_CODE -> {
                form.addView(label("在目标设备的“开发者选项 → 无线调试”中选择“使用配对码配对设备”。输入临时配对地址、端口和六位配对码。", 14, ui.secondary, 14))
                form.addView(label("配对地址", 14, ui.secondary, 18))
                form.addView(hostInput)
                form.addView(label("临时配对端口", 14, ui.secondary, 12))
                form.addView(portInput)
                form.addView(label("配对码", 14, ui.secondary, 12))
                form.addView(codeInput)
                actionButton.text = "配对、安装并启动"
            }
            ConnectionMode.WIRELESS_QR -> {
                form.addView(label("两台设备连接同一 Wi-Fi。在目标设备的“无线调试 → 使用二维码配对设备”中扫描下方二维码。", 14, ui.secondary, 14))
                actionButton.text = "显示配对二维码"
            }
        }
        form.addView(actionButton, marginTop(16))
        if (busy) actionButton.text = "正在处理…"
        actionButton.isEnabled = !busy
        renderHistory()
    }

    private fun renderHistory() {
        historyList.removeAllViews()
        val records = history.list(mode)
        if (records.isEmpty()) historyList.addView(label("暂无历史设备。", 14, ui.secondary, 8))
        records.forEach { record ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            row.addView(label("${record.host}:${record.port}", 14, ui.primary, 0).apply {
                typeface = Typeface.MONOSPACE
            })
            if (record.guid.isNotEmpty()) row.addView(label(record.guid, 12, ui.secondary, 4))
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(ui.button(primaryAction = true).apply {
                text = "连接"
                isEnabled = !busy
                setOnClickListener { connectHistory(record) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            actions.addView(ui.button(destructive = true).apply {
                text = "删除记录"
                isEnabled = !busy
                setOnClickListener { history.remove(record); renderHistory() }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
            row.addView(actions, marginTop(8))
            historyList.addView(row, marginTop(12))
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
                    showStatus("请输入六位配对码。", StatusTone.ERROR); return
                }
                runAction {
                    showStatus("正在与 ${endpoint.host}:${endpoint.port} 配对…", StatusTone.WORKING)
                    val guid = pair(endpoint, code)
                    codeInput.text.clear()
                    showStatus("已配对；正在查找无线连接服务…", StatusTone.WORKING)
                    val service = discoverConnection(guid, 30_000)
                    if (service == null) {
                        showStatus("已配对，但未发现连接服务。请确认无线调试仍已开启，然后重试。", StatusTone.WARNING)
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
            showStatus("请输入有效的 IP/主机名和 1–65535 端口。", StatusTone.ERROR)
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
                            showStatus("已扫码，正在与 ${service.host}:${service.port} 配对…", StatusTone.WORKING)
                            val guid = pair(AdbEndpoint(service.host, service.port), qr.password)
                            showStatus("已配对；正在查找无线连接服务…", StatusTone.WORKING)
                            val connection = withTimeoutOrNull(30_000) {
                                while (true) {
                                    val candidate = connections.receive()
                                    if (candidate.name.contains(guid, ignoreCase = true) || candidate.host == service.host)
                                        return@withTimeoutOrNull candidate
                                }
                                @Suppress("UNREACHABLE_CODE") null
                            }
                            if (connection == null) showStatus("已配对，但未发现无线连接服务。", StatusTone.WARNING)
                            else install(ConnectionRecord(ConnectionMode.WIRELESS_QR,
                                connection.host, connection.port, guid))
                        } finally { stopQrPairing(cancelJob = false) }
                    }
                }
            }, onConnection = { connections.trySend(it) }, onError = { error ->
                Log.e("Nl2shHelper", "NSD discovery failed: $error")
                showStatus("网络服务发现失败。请检查 Wi-Fi 连接。", StatusTone.ERROR)
                stopQrPairing()
            })
        qrDiscovery = discovery
        val image = ImageView(this).apply {
            setImageBitmap(qr.bitmap())
            contentDescription = "无线调试配对二维码"
            adjustViewBounds = true
            maxHeight = dp(320)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(16), dp(16), dp(16), dp(16))
            // QR contrast is protocol-critical, not a page theme color.
            setBackgroundColor(getColor(android.R.color.white))
        }
        qrDialog = AlertDialog.Builder(this)
            .setTitle("无线调试二维码配对")
            .setView(image)
            .setNegativeButton("取消") { _, _ -> cancelQrPairing() }
            .setOnCancelListener { cancelQrPairing() }
            .create().also { it.show() }
        try {
            discovery.start()
            showStatus("等待目标设备扫描二维码…", StatusTone.WORKING)
        } catch (error: Exception) {
            stopQrPairing()
            report(error)
        }
    }

    private fun cancelQrPairing() {
        stopQrPairing()
        showStatus("已取消二维码配对。", StatusTone.IDLE)
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
            showStatus("正在查找 ${record.guid} 的无线连接服务…", StatusTone.WORKING)
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
            withContext(Dispatchers.Main) { showStatus(message, StatusTone.WORKING) }
        }
        history.save(record)
        getSharedPreferences("connection", MODE_PRIVATE).edit().putString("web_url", webUrl).apply()
        showStatus("已启动。点击按钮在系统浏览器访问 $webUrl", StatusTone.SUCCESS)
        openButton.isEnabled = true
        renderHistory()
    }

    private fun runAction(action: suspend () -> Unit): Job? {
        if (busy) return null
        busy = true
        showStatus("正在处理连接请求…", StatusTone.WORKING)
        renderMode()
        return scope.launch {
            try { action() }
            catch (error: CancellationException) {
                showStatus("操作已取消。", StatusTone.IDLE)
                throw error
            }
            catch (error: Exception) { report(error) }
            finally { busy = false; activeJob = null; renderMode() }
        }.also { activeJob = it }
    }

    private fun report(error: Throwable) {
        Log.e("Nl2shHelper", "ADB operation failed", error)
        showStatus("${error.message ?: error.javaClass.simpleName}", StatusTone.ERROR)
    }

    private fun validHost(value: String): Boolean =
        value.length in 1..253 && value.matches(Regex("[A-Za-z0-9.-]+")) &&
            !value.startsWith('-') && !value.endsWith('-') && !value.contains("..")

    @Suppress("DEPRECATION") // API 26–29 require the legacy inset accessors.
    private fun applySystemInsets(view: View, insets: WindowInsets) {
        if (Build.VERSION.SDK_INT >= 30) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        } else {
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
        }
    }

    private fun showStatus(message: String, tone: StatusTone) {
        statusLabel.text = tone.label
        statusLabel.setTextColor(ui.statusColor(tone))
        statusCard.background = ui.statusBackground(tone)
        statusView.text = message
        progress.visibility = if (tone == StatusTone.WORKING) View.VISIBLE else View.GONE
    }

    private fun label(text: String, size: Int, color: Int, top: Int): TextView =
        ui.label(text, size, color, top)

    private fun marginTop(top: Int): LinearLayout.LayoutParams = ui.margin(top)
    private fun dp(value: Int): Int = ui.dp(value)

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
