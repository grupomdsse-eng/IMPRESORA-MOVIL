package es.grupomds.mdsprint

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.OutputStream
import kotlin.math.roundToInt

/**
 * Conversor PDF -> PWG Raster sGray/8.
 *
 * PWG Raster es el formato base de impresión driverless de IPP Everywhere.
 * Se genera por bandas para no reservar una página completa en memoria.
 */
object PwgRasterEncoder {
    data class EncodeResult(
        val ok: Boolean,
        val message: String,
        val pages: Int = 0,
        val dpi: Int = 300
    )

    private const val BAND_HEIGHT = 64

    fun encode(
        pdfFile: File,
        outputFile: File,
        media: PrintAttributes.MediaSize,
        dpi: Int
    ): EncodeResult {
        val safeDpi = dpi.coerceIn(150, 600)
        val widthPx = ((media.widthMils / 1000.0) * safeDpi).roundToInt().coerceAtLeast(1)
        val heightPx = ((media.heightMils / 1000.0) * safeDpi).roundToInt().coerceAtLeast(1)

        // Evita consumos de memoria patológicos si llega un tamaño erróneo.
        if (widthPx > 10_000 || heightPx > 18_000) {
            return EncodeResult(false, "El tamaño solicitado es demasiado grande para rasterizarlo", dpi = safeDpi)
        }

        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        return try {
            pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            if (renderer.pageCount < 1) {
                return EncodeResult(false, "El PDF no contiene páginas", dpi = safeDpi)
            }

            BufferedOutputStream(outputFile.outputStream(), 128 * 1024).use { out ->
                // PWG Raster v2 sync word (network byte order representation: "RaS2").
                out.write(byteArrayOf('R'.code.toByte(), 'a'.code.toByte(), 'S'.code.toByte(), '2'.code.toByte()))

                for (pageIndex in 0 until renderer.pageCount) {
                    writePageHeader(
                        out = out,
                        media = media,
                        widthPx = widthPx,
                        heightPx = heightPx,
                        dpi = safeDpi,
                        totalPages = renderer.pageCount
                    )
                    renderer.openPage(pageIndex).use { page ->
                        encodePage(page, out, widthPx, heightPx)
                    }
                }
            }

            EncodeResult(
                ok = true,
                message = "PWG Raster generado: ${renderer.pageCount} página(s), ${widthPx}×${heightPx}px, ${safeDpi} dpi",
                pages = renderer.pageCount,
                dpi = safeDpi
            )
        } catch (e: Exception) {
            runCatching { outputFile.delete() }
            EncodeResult(false, "No se pudo generar PWG Raster: ${e.message ?: e.javaClass.simpleName}", dpi = safeDpi)
        } finally {
            runCatching { renderer?.close() }
            runCatching { pfd?.close() }
        }
    }

    private fun encodePage(
        page: PdfRenderer.Page,
        out: OutputStream,
        widthPx: Int,
        heightPx: Int
    ) {
        var previousRow: ByteArray? = null
        var repeatedRows = 0

        fun flushRow() {
            val row = previousRow ?: return
            out.write(repeatedRows.coerceIn(0, 255))
            writePackBitsRow(out, row)
            previousRow = null
            repeatedRows = 0
        }

        var bandTop = 0
        while (bandTop < heightPx) {
            val bandHeight = minOf(BAND_HEIGHT, heightPx - bandTop)
            val bitmap = Bitmap.createBitmap(widthPx, bandHeight, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.WHITE)
                val scaleX = widthPx.toFloat() / page.width.toFloat()
                val scaleY = heightPx.toFloat() / page.height.toFloat()
                val matrix = Matrix().apply {
                    setScale(scaleX, scaleY)
                    postTranslate(0f, -bandTop.toFloat())
                }
                page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

                val argb = IntArray(widthPx)
                for (y in 0 until bandHeight) {
                    bitmap.getPixels(argb, 0, widthPx, 0, y, widthPx, 1)
                    val gray = ByteArray(widthPx)
                    for (x in 0 until widthPx) {
                        val c = argb[x]
                        val a = c ushr 24 and 0xFF
                        var r = c ushr 16 and 0xFF
                        var g = c ushr 8 and 0xFF
                        var b = c and 0xFF
                        if (a != 255) {
                            // Componer transparencia sobre fondo blanco.
                            r = (r * a + 255 * (255 - a)) / 255
                            g = (g * a + 255 * (255 - a)) / 255
                            b = (b * a + 255 * (255 - a)) / 255
                        }
                        gray[x] = ((77 * r + 150 * g + 29 * b + 128) ushr 8).toByte()
                    }

                    val prev = previousRow
                    if (prev != null && prev.contentEquals(gray) && repeatedRows < 255) {
                        repeatedRows++
                    } else {
                        flushRow()
                        previousRow = gray
                        repeatedRows = 0
                    }
                }
            } finally {
                bitmap.recycle()
            }
            bandTop += bandHeight
        }
        flushRow()
    }

    /** PWG/CUPS raster line compression (PackBits variant used by PWG Raster). */
    private fun writePackBitsRow(out: OutputStream, raw: ByteArray) {
        var pos = 0
        val len = raw.size
        while (pos < len) {
            val start = pos
            val current = raw[pos]
            pos++

            if (pos == len || raw[pos] == current) {
                var repetitions = 0
                while (pos < len && raw[pos] == current && repetitions < 127) {
                    pos++
                    repetitions++
                }
                out.write(repetitions)
                out.write(current.toInt() and 0xFF)
            } else {
                var verbatim = 1
                while (true) {
                    if (pos >= len) break
                    val cur = raw[pos]
                    pos++
                    verbatim++
                    if (verbatim == 127) break
                    if (pos < len && raw[pos] == cur) break
                }
                if (pos < len) verbatim--

                if (verbatim == 1) {
                    pos = start + 1
                    out.write(0)
                    out.write(raw[start].toInt() and 0xFF)
                } else {
                    pos = start + verbatim
                    out.write(257 - verbatim)
                    out.write(raw, start, verbatim)
                }
            }
        }
    }

    /**
     * Escribe cups_page_header2_t en el orden de red que usa PWG Raster.
     * Debe ocupar exactamente 1796 bytes.
     */
    private fun writePageHeader(
        out: OutputStream,
        media: PrintAttributes.MediaSize,
        widthPx: Int,
        heightPx: Int,
        dpi: Int,
        totalPages: Int
    ) {
        val bytes = ByteArrayOutputStream(1796)
        val data = DataOutputStream(bytes)

        fun fixedString(value: String, size: Int = 64) {
            val src = value.toByteArray(Charsets.US_ASCII)
            val n = minOf(src.size, size - 1)
            data.write(src, 0, n)
            repeat(size - n) { data.writeByte(0) }
        }
        fun pad(size: Int) = repeat(size) { data.writeByte(0) }
        fun u32(value: Int) = data.writeInt(value)

        val pageWidthPoints = ((media.widthMils / 1000.0) * 72.0).roundToInt()
        val pageHeightPoints = ((media.heightMils / 1000.0) * 72.0).roundToInt()
        val pageName = pwgMediaName(media)

        // 4 × 64 bytes: MediaClass, MediaColor, MediaType, OutputType.
        fixedString("PwgRaster")
        fixedString("")
        fixedString("")
        fixedString("")

        pad(12)                         // AdvanceDistance, AdvanceMedia, Collate
        u32(0)                          // CutMedia
        u32(0)                          // Duplex
        u32(dpi); u32(dpi)              // HWResolution
        pad(16)                         // ImagingBoundingBox
        u32(0); u32(0); u32(0)          // InsertSheet, Jog, LeadingEdge
        pad(12)                         // Margins + ManualFeed
        u32(0); u32(0)                  // MediaPosition, MediaWeight
        pad(8)                          // MirrorPrint, NegativePrint
        u32(1); u32(0)                  // NumCopies, Orientation
        pad(4)                          // OutputFaceUp
        u32(pageWidthPoints); u32(pageHeightPoints)
        pad(8)                          // Separations, TraySwitch
        u32(0)                          // Tumble
        u32(widthPx); u32(heightPx)     // cupsWidth, cupsHeight
        pad(4)                          // cupsMediaType
        u32(8); u32(8); u32(widthPx)    // bits/color, bits/pixel, bytes/line
        u32(0); u32(18)                 // Chunky, sGray
        pad(16)                         // Compression, RowCount, RowFeed, RowStep
        u32(1)                          // cupsNumColors
        pad(28)                         // scaling + page/bbox floats
        u32(totalPages)                 // cupsInteger[0] = TotalPageCount
        u32(1); u32(1)                  // CrossFeedTransform, FeedTransform
        u32(0); u32(0); u32(0); u32(0) // ImageBox
        u32(0x00FFFFFF); u32(0)         // AlternatePrimary, PrintQuality
        pad(20)                         // Vendor remaining ints
        u32(0); u32(0)                  // VendorIdentifier, VendorLength
        pad(1088)                       // cupsString[16][64] + remaining fixed fields area
        pad(64)                         // cupsMarkerType
        fixedString("")                 // cupsRenderingIntent
        fixedString(pageName)           // cupsPageSizeName

        data.flush()
        val header = bytes.toByteArray()
        check(header.size == 1796) { "Cabecera PWG inválida: ${header.size} bytes" }
        out.write(header)
    }

    private fun pwgMediaName(media: PrintAttributes.MediaSize): String = when (media.id) {
        PrintAttributes.MediaSize.ISO_A4.id -> "iso_a4_210x297mm"
        PrintAttributes.MediaSize.ISO_A5.id -> "iso_a5_148x210mm"
        PrintAttributes.MediaSize.NA_LETTER.id -> "na_letter_8.5x11in"
        PrintAttributes.MediaSize.NA_LEGAL.id -> "na_legal_8.5x14in"
        else -> {
            val w = media.widthMils * 25.4 / 1000.0
            val h = media.heightMils * 25.4 / 1000.0
            "custom_${formatMm(w)}x${formatMm(h)}mm"
        }
    }

    private fun formatMm(value: Double): String {
        val rounded = value.roundToInt()
        return if (kotlin.math.abs(value - rounded) < 0.05) rounded.toString()
        else String.format(java.util.Locale.US, "%.1f", value)
    }
}
