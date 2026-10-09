package ernest.nl2sh.helper

import android.content.Context
import android.content.pm.PackageManager
import android.util.Base64
import ernest.ascrcpy.adb.AdbClient
import ernest.ascrcpy.adb.AdbEndpoint
import ernest.ascrcpy.adb.DefaultAdbClient
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

internal enum class BridgeAction { INSPECT, INSTALL, OPEN_APP, ACCESSIBILITY_SETTINGS, KEYBOARD_SETTINGS }
internal data class PreparedBridge(val asset: RuntimeAsset, val file: File)
internal data class BridgeSnapshot(val nativeVersion: String?, val installed: Boolean, val version: String?,
    val protocol: Int?, val expectedVersion: String?, val expectedProtocol: Int?,
    val accessibilityEnabled: Boolean, val accessibilityRunning: Boolean?,
    val keyboardEnabled: Boolean, val keyboardSelected: Boolean, val keyboardRunning: Boolean?,
    val note: String?, val extensions: String = "JADX / Tailcat 状态未知") {
    val drift: Boolean get() = installed && expectedVersion != null &&
        (version != expectedVersion || protocol != expectedProtocol)
    fun describe(): String = buildString {
        append("nl2sh：${nativeVersion ?: "未知"}\nBridge：${if (installed) version ?: "已安装，版本未知" else "未安装"}")
        append("\n协议：${protocol ?: "未知"}；期望：${expectedProtocol ?: "未知"}")
        append("\n建议版本：${expectedVersion ?: "无已验证兼容性信息"}")
        append("\n无障碍：${if (accessibilityEnabled) "已启用" else "未启用"} / ${running(accessibilityRunning)}")
        append("\n键盘：${if (keyboardEnabled) "已启用" else "未启用"} / ${if (keyboardSelected) "已选中" else "未选中"} / ${running(keyboardRunning)}")
        append("\n$extensions")
        note?.let { append("\n$it") }
    }
    private fun running(value: Boolean?) = when (value) { true -> "运行中"; false -> "未运行"; null -> "运行状态未知" }
}

/** Explicit companion management never rewrites system Accessibility or keyboard settings. */
internal class DeviceBridgeManager(private val context: Context,
    private val progress: (TransferProgress) -> Unit = {},
    private val policyLoader: suspend (String, String) -> RuntimePolicy = { version, abi ->
        ReleaseRepository(context).policy("v$version", abi)
    },
    private val artifactLoader: suspend (RuntimeAsset) -> File = { asset -> ReleaseRepository(context, progress).cachedBridge(asset) }) {
    suspend fun perform(record: ConnectionRecord, action: BridgeAction, report: suspend (String) -> Unit): BridgeSnapshot = withContext(Dispatchers.IO) {
        val client = DefaultAdbClient.factory(context).create()
        try {
            report("连接目标，检查 Bridge…")
            withTimeout(45_000) {
                if (record.mode == ConnectionMode.TCP) client.connect(AdbEndpoint(record.host, record.port))
                else client.connectWireless(AdbEndpoint(record.host, record.port))
            }
            val version = run(client, "/data/local/tmp/nl2sh --version 2>/dev/null || true")
                .removePrefix("nl2sh ").takeIf { it.matches(Regex("[A-Za-z0-9.-]{1,64}")) }
            val abi = selectAbi(run(client, "getprop ro.product.cpu.abilist").ifBlank { run(client, "getprop ro.product.cpu.abi") })
            val extensions = extensionDetails(client, record.host)
            var expected: RuntimeAsset? = null
            var note: String? = null
            // Only explicit diagnostics/install query release metadata; opening Settings never does.
            if (action == BridgeAction.INSPECT || action == BridgeAction.INSTALL) {
                try {
                    expected = policyLoader(requireNotNull(version) { "目标原生版本未知" }, abi).bridge
                    if (expected == null) note = "已验证 Manifest 未推荐 Bridge。"
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException || action == BridgeAction.INSTALL) throw error
                    note = "兼容性信息未验证：${error.message?.take(160)}"
                }
            }
            when (action) {
                BridgeAction.INSTALL -> {
                    val asset = requireNotNull(expected) { "签名 Manifest 没有 Bridge，未安装。" }
                    report("验证 Bridge 签名、摘要、包名、版本与 APK 证书…")
                    val file = artifactLoader(asset)
                    validateBridgeApk(context, file, asset)
                    report("推送已验证 APK…")
                    run(client, "mkdir -p /data/local/tmp/nl2sh-helper")
                    val remote = "/data/local/tmp/nl2sh-helper/bridge.apk"
                    try {
                        withTimeout(120_000) {
                            val job = currentCoroutineContext()
                            ProgressInputStream(file.inputStream(), "推送 Bridge ${asset.version}（等待设备确认）", file.length(), progress, { job.ensureActive() }).use { input -> client.push(input, remote) }
                        }
                        report("推送完成；核对设备暂存 APK 摘要…")
                        check(run(client, "toybox sha256sum $remote").substringBefore(' ').equals(asset.sha, true)) { "设备暂存 APK 摘要不一致，未安装。" }
                        run(client, "chmod 644 $remote")
                        report("安装 Bridge ${asset.version}，等待系统安装结果…")
                        val result = run(client, "pm install -r $remote")
                        check(result.lineSequence().any { it.trim() == "Success" }) {
                            "Bridge 安装失败，已有应用保留。签名冲突需显式迁移，不会自动卸载：${result.take(300)}"
                        }
                        report("安装完成；核对 Bridge 实际版本与协议…")
                        val installed = inspect(client, version, expected, null)
                        check(installed.version == asset.version && installed.protocol == asset.protocol) { "Bridge 安装后版本/协议不匹配。" }
                        run(client, "am start -n com.nl2sh.bridge/.MainActivity")
                        note = "已安装并打开 Bridge。请在目标设备手动启用无障碍，按需选择键盘。"
                    } finally { runCatching { run(client, "rm -f $remote") } }
                }
                BridgeAction.OPEN_APP -> run(client, "am start -n com.nl2sh.bridge/.MainActivity")
                BridgeAction.ACCESSIBILITY_SETTINGS -> run(client, "am start -a android.settings.ACCESSIBILITY_SETTINGS")
                BridgeAction.KEYBOARD_SETTINGS -> run(client, "am start -a android.settings.INPUT_METHOD_SETTINGS")
                BridgeAction.INSPECT -> Unit
            }
            inspect(client, version, expected, note).copy(extensions = extensions)
        } finally { client.close() }
    }

    private suspend fun extensionDetails(client: AdbClient, host: String): String {
        return try {
            val status = JSONObject(run(client, "/data/local/tmp/nl2sh service status --json"))
            if (status.getInt("protocol") != 1 || status.getString("state") != "ready") return "原生服务未在线，JADX / Tailcat 状态未知"
            val port = status.getInt("port")
            require(port in 1..65535)
            val connection = java.net.URL(webUrl(host, port) + "api/info").openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection
            connection.connectTimeout = 3000; connection.readTimeout = 15000
            val info = try {
                check(connection.responseCode == 200)
                val bytes = connection.inputStream.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        check(out.size() + count <= 65_536)
                        out.write(buffer, 0, count)
                    }
                    out.toByteArray()
                }
                JSONObject(bytes.toString(Charsets.UTF_8))
            } finally { connection.disconnect() }
            require(info.getInt("protocol") == 1 && info.getLong("pid") == status.getLong("pid") &&
                info.getInt("port") == port && info.getString("version") == status.getString("version"))
            val capabilities = info.getJSONObject("capabilities")
            val jadx = capabilities.optJSONObject("jadx")
            val jadxText = if (jadx != null) "${jadx.optString("helper_version").take(128)} / 协议 ${jadx.optInt("protocol")}" else
                if (capabilities.optBoolean("jadx_provisionable")) "未安装，可在批准后获取" else "未就绪，需显式配置 helper"
            val tailcat = if (capabilities.isNull("tailcat")) "未就绪" else capabilities.optString("tailcat").take(256)
            "JADX：$jadxText\nTailcat：$tailcat\n更新归属：${info.optJSONObject("update_ownership")?.optString("owner") ?: "未知"}"
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            "原生扩展状态未验证，JADX / Tailcat 状态未知"
        }
    }

    private suspend fun inspect(client: AdbClient, native: String?, expected: RuntimeAsset?, note: String?): BridgeSnapshot {
        val installed = run(client, "pm path com.nl2sh.bridge 2>/dev/null || true").startsWith("package:")
        val enabledAccessibility = run(client, "settings get secure enabled_accessibility_services")
            .split(':').any { it == "com.nl2sh.bridge/.BridgeService" || it == "com.nl2sh.bridge/com.nl2sh.bridge.BridgeService" }
        val enabledIme = run(client, "settings get secure enabled_input_methods").split(':')
            .any { it.substringBefore(';').startsWith("com.nl2sh.bridge/") }
        val selectedIme = run(client, "settings get secure default_input_method").startsWith("com.nl2sh.bridge/")
        val caps = if (installed) runCatching {
            val reply = run(client, "content call --uri content://com.nl2sh.bridge.ops --method capabilities")
            val encoded = requireNotNull(Regex("data=([A-Za-z0-9_-]+)").find(reply)).groupValues[1]
            JSONObject(Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP).toString(Charsets.UTF_8))
        }.getOrNull() else null
        val installedVersion = caps?.optString("app_version")?.takeIf { it.isNotBlank() }
            ?: if (installed) Regex("versionName=([^\\s]+)").find(run(client, "dumpsys package com.nl2sh.bridge | grep versionName"))?.groupValues?.get(1) else null
        val services = caps?.optJSONObject("services")
        return BridgeSnapshot(native, installed, installedVersion, caps?.optInt("protocol_version")?.takeIf { it > 0 },
            expected?.version, expected?.protocol, enabledAccessibility,
            services?.optBoolean("accessibility"), enabledIme, selectedIme, services?.optBoolean("ime"), note)
    }

    private suspend fun run(client: AdbClient, command: String): String {
        val output = withTimeout(45_000) { client.shell("$command; status=\$?; printf '\\n__NL2SH_STATUS:%s\\n' \"\$status\"").text() }
        check(output.length <= 131_072) { "Bridge diagnostics exceeds limit" }
        val marker = Regex("__NL2SH_STATUS:(\\d+)").findAll(output).lastOrNull()
        check(marker?.groupValues?.get(1) == "0") { "设备操作失败：${output.take(500)}" }
        return output.substringBeforeLast("__NL2SH_STATUS:").trim()
    }
}

@Suppress("DEPRECATION")
internal fun validateBridgeApk(context: Context, file: File, asset: RuntimeAsset) {
    require(asset.packageName == "com.nl2sh.bridge" && asset.protocol == 2 && file.length() == asset.size && sha256(file) == asset.sha) { "Bridge identity, size or digest mismatch" }
    val info = requireNotNull(context.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNATURES)) { "Invalid signed APK" }
    require(info.packageName == asset.packageName && info.versionName == asset.version) { "APK package or version mismatch" }
    val certificates = requireNotNull(info.signatures).map { signature ->
        MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    require(certificates.size == 1 && certificates.single().equals(asset.certificateSha, true)) { "APK signing certificate mismatch" }
}
