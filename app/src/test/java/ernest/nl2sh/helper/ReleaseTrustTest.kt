package ernest.nl2sh.helper

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class ReleaseTrustTest {
    private fun bytes(name: String) = requireNotNull(javaClass.getResourceAsStream("/signatures/$name")).use { it.readBytes() }

    @Test fun acceptsExactBytesAndRejectsTamperingAndDuplicateSignatures() {
        val verifier = ReleaseTrust(bytes("public.gpg"), "E666C4F542FA28385C6B26A8BF18458C33113D6D")
        val message = bytes("message.txt")
        val signature = bytes("message.sig")
        verifier.verify(message, signature)
        assertTrue(runCatching { verifier.verify("modified".toByteArray(), signature) }.isFailure)
        assertTrue(runCatching { verifier.verify(message, signature + signature) }.isFailure)
        assertTrue(runCatching { verifier.verify(message, ByteArray(0)) }.isFailure)
        assertTrue(runCatching { ReleaseTrust(bytes("public.gpg")) }.isFailure)
    }

    @Test fun requiresCompatibleInstallerVersion() {
        assertTrue(versionAtLeast("0.2.0", "0.2.0"))
        assertTrue(versionAtLeast("0.10.0", "0.2.0"))
        assertFalse(versionAtLeast("0.1.9", "0.2.0"))
        assertTrue(runCatching { versionAtLeast("0.2.0", "bad") }.isFailure)
    }
}
