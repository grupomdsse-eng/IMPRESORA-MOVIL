package es.grupomds.mdsprint

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

class PrinterStore(context: Context) {
    private val prefs = context.getSharedPreferences("mds_printers", Context.MODE_PRIVATE)

    fun list(): List<PrinterConfig> {
        val raw = prefs.getString("printers", "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    add(
                        PrinterConfig(
                            name = o.optString("name", "Impresora IPP"),
                            host = o.getString("host"),
                            port = o.optInt("port", 631),
                            path = o.optString("path", "/ipp/print")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun add(config: PrinterConfig) {
        val current = list().toMutableList()
        current.removeAll { it.host.equals(config.host, true) && it.port == config.port && it.normalizedPath == config.normalizedPath }
        current.add(config)
        save(current)
    }

    fun remove(config: PrinterConfig) {
        save(list().filterNot { it.host == config.host && it.port == config.port && it.normalizedPath == config.normalizedPath })
    }

    private fun save(items: List<PrinterConfig>) {
        val array = JSONArray()
        items.forEach { p ->
            array.put(JSONObject().apply {
                put("name", p.name)
                put("host", p.host)
                put("port", p.port)
                put("path", p.normalizedPath)
            })
        }
        prefs.edit().putString("printers", array.toString()).apply()
    }

    companion object {
        fun toLocalId(config: PrinterConfig): String {
            val raw = listOf(config.name, config.host, config.port.toString(), config.normalizedPath).joinToString("\n")
            return Base64.encodeToString(raw.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }

        fun fromLocalId(localId: String): PrinterConfig? = runCatching {
            val raw = String(Base64.decode(localId, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)
            val parts = raw.split('\n')
            require(parts.size >= 4)
            PrinterConfig(parts[0], parts[1], parts[2].toInt(), parts.drop(3).joinToString("\n"))
        }.getOrNull()
    }
}
