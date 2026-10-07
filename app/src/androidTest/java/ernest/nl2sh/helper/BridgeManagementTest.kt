package ernest.nl2sh.helper

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BridgeManagementTest {
    @Suppress("DEPRECATION")
    @Test fun installsVerifiedCompanionAndPreservesNativePidAndSettings() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val host = requireNotNull(arguments.getString("target_host"))
        val record = ConnectionRecord(ConnectionMode.TCP, host, requireNotNull(arguments.getString("adb_port")).toInt())
        val webPort = requireNotNull(arguments.getString("web_port")).toInt()
        fun pid(): Long {
            val connection = URL("http://$host:$webPort/api/info").openConnection(Proxy.NO_PROXY) as HttpURLConnection
            connection.connectTimeout = 3000; connection.readTimeout = 15000
            try { return JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).getLong("pid") }
            finally { connection.disconnect() }
        }
        val originalPid = pid()
        val apk = File(context.cacheDir, "bridge-fixture.apk")
        instrumentation.context.assets.open("bridge.apk").use { input -> apk.outputStream().use { input.copyTo(it) } }
        val info = requireNotNull(context.packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES))
        val certificate = MessageDigest.getInstance("SHA-256").digest(requireNotNull(info.signatures).single().toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val artifact = RuntimeAsset(requireNotNull(info.versionName), 2, "https://example.invalid/bridge.apk",
            "https://example.invalid/bridge.apk.sig", sha256(apk), apk.length(), "com.nl2sh.bridge", certificate)
        val manager = DeviceBridgeManager(context, { version, _ -> RuntimePolicy(version, artifact, artifact) }, { apk })
        val before = manager.perform(record, BridgeAction.INSPECT) { }
        assertTrue(before.installed)
        assertEquals(2, before.protocol)
        assertFalse(before.drift)
        val wrong = DeviceBridgeManager(context, { version, _ -> RuntimePolicy(version, artifact, artifact.copy(certificateSha = "0".repeat(64))) }, { apk })
        val refusal = runCatching { wrong.perform(record, BridgeAction.INSTALL) { } }.exceptionOrNull()
        assertNotNull(refusal)
        assertTrue(requireNotNull(refusal).message.orEmpty().contains("certificate mismatch"))
        assertEquals(originalPid, pid())
        val installed = manager.perform(record, BridgeAction.INSTALL) { }
        assertEquals(artifact.version, installed.version)
        assertEquals(2, installed.protocol)
        assertEquals(before.accessibilityEnabled, installed.accessibilityEnabled)
        assertEquals(before.keyboardEnabled, installed.keyboardEnabled)
        assertEquals(before.keyboardSelected, installed.keyboardSelected)
        assertEquals(originalPid, pid())
        manager.perform(record, BridgeAction.KEYBOARD_SETTINGS) { }
        manager.perform(record, BridgeAction.ACCESSIBILITY_SETTINGS) { }
        manager.perform(record, BridgeAction.OPEN_APP) { }
        assertEquals(originalPid, pid())
    }
}
