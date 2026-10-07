package ernest.nl2sh.helper

import java.io.ByteArrayInputStream
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.PGPObjectFactory
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureList
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider

/** Verifies exact release bytes without trusting a key supplied by the download server. */
internal class ReleaseTrust(publicKey: ByteArray, fingerprint: String = FINGERPRINT) {
    private val key = PGPPublicKeyRingCollection(ByteArrayInputStream(publicKey), JcaKeyFingerprintCalculator())
        .keyRings.asSequence().map { it.publicKey }.single {
            it.fingerprint.joinToString("") { byte -> "%02X".format(byte.toInt() and 255) } == fingerprint
        }

    fun verify(data: ByteArray, signature: ByteArray) {
        require(signature.size in 1..16_384) { "Invalid release signature size" }
        val objects = PGPObjectFactory(signature, JcaKeyFingerprintCalculator())
        val signatures = objects.nextObject() as? PGPSignatureList
            ?: error("Invalid detached release signature")
        require(signatures.size() == 1 && objects.nextObject() == null) { "Expected one release signature" }
        val signed = signatures[0]
        require(signed.signatureType == PGPSignature.BINARY_DOCUMENT && signed.hashAlgorithm == HashAlgorithmTags.SHA256) {
            "Release requires a binary SHA-256 signature"
        }
        // Use a private provider instance; do not replace Android's provider used by ADB.
        signed.init(JcaPGPContentVerifierBuilderProvider().setProvider(BouncyCastleProvider()), key)
        signed.update(data)
        check(signed.verify()) { "Release signature verification failed" }
    }

    companion object {
        const val FINGERPRINT = "5230D3A7CCBEED4616D39C51FC6AD1BC63F7D4D8"
    }
}
