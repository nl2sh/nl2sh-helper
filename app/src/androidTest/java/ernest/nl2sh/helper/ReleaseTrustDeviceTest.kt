package ernest.nl2sh.helper

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReleaseTrustDeviceTest {
    @Test fun verifiesOnApi26AndRejectsPolicyDrift() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun bytes(name: String) = instrumentation.context.assets.open(name).use { it.readBytes() }
        // Constructing the production verifier also checks the packaged trust-root pin.
        ReleaseTrust(instrumentation.targetContext.assets.open("nl2sh-release.gpg").use { it.readBytes() })
        val verifier = ReleaseTrust(bytes("public.gpg"), "E666C4F542FA28385C6B26A8BF18458C33113D6D")
        verifier.verify(bytes("message.txt"), bytes("message.sig"))
        assertTrue(runCatching { verifier.verify("changed".toByteArray(), bytes("message.sig")) }.isFailure)
        val prefix = "https://github.com/nl2sh/nl2sh/releases/download/v1.1.0/"
        val name = "nl2sh-android-x86_64"
        val json = JSONObject().put("version", "1.1.0").put("protocol", 1).put("min_android_api", 26)
            .put("url", "$prefix$name").put("signature_url", "$prefix$name.sig")
            .put("sha256", "a".repeat(64)).put("size_bytes", 100)
        parseAsset(json, prefix, name, 1, 32_000_000)
        json.put("url", "https://example.invalid/binary")
        assertTrue(runCatching { parseAsset(json, prefix, name, 1, 32_000_000) }.isFailure)
    }
}
