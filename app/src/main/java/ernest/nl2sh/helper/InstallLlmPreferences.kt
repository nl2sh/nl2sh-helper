package ernest.nl2sh.helper

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Private, backup-excluded installer preferences; API keys are encrypted by Android Keystore. */
internal class InstallLlmPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("install_llm", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("nl2sh-helper-install-llm", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("nl2sh-helper-install-llm", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun load(): InstallLlmConfig {
        val secret = prefs.getString("secret", null)?.let { encoded ->
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        }.orEmpty()
        return InstallLlmConfig(prefs.getString("provider", "openrouter").orEmpty(),
            prefs.getString("model", "").orEmpty(), secret, prefs.getString("endpoint", "").orEmpty())
    }
    fun save(config: InstallLlmConfig) {
        config.toml() // Validate before storing anything.
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val secret = Base64.encodeToString(cipher.iv + cipher.doFinal(config.apiKey.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        check(prefs.edit().putString("provider", config.provider).putString("model", config.model)
            .putString("endpoint", config.endpoint).putString("secret", secret).commit()) { "无法保存模型安装参数。" }
    }
    fun clear() { check(prefs.edit().clear().commit()) { "无法清除模型安装参数。" } }
}
