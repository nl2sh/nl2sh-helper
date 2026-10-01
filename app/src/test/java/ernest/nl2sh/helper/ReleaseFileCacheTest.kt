package ernest.nl2sh.helper

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReleaseFileCacheTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val release = ReleaseBinary("v1.0.4", "armeabi-v7a", "https://example.invalid/nl2sh", "https://example.invalid/sha")

    @Test fun validCacheSkipsSecondBinaryDownloadAndRepairsCorruption() {
        val bytes = "verified nl2sh binary".toByteArray()
        val source = temporaryFolder.newFile("source").apply { writeBytes(bytes) }
        val expected = sha256(source)
        val cache = ReleaseFileCache(File(temporaryFolder.root, "cache"))
        var downloads = 0
        val download: (File) -> Unit = { destination ->
            downloads += 1
            destination.writeBytes(bytes)
        }

        val first = cache.getOrDownload(release, expected, download)
        assertEquals(1, downloads)
        assertEquals(expected, sha256(first))
        val second = cache.getOrDownload(release, expected, download)
        assertEquals(first, second)
        assertEquals(1, downloads)

        first.writeText("corrupt")
        val repaired = cache.getOrDownload(release, expected, download)
        assertEquals(2, downloads)
        assertEquals(expected, sha256(repaired))
    }

    @Test fun checksumMismatchDoesNotPublishAFile() {
        val cacheRoot = File(temporaryFolder.root, "cache")
        val cache = ReleaseFileCache(cacheRoot)
        val expected = "0".repeat(64)
        assertThrows(IllegalStateException::class.java) {
            cache.getOrDownload(release, expected) { it.writeText("wrong") }
        }
        assertFalse(File(cacheRoot, "v1.0.4/armeabi-v7a/nl2sh").exists())
        val directory = File(cacheRoot, "v1.0.4/armeabi-v7a")
        assertTrue(directory.listFiles().isNullOrEmpty())
    }
}
