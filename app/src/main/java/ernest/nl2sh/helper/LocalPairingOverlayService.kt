package ernest.nl2sh.helper

import android.app.*
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.text.InputType
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import kotlinx.coroutines.*
import kotlin.math.roundToInt

/** Owns a user-visible pairing session independently of the Activity behind Settings. */
class LocalPairingOverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var manager: WindowManager
    private lateinit var root: LinearLayout
    private lateinit var body: ScrollView
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var port: EditText
    private lateinit var code: EditText
    private lateinit var status: TextView
    private lateinit var pairButton: Button
    private var job: Job? = null
    private var added = false
    private var destroying = false
    private var paired = false
    private var collapsed = false
    private var maxBodyHeight = 1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "close") { stopSelf(); return START_NOT_STICKY }
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == "pair_notification") {
            val input = RemoteInput.getResultsFromIntent(intent)?.getCharSequence("pairing")?.toString().orEmpty()
            intent.clipData = null
            if (added) {
                val values = parseNotificationPairing(input)
                if (values == null) { status.text = "失败：通知输入格式为临时端口 空格 六位码。"; updateNotification("输入格式无效，请重试。") }
                else startPairing(values.first, values.second)
            } else stopSelf()
            return START_NOT_STICKY
        }
        if (added) return START_NOT_STICKY
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("local_pairing", "本机配对浮窗", NotificationManager.IMPORTANCE_LOW))
        startForeground(31, notification("系统隐藏浮窗时，在此输入临时端口和六位码。"))
        try {
            showWindow()
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Toast.makeText(this, "无法打开浮窗或开发者选项，请检查浮窗权限。", Toast.LENGTH_LONG).show()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun notification(message: String): Notification {
        val close = PendingIntent.getService(this, 1, Intent(this, javaClass).setAction("close"), PendingIntent.FLAG_IMMUTABLE)
        val reply = PendingIntent.getService(this, 2, Intent(this, javaClass).setAction("pair_notification"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        val returnIntent = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("local_pairing_complete", paired).putExtra("close_pairing_overlay", true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, "local_pairing")
            .setSmallIcon(R.drawable.ic_launcher_foreground).setContentTitle(if (paired) "本机已配对" else "本机配对浮窗")
            .setContentText(message).setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(returnIntent).setOngoing(true).setOnlyAlertOnce(true)
        if (!paired && job == null) builder.addAction(Notification.Action.Builder(null, "输入配对信息", reply)
            .addRemoteInput(RemoteInput.Builder("pairing").setLabel("临时端口 空格 六位码").build()).build())
        builder.addAction(Notification.Action.Builder(null, "返回助手", returnIntent).build())
        builder.addAction(Notification.Action.Builder(null, "关闭浮窗", close).build())
        return builder.build()
    }

    private fun updateNotification(message: String) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        if (!destroying) getSystemService(NotificationManager::class.java).notify(31, notification(message))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun color(id: Int) = getColor(id)
    private fun background(focused: Boolean = false) = GradientDrawable().apply {
        setColor(color(R.color.ui_background_alt)); cornerRadius = dp(8).toFloat()
        setStroke(dp(if (focused) 2 else 1), color(if (focused) R.color.ui_border_focus else R.color.ui_border))
    }
    private fun text(value: String, size: Float = 14f) = TextView(this).apply {
        text = value; textSize = size; tag = size; setTextColor(color(R.color.ui_text_primary))
    }
    private fun button(value: String, action: () -> Unit) = Button(ContextThemeWrapper(this, R.style.Theme_Nl2shHelper)).apply {
        text = value; textSize = 14f; tag = 14f; isAllCaps = false; minHeight = dp(48)
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(color(R.color.ui_text_secondary), color(R.color.ui_accent))))
        background = background(); setOnClickListener { action() }
    }

    private fun showWindow() {
        manager = getSystemService(WindowManager::class.java)
        params = WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START; x = dp(12); y = dp(80)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = background(); setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val handle = text("本机配对 · 拖动", 14f).apply {
            gravity = Gravity.CENTER_VERTICAL; minHeight = dp(48); contentDescription = "拖动此处移动配对浮窗"
        }
        header.addView(handle, LinearLayout.LayoutParams(0, -2, 1f))
        val collapse = button("收起") { }
        collapse.setOnClickListener {
            collapsed = !collapsed; body.visibility = if (collapsed) View.GONE else View.VISIBLE
            collapse.text = if (collapsed) "展开" else "收起"
            releaseKeyboard(); resizeWindow()
        }
        header.addView(collapse, LinearLayout.LayoutParams(dp(64), -2))
        header.addView(button("关闭") { stopSelf() }, LinearLayout.LayoutParams(dp(64), -2))
        root.addView(header)
        enableDragging(handle)
        body = object : ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val limit = if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED)
                    maxBodyHeight else minOf(maxBodyHeight, View.MeasureSpec.getSize(heightMeasureSpec))
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST))
            }
        }
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        form.addView(text("在无线调试中打开配对码窗口。系统隐藏浮窗时，可在通知中输入“临时端口 六位码”。"))
        port = field("临时配对端口", false)
        code = field("六位配对码", true)
        form.addView(port); form.addView(code)
        status = text("就绪：等待输入配对信息。").apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        form.addView(status)
        pairButton = button("配对") { startPairing() }
        form.addView(pairButton)
        form.addView(button("返回助手") {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("local_pairing_complete", paired))
            stopSelf()
        })
        body.addView(form); root.addView(body)
        resizeWindow(false)
        manager.addView(root, params); added = true
    }

    private fun field(label: String, password: Boolean): EditText =
        EditText(ContextThemeWrapper(this, R.style.Theme_Nl2shHelper)).apply {
            hint = label; textSize = 16f; tag = 16f; minHeight = dp(48); setPadding(dp(8), dp(8), dp(8), dp(8))
            inputType = InputType.TYPE_CLASS_NUMBER or if (password) InputType.TYPE_NUMBER_VARIATION_PASSWORD else 0
            setTextColor(color(R.color.ui_text_primary)); setHintTextColor(color(R.color.ui_text_secondary))
            background = background()
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            isSaveEnabled = false
            onFocusChangeListener = View.OnFocusChangeListener { _, focused -> background = background(focused) }
            setOnTouchListener { _, event ->
                if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                    params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                    manager.updateViewLayout(root, params)
                    requestFocus()
                    post { getSystemService(InputMethodManager::class.java).showSoftInput(this, InputMethodManager.SHOW_IMPLICIT) }
                }
                false
            }
        }

    private fun startPairing() {
        val selectedPort = port.text.toString().trim().toIntOrNull()
        val secret = code.text.toString().trim()
        if (selectedPort == null || selectedPort !in 1..65535 || !secret.matches(Regex("[0-9]{6}"))) {
            status.text = "失败：请输入 1–65535 临时配对端口和六位码。"; return
        }
        startPairing(selectedPort, secret)
    }

    private fun startPairing(selectedPort: Int, secret: String) {
        if (job != null) return
        paired = false
        port.isEnabled = false; code.isEnabled = false; pairButton.isEnabled = false; pairButton.text = "正在配对…"
        code.text.clear(); releaseKeyboard()
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                LocalDeviceConnector(applicationContext).pairLocal(selectedPort, secret) { status.text = "处理中：$it" }
                paired = true
                status.text = "已配对：返回助手启动 nl2sh。尚未安装或检查 Web 服务。"
                updateNotification("配对成功，返回助手启动 / 首次安装 nl2sh。")
                port.text.clear()
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                status.text = "失败：请保持系统配对码窗口打开，核对端口和当前六位码后重试。"
            } finally {
                code.text.clear(); port.isEnabled = true; code.isEnabled = true; pairButton.isEnabled = true; pairButton.text = "配对"; job = null
                updateNotification(if (paired) "已配对，返回助手启动 nl2sh。" else "配对失败，请核对临时端口和当前六位码后重试。")
            }
        }
        updateNotification("正在配对，请保持系统配对码窗口打开。")
        job?.start()
    }

    private fun releaseKeyboard() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(root.windowToken, 0)
        root.clearFocus()
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (added) manager.updateViewLayout(root, params)
    }

    private fun resizeWindow(update: Boolean = true) {
        val bounds = if (android.os.Build.VERSION.SDK_INT >= 30) manager.currentWindowMetrics.bounds
            else android.graphics.Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        params.width = minOf(dp(320), bounds.width() - dp(16)).coerceAtLeast(1)
        maxBodyHeight = minOf(dp(360), bounds.height() * 3 / 5).coerceAtLeast(dp(48))
        body.layoutParams = LinearLayout.LayoutParams(-1, -2)
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        root.measure(View.MeasureSpec.makeMeasureSpec(params.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(bounds.height(), View.MeasureSpec.AT_MOST))
        val position = clampOverlayPosition(params.x, params.y, bounds.width(), bounds.height(), params.width, root.measuredHeight)
        params.x = position.first; params.y = position.second
        if (update && added) manager.updateViewLayout(root, params)
    }

    @Suppress("ClickableViewAccessibility")
    private fun enableDragging(handle: View) {
        var x = 0; var y = 0; var touchX = 0f; var touchY = 0f
        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { x = params.x; y = params.y; touchX = event.rawX; touchY = event.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    params.x = x + (event.rawX - touchX).roundToInt(); params.y = y + (event.rawY - touchY).roundToInt()
                    resizeWindow(); true
                }
                MotionEvent.ACTION_UP -> { handle.performClick(); true }
                else -> false
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (added) {
            fun updateFonts(view: View) {
                if (view is TextView) view.textSize = view.tag as? Float ?: 14f
                if (view is ViewGroup) for (index in 0 until view.childCount) updateFonts(view.getChildAt(index))
            }
            updateFonts(root)
            resizeWindow()
        }
    }

    override fun onDestroy() {
        destroying = true
        scope.cancel()
        if (added) { code.text.clear(); manager.removeView(root); added = false }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}

internal fun clampOverlayPosition(x: Int, y: Int, screenWidth: Int, screenHeight: Int,
                                  width: Int, height: Int): Pair<Int, Int> =
    x.coerceIn(0, (screenWidth - width).coerceAtLeast(0)) to
        y.coerceIn(0, (screenHeight - height).coerceAtLeast(0))

internal fun parseNotificationPairing(value: String): Pair<Int, String>? {
    val parts = value.trim().split(Regex("\\s+"))
    if (parts.size != 2 || !parts[1].matches(Regex("[0-9]{6}"))) return null
    val port = parts[0].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
    return port to parts[1]
}
