package ernest.nl2sh.helper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal enum class ConnectionMode { LOCAL, TCP, WIRELESS_CODE, WIRELESS_QR }

/** Pairing codes and QR passwords are deliberately never persisted. */
internal data class ConnectionRecord(
    val mode: ConnectionMode,
    val host: String,
    val port: Int,
    val guid: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val identity: String get() = if (mode == ConnectionMode.LOCAL) "LOCAL" else if (mode == ConnectionMode.TCP) "TCP:$host:$port" else "${mode.name}:$guid"
}

internal class ConnectionHistory(context: Context) {
    private val preferences = context.getSharedPreferences("connection_history", Context.MODE_PRIVATE)

    fun list(mode: ConnectionMode): List<ConnectionRecord> = all().filter { it.mode == mode }

    fun save(record: ConnectionRecord) {
        write(all().filterNot { it.identity == record.identity } + record.copy(updatedAt = System.currentTimeMillis()))
    }

    fun remove(record: ConnectionRecord) = write(all().filterNot { it.identity == record.identity })

    private fun all(): List<ConnectionRecord> = runCatching {
        val array = JSONArray(preferences.getString("entries", "[]"))
        (0 until array.length()).mapNotNull { index ->
            val value = array.optJSONObject(index) ?: return@mapNotNull null
            val mode = ConnectionMode.entries.firstOrNull { it.name == value.optString("kind") }
                ?: return@mapNotNull null
            val host = value.optString("host")
            val port = value.optInt("port")
            val guid = value.optString("guid")
            if (host.isBlank() || port !in 1..65535 || (mode != ConnectionMode.TCP && mode != ConnectionMode.LOCAL && guid.isBlank()))
                return@mapNotNull null
            ConnectionRecord(mode, host, port, guid, value.optLong("updatedAt"))
        }.sortedByDescending { it.updatedAt }
    }.getOrDefault(emptyList())

    private fun write(records: List<ConnectionRecord>) {
        val array = JSONArray()
        records.forEach { record ->
            array.put(JSONObject().apply {
                put("kind", record.mode.name)
                put("host", record.host)
                put("port", record.port)
                put("guid", record.guid)
                put("updatedAt", record.updatedAt)
            })
        }
        preferences.edit().putString("entries", array.toString()).apply()
    }
}
