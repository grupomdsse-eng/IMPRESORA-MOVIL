package es.grupomds.mdsprint

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DiagnosticsStore(context: Context) {
    private val prefs = context.getSharedPreferences("mds_diagnostics", Context.MODE_PRIVATE)

    fun save(message: String) {
        val time = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())
        prefs.edit().putString("last", "$time\n$message").apply()
    }

    fun last(): String = prefs.getString(
        "last",
        "Todavía no hay diagnósticos de impresión."
    ) ?: "Todavía no hay diagnósticos de impresión."
}
