package es.grupomds.mdsprint

import android.print.PrintAttributes
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

object IppClient {
    data class Result(val ok: Boolean, val message: String, val ippStatus: Int? = null)

    private val requestId = AtomicInteger(1)

    fun testPrinter(printer: PrinterConfig): Result {
        val ippUri = ippUri(printer)
        val header = buildIppRequest(0x000B) { out ->
            writeText(out, 0x47, "attributes-charset", "utf-8")
            writeText(out, 0x48, "attributes-natural-language", "es")
            writeText(out, 0x45, "printer-uri", ippUri)
            writeText(out, 0x44, "requested-attributes", "printer-name")
            writeText(out, 0x44, "", "document-format-supported")
            writeText(out, 0x44, "", "media-supported")
            out.writeByte(0x03)
        }
        return post(printer, header, null)
    }

    fun printPdf(
        printer: PrinterConfig,
        pdfFile: File,
        attributes: PrintAttributes?,
        copies: Int,
        jobName: String
    ): Result {
        val ippUri = ippUri(printer)
        val header = buildIppRequest(0x0002) { out ->
            writeText(out, 0x47, "attributes-charset", "utf-8")
            writeText(out, 0x48, "attributes-natural-language", "es")
            writeText(out, 0x45, "printer-uri", ippUri)
            writeText(out, 0x42, "requesting-user-name", "Android")
            writeText(out, 0x42, "job-name", jobName.take(120))
            writeText(out, 0x49, "document-format", "application/pdf")

            out.writeByte(0x02) // job-attributes-tag
            writeInteger(out, "copies", copies.coerceIn(1, 99))

            val media = attributes?.mediaSize
            if (media != null) {
                val keyword = standardMediaKeyword(media.id)
                if (keyword != null) {
                    writeText(out, 0x44, "media", keyword)
                } else {
                    val xHundredthMm = (media.widthMils * 2.54).roundToInt()
                    val yHundredthMm = (media.heightMils * 2.54).roundToInt()
                    writeMediaCol(out, xHundredthMm, yHundredthMm)
                }
            }

            out.writeByte(0x03) // end-of-attributes-tag
        }
        return post(printer, header, pdfFile)
    }

    private fun standardMediaKeyword(id: String): String? = when (id) {
        PrintAttributes.MediaSize.ISO_A4.id -> "iso_a4_210x297mm"
        PrintAttributes.MediaSize.ISO_A5.id -> "iso_a5_148x210mm"
        PrintAttributes.MediaSize.NA_LETTER.id -> "na_letter_8.5x11in"
        PrintAttributes.MediaSize.NA_LEGAL.id -> "na_legal_8.5x14in"
        else -> null
    }

    private fun buildIppRequest(operationId: Int, body: (DataOutputStream) -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeByte(0x02)
            out.writeByte(0x00) // IPP/2.0
            out.writeShort(operationId)
            out.writeInt(requestId.getAndIncrement())
            out.writeByte(0x01) // operation-attributes-tag
            body(out)
        }
        return bytes.toByteArray()
    }

    private fun post(printer: PrinterConfig, header: ByteArray, file: File?): Result {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL("http", printer.host, printer.port, printer.normalizedPath)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 7_000
                readTimeout = 120_000
                doOutput = true
                doInput = true
                useCaches = false
                setRequestProperty("Content-Type", "application/ipp")
                setRequestProperty("Accept", "application/ipp")
                val total = header.size.toLong() + (file?.length() ?: 0L)
                setFixedLengthStreamingMode(total)
            }

            connection.outputStream.use { output ->
                output.write(header)
                if (file != null) {
                    file.inputStream().buffered().use { input -> input.copyTo(output, 64 * 1024) }
                }
            }

            val http = connection.responseCode
            if (http !in 200..299) {
                return Result(false, "La impresora respondió HTTP $http")
            }

            val input = BufferedInputStream(connection.inputStream)
            val data = DataInputStream(input)
            val versionMajor = data.readUnsignedByte()
            val versionMinor = data.readUnsignedByte()
            val status = data.readUnsignedShort()
            data.readInt() // request-id

            if (status <= 0x00FF) {
                Result(true, "IPP $versionMajor.$versionMinor · trabajo aceptado", status)
            } else {
                Result(false, "La impresora rechazó el trabajo (IPP ${statusHex(status)})", status)
            }
        } catch (e: Exception) {
            Result(false, e.message ?: e.javaClass.simpleName)
        } finally {
            connection?.disconnect()
        }
    }

    private fun ippUri(p: PrinterConfig): String {
        val host = if (p.host.contains(':') && !p.host.startsWith('[')) "[${p.host}]" else p.host
        return "ipp://$host:${p.port}${p.normalizedPath}"
    }

    private fun writeText(out: DataOutputStream, tag: Int, name: String, value: String) {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val valueBytes = value.toByteArray(Charsets.UTF_8)
        out.writeByte(tag)
        out.writeShort(nameBytes.size)
        out.write(nameBytes)
        out.writeShort(valueBytes.size)
        out.write(valueBytes)
    }

    private fun writeInteger(out: DataOutputStream, name: String, value: Int) {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        out.writeByte(0x21)
        out.writeShort(nameBytes.size)
        out.write(nameBytes)
        out.writeShort(4)
        out.writeInt(value)
    }

    private fun writeMediaCol(out: DataOutputStream, xHundredthMm: Int, yHundredthMm: Int) {
        // media-col = { media-size = { x-dimension, y-dimension } }
        writeEmptyValue(out, 0x34, "media-col") // begCollection
        writeText(out, 0x4A, "", "media-size")  // memberAttrName
        writeEmptyValue(out, 0x34, "")           // nested begCollection
        writeText(out, 0x4A, "", "x-dimension")
        writeInteger(out, "", xHundredthMm)
        writeText(out, 0x4A, "", "y-dimension")
        writeInteger(out, "", yHundredthMm)
        writeEmptyValue(out, 0x37, "")           // nested endCollection
        writeEmptyValue(out, 0x37, "")           // media-col endCollection
    }

    private fun writeEmptyValue(out: DataOutputStream, tag: Int, name: String) {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        out.writeByte(tag)
        out.writeShort(nameBytes.size)
        out.write(nameBytes)
        out.writeShort(0)
    }

    private fun statusHex(status: Int) = "0x" + status.toString(16).padStart(4, '0')
}
