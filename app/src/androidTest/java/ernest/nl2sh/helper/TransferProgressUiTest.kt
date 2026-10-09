package ernest.nl2sh.helper

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.view.accessibility.AccessibilityNodeInfo
import ernest.nl2sh.helper.ui.HelperUiState
import ernest.nl2sh.helper.ui.StatusTone
import ernest.nl2sh.helper.ui.UiStatus
import ernest.nl2sh.helper.ui.components.StatusCard
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Component smoke test uses simulated counters, never connects or installs a runtime. */
@RunWith(AndroidJUnit4::class)
class TransferProgressUiTest {
    @Test fun transferCountersAndUnmeasuredInstallationPhasesRemainReadable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val state = HelperUiState()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent { Nl2shTheme {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp)) { state.StatusCard() }
                } }
                state.status = UiStatus(StatusTone.WORKING, "下载 nl2sh v1.2.0", TransferProgress("下载", 50, 100))
            }
            awaitText("50%")
            screenshot("helper-progress-download.png")
            scenario.onActivity {
                state.status = UiStatus(StatusTone.WORKING, "推送 nl2sh v1.2.0（等待设备确认）", TransferProgress("推送", 100, 100))
            }
            awaitText("100%")
            awaitText("等待设备确认")
            screenshot("helper-progress-push.png")
            scenario.onActivity { state.status = UiStatus(StatusTone.WORKING, "安装更新：等待系统安装结果与 Web 健康检查…") }
            awaitText("等待系统安装结果")
            assertFalse(allText().contains("100%"))
            screenshot("helper-progress-install.png")
            scenario.onActivity { state.status = UiStatus(StatusTone.ERROR, "更新失败；已恢复旧程序与服务。") }
            awaitText("更新失败")
            assertFalse(allText().contains("100%"))
            assertFalse(allText().contains("50%"))
            screenshot("helper-progress-error.png")
        }
        instrumentation.waitForIdleSync()
    }
    private fun allText(): String {
        fun collect(node: AccessibilityNodeInfo?): String {
            if (node == null) return ""
            return buildString {
                append(node.text ?: ""); append('\n')
                for (index in 0 until node.childCount) append(collect(node.getChild(index)))
            }
        }
        return collect(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow)
    }
    private fun awaitText(text: String) {
        repeat(50) {
            if (allText().contains(text)) return
            Thread.sleep(100)
        }
        fail("Missing visible status text: $text; ${allText()}")
    }
    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot()?.let { image ->
            File(instrumentation.targetContext.cacheDir, name).outputStream().use {
                image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            image.recycle()
        }
    }
}
