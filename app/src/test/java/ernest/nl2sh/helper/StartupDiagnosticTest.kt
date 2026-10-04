package ernest.nl2sh.helper

import org.junit.Assert.assertTrue
import org.junit.Test

class StartupDiagnosticTest {
    @Test fun failureIncludesEveryAttemptProcessStateAndLog() {
        val message = formatStartupFailure(listOf(
            StartupAttemptDiagnostic(1, "pid=12\nalive=no\nexe=unknown", "first failure"),
            StartupAttemptDiagnostic(2, "pid=34\nalive=yes\nexe=/data/local/tmp/nl2sh", "second failure"),
        ))

        assertTrue(message.contains("第1次"))
        assertTrue(message.contains("alive=no"))
        assertTrue(message.contains("first failure"))
        assertTrue(message.contains("第2次"))
        assertTrue(message.contains("/data/local/tmp/nl2sh"))
        assertTrue(message.contains("second failure"))
    }
}
