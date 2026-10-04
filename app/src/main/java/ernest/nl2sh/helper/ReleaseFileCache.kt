package ernest.nl2sh.helper

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Stores a verified binary by release and ABI, downloading only when absent or corrupt. */
internal class ReleaseFileCache(private val root: File) {
    fun getOrDownload(release: ReleaseBinary, expectedSha: String, download: (File) -> Unit): File {
        require(release.tag.matches(Regex("[A-Za-z0-9._-]{1,64}")))
        require(release.abi == "arm64-v8a" || release.abi == "armeabi-v7a")
        val destination = File(root, "${release.tag}/${release.abi}/nl2sh")
        if (destination.isFile && sha256(destination) == expectedSha) return destination
        val directory = requireNotNull(destination.parentFile)
        check(directory.mkdirs() || directory.isDirectory) { "Cannot create release cache" }
        val temp = File.createTempFile("nl2sh-", ".download", directory)
        try {
            download(temp)
            check(sha256(temp) == expectedSha) { "Downloaded nl2sh SHA-256 mismatch" }
            try {
                Files.move(temp.toPath(), destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
        return destination
    }
}
