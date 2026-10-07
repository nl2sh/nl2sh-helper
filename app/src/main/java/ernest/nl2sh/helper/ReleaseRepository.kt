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

internal data class ReleaseBinary(val tag: String, val abi: String, val url: String, val shaUrl: String)

internal class ReleaseRepository(private val context: Context) {
    suspend fun latest(abi: String): ReleaseBinary = withContext(Dispatchers.IO) {
        require(abi in setOf("arm64-v8a", "armeabi-v7a", "x86_64"))
        val release = JSONObject(fetchText("https://api.github.com/repos/nl2sh/nl2sh/releases/latest", 512 * 1024))
        val tag = release.getString("tag_name")
        require(tag.matches(Regex("[A-Za-z0-9._-]{1,64}"))) { "Invalid release tag" }
        val name = "nl2sh-android-$abi"
        val assets = release.getJSONArray("assets")
        var binaryUrl: String? = null
        var shaUrl: String? = null
        for (index in 0 until assets.length()) {
            val asset = assets.getJSONObject(index)
            when (asset.getString("name")) {
                name -> {
                    require(asset.getLong("size") in 1..32_000_000) { "Unexpected binary size" }
                    binaryUrl = asset.getString("browser_download_url")
                }
                "$name.sha256" -> shaUrl = asset.getString("browser_download_url")
            }
        }
        val prefix = "https://github.com/nl2sh/nl2sh/releases/download/$tag/"
        val binary = requireNotNull(binaryUrl) { "No $abi binary in latest release" }
        val checksum = requireNotNull(shaUrl) { "No checksum for $abi binary" }
        require(binary == "$prefix$name" && checksum == "$prefix$name.sha256") {
            "Unexpected release asset URL"
        }
        ReleaseBinary(tag, abi, binary, checksum)
    }

    suspend fun cachedBinary(release: ReleaseBinary): Pair<File, String> = withContext(Dispatchers.IO) {
        val shaText = fetchText(release.shaUrl, 1024)
        val expected = parseSha256(shaText)
        val file = ReleaseFileCache(File(context.filesDir, "releases"))
            .getOrDownload(release, expected) { temp -> download(release.url, temp, 32_000_000) }
        file to expected
    }

    private suspend fun fetchText(url: String, limit: Int): String {
        var lastError: IOException? = null
        repeat(3) { attempt ->
            try {
                val connection = connect(url)
                try {
                    connection.inputStream.use { input ->
                        return input.readBytesLimited(limit).toString(Charsets.UTF_8)
                    }
                } finally {
                    connection.disconnect()
                }
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
