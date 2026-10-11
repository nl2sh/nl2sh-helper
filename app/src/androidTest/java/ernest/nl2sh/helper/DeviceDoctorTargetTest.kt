package ernest.nl2sh.helper

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ernest.nl2sh.helper.ui.HelperActions
import ernest.nl2sh.helper.ui.HelperUiState
import ernest.nl2sh.helper.ui.service.DoctorCard
import java.io.File
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in integration: the controller is disposable; the target must run an independently staged ELF. */
@RunWith(AndroidJUnit4::class)
class DeviceDoctorTargetTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = instrumentation.targetContext
    private fun target(): ConnectionRecord {
        require(args.getString("acceptance_target") == "yes") { "Explicit target opt-in required" }
        return ConnectionRecord(ConnectionMode.TCP, requireNotNull(args.getString("target_host")),
            requireNotNull(args.getString("adb_port")).toInt())
    }
    private fun info(): JSONObject {
        val url = "http://${target().host}:${requireNotNull(args.getString("web_port"))}/api/info"
        val connection = URL(url).openConnection(Proxy.NO_PROXY) as HttpURLConnection
        connection.connectTimeout = 3000; connection.readTimeout = 15000
        try { return JSONObject(connection.inputStream.bufferedReader().use { it.readText() }) }
        finally { connection.disconnect() }
    }
    private fun verify(report: DoctorReport) {
        for (name in listOf("ADB 授权与连接", "执行身份", "系统与架构", "nl2sh 安装", "受管服务", "运行程序摘要", "Web 连通性与身份", "MCP / A2A")) {
            assertEquals("Check must have actual evidence: $name", DoctorState.OK, report.checks.single { it.name == name }.state)
        }
        assertTrue(report.checks.single { it.name == "运行程序摘要" }.detail.contains(requireNotNull(args.getString("runtime_sha256"))))
        assertNotNull(report.verifiedWebUrl)
        // This target deliberately has no active accessibility/IME service: do not invent success.
        if (args.getString("bridge_unavailable") == "yes") {
            assertNotEquals(DoctorState.OK, report.checks.single { it.name == "Bridge 无障碍" }.state)
            assertNotEquals(DoctorState.OK, report.checks.single { it.name == "Bridge 输入法" }.state)
        }
    }
    @Test fun remoteDoctorAndReconnectPreserveRunningIdentity() = runBlocking {
        val before = info()
        val report = DeviceDoctor(context).inspect(target())
        verify(report)
        assertEquals(before.getLong("pid"), info().getLong("pid"))
        val installer = DeviceInstaller(context) { error("Reconnect must not query or download a release") }
        repeat(2) {
            val result = installer.perform(target().host, target().port, false, DeviceAction.CONNECT) { }
            assertEquals(report.verifiedWebUrl, result.url)
            assertEquals(before.getLong("pid"), info().getLong("pid"))
        }
    }
    @Test fun realReportRendersAtSmallWidthLargeFontAndLandscape() = runBlocking {
        val report = DeviceDoctor(context).inspect(target())
        verify(report)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS").close()
        }
        val state = HelperUiState().apply { doctorReport = report }
        val actions = HelperActions(diagnoseSelected = {}, diagnose = {}, repairDoctor = {}, clearLlmConfig = {},
            selectMode = {}, connect = {}, openDeveloperOptions = {}, openPairingOverlay = {}, connectHistory = {},
            removeHistory = {}, manageBridge = {}, confirmDeviceAction = {}, cancelQr = {}, openBrowser = {})
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent { Nl2shTheme {
                    CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) { state.DoctorCard(actions) }
                        }
                    }
                } }
            }
            awaitText("设备一键诊断")
            screenshot("doctor-narrow-large-font.png")
            // Activity recreation restores the production page; inspect that page in real landscape.
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            Thread.sleep(1500)
            assertTrue(context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
            screenshot("helper-landscape.png")
        }
    }
    private fun awaitText(expected: String) {
        fun collect(node: AccessibilityNodeInfo?): String = if (node == null) "" else buildString {
            append(node.text ?: "")
            for (index in 0 until node.childCount) append(collect(node.getChild(index)))
        }
        repeat(50) {
            if (collect(instrumentation.uiAutomation.rootInActiveWindow).contains(expected)) return
            Thread.sleep(100)
        }
        fail("Expected diagnostic UI did not become visible")
    }
    private fun screenshot(name: String) {
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(context.cacheDir, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { image.recycle() }
    }
}
