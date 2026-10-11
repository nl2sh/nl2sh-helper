package ernest.nl2sh.helper

import android.content.Context
import ernest.ascrcpy.adb.AdbClient
import ernest.ascrcpy.adb.AdbEndpoint
import ernest.ascrcpy.adb.DefaultAdbClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

internal enum class DoctorState(val label: String) { OK("通过"), ATTENTION("需处理"), UNKNOWN("未知") }
internal enum class DoctorRepair(val label: String) {
    CONNECT("连接 / 首次安装"), RESTART("重启服务"), BRIDGE("打开 Bridge 管理"),
    ACCESSIBILITY("打开无障碍设置"), KEYBOARD("打开键盘设置"), WEB("打开 Web 设置")
}
internal data class DoctorCheck(val name: String, val state: DoctorState, val detail: String, val repair: DoctorRepair? = null)
internal data class DoctorReport(val target: ConnectionRecord, val checks: List<DoctorCheck>, val verifiedWebUrl: String? = null) {
    val needsAttention: Boolean get() = checks.any { it.state != DoctorState.OK }
}

/** Read-only, offline target diagnostics. Never installs, starts, changes settings or reads tokens. */
internal class DeviceDoctor(private val context: Context) {
    suspend fun inspect(record: ConnectionRecord): DoctorReport = withContext(Dispatchers.IO) {
        val client = DefaultAdbClient.factory(context).create()
        val checks = mutableListOf<DoctorCheck>()
        try {
            try {
                withTimeout(15_000) {
                    if (record.mode == ConnectionMode.TCP) client.connect(AdbEndpoint(record.host, record.port))
                    else client.connectWireless(AdbEndpoint(record.host, record.port))
                }
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                return@withContext DoctorReport(record, listOf(DoctorCheck("ADB 授权与连接", DoctorState.ATTENTION,
                    "连接未完成。请检查地址、当前连接端口、无线调试及目标设备的授权提示。", DoctorRepair.CONNECT)))
            }
            checks += DoctorCheck("ADB 授权与连接", DoctorState.OK, "已完成目标 ADB 连接。")
            probe(checks, "执行身份") {
                val uid = run(client, "id -u").toIntOrNull()
                DoctorCheck("执行身份", if (uid == 2000 || uid == 0) DoctorState.OK else DoctorState.ATTENTION,
                    "实际 UID：${uid ?: "未知"}；完整 UI 能力要求 shell/root。")
            }
            probe(checks, "系统与架构") {
                val api = run(client, "getprop ro.build.version.sdk").toIntOrNull()
                val abis = run(client, "getprop ro.product.cpu.abilist").ifBlank { run(client, "getprop ro.product.cpu.abi") }
                val abi = runCatching { selectAbi(abis) }.getOrNull()
                DoctorCheck("系统与架构", if (api != null && api >= 26 && abi != null) DoctorState.OK else DoctorState.ATTENTION,
                    "API：${api ?: "未知"}；ABI：${abi ?: "无支持的发布架构"}")
            }
            var version: String? = null
            probe(checks, "nl2sh 安装") {
                version = run(client, "if [ -x /data/local/tmp/nl2sh ]; then /data/local/tmp/nl2sh --version; fi")
                    .removePrefix("nl2sh ").takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,64}")) }
                DoctorCheck("nl2sh 安装", if (version != null) DoctorState.OK else DoctorState.ATTENTION,
                    version?.let { "已安装版本：$it" } ?: "默认路径没有可验证的程序。", if (version == null) DoctorRepair.CONNECT else null)
            }
            var service: JSONObject? = null
            var verifiedWebUrl: String? = null
            probe(checks, "受管服务") {
                if (version == null) DoctorCheck("受管服务", DoctorState.UNKNOWN, "程序版本未知，未检查服务。")
                else {
                    val state = JSONObject(run(client, "/data/local/tmp/nl2sh service status --json"))
                    require(state.getInt("protocol") == 1)
                    service = state
                    val ready = validDoctorService(state)
                    DoctorCheck("受管服务", if (ready) DoctorState.OK else DoctorState.ATTENTION,
                        if (ready) "运行版本：${state.getString("version")}；PID：${state.getLong("pid")}；端口：${state.getInt("port")}" else "原生服务未就绪；当前状态：${state.optString("state").take(32)}。",
                        if (!ready) DoctorRepair.RESTART else null)
                }
            }
            probe(checks, "运行程序摘要") {
                val state = requireNotNull(service)
                require(validDoctorService(state))
                val pid = state.getLong("pid")
                val running = digest(run(client, "toybox sha256sum /proc/$pid/exe"))
                val installed = digest(run(client, "toybox sha256sum /data/local/tmp/nl2sh"))
                require(running != null && installed != null)
                DoctorCheck("运行程序摘要", if (running == installed) DoctorState.OK else DoctorState.ATTENTION,
                    "运行 ELF SHA-256：$running\n" + if (running == installed) "与当前安装程序一致。" else "与磁盘程序不同，请核对更新结果后显式重启。",
                    if (running != installed) DoctorRepair.RESTART else null)
            }
            probe(checks, "Web 连通性与身份") {
                val state = requireNotNull(service)
                require(validDoctorService(state))
                val port = state.getInt("port")
                val health = fetch(record.host, port, "healthz")
                val info = fetch(record.host, port, "api/info")
                val verified = health.optString("status") == "ok" && doctorWebMatches(state, info)
                if (verified) verifiedWebUrl = webUrl(record.host, port)
                DoctorCheck("Web 连通性与身份", if (verified) DoctorState.OK else DoctorState.ATTENTION,
                    if (verified) "控制端已核对实际 Web 端口、PID 与运行版本。" else "Web 回复与受管服务身份不一致。")
            }
            probe(checks, "MCP / A2A") {
                doctorProtocolCheck(service?.optJSONObject("connections")?.optString("state"))
            }
            try {
                val bridge = DeviceBridgeManager(context).inspectInstalled(client, version)
                checks += DoctorCheck("Bridge", if (bridge.installed && bridge.protocol == 2) DoctorState.OK else DoctorState.ATTENTION,
                    "版本：${bridge.version ?: "未验证"}；协议：${bridge.protocol ?: "未知"}",
                    if (!bridge.installed || bridge.protocol != 2) DoctorRepair.BRIDGE else null)
                checks += DoctorCheck("Bridge 无障碍", if (bridge.accessibilityEnabled && bridge.accessibilityRunning == true) DoctorState.OK else DoctorState.ATTENTION,
                    "启用：${bridge.accessibilityEnabled}；运行：${bridge.accessibilityRunning ?: "未知"}", DoctorRepair.ACCESSIBILITY)
                checks += DoctorCheck("Bridge 输入法", if (bridge.keyboardSelected && bridge.keyboardRunning == true) DoctorState.OK else DoctorState.ATTENTION,
                    "启用：${bridge.keyboardEnabled}；选中：${bridge.keyboardSelected}；运行：${bridge.keyboardRunning ?: "未知"}", DoctorRepair.KEYBOARD)
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                checks += DoctorCheck("Bridge", DoctorState.UNKNOWN, "无法验证 Bridge 与系统设置。", DoctorRepair.BRIDGE)
            }
            DoctorReport(record, checks, verifiedWebUrl)
        } finally { client.close() }
    }
    private suspend fun probe(checks: MutableList<DoctorCheck>, name: String, block: suspend () -> DoctorCheck) {
        checks += try { block() } catch (error: Exception) {
            if (error is CancellationException && error !is TimeoutCancellationException) throw error
            DoctorCheck(name, DoctorState.UNKNOWN, "未能取得可验证的结果；不会据此判断正常或自动修改设备。")
        }
    }
    private suspend fun run(client: AdbClient, command: String): String {
        val output = withTimeout(10_000) { client.shell("$command; status=\$?; printf '\\n__NL2SH_STATUS:%s\\n' \"\$status\"").text() }
        require(output.length <= 65_536)
        require(Regex("__NL2SH_STATUS:(\\d+)").findAll(output).lastOrNull()?.groupValues?.get(1) == "0")
        return output.substringBeforeLast("__NL2SH_STATUS:").trim()
    }
    private fun fetch(host: String, port: Int, path: String): JSONObject {
        val connection = URL(webUrl(host, port) + path).openConnection(Proxy.NO_PROXY) as HttpURLConnection
        connection.connectTimeout = 3000; connection.readTimeout = 5000; connection.instanceFollowRedirects = false
        try {
            require(connection.responseCode == 200)
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 65_536)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            return JSONObject(bytes.toString(Charsets.UTF_8))
        } finally { connection.disconnect() }
    }
    private fun digest(output: String): String? = output.substringBefore(' ').lowercase().takeIf { it.matches(Regex("[a-f0-9]{64}")) }
}

internal data class DoctorIdentity(val pid: Long, val version: String, val port: Int) {
    init {
        require(pid in 2..Int.MAX_VALUE.toLong() && port in 1..65535 && version.matches(Regex("[A-Za-z0-9._-]{1,64}")))
    }
}
private fun doctorIdentity(json: JSONObject): DoctorIdentity? = try {
    require(json.getInt("protocol") == 1)
    DoctorIdentity(json.getLong("pid"), json.getString("version"), json.getInt("port"))
} catch (_: Exception) { null }
internal fun validDoctorService(state: JSONObject): Boolean = state.optString("state") == "ready" && doctorIdentity(state) != null
internal fun doctorWebMatches(state: JSONObject, info: JSONObject): Boolean = validDoctorService(state) &&
    doctorIdentity(info)?.let { it == doctorIdentity(state) } == true
internal fun doctorProtocolCheck(state: String?): DoctorCheck = when (state) {
    "running" -> DoctorCheck("MCP / A2A", DoctorState.OK, "设备端已验证协议进程运行；远端鉴权与连通性未测试。")
    "stopped" -> DoctorCheck("MCP / A2A", DoctorState.ATTENTION, "协议未运行；在 Web 的 MCP/A2A 设置中配置，按提示显式启动或重启。", DoctorRepair.WEB)
    else -> DoctorCheck("MCP / A2A", DoctorState.UNKNOWN, "协议状态未验证；旧版本可能不提供连接信息。", DoctorRepair.WEB)
}
