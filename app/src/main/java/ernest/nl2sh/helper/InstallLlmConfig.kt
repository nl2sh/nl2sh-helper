package ernest.nl2sh.helper

/** Installer defaults match install-android.sh; credentials are never included in toString. */
internal class InstallLlmConfig(val provider: String = "openrouter", val model: String = "",
    val apiKey: String = "", val endpoint: String = "") {
    fun toml(): String {
        val defaults = when (provider.trim().lowercase()) {
            "openrouter" -> "https://openrouter.ai/api/v1" to "openrouter/free"
            "openai" -> "https://api.openai.com/v1" to "gpt-4o-mini"
            "deepseek" -> "https://api.deepseek.com" to "deepseek-flash"
            "moonshot", "kimi" -> "https://api.moonshot.cn/v1" to "kimi-k2-turbo-preview"
            "siliconflow" -> "https://api.siliconflow.cn/v1" to "Qwen/Qwen3-8B"
            "ollama" -> "http://127.0.0.1:11434/v1" to "qwen3"
            "custom" -> { require(endpoint.isNotBlank()) { "custom provider 需要填写 endpoint。" }; endpoint.trim() to "custom-model" }
            else -> throw IllegalArgumentException("不支持的 provider，请选择列表中的名称。")
        }
        fun quote(value: String): String {
            require(value.none { it == '\n' || it == '\r' || it.code < 32 || it.code == 127 }) { "模型安装参数不能包含控制字符。" }
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
        return "model = ${quote(model.trim().ifEmpty { defaults.second })}\n" +
            "endpoint = ${quote(endpoint.trim().ifEmpty { defaults.first })}\n" +
            "api_type = \"auto\"\napi_key = ${quote(apiKey)}\n"
    }
}
