package com.hayeco.flipspaces.core.spatial

/**
 * 0.5.91 (a Glasses report button): one plain text file a tester attaches to
 * a GitHub issue: the detected model, what the glasses report to Android, and what worked and what did not. Nothing in
 * it identifies the person (the glasses' serial is shortened to its last four characters).
 */
data class GlassesReport(
    val appVersion: String,
    val phone: String,
    val android: String,
    val displays: List<Display>,
    val usb: List<Usb>,
    val controlLink: String,
    val serial: String?,
    val stereoState: Int?,
    val edidIndex: Int?,
    val headTracking: String,
    val headTrackingWorks: Boolean,
    val calibration: String,
    val hands: String,
    val timeUtc: String,
    /** 1.1.12: the newest PocketXR-Tracking line ([TrackingBeat]), null before one. */
    val tracking: String? = null,
) {
    data class Display(val name: String, val productId: String?, val modes: List<String>, val current: String)
    /**
     * 1.1.10: [ifaces] keeps each interface's class too (a HID interface often has no name, so the names alone missed it);
     * [infos] falls back to the names for a report built without them.
     */
    data class Usb(val vendorId: Int, val productId: Int, val name: String, val interfaces: List<String>, val ifaces: List<UsbInterfaceInfo> = emptyList()) {
        val infos: List<UsbInterfaceInfo> get() = ifaces.ifEmpty { interfaces.map(UsbInterfaceInfo::named) }
    }

    /** The model, from the first display that names one, else from the USB product. */
    val model: GlassesModel
        get() {
            val xrealUsb = usb.firstOrNull { it.vendorId == XREAL_VENDOR }?.productId
            displays.forEach { d ->
                val m = GlassesModel.detect(GlassesId(d.name, d.productId.orEmpty()), xrealUsb)
                if (m != GlassesModel.UNKNOWN) return m
            }
            return GlassesModel.detect(null, xrealUsb)
        }

    fun text(): String = buildString {
        appendLine("Pocket XR glasses report")
        appendLine("Made $timeUtc (UTC), Pocket XR $appVersion, $phone, Android $android")
        appendLine()
        appendLine("Model: ${model.line()}")
        // 0.5.95: a setting the glasses themselves need, so a tester checks it before reporting a problem.
        model.setupHint?.let { appendLine("Check on the glasses: $it") }
        appendLine()
        appendLine("Displays (not the phone's own):")
        if (displays.isEmpty()) appendLine("- none: Android shows no glasses display")
        displays.forEach { d ->
            appendLine("- \"${d.name}\"${d.productId?.let { " (EDID product $it)" } ?: ""}: now ${d.current}; modes ${d.modes.joinToString(", ")}")
        }
        appendLine()
        appendLine("USB devices from XREAL:")
        val xreal = usb.filter { it.vendorId == XREAL_VENDOR }
        if (xreal.isEmpty()) appendLine("- none")
        xreal.forEach { u ->
            appendLine("- %04X:%04X \"%s\": %s".format(java.util.Locale.ROOT, u.vendorId, u.productId, u.name, u.infos.joinToString(", ") { it.label() }.ifEmpty { "no interfaces listed" }))
            // 1.1.10: how these glasses carry head tracking, so a report from HID glasses says so at once.
            appendLine("  Head tracking link: ${HeadImuTransport.choose(u.vendorId, u.productId, u.infos).label}")
        }
        appendLine()
        appendLine("What worked:")
        appendLine("- Head tracking: ${if (headTrackingWorks) "yes" else "no"} ($headTracking)")
        tracking?.let { appendLine("- Tracking now: $it") }
        appendLine("- Control link: $controlLink")
        appendLine("- Glasses serial: ${serial?.let { "ends ${it.takeLast(4)}" } ?: "not read"}")
        appendLine("- 2D / 3D state: ${when (stereoState) { null -> "not read"; 1 -> "3D side by side (1)"; 0 -> "2D (0)"; else -> "$stereoState" }}")
        appendLine("- EDID index: ${edidIndex ?: "not read"}")
        appendLine("- Factory calibration: $calibration")
        appendLine("- Hand tracking: $hands")
    }

    companion object {
        const val XREAL_VENDOR = 0x3318
    }
}
