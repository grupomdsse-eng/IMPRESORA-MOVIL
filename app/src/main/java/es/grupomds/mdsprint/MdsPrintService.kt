package es.grupomds.mdsprint

import android.os.Handler
import android.os.Looper
import android.printservice.PrintJob
import android.printservice.PrintService
import java.io.File

class MdsPrintService : PrintService() {
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreatePrinterDiscoverySession() = MdsPrinterDiscoverySession(this)

    override fun onPrintJobQueued(printJob: PrintJob) {
        val printerId = printJob.info.printerId
        if (printerId == null) {
            fail(printJob, "Android no indicó la impresora seleccionada")
            return
        }

        val printer = PrinterStore.fromLocalId(printerId.localId)
        if (printer == null) {
            fail(printJob, "No se pudo recuperar la configuración de la impresora")
            return
        }

        // Guarda también las impresoras descubiertas: si después hay que dar soporte,
        // aparecerán en Configuración avanzada sin que el cliente escriba la IP.
        PrinterStore(this).add(printer)

        if (!printJob.start()) return

        // Los objetos de PrintJob se consultan en el hilo principal. Después se hace
        // toda la red/rasterización en segundo plano.
        val attributes = printJob.info.attributes
        val copies = printJob.info.copies
        val jobName = printJob.document.info.name ?: "Documento Android"
        val data = printJob.document.data
        if (data == null) {
            fail(printJob, "Android no entregó datos del documento")
            return
        }

        Thread {
            var tempPdf: File? = null
            try {
                tempPdf = File.createTempFile("mds_print_", ".pdf", cacheDir)
                android.os.ParcelFileDescriptor.AutoCloseInputStream(data).use { input ->
                    tempPdf.outputStream().buffered(128 * 1024).use { output ->
                        input.copyTo(output, 128 * 1024)
                    }
                }

                if (tempPdf.length() < 8) {
                    throw IllegalStateException("El documento recibido por Android está vacío")
                }

                val result = IppClient.printSmart(
                    printer = printer,
                    pdfFile = tempPdf,
                    attributes = attributes,
                    copies = copies,
                    jobName = jobName,
                    tempDir = cacheDir
                )

                DiagnosticsStore(this).save(buildString {
                    append("IMPRESORA: ${printer.name}\n")
                    append("DESTINO: ${printer.host}:${printer.port}${printer.normalizedPath}\n")
                    append("RESULTADO: ${if (result.ok) "CORRECTO" else "ERROR"}\n")
                    result.formatUsed?.let { append("FORMATO: $it\n") }
                    append("MENSAJE: ${result.message}\n")
                    if (result.detail.isNotBlank()) append("\n${result.detail}")
                })

                mainHandler.post {
                    if (result.ok) printJob.complete() else printJob.fail(result.message)
                }
            } catch (e: Exception) {
                val message = e.message ?: "Error enviando el trabajo de impresión"
                DiagnosticsStore(this).save(
                    "IMPRESORA: ${printer.name}\n" +
                        "DESTINO: ${printer.host}:${printer.port}${printer.normalizedPath}\n" +
                        "RESULTADO: ERROR\nMENSAJE: $message"
                )
                mainHandler.post { printJob.fail(message) }
            } finally {
                runCatching { tempPdf?.delete() }
            }
        }.apply { name = "MDS-IPP-Print" }.start()
    }

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        printJob.cancel()
    }

    private fun fail(printJob: PrintJob, message: String) {
        DiagnosticsStore(this).save("RESULTADO: ERROR\nMENSAJE: $message")
        printJob.fail(message)
    }
}
