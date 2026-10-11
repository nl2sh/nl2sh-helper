package ernest.nl2sh.helper

import org.junit.Assert.*
import org.junit.Test

class InstallLlmConfigTest {
    @Test fun defaultsMatchInstaller() {
        mapOf("openrouter" to "openrouter/free", "openai" to "gpt-4o-mini", "deepseek" to "deepseek-flash",
            "moonshot" to "kimi-k2-turbo-preview", "kimi" to "kimi-k2-turbo-preview", "siliconflow" to "Qwen/Qwen3-8B", "ollama" to "qwen3")
            .forEach { (provider, model) -> assertTrue(InstallLlmConfig(provider).toml().contains("model = \"$model\"")) }
    }
    @Test fun escapesCredentialsAndKeepsThemOutOfToString() {
        val config = InstallLlmConfig("custom", "model", "a\"b\\c", "https://example.com/v1")
        assertTrue(config.toml().contains("api_key = \"a\\\"b\\\\c\""))
        assertFalse(config.toString().contains(config.apiKey))
    }
    @Test fun rejectsInvalidInputs() {
        listOf(InstallLlmConfig("unknown"), InstallLlmConfig("custom"), InstallLlmConfig(apiKey = "secret\nvalue"), InstallLlmConfig(model = "a\u0000b"))
            .forEach { assertThrows(IllegalArgumentException::class.java) { it.toml() } }
    }
}
