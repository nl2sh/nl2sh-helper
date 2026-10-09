package ernest.nl2sh.helper

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class OfflineReleaseCacheTest {
    private fun fixture(test: (File, OfflineReleaseCache, (String) -> File) -> Unit) {
        val root = Files.createTempDirectory("offline-release").toFile()
        val policies = mutableMapOf<String, RuntimePolicy>()
        val cache = OfflineReleaseCache(root, { tag, _, bytes, signature ->
            require(bytes.contentEquals(tag.toByteArray()) && signature.contentEquals(byteArrayOf(1)))
            requireNotNull(policies[tag])
        }, { _, signature -> require(signature.contentEquals(byteArrayOf(2))) })
        fun add(version: String): File {
            val tag = "v$version"
            val binary = File(root, "$tag/x86_64/nl2sh")
            binary.parentFile!!.mkdirs()
            binary.writeBytes(ByteArray(64).also {
                it[0] = 0x7f; it[1] = 69; it[2] = 76; it[3] = 70
                it[4] = 2; it[5] = 1; it[18] = 62
            })
            val asset = RuntimeAsset(version, 1, "https://example.invalid/$tag", "https://example.invalid/$tag.sig", sha256(binary), 64)
            val policy = RuntimePolicy(version, asset, null)
            policies[tag] = policy
            cache.save(ReleaseBinary(tag, "x86_64", asset.url, asset.signatureUrl, asset),
                SignedManifest(tag.toByteArray(), byteArrayOf(1), policy), byteArrayOf(2))
            return binary
        }
        try { test(root, cache, ::add) } finally { root.deleteRecursively() }
    }

    @Test fun completeCacheUsesNumericVersionOrderingAndRequiresMatchingAbi() = fixture { _, cache, add ->
        add("1.9.0"); add("1.10.0")
        assertEquals("v1.10.0", cache.newest("x86_64")!!.release.tag)
        assertTrue(cache.newest("x86_64")!!.cacheNotice!!.contains("尚未确认"))
        assertNull(cache.newest("arm64-v8a"))
    }

    @Test fun corruptNewestBinaryFallsBackToVerifiedOlderEntry() = fixture { _, cache, add ->
        add("1.0.0"); add("2.0.0").appendText("tamper")
        assertEquals("v1.0.0", cache.newest("x86_64")!!.release.tag)
    }

    @Test fun missingMetadataAndTamperedSignaturesCannotInstall() = fixture { root, cache, add ->
        add("1.0.0")
        val metadata = File(root, "v1.0.0/x86_64/offline.metadata")
        val original = metadata.readBytes()
        metadata.writeBytes(original.copyOf().also { it[it.lastIndex] = 3 })
        assertNull(cache.newest("x86_64"))
        metadata.writeBytes(original.copyOf().also { it[8] = 0 })
        assertNull(cache.newest("x86_64"))
        metadata.delete()
        assertNull(cache.newest("x86_64"))
    }

    @Test fun cancellationPropagatesWithoutSelectingAnotherEntry() = fixture { _, cache, add ->
        add("1.0.0")
        assertThrows(CancellationException::class.java) {
            cache.newest("x86_64") { throw CancellationException("cancelled") }
        }
    }

    @Test fun versionComparisonPreventsSameVersionAndDowngrades() {
        assertEquals(0, compareReleaseVersions("1.2.3", "1.2.3"))
        assertTrue(compareReleaseVersions("1.9.0", "1.10.0") < 0)
        assertTrue(compareReleaseVersions("1.0.0-rc.9", "1.0.0-rc.10") < 0)
        assertTrue(compareReleaseVersions("1.0.0-rc.10", "1.0.0") < 0)
        assertTrue(compareReleaseVersions("1.0.0", "0.9.9") > 0)
        assertFalse(validReleaseVersion("1.0.0-01"))
        assertThrows(IllegalArgumentException::class.java) { compareReleaseVersions("unknown", "1.0.0") }
    }
}
