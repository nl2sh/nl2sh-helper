package ernest.nl2sh.helper

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private lateinit var statusView: TextView
    private lateinit var installButton: Button
    private lateinit var openButton: Button
    private var installJob: Job? = null
    private var webUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("connection", MODE_PRIVATE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(28))
            setBackgroundColor(Color.rgb(247, 249, 251))
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        root.addView(label("nl2sh 助手", 27, Color.rgb(25, 54, 80), 0))
        root.addView(label("连接目标 Android 设备，自动安装最新 nl2sh 并启动 Web 界面。", 16, Color.DKGRAY, 12))
        root.addView(label("使用说明", 20, Color.rgb(25, 54, 80), 28))
        root.addView(label("1. 在目标设备开启 TCP ADB，首次连接时批准 RSA 授权。\n2. 输入设备 IP 和 ADB 端口，点击安装并启动。助手自动选择 32/64 位版本，校验 SHA-256 并缓存。\n3. 启动后通过下方按钮在系统浏览器打开目标设备 Web 界面。两台设备需能相互访问，目标设备须允许访问 9999 端口。", 15, Color.DKGRAY, 10))
        root.addView(label("目标设备地址", 16, Color.rgb(25, 54, 80), 24))
        hostInput = EditText(this).apply {
            hint = "例如 192.168.1.20"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.getString("host", ""))
        }
        root.addView(hostInput)
        root.addView(label("ADB 端口", 16, Color.rgb(25, 54, 80), 18))
        portInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setText(prefs.getInt("port", 5555).toString())
        }
        root.addView(portInput)
        installButton = Button(this).apply {
            text = "安装并启动"
            setOnClickListener { startInstall() }
        }
        root.addView(installButton, marginTop(22))
        openButton = Button(this).apply {
            text = "在浏览器中打开 nl2sh"
            isEnabled = false
            setOnClickListener {
                webUrl?.let { url -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            }
        }
        root.addView(openButton, marginTop(8))
        statusView = label("等待连接。", 15, Color.DKGRAY, 20)
        root.addView(statusView)
        root.addView(label("目标 Web 界面当前无需登录，仅应在可信网络中使用。模型服务可在 Web 界面内配置。", 13, Color.GRAY, 26))
    }

    private fun startInstall() {
        if (installJob?.isActive == true) return
        val host = hostInput.text.toString().trim()
        val port = portInput.text.toString().toIntOrNull()
        if (!validHost(host) || port == null || port !in 1..65535) {
            statusView.text = "请输入有效的 IP/主机名和 1–65535 端口。"
            return
        }
        getSharedPreferences("connection", MODE_PRIVATE).edit().putString("host", host).putInt("port", port).apply()
        installButton.isEnabled = false
        openButton.isEnabled = false
        webUrl = null
        installJob = scope.launch {
            try {
                webUrl = DeviceInstaller(applicationContext).install(host, port) { message ->
                    withContext(Dispatchers.Main) { statusView.text = message }
                }
                statusView.text = "已启动。点击按钮在系统浏览器访问 $webUrl"
                openButton.isEnabled = true
            } catch (error: Exception) {
                statusView.text = "失败：${error.message ?: error.javaClass.simpleName}"
            } finally {
                installButton.isEnabled = true
            }
        }
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

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
