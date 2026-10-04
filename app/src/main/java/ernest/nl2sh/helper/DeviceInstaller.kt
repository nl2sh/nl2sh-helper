package ernest.nl2sh.helper

import android.content.Context
import ernest.ascrcpy.adb.AdbClient
import ernest.ascrcpy.adb.AdbEndpoint
import ernest.ascrcpy.adb.DefaultAdbClient
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

internal class DeviceInstaller(private val context: Context) {
    suspend fun install(host: String, port: Int, wireless: Boolean, report: suspend (String) -> Unit): String = withContext(Dispatchers.IO) {
        val client = DefaultAdbClient.factory(context).create()
        try {
            report("连接目标设备…首次连接请在目标设备批准 ADB 授权")
            withTimeout(45_000) {
                if (wireless) client.connectWireless(AdbEndpoint(host, port))
                else client.connect(AdbEndpoint(host, port))
            }
            check(!portOpen(host) || isRunning(client)) { "目标设备 9999 端口已被其他服务占用" }
            report("检测目标设备架构…")
            val abiOutput = runChecked(client, "getprop ro.product.cpu.abilist")
                .ifBlank { runChecked(client, "getprop ro.product.cpu.abi") }
            val abi = selectAbi(abiOutput)
            report("目标架构：$abi；检查最新发布版本…")
            val repository = ReleaseRepository(context)
            val release = repository.latest(abi)
            report("最新版本：${release.tag}；检查缓存及 SHA-256…")
            val (binary, expectedSha) = repository.cachedBinary(release)
            deploy(client, binary, expectedSha, report)
            report("核对目标程序版本…")
            val installedVersion = runChecked(client, "$REMOTE_BINARY --version")
            check(installedVersion == "nl2sh ${release.tag.removePrefix("v")}") {
                "设备上的程序版本与发布标签不一致：$installedVersion"
            }
            report("启动目标设备 Web 服务…")
            stopManagedAndWait(client, host)
            val failures = mutableListOf<StartupAttemptDiagnostic>()
            var ready = false
            for (attempt in 1..2) {
                runChecked(client, startCommand())
                for (check in 1..20) {
                    delay(500)
                    if (isRunning(client) && webReady(host)) {
                        ready = true
                        break
                    }
                }
                if (ready) break
                failures += startupDiagnostic(client, attempt)
                if (attempt == 1) {
                    report("首次启动未通过检查，正在停止残留进程并重试…")
                    stopManagedAndWait(client, host)
                    report("重新启动目标设备 Web 服务…")
                }
            }
            check(ready) { formatStartupFailure(failures) }
            report("${release.tag} 已在目标设备后台运行。")
            "http://$host:9999/"
        } finally {
            client.close()
        }
    }

    private suspend fun deploy(client: AdbClient, binary: File, expectedSha: String, report: suspend (String) -> Unit) {
        runChecked(client, "mkdir -p $REMOTE_DIR")
        val current = runChecked(client, "toybox sha256sum $REMOTE_BINARY 2>/dev/null || true")
            .trim().substringBefore(' ').lowercase()
        if (current == expectedSha) {
            runChecked(client, "rm -f $LEGACY_REMOTE_BINARY")
            report("设备上的程序摘要一致，无需重复推送。")
            return
        }
        report("推送已校验程序到目标设备…")
        binary.inputStream().use { source ->
            withTimeout(120_000) { client.push(source, "$REMOTE_BINARY.download") }
        }
        report("校验目标文件…")
        runChecked(client, "chmod 755 $REMOTE_BINARY.download && mv -f $REMOTE_BINARY.download $REMOTE_BINARY")
        val uploaded = runChecked(client, "toybox sha256sum $REMOTE_BINARY").trim().substringBefore(' ').lowercase()
        check(uploaded == expectedSha) { "设备上的程序 SHA-256 校验失败" }
        runChecked(client, "rm -f $LEGACY_REMOTE_BINARY")
    }

    private suspend fun portOpen(host: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket -> socket.connect(InetSocketAddress(host, 9999), 1_000) }
            true
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun webReady(host: String): Boolean = withContext(Dispatchers.IO) {
        val connection = (URL("http://$host:9999/api/sessions").openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
            connectTimeout = 1_000
            readTimeout = 1_000
        }
        try {
            connection.responseCode == 200
        } catch (_: Exception) {
            false
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun isRunning(client: AdbClient): Boolean {
        val output = runChecked(client, "if [ -f $REMOTE_DIR/helper.pid ]; then pid=\$(cat $REMOTE_DIR/helper.pid); " +
            "case \"\$pid\" in *[!0-9]*|'') ;; *) " +
            "exe=\$(readlink /proc/\$pid/exe 2>/dev/null); " +
            "case \"\$exe\" in $REMOTE_BINARY|$LEGACY_REMOTE_BINARY) kill -0 \"\$pid\" 2>/dev/null && printf running;; esac;; esac; fi")
        return output.contains("running")
    }

    private suspend fun stopManagedAndWait(client: AdbClient, host: String) {
        runChecked(client, stopManagedCommand())
        for (attempt in 1..10) {
            if (!portOpen(host)) return
            delay(500)
        }
        check(!portOpen(host)) { "目标设备 9999 端口已被其他服务占用" }
    }

    private suspend fun startupDiagnostic(client: AdbClient, attempt: Int): StartupAttemptDiagnostic {
        val process = runCatching {
            runChecked(client, "pid=\$(cat $REMOTE_DIR/helper.pid 2>/dev/null || true); " +
                "printf 'pid=%s\\n' \"\$pid\"; " +
                "case \"\$pid\" in *[!0-9]*|'') printf 'alive=no\\nexe=unknown\\n';; *) " +
                "if kill -0 \"\$pid\" 2>/dev/null; then alive=yes; else alive=no; fi; " +
                "printf 'alive=%s\\nexe=%s\\n' \"\$alive\" \"\$(readlink /proc/\$pid/exe 2>/dev/null || true)\";; esac")
        }.getOrElse { "process diagnostic failed: ${it.message ?: it.javaClass.simpleName}" }
        val log = runCatching {
            runChecked(client, "tail -n 40 $REMOTE_DIR/nl2sh-web.log 2>/dev/null || true")
        }.getOrElse { "log diagnostic failed: ${it.message ?: it.javaClass.simpleName}" }
        return StartupAttemptDiagnostic(attempt, process, log)
    }

    private suspend fun runChecked(client: AdbClient, command: String): String {
        val marked = "$command; status=\$?; printf '\\n__NL2SH_STATUS:%s\\n' \"\$status\""
        val output = withTimeout(45_000) { client.shell(marked).text() }
        val marker = Regex("__NL2SH_STATUS:(\\d+)").findAll(output).lastOrNull()
        val status = marker?.groupValues?.get(1)?.toIntOrNull()
        check(status == 0) { "Device command failed: ${output.take(500)}" }
        return output.substringBeforeLast("__NL2SH_STATUS:").trim()
    }

    private fun stopManagedCommand(): String =
        "if [ -f $REMOTE_DIR/helper.pid ]; then pid=\$(cat $REMOTE_DIR/helper.pid); " +
            "case \"\$pid\" in *[!0-9]*|'') ;; *) " +
            "exe=\$(readlink /proc/\$pid/exe 2>/dev/null); " +
            "case \"\$exe\" in $REMOTE_BINARY|$LEGACY_REMOTE_BINARY) kill \"\$pid\" 2>/dev/null || true;; esac;; esac; fi"

    private fun startCommand(): String =
        "cd /data/local/tmp && { nohup ./nl2sh --web-only >$REMOTE_DIR/nl2sh-web.log 2>&1 </dev/null & echo \$! > $REMOTE_DIR/helper.pid; }"

    companion object {
        private const val REMOTE_DIR = "/data/local/tmp/nl2sh-helper"
        private const val REMOTE_BINARY = "/data/local/tmp/nl2sh"
        private const val LEGACY_REMOTE_BINARY = "$REMOTE_DIR/nl2sh"
    }
}

internal data class StartupAttemptDiagnostic(val attempt: Int, val process: String, val log: String)

internal fun formatStartupFailure(failures: List<StartupAttemptDiagnostic>): String = buildString {
    append("后台 Web 服务两次启动均未通过进程与 9999 健康检查。")
    failures.forEach { failure ->
        append("\n第${failure.attempt}次：")
        append(failure.process.ifBlank { "无进程状态" })
        append("\n日志：")
        append(failure.log.ifBlank { "（空）" })
    }
}.take(4_000)

internal fun selectAbi(abiList: String): String {
    val supported = abiList.trim().split(',', '\n', '\r').map(String::trim)
    return when {
        "arm64-v8a" in supported -> "arm64-v8a"
        "armeabi-v7a" in supported -> "armeabi-v7a"
        else -> error("目标设备不支持 nl2sh 的 ARM64/ARMv7 发布包：$abiList")
    }
}
