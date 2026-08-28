package es.grupomds.mdsprint

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class AdvancedActivity : Activity() {
    private val printerStore by lazy { PrinterStore(this) }
    private val paperStore by lazy { PaperStore(this) }
    private lateinit var printerList: LinearLayout
    private lateinit var paperList: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "MDS Print · Configuración avanzada"
        setContentView(buildUi())
        refreshPrinters()
        refreshPapers()
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(36))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "MDS Print"
            textSize = 27f
            setTextColor(Color.rgb(28, 111, 184))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(text("Servicio de impresión Android con Oficio México 216 × 340 mm y tamaños personalizados."))

        root.addView(Button(this).apply {
            text = "ABRIR AJUSTES DE IMPRESIÓN DE ANDROID"
            setOnClickListener {
                runCatching { startActivity(Intent(Settings.ACTION_PRINT_SETTINGS)) }
                    .onFailure { Toast.makeText(this@AdvancedActivity, "Abre Ajustes > Impresión y activa MDS Print Service", Toast.LENGTH_LONG).show() }
            }
        }, params(top = 12))

        section(root, "1 · Impresoras IPP")
        root.addView(text("Las impresoras de red compatibles con IPP se buscan automáticamente. También puedes añadir una por IP."))

        val name = input("Nombre (ej. Brother Oficina)")
        val host = input("IP o host (ej. 192.168.1.50)")
        val port = input("Puerto IPP", InputType.TYPE_CLASS_NUMBER).apply { setText("631") }
        val path = input("Ruta IPP").apply { setText("/ipp/print") }
        root.addView(name); root.addView(host); root.addView(port); root.addView(path)
        root.addView(Button(this).apply {
            text = "AÑADIR IMPRESORA"
            setOnClickListener {
                val h = host.text.toString().trim()
                val p = port.text.toString().toIntOrNull() ?: 631
                if (h.isBlank()) {
                    toast("Escribe la IP de la impresora")
                    return@setOnClickListener
                }
                printerStore.add(
                    PrinterConfig(
                        name = name.text.toString().trim().ifBlank { "Impresora $h" },
                        host = h,
                        port = p,
                        path = path.text.toString().trim().ifBlank { "/ipp/print" }
                    )
                )
                name.text.clear(); host.text.clear()
                refreshPrinters()
            }
        })
        printerList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(printerList)

        section(root, "2 · Tamaños de papel")
        root.addView(text("Oficio México ya viene incluido. Los tamaños que añadas aquí aparecerán en Papel dentro del diálogo de impresión de Android cuando uses MDS Print Service."))
        val paperName = input("Nombre (ej. Oficio especial)")
        val width = input("Ancho en mm", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val height = input("Alto en mm", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        root.addView(paperName); root.addView(width); root.addView(height)
        root.addView(Button(this).apply {
            text = "AÑADIR TAMAÑO PERSONALIZADO"
            setOnClickListener {
                val w = width.text.toString().replace(',', '.').toDoubleOrNull()
                val h = height.text.toString().replace(',', '.').toDoubleOrNull()
                if (w == null || h == null) {
                    toast("Introduce ancho y alto válidos")
                    return@setOnClickListener
                }
                runCatching {
                    paperStore.add(paperName.text.toString().trim().ifBlank { "Papel personalizado" }, w, h)
                }.onSuccess {
                    paperName.text.clear(); width.text.clear(); height.text.clear(); refreshPapers()
                    toast("Tamaño guardado. Cierra y vuelve a abrir el selector de impresoras si ya estaba abierto.")
                }.onFailure { toast("Tamaño fuera de rango") }
            }
        })
        paperList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(paperList)

        section(root, "3 · Cómo usarlo")
        root.addView(text("1. Activa MDS Print Service en Ajustes de impresión.\n2. Abre un PDF en Chrome, Drive, Adobe, Gmail, etc.\n3. Pulsa Imprimir.\n4. Selecciona una impresora gestionada por MDS Print.\n5. En Papel selecciona “Oficio México 216 × 340 mm”.\n6. Imprime."))

        return scroll
    }

    private fun refreshPrinters() {
        if (!::printerList.isInitialized) return
        printerList.removeAllViews()
        val items = printerStore.list()
        if (items.isEmpty()) {
            printerList.addView(text("No hay impresoras manuales guardadas. La detección automática se realiza desde el selector de impresión."))
            return
        }
        items.forEach { p ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setBackgroundColor(Color.rgb(245, 247, 250))
            }
            card.addView(TextView(this).apply { text = p.name; textSize = 17f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
            card.addView(text("${p.host}:${p.port}${p.normalizedPath}"))
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
            actions.addView(Button(this).apply {
                text = "PROBAR"
                setOnClickListener {
                    isEnabled = false
                    text = "PROBANDO…"
                    Thread {
                        val result = IppClient.testPrinter(p)
                        runOnUiThread {
                            isEnabled = true
                            text = "PROBAR"
                            toast(if (result.ok) "Conexión IPP correcta" else "Error: ${result.message}")
                        }
                    }.start()
                }
            })
            actions.addView(Button(this).apply {
                text = "ELIMINAR"
                setOnClickListener { printerStore.remove(p); refreshPrinters() }
            })
            card.addView(actions)
            printerList.addView(card, params(top = 8))
        }
    }

    private fun refreshPapers() {
        if (!::paperList.isInitialized) return
        paperList.removeAllViews()
        paperStore.all().forEach { p ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
            row.addView(TextView(this).apply {
                text = p.label
                textSize = 16f
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (!p.builtIn) {
                row.addView(Button(this).apply {
                    text = "BORRAR"
                    setOnClickListener { paperStore.remove(p.id); refreshPapers() }
                })
            }
            paperList.addView(row)
        }
    }

    private fun section(root: LinearLayout, title: String) {
        root.addView(TextView(this).apply {
            text = title
            textSize = 20f
            setTextColor(Color.rgb(28, 111, 184))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, params(top = 26, bottom = 6))
    }

    private fun input(hint: String, type: Int = InputType.TYPE_CLASS_TEXT) = EditText(this).apply {
        this.hint = hint
        inputType = type
        textSize = 16f
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    private fun text(value: String) = TextView(this).apply {
        text = value
        textSize = 15f
        setTextColor(Color.DKGRAY)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun params(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { setMargins(0, dp(top), 0, dp(bottom)) }

    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
