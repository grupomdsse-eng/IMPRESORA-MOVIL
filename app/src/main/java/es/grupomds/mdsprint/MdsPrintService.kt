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
            printJob.fail("Android no indicó la impresora seleccionada")
            return
        }

        val printer = PrinterStore.fromLocalId(printerId.localId)
        if (printer == null) {
            printJob.fail("No se pudo recuperar la configuración de la impresora")
            return
        }

        if (!printJob.start()) return

        val attributes = printJob.info.attributes
        val copies = printJob.info.copies
        val jobName = printJob.document.info.name ?: "Documento Android"
        val data = printJob.document.data
        if (data == null) {
            printJob.fail("Android no entregó datos del documento")
            return
        }

        Thread {
            var temp: File? = null
            try {
                temp = File.createTempFile("mds_print_", ".pdf", cacheDir)
                android.os.ParcelFileDescriptor.AutoCloseInputStream(data).use { input ->
                    temp.outputStream().buffered().use { output -> input.copyTo(output, 64 * 1024) }
                }

                val result = IppClient.printPdf(printer, temp, attributes, copies, jobName)
                mainHandler.post {
                    if (result.ok) printJob.complete() else printJob.fail(result.message)
                }
            } catch (e: Exception) {
                mainHandler.post { printJob.fail(e.message ?: "Error enviando el trabajo IPP") }
            } finally {
                temp?.delete()
            }
        }.start()
    }

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        printJob.cancel()
    }
}
