package ernest.nl2sh.helper

import android.content.Context
import ernest.ascrcpy.adb.AdbClient
import ernest.ascrcpy.adb.AdbEndpoint
import ernest.ascrcpy.adb.DefaultAdbClient
import java.io.File
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

internal enum class DeviceAction { CONNECT, UPDATE, RESTART, STOP }
internal data class DeviceResult(val url: String?, val version: String?, val legacy: Boolean = false)
internal data class NativeServiceState(val state: String, val pid: Long?, val version: String?, val port: Int?) {
    init {
        require(state in setOf("ready", "starting", "stopped")) { "Unknown service state" }
        require(state == "stopped" || (pid != null && pid > 1 && !version.isNullOrBlank() && port in 1..65535)) {
            "Incomplete native service identity"
        }
    }
}

/** Connect only installs when absent. Binary changes and process replacement require explicit actions. */
internal data class PreparedRelease(val release: ReleaseBinary, val binary: File, val sha: String)

internal class DeviceInstaller(private val context: Context,
    private val releaseLoader: suspend (String) -> PreparedRelease = { abi ->
        val repository = ReleaseRepository(context)
        val release = repository.latest(abi)
        val (binary, sha) = repository.cachedBinary(release)
        PreparedRelease(release, binary, sha)
    }) {
    suspend fun perform(host: String, port: Int, wireless: Boolean, action: DeviceAction,
                        report: suspend (String) -> Unit): DeviceResult = withContext(Dispatchers.IO) {
        val client = DefaultAdbClient.factory(context).create()
        try {
            report("连接目标设备…首次连接请在目标设备批准 ADB 授权")
            withTimeout(45_000) {
                if (wireless) client.connectWireless(AdbEndpoint(host, port)) else client.connect(AdbEndpoint(host, port))
            }
            val installed = runChecked(client, "test -x $REMOTE_BINARY && printf installed || true") == "installed"
            when {
                action == DeviceAction.STOP -> {
                    if (installed) stop(client)
                    report("受管服务已停止；配置和会话保留。")
                    DeviceResult(null, null)
                }
                action == DeviceAction.RESTART -> {
                    check(installed) { "目标尚未安装 nl2sh，请先连接并安装。" }
                    stop(client)
                    startAndCheck(client, host, installedVersion(client), report)
                }
                action == DeviceAction.CONNECT && installed -> connectExisting(client, host, report)
                else -> update(client, host, installed, report)
            }
        } finally { client.close() }
    }

    private suspend fun connectExisting(client: AdbClient, host: String, report: suspend (String) -> Unit): DeviceResult {
        val version = installedVersion(client)
        if (supportsService(client)) {
            val status = native(client, "status --json")
            if (status.state == "ready") {
                check(webReady(host, status)) { "目标服务在设备内在线，但控制端无法访问实际 Web 端口 ${status.port}。" }
                report("复用正在运行的 ${status.version}，端口 ${status.port}；未检查更新或重启。")
                return DeviceResult(webUrl(host, requireNotNull(status.port)), status.version)
            }
            check(status.state == "stopped") { "受管服务正在运行但未通过健康检查，请查看日志或显式重启。" }
        } else if (legacyRunning(client)) {
            check(legacyWebReady(host)) { "旧受管服务未通过 Web 检查，请显式重启或更新。" }
            report("已连接旧版 $version；未升级或重启。显式更新后可使用原生生命周期协议。")
            return DeviceResult(webUrl(host, 9999), version, true)
        }
        report("程序已安装，启动已停止的服务…")
        return startAndCheck(client, host, version, report)
    }

    private suspend fun update(client: AdbClient, host: String, installed: Boolean,
                               report: suspend (String) -> Unit): DeviceResult {
        report("检测目标设备架构…")
        val abi = selectAbi(runChecked(client, "getprop ro.product.cpu.abilist")
            .ifBlank { runChecked(client, "getprop ro.product.cpu.abi") })
        report("目标架构：$abi；检查最新发布版本…")
        report("正在获取发布并验证缓存及 SHA-256…")
        val prepared = releaseLoader(abi)
        val release = prepared.release
        val expectedVersion = release.tag.removePrefix("v")
        val binary = prepared.binary
        val expectedSha = prepared.sha
        check(release.abi == abi) { "发布架构与目标不一致。" }
        validateRuntimeElf(binary, abi)
        check(sha256(binary) == expectedSha) { "发布缓存摘要无效。" }
        report("已校验发布：${release.tag}")
        val originalSha = if (installed) remoteSha(client, REMOTE_BINARY) else null
        check(!installed || originalSha != null) { "无法校验现有程序，未开始替换。" }
        val originalNative = installed && supportsService(client)
        val originalVersion = if (installed) installedVersion(client) else null
        if (originalSha == expectedSha) {
            check(originalVersion == expectedVersion) { "设备版本与已校验发布不一致。" }
            writeOwner(client, expectedVersion, expectedSha)
            report("已是目标版本，摘要一致；已确认 Helper 管理归属，不推送或重启。")
            return connectExisting(client, host, report)
        }
        runChecked(client, "mkdir -p $REMOTE_DIR")
        report("推送已校验程序；现有服务继续运行…")
        binary.inputStream().use { source ->
            withTimeout(120_000) { client.push(source, "$REMOTE_BINARY.download") }
        }
        runChecked(client, "chmod 755 $REMOTE_BINARY.download")
        check(remoteSha(client, "$REMOTE_BINARY.download") == expectedSha) { "暂存程序 SHA-256 校验失败，现有程序未替换。" }
        check(installedVersion(client, "$REMOTE_BINARY.download") == expectedVersion) { "暂存程序版本与发布不一致，现有程序未替换。" }
        val originalOwner = installed && runChecked(client, "test -f $REMOTE_BINARY.owner.json && printf owner || true") == "owner"
        if (installed) {
            // Preserve the verified old binary before stopping or replacing it.
            runChecked(client, "cp $REMOTE_BINARY $REMOTE_BINARY.previous.download && chmod 755 $REMOTE_BINARY.previous.download")
            check(remoteSha(client, "$REMOTE_BINARY.previous.download") == originalSha) { "备份摘要不一致，现有程序未替换。" }
            runChecked(client, "mv -f $REMOTE_BINARY.previous.download $REMOTE_BINARY.previous")
            if (originalOwner) runChecked(client, "cp $REMOTE_BINARY.owner.json $REMOTE_BINARY.owner.json.previous && chmod 600 $REMOTE_BINARY.owner.json.previous")
        }
        return guardedUpgrade(originalSha != null, promote = {
            if (installed) stop(client)
            runChecked(client, "mv -f $REMOTE_BINARY.download $REMOTE_BINARY")
            check(remoteSha(client, REMOTE_BINARY) == expectedSha) { "安装后摘要不一致。" }
            writeOwner(client, expectedVersion, expectedSha)
            val result = startAndCheck(client, host, expectedVersion, report)
            runChecked(client, "rm -f $LEGACY_REMOTE_BINARY")
            report("${release.tag} 已更新并通过版本及 Web 检查；旧程序保留用于回滚。")
            result
        }, restore = {
            if (remoteSha(client, REMOTE_BINARY) != originalSha) {
                // Require owned shutdown before restoring; never signal a PID by name.
                stop(client, if (originalNative) "$REMOTE_BINARY.previous" else REMOTE_BINARY)
                runChecked(client, "if [ -f $REMOTE_BINARY ]; then mv -f $REMOTE_BINARY $REMOTE_BINARY.failed; fi")
            }
            if (originalSha != null && originalVersion != null) {
                check(remoteSha(client, "$REMOTE_BINARY.previous") == originalSha) { "回滚备份摘要无效" }
                runChecked(client, "cp $REMOTE_BINARY.previous $REMOTE_BINARY.download && chmod 755 $REMOTE_BINARY.download && mv -f $REMOTE_BINARY.download $REMOTE_BINARY")
                check(remoteSha(client, REMOTE_BINARY) == originalSha)
                if (originalOwner) runChecked(client, "cp $REMOTE_BINARY.owner.json.previous $REMOTE_BINARY.owner.json.download && chmod 600 $REMOTE_BINARY.owner.json.download && mv -f $REMOTE_BINARY.owner.json.download $REMOTE_BINARY.owner.json")
                else runChecked(client, "rm -f $REMOTE_BINARY.owner.json")
                startAndCheck(client, host, originalVersion) { }
            }
        })
    }

    private suspend fun writeOwner(client: AdbClient, version: String, sha: String) {
        val marker = JSONObject().put("protocol", 1).put("owner", "nl2sh-helper")
            .put("version", version).put("sha256", sha).put("source", "https://github.com/nl2sh/nl2sh").toString()
        runChecked(client, "printf '%s' ${shellQuote(marker)} > $REMOTE_BINARY.owner.json.download && chmod 600 $REMOTE_BINARY.owner.json.download && mv -f $REMOTE_BINARY.owner.json.download $REMOTE_BINARY.owner.json")
    }

    private suspend fun startAndCheck(client: AdbClient, host: String, version: String,
                                     report: suspend (String) -> Unit): DeviceResult {
        report("启动并核对目标 Web 服务…")
        if (supportsService(client)) {
            val status = native(client, "start --json")
            check(status.state == "ready" && status.version == version) { "原生服务状态或实际运行版本不符合目标。" }
            check(webReady(host, status)) { "控制端无法验证目标 Web 端口 ${status.port} 的 PID/版本。" }
            return DeviceResult(webUrl(host, requireNotNull(status.port)), version)
        }
        // Compatibility for existing Releases until native lifecycle protocol is published.
        if (!legacyRunning(client)) {
            check(!legacyPortOpen(host)) { "旧版固定端口 9999 已被其他服务占用。" }
            runChecked(client, "mkdir -p $REMOTE_DIR && cd /data/local/tmp && { nohup ./nl2sh --web-only >$REMOTE_DIR/nl2sh-web.log 2>&1 </dev/null & echo \$! >$REMOTE_DIR/helper.pid; }")
        }
        repeat(20) {
            if (legacyRunning(client) && legacyWebReady(host)) return DeviceResult(webUrl(host, 9999), version, true)
            delay(500)
        }
        error("旧版启动未通过检查。请查看 $REMOTE_DIR/nl2sh-web.log；显式更新可迁移原生协议。")
    }

    private suspend fun native(client: AdbClient, command: String, path: String = REMOTE_BINARY): NativeServiceState {
        val text = runChecked(client, "$path service $command")
        check(text.length <= 16_384) { "Service response exceeds limit" }
        val json = JSONObject(text)
        check(json.getInt("protocol") == 1) { "Unsupported service protocol" }
        return NativeServiceState(json.getString("state"),
            if (json.isNull("pid")) null else json.getLong("pid"),
            if (json.isNull("version")) null else json.getString("version"),
            if (json.isNull("port")) null else json.getInt("port"))
    }

    private suspend fun supportsService(client: AdbClient, path: String = REMOTE_BINARY): Boolean =
        runChecked(client, "$path --help").lineSequence().any { Regex("^\\s+service\\s+").containsMatchIn(it) }

    private suspend fun installedVersion(client: AdbClient, path: String = REMOTE_BINARY): String {
        val text = runChecked(client, "$path --version")
        check(text.matches(Regex("nl2sh [A-Za-z0-9._-]{1,64}"))) { "目标程序版本输出无效：${text.take(120)}" }
        return text.removePrefix("nl2sh ")
    }

    private suspend fun remoteSha(client: AdbClient, path: String): String? {
        val value = runChecked(client, "toybox sha256sum $path 2>/dev/null || true").substringBefore(' ').lowercase()
        return value.takeIf { it.matches(Regex("[a-f0-9]{64}")) }
    }

    private suspend fun stop(client: AdbClient, controller: String = REMOTE_BINARY) {
        if (supportsService(client, controller)) {
            check(native(client, "stop --json", controller).state == "stopped") { "受管服务未停止。" }
            return
        }
        runChecked(client, legacyStopCommand())
        repeat(20) {
            if (!legacyRunning(client)) return
            delay(500)
        }
        error("旧受管进程未退出；未终止其他进程。")
    }

    private suspend fun legacyRunning(client: AdbClient): Boolean = runChecked(client,
        "if [ -f $REMOTE_DIR/helper.pid ]; then pid=\$(cat $REMOTE_DIR/helper.pid); " +
        "case \"\$pid\" in *[!0-9]*|'') ;; *) exe=\$(readlink /proc/\$pid/exe 2>/dev/null); " +
        "case \"\$exe\" in $REMOTE_BINARY|$LEGACY_REMOTE_BINARY) kill -0 \"\$pid\" 2>/dev/null && printf running;; esac;; esac; fi").contains("running")

    private fun legacyStopCommand(): String =
        "if [ -f $REMOTE_DIR/helper.pid ]; then pid=\$(cat $REMOTE_DIR/helper.pid); " +
        "case \"\$pid\" in *[!0-9]*|'') ;; *) exe=\$(readlink /proc/\$pid/exe 2>/dev/null); " +
        "case \"\$exe\" in $REMOTE_BINARY|$LEGACY_REMOTE_BINARY) kill \"\$pid\" 2>/dev/null || true;; esac;; esac; fi"

    private fun webReady(host: String, state: NativeServiceState): Boolean = runCatching {
        val health = fetchLocal(host, requireNotNull(state.port), "/healthz")
        val info = JSONObject(fetchLocal(host, state.port, "/api/info"))
        JSONObject(health).getString("status") == "ok" && info.getInt("protocol") == 1 &&
            info.getLong("pid") == state.pid && info.getString("version") == state.version && info.getInt("port") == state.port
    }.getOrDefault(false)

    private fun legacyPortOpen(host: String): Boolean = runCatching {
        java.net.Socket().use { it.connect(java.net.InetSocketAddress(host, 9999), 1_000) }; true
    }.getOrDefault(false)

    private fun legacyWebReady(host: String): Boolean = runCatching { fetchLocal(host, 9999, "/api/sessions"); true }.getOrDefault(false)

    private fun fetchLocal(host: String, port: Int, path: String): String {
        val connection = (URL(webUrl(host, port).removeSuffix("/") + path).openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
            connectTimeout = 3_000; readTimeout = 15_000
        }
        try {
            check(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
            return connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(out.size() + count <= 65_536) { "Web response exceeds limit" }
                    out.write(buffer, 0, count)
                }
                out.toString("UTF-8")
            }
        } finally { connection.disconnect() }
    }

    private suspend fun runChecked(client: AdbClient, command: String): String {
        val marked = "$command; status=\$?; printf '\\n__NL2SH_STATUS:%s\\n' \"\$status\""
        val output = withTimeout(45_000) { client.shell(marked).text() }
        val marker = Regex("__NL2SH_STATUS:(\\d+)").findAll(output).lastOrNull()
        check(marker?.groupValues?.get(1)?.toIntOrNull() == 0) { "Device command failed: ${output.take(500)}" }
        return output.substringBeforeLast("__NL2SH_STATUS:").trim()
    }

    companion object {
        private const val REMOTE_DIR = "/data/local/tmp/nl2sh-helper"
        private const val REMOTE_BINARY = "/data/local/tmp/nl2sh"
        private const val LEGACY_REMOTE_BINARY = "$REMOTE_DIR/nl2sh"
    }
}

internal fun shellQuote(text: String): String = "'" + text.replace("'", "'\\''") + "'"
internal fun webUrl(host: String, port: Int): String = "http://${if (host.contains(':')) "[$host]" else host}:$port/"

internal data class StartupAttemptDiagnostic(val attempt: Int, val process: String, val log: String)
internal fun formatStartupFailure(failures: List<StartupAttemptDiagnostic>): String = buildString {
    append("后台 Web 服务未通过进程与健康检查。")
    failures.forEach { append("\n第${it.attempt}次：${it.process}\n日志：${it.log.ifBlank { "（空）" }}") }
}.take(4_000)

internal fun selectAbi(abiList: String): String {
    val supported = abiList.trim().split(',', '\n', '\r').map(String::trim)
    return when {
        "x86_64" in supported -> "x86_64"
        "arm64-v8a" in supported -> "arm64-v8a"
        "armeabi-v7a" in supported -> "armeabi-v7a"
        else -> error("目标设备不支持 nl2sh 的 ARM64/ARMv7/x86_64 发布包：$abiList")
    }
}

internal fun validateRuntimeElf(file: File, abi: String) {
    require(file.length() in 64..32_000_000) { "Invalid runtime size" }
    val header = ByteArray(20)
    java.io.DataInputStream(file.inputStream()).use { it.readFully(header) }
    val machine = (header[18].toInt() and 255) or ((header[19].toInt() and 255) shl 8)
    val expected = when (abi) { "arm64-v8a" -> 183; "armeabi-v7a" -> 40; "x86_64" -> 62; else -> error("Unsupported ABI") }
    val elfClass = if (abi == "armeabi-v7a") 1 else 2
    require(header.take(4) == listOf(0x7f.toByte(), 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()) &&
        header[4].toInt() == elfClass && header[5].toInt() == 1 && machine == expected) { "Runtime ELF does not match target ABI" }
}
