package ernest.nl2sh.helper

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException

internal data class SignedManifest(val bytes: ByteArray, val signature: ByteArray, val policy: RuntimePolicy)

/** Stores a complete authentication record only after the executable has been verified. */
internal class OfflineReleaseCache(private val root: File,
    private val authenticate: (String, String, ByteArray, ByteArray) -> RuntimePolicy,
    private val verifyBinary: (ByteArray, ByteArray) -> Unit) {
    fun save(release: ReleaseBinary, manifest: SignedManifest, signature: ByteArray) {
        require(release.tag.startsWith("v") && validReleaseVersion(release.tag.removePrefix("v")))
        require(release.abi in setOf("arm64-v8a", "armeabi-v7a", "x86_64"))
        require(manifest.policy.native == release.authenticated && manifest.policy.version == release.tag.removePrefix("v"))
        val directory = File(root, "${release.tag}/${release.abi}")
        check(directory.mkdirs() || directory.isDirectory)
        val temp = File.createTempFile("offline-", ".metadata", directory)
        try {
            DataOutputStream(temp.outputStream()).use { output ->
                output.writeInt(1)
                for ((bytes, maximum) in listOf(manifest.bytes to 1_048_576, manifest.signature to 16_384, signature to 16_384)) {
                    require(bytes.size in 1..maximum)
                    output.writeInt(bytes.size); output.write(bytes)
                }
            }
            val target = File(directory, "offline.metadata")
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { temp.delete() }
    }

    fun newest(abi: String, checkpoint: () -> Unit = {}): PreparedRelease? {
        require(abi in setOf("arm64-v8a", "armeabi-v7a", "x86_64"))
        val versions = root.listFiles().orEmpty().filter { it.isDirectory && validReleaseVersion(it.name.removePrefix("v")) && it.name.startsWith("v") }
            .sortedWith { a, b -> compareReleaseVersions(b.name.removePrefix("v"), a.name.removePrefix("v")) }
        for (version in versions) {
            checkpoint()
            try {
                val directory = File(version, abi)
                val metadata = File(directory, "offline.metadata")
                require(metadata.isFile && metadata.length() in 1..1_081_360) { "Incomplete offline metadata" }
                val (bytes, manifestSignature, binarySignature) = DataInputStream(metadata.inputStream()).use { input ->
                    require(input.readInt() == 1) { "Unsupported offline metadata" }
                    fun read(maximum: Int): ByteArray {
                        val size = input.readInt()
                        require(size in 1..maximum) { "Offline metadata exceeds limit" }
                        return ByteArray(size).also(input::readFully)
                    }
                    val contents = Triple(read(1_048_576), read(16_384), read(16_384))
                    require(input.read() == -1) { "Unexpected offline metadata" }
                    contents
                }
                val policy = authenticate(version.name, abi, bytes, manifestSignature)
                val asset = policy.native
                require(policy.version == version.name.removePrefix("v") && asset.version == policy.version)
                val binary = File(directory, "nl2sh")
                require(asset.size in 64..32_000_000 && binary.isFile && binary.length() == asset.size) { "Incomplete cached executable" }
                require(sha256(binary) == asset.sha) { "Cached SHA-256 mismatch" }
                verifyBinary(binary.readBytes(), binarySignature)
                validateRuntimeElf(binary, abi)
                checkpoint()
                return PreparedRelease(ReleaseBinary(version.name, abi, asset.url, asset.signatureUrl, asset), binary, asset.sha,
                    cacheNotice = "更新检查失败；使用已验证的缓存版本 ${version.name}，尚未确认是否为最新版本。")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // An incomplete or corrupt newer entry must not hide a valid older cache.
            }
        }
        return null
    }
}

private val releaseVersion = Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*))?")
internal fun validReleaseVersion(version: String): Boolean {
    if (version.length > 64) return false
    val groups = releaseVersion.matchEntire(version)?.groupValues ?: return false
    return groups.drop(1).take(3).all { it.toLongOrNull() != null } &&
        groups[4].split('.').none { it.length > 1 && it.all(Char::isDigit) && it.startsWith("0") }
}

/** Compare SemVer precedence so an offline cache cannot replace the same or a newer version. */
internal fun compareReleaseVersions(first: String, second: String): Int {
    require(validReleaseVersion(first) && validReleaseVersion(second)) { "无法比较程序版本，未使用缓存替换现有程序。" }
    val a = requireNotNull(releaseVersion.matchEntire(first)).groupValues
    val b = requireNotNull(releaseVersion.matchEntire(second)).groupValues
    for (index in 1..3) a[index].toLong().compareTo(b[index].toLong()).takeIf { it != 0 }?.let { return it }
    if (a[4].isEmpty() || b[4].isEmpty()) return when { a[4] == b[4] -> 0; a[4].isEmpty() -> 1; else -> -1 }
    val preA = a[4].split('.'); val preB = b[4].split('.')
    for (index in 0 until minOf(preA.size, preB.size)) {
        val x = preA[index]; val y = preB[index]
        val nx = x.all(Char::isDigit); val ny = y.all(Char::isDigit)
        val result = when {
            nx && ny -> x.length.compareTo(y.length).takeIf { it != 0 } ?: x.compareTo(y)
            nx != ny -> if (nx) -1 else 1
            else -> x.compareTo(y)
        }
        if (result != 0) return result
    }
    return preA.size.compareTo(preB.size)
}
