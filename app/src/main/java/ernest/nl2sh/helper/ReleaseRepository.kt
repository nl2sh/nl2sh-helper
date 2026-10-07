package ernest.nl2sh.helper

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class ReleaseBinary(val tag: String, val abi: String, val url: String, val shaUrl: String,
    val authenticated: RuntimeAsset? = null)
internal data class RuntimeAsset(val version: String, val protocol: Int, val url: String,
    val signatureUrl: String, val sha: String, val size: Long, val packageName: String? = null,
    val certificateSha: String? = null)
internal data class RuntimePolicy(val version: String, val native: RuntimeAsset, val bridge: RuntimeAsset?)

internal class ReleaseRepository(private val context: Context) {
    suspend fun latest(abi: String): ReleaseBinary = withContext(Dispatchers.IO) {
        require(abi in setOf("arm64-v8a", "armeabi-v7a", "x86_64"))
        val release = JSONObject(fetchText("https://api.github.com/repos/nl2sh/nl2sh/releases/latest", 512 * 1024))
        val tag = release.getString("tag_name")
        require(tag.matches(Regex("[A-Za-z0-9._-]{1,64}"))) { "Invalid release tag" }
        val policy = policy(tag, abi)
        ReleaseBinary(tag, abi, policy.native.url, policy.native.signatureUrl, policy.native)
    }

    suspend fun policy(tag: String, abi: String): RuntimePolicy = withContext(Dispatchers.IO) {
        require(tag.matches(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?"))) { "Invalid release tag" }
        val prefix = "https://github.com/nl2sh/nl2sh/releases/download/$tag/"
        val bytes = fetchBytes("${prefix}nl2sh-runtime.json", 1024 * 1024)
        val signature = fetchBytes("${prefix}nl2sh-runtime.json.sig", 16_384)
        trust().verify(bytes, signature)
        val manifest = JSONObject(bytes.toString(Charsets.UTF_8))
        require(manifest.getInt("schema") == 1 && manifest.getString("nl2sh") == tag.removePrefix("v")) {
            "Unsupported or mismatched runtime manifest"
        }
        val helper = manifest.getJSONObject("nl2sh_helper")
        require(helper.getInt("service_protocol") == 1 && versionAtLeast(BuildConfig.VERSION_NAME, helper.getString("min_version"))) {
            "此发布需要较新的 nl2sh助手，请先升级助手。"
        }
        val native = parseAsset(manifest.getJSONObject("binaries").getJSONObject(abi), prefix, "nl2sh-android-$abi", 1, 32_000_000)
        require(native.version == tag.removePrefix("v")) { "Native version mismatch" }
        val bridge = manifest.optJSONObject("android_bridge")?.let {
            parseAsset(it, prefix, "nl2sh-android-bridge.apk", 2, 64 * 1024 * 1024).also { asset ->
                require(asset.packageName == "com.nl2sh.bridge" && asset.certificateSha?.matches(Regex("[a-fA-F0-9]{64}")) == true) {
                    "Invalid Bridge package or certificate"
                }
            }
        }
        RuntimePolicy(manifest.getString("nl2sh"), native, bridge)
    }

    suspend fun cachedBinary(release: ReleaseBinary): Pair<File, String> = withContext(Dispatchers.IO) {
        val asset = requireNotNull(release.authenticated) { "Unsigned release is not installable" }
        val signature = fetchBytes(asset.signatureUrl, 16_384)
        val verifier = trust()
        val file = ReleaseFileCache(File(context.filesDir, "releases"))
            .getOrDownload(release, asset.sha) { temp ->
                download(asset.url, temp, 32_000_000)
                require(temp.length() == asset.size) { "Release size mismatch" }
                verifier.verify(temp.readBytes(), signature)
            }
        require(file.length() == asset.size) { "Cached release size mismatch" }
        verifier.verify(file.readBytes(), signature)
        file to asset.sha
    }

    private fun trust() = ReleaseTrust(context.assets.open("nl2sh-release.gpg").use { it.readBytes() })

    private suspend fun fetchText(url: String, limit: Int) = fetchBytes(url, limit).toString(Charsets.UTF_8)

    private suspend fun fetchBytes(url: String, limit: Int): ByteArray {
        var lastError: IOException? = null
        repeat(3) { attempt ->
            try {
                val connection = connect(url)
                try {
                    return connection.inputStream.use { it.readBytesLimited(limit) }
                } finally { connection.disconnect() }
            } catch (error: IOException) {
                lastError = error
                if (attempt < 2) delay((attempt + 1) * 750L)
            }
        }
        throw lastError ?: IOException("Release request failed")
    }

    private fun download(url: String, destination: File, limit: Int) {
        val connection = connect(url)
        try {
            connection.inputStream.use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        check(total <= limit) { "Download exceeds size limit" }
                        output.write(buffer, 0, count)
                    }
                    check(total > 0) { "Empty nl2sh download" }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun connect(url: String): HttpURLConnection {
        require(url.startsWith("https://")) { "HTTPS is required for release downloads" }
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", "nl2sh-helper")
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            check(connection.responseCode == 200) { "Release request failed: HTTP ${connection.responseCode}" }
            require(connection.url.protocol == "https") { "Insecure release redirect" }
            return connection
        } catch (error: Exception) {
            connection.disconnect()
            throw error
        }
    }
}

internal fun parseSha256(text: String): String {
    val match = Regex("^([0-9a-fA-F]{64})(?:\\s|$)").find(text.trim())
    return requireNotNull(match) { "Invalid SHA-256 file" }.groupValues[1].lowercase()
}

internal fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        check(out.size() + count <= limit) { "Response exceeds size limit" }
        out.write(buffer, 0, count)
    }
    return out.toByteArray()
}

internal fun parseAsset(json: JSONObject, prefix: String, name: String, protocol: Int, maximum: Long): RuntimeAsset {
    require(json.getInt("protocol") == protocol && json.getInt("min_android_api") == 26) { "Unsupported runtime protocol/API" }
    val url = json.getString("url")
    val signatureUrl = json.getString("signature_url")
    val sha = json.getString("sha256").lowercase()
    val size = json.getLong("size_bytes")
    require(url == "$prefix$name" && signatureUrl == "$url.sig" && sha.matches(Regex("[0-9a-f]{64}")) && size in 1..maximum) {
        "Invalid runtime asset identity or limits"
    }
    val version = json.getString("version")
    require(version.matches(Regex("[A-Za-z0-9.-]{1,64}"))) { "Invalid component version" }
    return RuntimeAsset(version, protocol, url, signatureUrl, sha, size,
        json.optString("package_name").takeIf { it.isNotBlank() },
        json.optString("certificate_sha256").takeIf { it.isNotBlank() })
}

internal fun versionAtLeast(current: String, minimum: String): Boolean {
    fun parts(value: String) = value.substringBefore('-').split('.').map {
        requireNotNull(it.toIntOrNull()?.takeIf { number -> number >= 0 }) { "Invalid helper version requirement" }
    }.also { require(it.size == 3) { "Invalid semantic version" } }
    val actual = parts(current)
    val required = parts(minimum)
    for (index in 0..2) {
        if (actual[index] != required[index]) return actual[index] > required[index]
    }
    return true
}
