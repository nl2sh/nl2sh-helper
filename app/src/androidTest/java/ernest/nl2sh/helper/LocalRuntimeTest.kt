package ernest.nl2sh.helper

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ernest.ascrcpy.adb.AdbEndpoint
import ernest.ascrcpy.adb.DefaultAdbClient
import java.io.File
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in: real local TLS pairing and installation on a disposable Android 11+ emulator. */
@RunWith(AndroidJUnit4::class)
class LocalRuntimeTest {
    @Test fun localPairInstallReconnectRestartAndStop() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        require(args.getString("disposable_local_device") == "yes")
        val pairingPort = requireNotNull(args.getString("pairing_port")).toInt()
        val code = requireNotNull(args.getString("pairing_code"))
        val port = requireNotNull(args.getString("connection_port")).toInt()
        val version = requireNotNull(args.getString("runtime_version"))
        val connector = LocalDeviceConnector(context)
        val record = connector.connect(pairingPort, code, port) { }
        assertEquals(ConnectionMode.LOCAL, record.mode)
        assertEquals(LOCAL_ADB_HOST, record.host)
        val client = DefaultAdbClient.factory(context).create()
        try {
            client.connectWireless(AdbEndpoint(record.host, record.port))
            assertEquals("2000", client.shell("id -u").text().trim())
        } finally { client.close() }
        val runtime = File(context.cacheDir, "local-runtime")
        instrumentation.context.assets.open("good-runtime").use { source ->
            runtime.outputStream().use { source.copyTo(it) }
        }
        var loads = 0
        val installer = DeviceInstaller(context) { abi ->
            loads++
            PreparedRelease(ReleaseBinary("v$version", abi, "https://example.invalid/binary", "https://example.invalid/checksum"), runtime, sha256(runtime))
        }
        val result = installer.perform(record.host, record.port, true, DeviceAction.CONNECT) { }
        assertEquals(1, loads)
        assertTrue(requireNotNull(result.url).startsWith("http://127.0.0.1:"))
        fun info(): JSONObject {
            val connection = URL(result.url + "api/info").openConnection(Proxy.NO_PROXY) as HttpURLConnection
            connection.connectTimeout = 3000; connection.readTimeout = 5000
            try { return JSONObject(connection.inputStream.bufferedReader().use { it.readText() }) }
            finally { connection.disconnect() }
        }
        val pid = info().getLong("pid")
        val saved = connector.reconnect(record) { }
        installer.perform(saved.host, saved.port, true, DeviceAction.CONNECT) { }
        assertEquals(1, loads)
        assertEquals(pid, info().getLong("pid"))
        ConnectionHistory(context).save(record)
        assertEquals(1, ConnectionHistory(context).list(ConnectionMode.LOCAL).size)
        installer.perform(saved.host, saved.port, true, DeviceAction.RESTART) { }
        assertNotEquals(pid, info().getLong("pid"))
        val stopped = installer.perform(saved.host, saved.port, true, DeviceAction.STOP) { }
        assertNull(stopped.url)
    }
}
