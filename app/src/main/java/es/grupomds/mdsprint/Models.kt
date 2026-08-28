package es.grupomds.mdsprint

data class PrinterConfig(
    val name: String,
    val host: String,
    val port: Int = 631,
    val path: String = "/ipp/print"
) {
    val normalizedPath: String
        get() = if (path.startsWith('/')) path else "/$path"
}

data class CustomPaper(
    val id: String,
    val label: String,
    val widthMm: Double,
    val heightMm: Double,
    val builtIn: Boolean = false
)
