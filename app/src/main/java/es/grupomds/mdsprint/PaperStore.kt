package es.grupomds.mdsprint

import android.content.Context
import android.print.PrintAttributes
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.roundToInt

class PaperStore(context: Context) {
    private val prefs = context.getSharedPreferences("mds_papers", Context.MODE_PRIVATE)

    val oficioMexico = CustomPaper(
        id = "MDS_OFICIO_MEXICO_216X340",
        label = "Oficio México 216 × 340 mm",
        widthMm = 216.0,
        heightMm = 340.0,
        builtIn = true
    )

    fun all(): List<CustomPaper> = listOf(oficioMexico) + custom()

    fun custom(): List<CustomPaper> {
        val raw = prefs.getString("custom", "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    add(
                        CustomPaper(
                            id = o.getString("id"),
                            label = o.getString("label"),
                            widthMm = o.getDouble("widthMm"),
                            heightMm = o.getDouble("heightMm")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun add(label: String, widthMm: Double, heightMm: Double) {
        require(widthMm in 20.0..1000.0 && heightMm in 20.0..2000.0)
        val list = custom().toMutableList()
        list.add(
            CustomPaper(
                id = "MDS_CUSTOM_${UUID.randomUUID()}",
                label = "$label ${formatMm(widthMm)} × ${formatMm(heightMm)} mm",
                widthMm = widthMm,
                heightMm = heightMm
            )
        )
        save(list)
    }

    fun remove(id: String) {
        save(custom().filterNot { it.id == id })
    }

    private fun save(items: List<CustomPaper>) {
        val array = JSONArray()
        items.forEach { p ->
            array.put(JSONObject().apply {
                put("id", p.id)
                put("label", p.label)
                put("widthMm", p.widthMm)
                put("heightMm", p.heightMm)
            })
        }
        prefs.edit().putString("custom", array.toString()).apply()
    }

    companion object {
        fun toMediaSize(paper: CustomPaper): PrintAttributes.MediaSize {
            val widthMils = (paper.widthMm / 25.4 * 1000.0).roundToInt()
            val heightMils = (paper.heightMm / 25.4 * 1000.0).roundToInt()
            return PrintAttributes.MediaSize(paper.id, paper.label, widthMils, heightMils)
        }

        private fun formatMm(value: Double): String =
            if (value % 1.0 == 0.0) value.toInt().toString() else String.format(java.util.Locale.US, "%.1f", value)
    }
}
