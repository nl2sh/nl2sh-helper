package ernest.nl2sh.helper

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run only against a disposable emulator with prepared ELF fixtures and explicit ADB/Web routes. */
@RunWith(AndroidJUnit4::class)
class RuntimeLifecycleTest {
    @Test fun reconnectPreservesPidAndFailedUpgradeRestoresVerifiedPreviousRuntime() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val host = requireNotNull(arguments.getString("target_host")) { "Pass target_host for a disposable emulator" }
        val adbPort = requireNotNull(arguments.getString("adb_port")).toInt()
        val webPort = requireNotNull(arguments.getString("web_port")).toInt()
        fun info(): JSONObject {
            val connection = URL("http://$host:$webPort/api/info").openConnection(Proxy.NO_PROXY) as HttpURLConnection
            connection.connectTimeout = 3000; connection.readTimeout = 15000
            try { return JSONObject(connection.inputStream.bufferedReader().use { it.readText() }) }
            finally { connection.disconnect() }
        }
        fun fixture(name: String): File = File(context.cacheDir, name).also { file ->
            instrumentation.context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        val before = info()
        val connect = DeviceInstaller(context) { error("Connecting must not query or download releases") }
        val connected = connect.perform(host, adbPort, false, DeviceAction.CONNECT) { }
        assertEquals("http://$host:$webPort/", connected.url)
        assertEquals(before.getLong("pid"), info().getLong("pid"))

        val bad = fixture("bad-runtime")
        val transfers = mutableListOf<TransferProgress>()
        val failed = DeviceInstaller(context, progress = transfers::add) { abi ->
            PreparedRelease(ReleaseBinary("v9.9.9", abi, "https://example.invalid/binary", "https://example.invalid/checksum"), bad, sha256(bad))
        }
        val error = runCatching { failed.perform(host, adbPort, false, DeviceAction.UPDATE) { } }.exceptionOrNull()
        assertNotNull(error)
        val pushed = transfers.last { it.label.startsWith("推送") }
        assertEquals(bad.length(), pushed.bytes)
        assertEquals(bad.length(), pushed.total)
        assertEquals(1f, pushed.fraction)
        assertTrue(requireNotNull(error).message!!.contains("已恢复旧程序"))
        val restored = info()
        assertEquals(before.getString("version"), restored.getString("version"))
        assertNotEquals(before.getLong("pid"), restored.getLong("pid"))
        // A failed upgrade must preserve the old installation owner as well as the binary.
        assertEquals(before.optJSONObject("update_ownership")?.optString("owner") ?: "standalone",
            restored.optJSONObject("update_ownership")?.optString("owner") ?: "standalone")

        val good = fixture("good-runtime")
        val version = requireNotNull(arguments.getString("runtime_version"))
        val upgraded = DeviceInstaller(context) { abi ->
            PreparedRelease(ReleaseBinary("v$version", abi, "https://example.invalid/binary", "https://example.invalid/checksum"), good, sha256(good))
        }
        upgraded.perform(host, adbPort, false, DeviceAction.UPDATE) { }
        val ready = info()
        assertEquals(version, ready.getString("version"))
        assertEquals("nl2sh-helper", ready.getJSONObject("update_ownership").getString("owner"))
        assertFalse(ready.getJSONObject("update_ownership").getBoolean("self_update_allowed"))
        upgraded.perform(host, adbPort, false, DeviceAction.UPDATE) { }
        assertEquals(ready.getLong("pid"), info().getLong("pid"))
        assertEquals("nl2sh-helper", info().getJSONObject("update_ownership").getString("owner"))
        connect.perform(host, adbPort, false, DeviceAction.CONNECT) { }
        assertEquals(ready.getLong("pid"), info().getLong("pid"))
    }
}
