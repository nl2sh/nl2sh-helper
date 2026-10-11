package ernest.nl2sh.helper

import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ernest.nl2sh.helper.ui.*
import ernest.nl2sh.helper.ui.service.DoctorCard
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

/** Component acceptance with synthetic report rows; never pairs, installs or changes target settings. */
@RunWith(AndroidJUnit4::class)
class DeviceDoctorUiTest {
    @Test fun webReplyMustMatchCompleteNativeIdentity() {
        val state = JSONObject("""{"protocol":1,"state":"ready","pid":42,"port":9999,"version":"1.1.0"}""")
        val info = JSONObject("""{"protocol":1,"pid":42,"port":9999,"version":"1.1.0"}""")
        assertTrue(doctorWebMatches(state, info))
        assertFalse(doctorWebMatches(state, JSONObject()))
        for (field in listOf("pid", "port", "version")) {
            val mismatch = JSONObject(info.toString()).put(field, if (field == "version") "1.2.0" else 43)
            assertFalse(doctorWebMatches(state, mismatch))
        }
    }
    @Test fun diagnosticRowsAndRepairsRenderAtLargeFontWithoutClaimingOnline() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.executeShellCommand("pm grant ${instrumentation.targetContext.packageName} android.permission.POST_NOTIFICATIONS").close()
        }
        val record = ConnectionRecord(ConnectionMode.TCP, "127.0.0.1", 5555)
        val state = HelperUiState().apply {
            doctorReport = DoctorReport(record, listOf(
                DoctorCheck("ADB 授权与连接", DoctorState.OK, "已完成目标 ADB 连接。"),
                DoctorCheck("Web 连通性与身份", DoctorState.UNKNOWN, "未能取得可验证的结果。"),
                doctorProtocolCheck("stopped")))
        }
        val actions = HelperActions(diagnoseSelected = {}, diagnose = {}, repairDoctor = {}, clearLlmConfig = {},
            selectMode = {}, connect = {}, openDeveloperOptions = {}, openPairingOverlay = {}, connectHistory = {},
            removeHistory = {}, manageBridge = {}, confirmDeviceAction = {}, cancelQr = {}, openBrowser = {})
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent { Nl2shTheme {
                    CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                        Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) { state.DoctorCard(actions) }
                    }
                } }
            }
            instrumentation.waitForIdleSync()
            var text = ""
            for (attempt in 0 until 50) {
                text = allText(instrumentation.uiAutomation.rootInActiveWindow)
                if (text.contains("一键诊断")) break
                Thread.sleep(100)
            }
            assumeTrue("UI acceptance requires the test app in foreground; another app or system window obscures it",
                instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == instrumentation.targetContext.packageName)
            assertTrue("Diagnostic button missing from the foreground test app", text.contains("一键诊断"))
            assertTrue(text.contains("设备一键诊断"))
            assertFalse(text.contains("已启动"))
            val image = instrumentation.uiAutomation.takeScreenshot()
            assertNotNull(image)
            image?.recycle()
        }
    }
    private fun allText(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        return buildString {
            append(node.text ?: ""); append('\n')
            for (i in 0 until node.childCount) append(allText(node.getChild(i)))
        }
    }
}
