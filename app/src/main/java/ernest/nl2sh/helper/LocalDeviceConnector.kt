package ernest.nl2sh.helper

import android.content.Context
import android.os.Build
import android.util.Log
import ernest.ascrcpy.adb.AdbEndpoint
import ernest.ascrcpy.adb.DefaultAdbClient
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.net.NetworkInterface

/** Local mode never connects to an advertised LAN address, even when NSD finds a remote phone. */
internal class LocalDeviceConnector(private val context: Context) {
    private val preferences = context.getSharedPreferences("local_adb", Context.MODE_PRIVATE)

    suspend fun connect(pairingPort: Int?, code: String, connectionPort: Int?,
                        fallbackPort: Int? = null, report: suspend (String) -> Unit): ConnectionRecord {
        check(Build.VERSION.SDK_INT >= 30) { "本机模式需要 Android 11+ 的无线调试。" }
        var guid = preferences.getString("guid", "").orEmpty()
        if (pairingPort != null) guid = pairLocal(pairingPort, code, report)
        report("正在查找本机无线调试连接端口…")
        val savedPort = preferences.getInt("port", 0).takeIf { it in 1..65535 }
        val selectedPort = connectionPort ?: discover(guid) ?: savedPort ?: fallbackPort
        requireNotNull(selectedPort) { "未发现本机连接服务。请填写无线调试主页的连接端口（不是配对端口）后重试；已完成配对无需再次输入配对码。" }
        check(probe(selectedPort)) { "无法连接本机无线调试端口 $selectedPort。请开启无线调试，核对连接端口；授权被撤销时重新配对。" }
        preferences.edit().putInt("port", selectedPort).apply()
        return ConnectionRecord(ConnectionMode.LOCAL, LOCAL_ADB_HOST, selectedPort, guid)
    }

    suspend fun pairLocal(port: Int, code: String, report: suspend (String) -> Unit): String {
        check(Build.VERSION.SDK_INT >= 30) { "本机模式需要 Android 11+ 的无线调试。" }
        require(port in 1..65535 && code.matches(Regex("[0-9]{6}"))) { "请输入有效的临时配对端口和六位码。" }
        report("正在与本机无线调试配对…")
        val guid = withContext(Dispatchers.IO) {
            val client = DefaultAdbClient.factory(context).create()
            try { withTimeout(45_000) { client.pairWireless(AdbEndpoint(LOCAL_ADB_HOST, port), code) } }
            finally { client.close() }
        }
        preferences.edit().putString("guid", guid).apply()
        return guid
    }

    suspend fun reconnect(record: ConnectionRecord, report: suspend (String) -> Unit): ConnectionRecord =
        connect(null, "", null, record.port, report)

    private suspend fun discover(guid: String): Int? {
        val services = Channel<QrPairingDiscovery.ResolvedService>(Channel.UNLIMITED)
        val discovery = QrPairingDiscovery(context, null, {}, { services.trySend(it) }, {
            Log.w("Nl2shHelper", "Local NSD discovery failed: $it; manual port remains available")
        })
        return try {
            discovery.start()
            val addresses = withContext(Dispatchers.IO) { localAddresses() }
            withTimeoutOrNull(10_000) {
                firstReachableWirelessService(services, { service ->
                    localServiceMatches(service.name, service.host, guid, addresses)
                }, { probe(it.port) }).port
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w("Nl2shHelper", "Local discovery unavailable; falling back to saved port", error)
            null
        } finally { discovery.stop(); services.close() }
    }

    private suspend fun probe(port: Int): Boolean = withContext(Dispatchers.IO) {
        val client = DefaultAdbClient.factory(context).create()
        try {
            withTimeout(5_000) { client.connectWireless(AdbEndpoint(LOCAL_ADB_HOST, port)) }
            true
        } catch (error: CancellationException) {
            if (error !is TimeoutCancellationException) throw error
            false
        } catch (_: Exception) { false }
        finally { client.close() }
    }

    private fun localAddresses(): Set<String> = NetworkInterface.getNetworkInterfaces()?.toList()
        .orEmpty().flatMap { it.inetAddresses.toList() }.map { it.hostAddress.orEmpty().substringBefore('%') }.toSet()
}

internal const val LOCAL_ADB_HOST = "127.0.0.1"
internal fun localServiceMatches(name: String, host: String, guid: String, addresses: Set<String>): Boolean =
    host.substringBefore('%') in addresses && (guid.isBlank() || name.contains(guid, ignoreCase = true))
