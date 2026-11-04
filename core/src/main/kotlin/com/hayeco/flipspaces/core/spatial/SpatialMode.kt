package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.ShizukuStatus

/** Where the head tracker got to, step by step, with the exact error when a step failed. */
sealed interface HeadTrackingStatus {
    val summary: String

    data object Off : HeadTrackingStatus {
        override val summary = "Head tracking is off."
    }

    data object Searching : HeadTrackingStatus {
        override val summary = "Looking for the glasses' USB network."
    }

    /** No Android network reaches the glasses. [networks] lists what Android does have. */
    data class NoNetwork(val networks: List<String>) : HeadTrackingStatus {
        override val summary =
            "No USB network to the glasses. Android lists: " + (networks.ifEmpty { listOf("no networks") }.joinToString("; ")) +
                ". One UI may not bring up the glasses' NCM or ECM interface."
    }

    data class Connecting(val network: String) : HeadTrackingStatus {
        override val summary = "Connecting to ${OneImuProtocol.HOST}:${OneImuProtocol.STREAM_PORT} over $network."
    }

    /** The TCP connection failed: [error] is the exception class and message, word for word. */
    data class ConnectFailed(val network: String, val error: String) : HeadTrackingStatus {
        override val summary = "TCP connect to ${OneImuProtocol.HOST}:${OneImuProtocol.STREAM_PORT} over $network failed: $error"
    }

    /** Connected, but no sensor report arrived. */
    data class NoReports(val network: String, val seconds: Int, val bytes: Long, val counts: FramerCounts, val hint: String = ONE_FAMILY_HINT) : HeadTrackingStatus {
        override val summary = "Connected over $network but no sensor reports in $seconds s ($bytes bytes received, " +
            "${counts.badLength} frames of another length, ${counts.unknownType} of another type). $hint"
    }

    /** 1.0.1: Air family glasses found on USB, but Android has not given Pocket XR access to them yet. */
    data class UsbPermission(val glasses: String) : HeadTrackingStatus {
        override val summary = "Found the $glasses on USB; waiting for USB access for head tracking. Allow Pocket XR in Android's prompt " +
            "(unplug and plug the glasses in again to see it again). The Space stays head locked until then."
    }

    /** 1.0.1: the Air family's motion sensor could not be opened over USB: [error] word for word. */
    data class UsbFailed(val glasses: String, val error: String) : HeadTrackingStatus {
        override val summary = "Could not open the $glasses' motion sensor over USB: $error"
    }

    companion object {
        const val ONE_FAMILY_HINT = "This firmware may not serve the One family stream."
        const val AIR_FAMILY_HINT = "The glasses' motion sensor did not answer the start command over USB HID."
    }

    data class Streaming(val network: String, val rateHz: Double) : HeadTrackingStatus {
        // 0.5.48: built when read, not with every status (the head thread made one a report, 1000 a second).
        override val summary: String get() = "Streaming over $network at %.0f reports a second.".format(rateHz)
    }

    /** The stream stopped after it had worked. */
    data class Lost(val network: String, val error: String) : HeadTrackingStatus {
        override val summary = "Lost the sensor stream over $network: $error. Retrying."
    }

    /**
     * 0.5.6: no glasses network has a route to the glasses' address; nothing is tried until the
     * network changes or the glasses are plugged in again.
     */
    data class NoRoute(val networks: List<String>) : HeadTrackingStatus {
        override val summary = "No route to ${OneImuProtocol.HOST} on " + networks.ifEmpty { listOf("any glasses network") }.joinToString(", ") +
            ". Not retrying until the glasses' network changes or they are plugged in again; the Space stays head locked."
    }

    /** 0.5.6: the last round failed for [why]; the next try is in [inSeconds] (backoff up to a minute). */
    data class RetryLater(val why: String, val inSeconds: Int) : HeadTrackingStatus {
        override val summary = "$why Next try in $inSeconds s."
    }

    val tracking: Boolean get() = this is Streaming
}

/**
 * 0.5.22 (at the unplug the stream "ended after 263.049 s: Connected ... but no sensor
 * reports in 263 s (33638154 bytes received ...)", after a steady 1000 Hz stream all session): a stream
 * that goes quiet. "No sensor reports" is kept for a stream that never delivered one; a stream that worked
 * and then stopped is [HeadTrackingStatus.Lost], with how long it has been silent.
 */
object HeadSilence {
    fun status(
        network: String, everStreamed: Boolean, silentS: Long, sinceStartS: Int, bytes: Long, counts: FramerCounts,
        hint: String = HeadTrackingStatus.ONE_FAMILY_HINT,
    ): HeadTrackingStatus =
        if (everStreamed) {
            HeadTrackingStatus.Lost(network, "no sensor report for $silentS s after it had streamed ($bytes bytes received in $sinceStartS s)")
        } else {
            HeadTrackingStatus.NoReports(network, sinceStartS, bytes, counts, hint)
        }
}

enum class SpatialMode(val label: String) {
    SPATIAL("Spatial: app panels stay put as you turn your head"),
    HEAD_LOCKED("Head locked: app panels in front of you, turned with the trackpad"),
    FLAT("Flat: apps open as DeX windows on the glasses"),
}

data class ModeChoice(val mode: SpatialMode, val reasons: List<String>)

/** Picks the best mode for what is available and says why the better ones are not. */
object SpatialModeChooser {
    fun choose(head: HeadTrackingStatus, shizuku: ShizukuStatus): ModeChoice {
        val reasons = mutableListOf<String>()
        if (shizuku != ShizukuStatus.READY) {
            reasons += "${shizuku.label}: app panels need Shizuku to create their virtual displays."
            return ModeChoice(SpatialMode.FLAT, reasons)
        }
        if (!head.tracking) {
            reasons += "No head tracking: ${head.summary}"
            return ModeChoice(SpatialMode.HEAD_LOCKED, reasons)
        }
        return ModeChoice(SpatialMode.SPATIAL, reasons)
    }
}

/** One five second window of renderer timings. */
data class PerfSummary(
    val fps: Double,
    val frameCpuMs: Double,
    val panels: List<PanelPerf>,
) {
    fun line(): String = "%.0f fps, frame %.2f ms CPU".format(fps, frameCpuMs) +
        panels.joinToString("") { "; panel ${it.panelId}: %.0f fps, %.2f ms".format(it.fps, it.costMs) }
}

data class PanelPerf(val panelId: Int, val fps: Double, val costMs: Double)

/**
 * Counts scene frames and, per panel, new app frames and the CPU time spent latching and drawing
 * them. Called from the GL thread only.
 */
class SpacePerf(private val windowNs: Long = 5_000_000_000L) {
    private var start = Long.MIN_VALUE
    private var frames = 0
    private var frameNs = 0L
    private val panelFrames = HashMap<Int, Int>()
    private val panelNs = HashMap<Int, Long>()

    var last: PerfSummary? = null
        private set

    fun panel(id: Int, costNs: Long, newFrame: Boolean) {
        panelNs[id] = (panelNs[id] ?: 0L) + costNs
        if (newFrame) panelFrames[id] = (panelFrames[id] ?: 0) + 1
    }

    /** Ends a frame. Returns a summary when a window completes. */
    fun frame(nowNs: Long, costNs: Long): PerfSummary? {
        if (start == Long.MIN_VALUE) start = nowNs
        frames++
        frameNs += costNs
        val elapsed = nowNs - start
        if (elapsed < windowNs) return null
        val seconds = elapsed / 1e9
        val ids = (panelNs.keys + panelFrames.keys).sorted()
        val summary = PerfSummary(
            fps = frames / seconds,
            frameCpuMs = frameNs / 1e6 / frames,
            panels = ids.map { PanelPerf(it, (panelFrames[it] ?: 0) / seconds, (panelNs[it] ?: 0L) / 1e6 / frames) },
        )
        start = nowNs
        frames = 0
        frameNs = 0
        panelFrames.clear()
        panelNs.clear()
        last = summary
        return summary
    }
}
