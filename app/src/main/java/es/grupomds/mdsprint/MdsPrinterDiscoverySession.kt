package es.grupomds.mdsprint

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.print.PrintAttributes
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrinterDiscoverySession

class MdsPrinterDiscoverySession(private val service: MdsPrintService) : PrinterDiscoverySession() {
    private val nsd = service.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    private val seen = linkedMapOf<String, PrinterConfig>()
    private var discovering = false

    private val listener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { stopNsd() }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

        override fun onServiceFound(info: NsdServiceInfo) {
            if (!info.serviceType.contains("_ipp._tcp")) return
            @Suppress("DEPRECATION")
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    val host = if (Build.VERSION.SDK_INT >= 34) {
                        resolved.hostAddresses.firstOrNull()?.hostAddress
                    } else {
                        @Suppress("DEPRECATION")
                        resolved.host?.hostAddress
                    } ?: return

                    val rp = resolved.attributes["rp"]?.toString(Charsets.UTF_8)?.trim()
                    val path = when {
                        rp.isNullOrBlank() -> "/ipp/print"
                        rp.startsWith('/') -> rp
                        else -> "/$rp"
                    }
                    val config = PrinterConfig(
                        name = resolved.serviceName.ifBlank { "IPP $host" },
                        host = host,
                        port = resolved.port.takeIf { it > 0 } ?: 631,
                        path = path
                    )
                    main.post { reportPrinter(config) }
                }
            })
        }
    }

    override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {
        PrinterStore(service).list().forEach { reportPrinter(it) }
        if (!discovering) {
            discovering = true
            runCatching { nsd.discoverServices("_ipp._tcp.", NsdManager.PROTOCOL_DNS_SD, listener) }
                .onFailure { discovering = false }
        }
    }

    override fun onStopPrinterDiscovery() {
        stopNsd()
    }

    override fun onValidatePrinters(printerIds: MutableList<PrinterId>) {
        printerIds.forEach { id ->
            PrinterStore.fromLocalId(id.localId)?.let { reportPrinter(it) }
        }
    }

    override fun onStartPrinterStateTracking(printerId: PrinterId) {
        PrinterStore.fromLocalId(printerId.localId)?.let { reportPrinter(it) }
    }

    override fun onStopPrinterStateTracking(printerId: PrinterId) = Unit

    override fun onDestroy() {
        stopNsd()
        seen.clear()
    }

    private fun stopNsd() {
        if (!discovering) return
        discovering = false
        runCatching { nsd.stopServiceDiscovery(listener) }
    }

    private fun reportPrinter(config: PrinterConfig) {
        if (isDestroyed) return
        val localId = PrinterStore.toLocalId(config)
        seen[localId] = config
        val printerId = service.generatePrinterId(localId)
        val displayName = if (config.name.startsWith("MDS · ")) config.name else "MDS · ${config.name}"
        val info = PrinterInfo.Builder(printerId, displayName, PrinterInfo.STATUS_IDLE)
            .setDescription("Oficio México 216 × 340 mm · IPP")
            .setCapabilities(buildCapabilities(printerId))
            .build()
        addPrinters(arrayListOf(info))
    }

    private fun buildCapabilities(printerId: PrinterId): PrinterCapabilitiesInfo {
        val builder = PrinterCapabilitiesInfo.Builder(printerId)
            .addMediaSize(PrintAttributes.MediaSize.ISO_A4, false)
            .addMediaSize(PrintAttributes.MediaSize.ISO_A5, false)
            .addMediaSize(PrintAttributes.MediaSize.NA_LETTER, false)
            .addMediaSize(PrintAttributes.MediaSize.NA_LEGAL, false)
            .addResolution(PrintAttributes.Resolution("300dpi", "300 dpi", 300, 300), true)
            .addResolution(PrintAttributes.Resolution("600dpi", "600 dpi", 600, 600), false)
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .setColorModes(
                PrintAttributes.COLOR_MODE_MONOCHROME or PrintAttributes.COLOR_MODE_COLOR,
                PrintAttributes.COLOR_MODE_COLOR
            )

        PaperStore(service).all().forEachIndexed { index, paper ->
            builder.addMediaSize(PaperStore.toMediaSize(paper), index == 0)
        }

        if (Build.VERSION.SDK_INT >= 23) {
            builder.setDuplexModes(PrintAttributes.DUPLEX_MODE_NONE, PrintAttributes.DUPLEX_MODE_NONE)
        }
        return builder.build()
    }
}
