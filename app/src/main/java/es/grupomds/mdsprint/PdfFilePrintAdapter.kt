package es.grupomds.mdsprint

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Adapta cualquier PDF al tamaño seleccionado en el cuadro de impresión.
 * Para el flujo normal de cliente, MainActivity lo inicia en Oficio México 216 × 340 mm.
 */
class PdfFilePrintAdapter(
    private val context: Context,
    private val uri: Uri,
    private val displayName: String
) : PrintDocumentAdapter() {

    @Volatile private var pageCount: Int = PrintDocumentInfo.PAGE_COUNT_UNKNOWN
    @Volatile private var attributes: PrintAttributes? = null
    @Volatile private var localPdf: File? = null

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal,
        callback: LayoutResultCallback,
        extras: android.os.Bundle?
    ) {
        attributes = newAttributes
        Thread {
            try {
                if (cancellationSignal.isCanceled) {
                    callback.onLayoutCancelled()
                    return@Thread
                }
                val file = ensureLocalPdf()
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { renderer -> pageCount = renderer.pageCount }
                }

                val info = PrintDocumentInfo.Builder(displayName)
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(pageCount)
                    .build()
                callback.onLayoutFinished(info, oldAttributes != newAttributes)
            } catch (e: Exception) {
                callback.onLayoutFailed(e.message ?: "No se pudo preparar el PDF")
            }
        }.start()
    }

    override fun onWrite(
        pages: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal,
        callback: WriteResultCallback
    ) {
        val targetAttributes = attributes
        Thread {
            val pdf = PdfDocument()
            try {
                val localFile = ensureLocalPdf()
                ParcelFileDescriptor.open(localFile, ParcelFileDescriptor.MODE_READ_ONLY).use { sourcePfd ->
                    PdfRenderer(sourcePfd).use { renderer ->
                        val media = targetAttributes?.mediaSize
                            ?: PaperStore.toMediaSize(PaperStore(context).oficioMexico)
                        val widthPt = (media.widthMils / 1000.0 * 72.0).roundToInt().coerceAtLeast(1)
                        val heightPt = (media.heightMils / 1000.0 * 72.0).roundToInt().coerceAtLeast(1)

                        // 200 dpi mantiene buena calidad y evita consumir demasiada memoria en móviles modestos.
                        val renderDpi = (targetAttributes?.resolution?.horizontalDpi ?: 200).coerceIn(150, 200)
                        val targetWidthPx = (media.widthMils / 1000.0 * renderDpi).roundToInt().coerceAtLeast(1)
                        val targetHeightPx = (media.heightMils / 1000.0 * renderDpi).roundToInt().coerceAtLeast(1)

                        for (i in 0 until renderer.pageCount) {
                            if (cancellationSignal.isCanceled) {
                                callback.onWriteCancelled()
                                return@Thread
                            }
                            if (!isPageRequested(i, pages)) continue

                            renderer.openPage(i).use { sourcePage ->
                                val sourceRatio = sourcePage.width.toFloat() / sourcePage.height.toFloat()
                                val targetRatio = targetWidthPx.toFloat() / targetHeightPx.toFloat()

                                val bitmapWidth: Int
                                val bitmapHeight: Int
                                if (sourceRatio > targetRatio) {
                                    bitmapWidth = targetWidthPx
                                    bitmapHeight = (targetWidthPx / sourceRatio).roundToInt().coerceAtLeast(1)
                                } else {
                                    bitmapHeight = targetHeightPx
                                    bitmapWidth = (targetHeightPx * sourceRatio).roundToInt().coerceAtLeast(1)
                                }

                                val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
                                bitmap.eraseColor(Color.WHITE)
                                val sx = bitmapWidth.toFloat() / sourcePage.width.toFloat()
                                val sy = bitmapHeight.toFloat() / sourcePage.height.toFloat()
                                val matrix = Matrix().apply { setScale(sx, sy) }
                                sourcePage.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

                                val pageInfo = PdfDocument.PageInfo.Builder(widthPt, heightPt, i + 1).create()
                                val outPage = pdf.startPage(pageInfo)
                                val canvas = outPage.canvas
                                canvas.drawColor(Color.WHITE)

                                // 3 mm de zona segura para evitar cortes en impresoras sin borde.
                                val marginPt = 3.0 / 25.4 * 72.0
                                val content = RectF(
                                    marginPt.toFloat(),
                                    marginPt.toFloat(),
                                    (widthPt - marginPt).toFloat(),
                                    (heightPt - marginPt).toFloat()
                                )
                                val scale = min(content.width() / bitmap.width, content.height() / bitmap.height)
                                val drawW = bitmap.width * scale
                                val drawH = bitmap.height * scale
                                val left = content.left + (content.width() - drawW) / 2f
                                val top = content.top + (content.height() - drawH) / 2f
                                canvas.drawBitmap(bitmap, null, RectF(left, top, left + drawW, top + drawH), null)
                                pdf.finishPage(outPage)
                                bitmap.recycle()
                            }
                        }
                    }
                }

                FileOutputStream(destination.fileDescriptor).use { output -> pdf.writeTo(output) }
                val written = requestedRanges(rendererCount = pageCount, pages = pages)
                callback.onWriteFinished(written)
            } catch (e: Exception) {
                callback.onWriteFailed(e.message ?: "No se pudo generar el PDF de impresión")
            } finally {
                pdf.close()
                runCatching { destination.close() }
            }
        }.start()
    }

    private fun ensureLocalPdf(): File {
        localPdf?.takeIf { it.exists() && it.length() > 0L }?.let { return it }
        synchronized(this) {
            localPdf?.takeIf { it.exists() && it.length() > 0L }?.let { return it }
            val file = File.createTempFile("mds_source_", ".pdf", context.cacheDir)
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().buffered().use { output -> input.copyTo(output, 64 * 1024) }
            } ?: throw IllegalStateException("No se pudo abrir el PDF")
            localPdf = file
            return file
        }
    }

    override fun onFinish() {
        localPdf?.delete()
        localPdf = null
    }

    private fun isPageRequested(index: Int, ranges: Array<out PageRange>): Boolean {
        if (ranges.any { it == PageRange.ALL_PAGES }) return true
        return ranges.any { index in it.start..it.end }
    }

    private fun requestedRanges(rendererCount: Int, pages: Array<out PageRange>): Array<PageRange> {
        if (pages.any { it == PageRange.ALL_PAGES }) return arrayOf(PageRange(0, (rendererCount - 1).coerceAtLeast(0)))
        return pages.map { PageRange(it.start, it.end) }.toTypedArray()
    }
}
