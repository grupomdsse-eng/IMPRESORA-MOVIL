package es.grupomds.mdsprint

import android.print.PrintAttributes
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt

object IppClient {
    data class Result(
        val ok: Boolean,
        val message: String,
        val ippStatus: Int? = null,
        val formatUsed: String? = null,
        val detail: String = ""
    )

    data class Capabilities(
        val printerName: String = "",
        val makeModel: String = "",
        val formats: Set<String> = emptySet(),
        val ippVersions: Set<String> = emptySet(),
        val jobAttributes: Set<String> = emptySet(),
        val pwgTypes: Set<String> = emptySet(),
        val pwgResolutionsDpi: Set<Int> = emptySet(),
        val media: Set<String> = emptySet(),
        val acceptingJobs: Boolean? = null
    ) {
        val supportsPdf: Boolean get() = formats.any { it.equals("application/pdf", true) }
        val supportsPwg: Boolean get() = formats.any { it.equals("image/pwg-raster", true) }
        val supportsMediaCol: Boolean get() = jobAttributes.any { it.equals("media-col", true) }

        fun summary(): String = buildString {
            if (makeModel.isNotBlank()) append("Modelo: $makeModel\n")
            if (printerName.isNotBlank()) append("Nombre IPP: $printerName\n")
            append("Formatos: ${formats.ifEmpty { setOf("no informados") }.joinToString()}\n")
            append("IPP: ${ippVersions.ifEmpty { setOf("no informado") }.joinToString()}\n")
            if (pwgResolutionsDpi.isNotEmpty()) append("PWG dpi: ${pwgResolutionsDpi.sorted().joinToString()}\n")
            if (pwgTypes.isNotEmpty()) append("PWG tipos: ${pwgTypes.joinToString()}\n")
            append("media-col: ${if (supportsMediaCol) "sí" else "no declarado"}\n")
            acceptingJobs?.let { append("Aceptando trabajos: ${if (it) "sí" else "no"}") }
        }.trim()
    }

    private data class IppResponse(
        val httpStatus: Int? = null,
        val ippStatus: Int? = null,
        val version: String = "",
        val attributes: Map<String, List<Any>> = emptyMap(),
        val error: String? = null
    ) {
        val transportOk: Boolean get() = error == null && httpStatus != null && httpStatus in 200..299 && ippStatus != null
        val ippOk: Boolean get() = transportOk && (ippStatus ?: Int.MAX_VALUE) <= 0x00FF
    }

    private val requestId = AtomicInteger(1)

    fun testPrinter(printer: PrinterConfig): Result {
        val (caps, error) = getCapabilities(printer)
        return if (caps != null) {
            Result(true, "Conexión IPP correcta", detail = caps.summary())
        } else {
            error ?: Result(false, "No se pudo consultar la impresora")
        }
    }

    /**
     * Motor inteligente:
     *  1) consulta document-format-supported;
     *  2) usa PDF si la impresora lo admite;
     *  3) si PDF no está soportado/rechaza el trabajo, convierte a PWG Raster;
     *  4) conserva media-col para 216 × 340 mm.
     */
    fun printSmart(
        printer: PrinterConfig,
        pdfFile: File,
        attributes: PrintAttributes?,
        copies: Int,
        jobName: String,
        tempDir: File
    ): Result {
        val media = attributes?.mediaSize ?: PaperStore.toMediaSize(
            CustomPaper(
                id = "MDS_OFICIO_MEXICO_216X340",
                label = "Oficio México 216 × 340 mm",
                widthMm = 216.0,
                heightMm = 340.0,
                builtIn = true
            )
        )

        val (caps, probeError) = getCapabilities(printer)
        val diagnostic = StringBuilder()
        if (caps != null) {
            diagnostic.append("CAPACIDADES\n").append(caps.summary()).append("\n\n")
            if (caps.acceptingJobs == false) {
                return Result(
                    false,
                    "La impresora está conectada pero no está aceptando trabajos",
                    detail = diagnostic.toString()
                )
            }
        } else {
            diagnostic.append("No se pudieron leer capacidades: ${probeError?.message ?: "desconocido"}\n")
            if (probeError?.detail?.isNotBlank() == true) diagnostic.append(probeError.detail).append("\n")
        }

        // PDF es preferible cuando la impresora lo declara; si la consulta no pudo
        // completarse, lo probamos igualmente porque muchas impresoras lo aceptan.
        if (caps == null || caps.formats.isEmpty() || caps.supportsPdf) {
            val pdfResult = printDocument(
                printer = printer,
                document = pdfFile,
                documentFormat = "application/pdf",
                media = media,
                copies = copies,
                jobName = jobName
            )
            diagnostic.append("INTENTO PDF: ${pdfResult.message}\n")
            if (pdfResult.ok) {
                return pdfResult.copy(
                    formatUsed = "PDF",
                    detail = diagnostic.append(pdfResult.detail).toString().trim()
                )
            }

            // Si ni siquiera hubo respuesta IPP, cambiar de formato no arreglará
            // una IP/puerto/ruta incorrectos.
            if (pdfResult.ippStatus == null && caps == null) {
                return pdfResult.copy(detail = diagnostic.append(pdfResult.detail).toString().trim())
            }
        } else {
            diagnostic.append("PDF omitido: la impresora no declara application/pdf.\n")
        }

        val canTryPwg = caps == null || caps.formats.isEmpty() || caps.supportsPwg
        if (!canTryPwg) {
            return Result(
                false,
                "El trabajo PDF fue rechazado y la impresora no declara PWG Raster para reintentarlo",
                detail = diagnostic.append("Formatos declarados: ${caps?.formats?.joinToString() ?: "ninguno"}").toString()
            )
        }

        if (caps != null && caps.pwgTypes.isNotEmpty() && caps.pwgTypes.none { it.equals("sgray_8", true) }) {
            return Result(
                false,
                "La impresora admite PWG Raster, pero no el modo sGray 8 necesario en esta versión",
                detail = diagnostic.append("Tipos PWG: ${caps.pwgTypes.joinToString()}").toString()
            )
        }

        val dpi = choosePwgDpi(caps?.pwgResolutionsDpi.orEmpty())
        val rasterFile = File.createTempFile("mds_pwg_", ".pwg", tempDir)
        return try {
            val encoded = PwgRasterEncoder.encode(pdfFile, rasterFile, media, dpi)
            diagnostic.append("RASTER: ${encoded.message}\n")
            if (!encoded.ok) {
                return Result(false, encoded.message, formatUsed = "PWG Raster", detail = diagnostic.toString())
            }

            val pwgResult = printDocument(
                printer = printer,
                document = rasterFile,
                documentFormat = "image/pwg-raster",
                media = media,
                copies = copies,
                jobName = jobName
            )
            diagnostic.append("INTENTO PWG: ${pwgResult.message}\n")
            pwgResult.copy(
                formatUsed = "PWG Raster ${dpi}dpi",
                detail = diagnostic.append(pwgResult.detail).toString().trim()
            )
        } finally {
            runCatching { rasterFile.delete() }
        }
    }

    private fun choosePwgDpi(values: Set<Int>): Int {
        if (values.isEmpty()) return 300
        if (300 in values) return 300
        return values.minByOrNull { abs(it - 300) }?.coerceIn(150, 600) ?: 300
    }

    private fun getCapabilities(printer: PrinterConfig): Pair<Capabilities?, Result?> {
        val requested = listOf(
            "printer-name",
            "printer-make-and-model",
            "document-format-supported",
            "ipp-versions-supported",
            "job-creation-attributes-supported",
            "pwg-raster-document-type-supported",
            "pwg-raster-document-resolution-supported",
            "media-supported",
            "printer-is-accepting-jobs"
        )

        val body = buildIppRequest(0x000B) { out ->
            writeText(out, 0x47, "attributes-charset", "utf-8")
            writeText(out, 0x48, "attributes-natural-language", "es")
            writeText(out, 0x45, "printer-uri", ippUri(printer))
            requested.forEachIndexed { index, value ->
                writeText(out, 0x44, if (index == 0) "requested-attributes" else "", value)
            }
            out.writeByte(0x03)
        }

        val response = post(printer, body, null)
        if (!response.ippOk) {
            return null to response.asResult("No se pudieron consultar las capacidades IPP")
        }

        fun strings(name: String): Set<String> = response.attributes[name]
            .orEmpty().mapNotNull { it as? String }.toSet()

        val resolutions = response.attributes["pwg-raster-document-resolution-supported"]
            .orEmpty()
            .mapNotNull { value ->
                when (value) {
                    is ResolutionValue -> if (value.units == 3 && value.x == value.y) value.x else null
                    else -> null
                }
            }.toSet()

        val accepting = response.attributes["printer-is-accepting-jobs"]
            ?.firstOrNull() as? Boolean

        return Capabilities(
            printerName = strings("printer-name").firstOrNull().orEmpty(),
            makeModel = strings("printer-make-and-model").firstOrNull().orEmpty(),
            formats = strings("document-format-supported"),
            ippVersions = strings("ipp-versions-supported"),
            jobAttributes = strings("job-creation-attributes-supported"),
            pwgTypes = strings("pwg-raster-document-type-supported"),
            pwgResolutionsDpi = resolutions,
            media = strings("media-supported"),
            acceptingJobs = accepting
        ) to null
    }

    private data class ResolutionValue(val x: Int, val y: Int, val units: Int)

    private fun printDocument(
        printer: PrinterConfig,
        document: File,
        documentFormat: String,
        media: PrintAttributes.MediaSize,
        copies: Int,
        jobName: String
    ): Result {
        val body = buildIppRequest(0x0002) { out ->
            writeText(out, 0x47, "attributes-charset", "utf-8")
            writeText(out, 0x48, "attributes-natural-language", "es")
            writeText(out, 0x45, "printer-uri", ippUri(printer))
            writeText(out, 0x42, "requesting-user-name", "Android")
            writeText(out, 0x42, "job-name", jobName.take(120))
            writeText(out, 0x49, "document-format", documentFormat)
            writeBoolean(out, "ipp-attribute-fidelity", false)

            out.writeByte(0x02) // job-attributes-tag
            writeInteger(out, "copies", copies.coerceIn(1, 99))
            writeText(out, 0x44, "sides", "one-sided")

            val standard = standardMediaKeyword(media.id)
            if (standard != null) {
                writeText(out, 0x44, "media", standard)
            } else {
                val xHundredthMm = (media.widthMils * 2.54).roundToInt()
                val yHundredthMm = (media.heightMils * 2.54).roundToInt()
                writeMediaCol(out, xHundredthMm, yHundredthMm)
            }
            out.writeByte(0x03)
        }

        val response = post(printer, body, document)
        return response.asResult(
            successPrefix = "Trabajo aceptado",
            format = documentFormat
        )
    }

    private fun buildIppRequest(operationId: Int, body: (DataOutputStream) -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            // IPP/1.1 maximiza compatibilidad. Las impresoras IPP 2.x aceptan 1.1.
            out.writeByte(0x01)
            out.writeByte(0x01)
            out.writeShort(operationId)
            out.writeInt(requestId.getAndIncrement())
            out.writeByte(0x01) // operation-attributes-tag
            body(out)
        }
        return bytes.toByteArray()
    }

    private fun post(printer: PrinterConfig, ippHeader: ByteArray, file: File?): IppResponse {
        val startUrl = URL("http", printer.host, printer.port, printer.normalizedPath)
        return postUrl(startUrl, ippHeader, file, redirects = 0)
    }

    private fun postUrl(url: URL, ippHeader: ByteArray, file: File?, redirects: Int): IppResponse {
        var connection: HttpURLConnection? = null
        return try {
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8_000
                readTimeout = 120_000
                doOutput = true
                doInput = true
                useCaches = false
                instanceFollowRedirects = false
                setRequestProperty("Content-Type", "application/ipp")
                setRequestProperty("Accept", "application/ipp")
                setRequestProperty("Connection", "close")
                setRequestProperty("User-Agent", "MDS-Print-Android/2.1")
                val total = ippHeader.size.toLong() + (file?.length() ?: 0L)
                setFixedLengthStreamingMode(total)
            }

            connection.outputStream.use { output ->
                output.write(ippHeader)
                if (file != null) {
                    file.inputStream().buffered(128 * 1024).use { input ->
                        input.copyTo(output, 128 * 1024)
                    }
                }
            }

            val http = connection.responseCode
            if (http in listOf(301, 302, 307, 308) && redirects < 2) {
                val location = connection.getHeaderField("Location")
                if (!location.isNullOrBlank()) {
                    return postUrl(URL(url, location), ippHeader, file, redirects + 1)
                }
            }

            val stream = if (http in 200..299) connection.inputStream else connection.errorStream
            val responseBytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            if (http !in 200..299) {
                return IppResponse(
                    httpStatus = http,
                    error = "HTTP $http${connection.responseMessage?.let { " · $it" } ?: ""}"
                )
            }
            if (responseBytes.size < 8) {
                return IppResponse(httpStatus = http, error = "Respuesta IPP vacía o incompleta")
            }
            parseIpp(http, responseBytes)
        } catch (e: Exception) {
            IppResponse(error = "${e.javaClass.simpleName}: ${e.message ?: "sin detalle"}")
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseIpp(httpStatus: Int, bytes: ByteArray): IppResponse {
        return try {
            val input = DataInputStream(ByteArrayInputStream(bytes))
            val major = input.readUnsignedByte()
            val minor = input.readUnsignedByte()
            val status = input.readUnsignedShort()
            input.readInt() // request-id

            val attributes = linkedMapOf<String, MutableList<Any>>()
            var currentName = ""

            while (input.available() > 0) {
                val tag = input.readUnsignedByte()
                if (tag == 0x03) break // end-of-attributes-tag
                if (tag in 0x01..0x0F) {
                    currentName = ""
                    continue // delimiter/group tag
                }

                if (input.available() < 4) break
                val nameLen = input.readUnsignedShort()
                val nameBytes = ByteArray(nameLen)
                input.readFully(nameBytes)
                if (nameLen > 0) currentName = nameBytes.toString(Charsets.UTF_8)
                val valueLen = input.readUnsignedShort()
                if (valueLen > input.available()) break
                val valueBytes = ByteArray(valueLen)
                input.readFully(valueBytes)

                if (currentName.isBlank()) continue
                val value: Any = decodeValue(tag, valueBytes) ?: continue
                attributes.getOrPut(currentName) { mutableListOf() }.add(value)
            }

            IppResponse(
                httpStatus = httpStatus,
                ippStatus = status,
                version = "$major.$minor",
                attributes = attributes
            )
        } catch (e: Exception) {
            IppResponse(httpStatus = httpStatus, error = "Respuesta IPP no válida: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun decodeValue(tag: Int, value: ByteArray): Any? = when (tag) {
        0x21, 0x23 -> if (value.size == 4) DataInputStream(ByteArrayInputStream(value)).readInt() else null
        0x22 -> value.firstOrNull()?.toInt()?.let { it != 0 }
        0x32 -> if (value.size == 9) {
            val input = DataInputStream(ByteArrayInputStream(value))
            ResolutionValue(input.readInt(), input.readInt(), input.readUnsignedByte())
        } else null
        0x10 -> null // unsupported
        else -> runCatching { value.toString(Charsets.UTF_8) }.getOrNull()
    }

    private fun IppResponse.asResult(
        successPrefix: String,
        format: String? = null
    ): Result {
        if (ippOk) {
            return Result(
                ok = true,
                message = "$successPrefix · IPP ${version.ifBlank { "?" }}",
                ippStatus = ippStatus,
                formatUsed = format,
                detail = "HTTP $httpStatus · IPP ${statusHex(ippStatus ?: 0)} ${statusName(ippStatus ?: 0)}"
            )
        }

        if (error != null) {
            return Result(false, "No se pudo comunicar con la impresora: $error", detail = error)
        }

        val code = ippStatus
        val label = code?.let { statusName(it) }.orEmpty()
        val human = when (code) {
            0x0402 -> "La impresora requiere autenticación"
            0x0403 -> "No autorizado por la impresora"
            0x0404 -> "La impresora no puede realizar este trabajo"
            0x0406 -> "La ruta IPP no existe en esta impresora"
            0x0408 -> "El documento es demasiado grande"
            0x040A -> "La impresora no admite este formato de documento"
            0x040B -> "La impresora no admite algún atributo del trabajo (por ejemplo, el tamaño de papel)"
            0x0411 -> "La impresora considera que el documento tiene un formato incorrecto"
            0x0500 -> "Error interno de la impresora"
            0x0506 -> "La impresora está ocupada"
            0x0507 -> "La impresora ha cancelado temporalmente el servicio"
            else -> "La impresora rechazó el trabajo"
        }
        return Result(
            false,
            "$human · IPP ${statusHex(code ?: 0)}${if (label.isNotBlank()) " ($label)" else ""}",
            ippStatus = code,
            formatUsed = format,
            detail = "HTTP $httpStatus · IPP ${statusHex(code ?: 0)} $label"
        )
    }

    private fun statusName(status: Int): String = when (status) {
        in 0x0000..0x00FF -> "successful"
        0x0400 -> "client-error-bad-request"
        0x0401 -> "client-error-forbidden"
        0x0402 -> "client-error-not-authenticated"
        0x0403 -> "client-error-not-authorized"
        0x0404 -> "client-error-not-possible"
        0x0405 -> "client-error-timeout"
        0x0406 -> "client-error-not-found"
        0x0408 -> "client-error-request-value-too-long"
        0x040A -> "client-error-document-format-not-supported"
        0x040B -> "client-error-attributes-or-values-not-supported"
        0x0411 -> "client-error-document-format-error"
        0x0500 -> "server-error-internal-error"
        0x0501 -> "server-error-operation-not-supported"
        0x0502 -> "server-error-service-unavailable"
        0x0506 -> "server-error-busy"
        0x0507 -> "server-error-job-canceled"
        else -> ""
    }

    private fun ippUri(p: PrinterConfig): String {
        val host = if (p.host.contains(':') && !p.host.startsWith('[')) "[${p.host}]" else p.host
        return "ipp://$host:${p.port}${p.normalizedPath}"
    }

    private fun standardMediaKeyword(id: String): String? = when (id) {
        PrintAttributes.MediaSize.ISO_A4.id -> "iso_a4_210x297mm"
        PrintAttributes.MediaSize.ISO_A5.id -> "iso_a5_148x210mm"
        PrintAttributes.MediaSize.NA_LETTER.id -> "na_letter_8.5x11in"
        PrintAttributes.MediaSize.NA_LEGAL.id -> "na_legal_8.5x14in"
        else -> null
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

    private fun writeBoolean(out: DataOutputStream, name: String, value: Boolean) {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        out.writeByte(0x22)
        out.writeShort(nameBytes.size)
        out.write(nameBytes)
        out.writeShort(1)
        out.writeByte(if (value) 1 else 0)
    }

    private fun writeMediaCol(out: DataOutputStream, xHundredthMm: Int, yHundredthMm: Int) {
        // media-col = { media-size = { x-dimension, y-dimension } }
        writeEmptyValue(out, 0x34, "media-col")
        writeText(out, 0x4A, "", "media-size")
        writeEmptyValue(out, 0x34, "")
        writeText(out, 0x4A, "", "x-dimension")
        writeInteger(out, "", xHundredthMm)
        writeText(out, 0x4A, "", "y-dimension")
        writeInteger(out, "", yHundredthMm)
        writeEmptyValue(out, 0x37, "")
        writeEmptyValue(out, 0x37, "")
    }

    private fun writeEmptyValue(out: DataOutputStream, tag: Int, name: String) {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        out.writeByte(tag)
        out.writeShort(nameBytes.size)
        out.write(nameBytes)
        out.writeShort(0)
    }

    private fun statusHex(status: Int): String = "0x" + status.toString(16).padStart(4, '0')
}
