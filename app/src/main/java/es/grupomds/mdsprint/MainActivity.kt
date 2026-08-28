package es.grupomds.mdsprint

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val openPdfRequest = 1001
    private lateinit var statusTitle: TextView
    private lateinit var statusText: TextView
    private lateinit var activateButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "MDS Oficio México"
        setContentView(buildUi())
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            setIntent(intent)
            handleIncomingIntent(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshServiceStatus()
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(36))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "MDS Oficio México"
            textSize = 29f
            setTextColor(BLUE)
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Imprime PDFs directamente en 216 × 340 mm"
            textSize = 17f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(5), 0, dp(20))
        })

        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Color.rgb(245, 247, 250))
        }
        statusTitle = TextView(this).apply {
            textSize = 19f
            setTypeface(typeface, Typeface.BOLD)
        }
        statusText = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(4), 0, 0)
            setTextColor(Color.DKGRAY)
        }
        statusCard.addView(statusTitle)
        statusCard.addView(statusText)
        root.addView(statusCard)

        root.addView(stepTitle("1 · Activar una sola vez"), params(top = 24))
        root.addView(body("Pulsa el botón y activa “MDS Print” en los ajustes de impresión de Android. Solo hay que hacerlo la primera vez."))
        activateButton = primaryButton("ACTIVAR MDS PRINT") {
            runCatching { startActivity(Intent(Settings.ACTION_PRINT_SETTINGS)) }
                .onFailure { toast("Abre Ajustes > Impresión y activa MDS Print") }
        }
        root.addView(activateButton, params(top = 10))

        root.addView(stepTitle("2 · Imprimir PDF"), params(top = 26))
        root.addView(body("Selecciona el PDF. Se abrirá el cuadro de impresión con Oficio México 216 × 340 mm ya preparado."))
        root.addView(primaryButton("SELECCIONAR PDF E IMPRIMIR") {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/pdf"
            }
            startActivityForResult(intent, openPdfRequest)
        }, params(top = 10))

        root.addView(TextView(this).apply {
            text = "También puedes abrir o compartir un PDF desde WhatsApp, correo, Drive, Chrome o Archivos y elegir “MDS Oficio México”."
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(14), 0, dp(16))
        })

        root.addView(Button(this).apply {
            text = "CONFIGURACIÓN AVANZADA · SOLO SOPORTE"
            setOnClickListener { startActivity(Intent(this@MainActivity, AdvancedActivity::class.java)) }
        }, params(top = 18))

        root.addView(TextView(this).apply {
            text = "Tamaño fijo recomendado: Oficio México · 216 × 340 mm"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.GRAY)
            setPadding(0, dp(24), 0, 0)
        })

        return scroll
    }

    private fun refreshServiceStatus() {
        val enabled = isMdsPrintServiceEnabled()
        when (enabled) {
            true -> {
                statusTitle.text = "✓ Listo para imprimir"
                statusTitle.setTextColor(Color.rgb(30, 130, 76))
                statusText.text = "MDS Print está activado. Ya puedes seleccionar o compartir un PDF."
                activateButton.text = "MDS PRINT ACTIVADO"
            }
            false -> {
                statusTitle.text = "Falta activar MDS Print"
                statusTitle.setTextColor(Color.rgb(190, 80, 35))
                statusText.text = "Es el único ajuste obligatorio de Android antes de imprimir."
                activateButton.text = "ACTIVAR MDS PRINT"
            }
            null -> {
                statusTitle.text = "Configuración inicial"
                statusTitle.setTextColor(BLUE)
                statusText.text = "Activa MDS Print una vez y después utiliza directamente el botón de imprimir."
                activateButton.text = "ABRIR AJUSTES DE IMPRESIÓN"
            }
        }
    }

    private fun isMdsPrintServiceEnabled(): Boolean? {
        if (Build.VERSION.SDK_INT < 33) return null
        val manager = getSystemService(PRINT_SERVICE) as PrintManager
        return runCatching {
            manager.isPrintServiceEnabled(ComponentName(this, MdsPrintService::class.java))
        }.getOrNull()
    }

    private fun handleIncomingIntent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let { uri ->
                if (intent.type == "application/pdf" || uri.toString().lowercase().contains(".pdf")) {
                    printPdf(uri)
                }
            }
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val uri = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                if (uri != null) printPdf(uri)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == openPdfRequest && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                runCatching {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                printPdf(uri)
            }
        }
    }

    private fun printPdf(uri: Uri) {
        if (isMdsPrintServiceEnabled() == false) {
            toast("Primero activa MDS Print. Solo hay que hacerlo una vez.")
            runCatching { startActivity(Intent(Settings.ACTION_PRINT_SETTINGS)) }
            return
        }

        val printManager = getSystemService(PRINT_SERVICE) as PrintManager
        val media = PaperStore.toMediaSize(PaperStore(this).oficioMexico).asPortrait()
        val attributes = PrintAttributes.Builder()
            .setMediaSize(media)
            .setResolution(PrintAttributes.Resolution("300dpi", "300 dpi", 300, 300))
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
            .build()

        val name = resolveDisplayName(uri) ?: "Documento"
        printManager.print(
            "MDS Oficio · $name",
            PdfFilePrintAdapter(this, uri, name),
            attributes
        )
    }

    private fun resolveDisplayName(uri: Uri): String? {
        return runCatching {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }

    private fun primaryButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 16f
        setTextColor(Color.WHITE)
        setBackgroundColor(ORANGE)
        minHeight = dp(52)
        setOnClickListener { action() }
    }

    private fun stepTitle(value: String) = TextView(this).apply {
        text = value
        textSize = 20f
        setTextColor(BLUE)
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun body(value: String) = TextView(this).apply {
        text = value
        textSize = 15f
        setTextColor(Color.DKGRAY)
        setPadding(0, dp(6), 0, 0)
    }

    private fun params(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { setMargins(0, dp(top), 0, 0) }

    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private val BLUE = Color.rgb(28, 111, 184)
        private val ORANGE = Color.rgb(255, 153, 0)
    }
}
