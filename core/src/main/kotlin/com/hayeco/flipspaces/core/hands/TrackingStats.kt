package com.hayeco.flipspaces.core.hands

/** Timings for one frame that went through hand detection. */
data class FrameSample(
    /** From the USB transfer that completed the frame to MediaPipe's result. */
    val captureToLandmarkMs: Long,
    val decodeMs: Long,
    /** From handing the image to MediaPipe to its result. */
    val inferenceMs: Long,
    val handPresent: Boolean,
    /** MediaPipe's handedness score for the hand, 0 when there was none. */
    val score: Float,
    /**
     * 0.5.25 (the `Tracking:` line said `GPU` with a 141 ms inference, while the
     * idle palm search ran on the CPU): what really ran this frame's inference ([TrackingStats.CPU_SEARCH],
     * [TrackingStats.GPU], [TrackingStats.CPU]); null takes the stats' [TrackingStats.delegate].
     */
    val ranOn: String? = null,
)

/** One window of [TrackingStats], logged and shown as a line on the setup screen. */
data class StatsSummary(
    val frames: Int,
    val trackedFps: Double,
    val cameraFps: Double,
    val latencyAvgMs: Double,
    val latencyMaxMs: Long,
    val decodeAvgMs: Double,
    val inferenceAvgMs: Double,
    /** Share of frames with a hand, 0 to 1. */
    val presence: Double,
    /** Mean score of the frames with a hand. */
    val scoreAvg: Double,
    /** Camera frames not detected on in this window: replaced while waiting (stale) or broken. */
    val dropped: Long,
    val delegate: String,
) {
    fun line(): String =
        "%.0f fps tracked of %.0f, latency %.0f ms (max %d), decode %.1f ms, inference %.1f ms, hand %.0f%% conf %.2f, dropped %d, %s".format(
            trackedFps, cameraFps, latencyAvgMs, latencyMaxMs, decodeAvgMs, inferenceAvgMs, presence * 100, scoreAvg, dropped, delegate,
        )
}

/**
 * Collects per frame timings and every [window] detected frames produces a [StatsSummary]
 * (about once a second at 30 fps). Called from the USB reader thread ([captured], [dropped]) and
 * from MediaPipe's result thread ([record]).
 */
class TrackingStats(val window: Int = 30) {
    init {
        require(window >= 1) { "Window must hold at least one frame" }
    }

    private val samples = ArrayList<FrameSample>(window)
    private var windowStartMs = -1L
    private var capturedInWindow = 0
    private var droppedInWindow = 0L

    var delegate: String = "CPU"

    @get:Synchronized
    var last: StatsSummary? = null
        private set

    @Synchronized
    fun captured(nowMs: Long) {
        if (windowStartMs < 0) windowStartMs = nowMs
        capturedInWindow++
    }

    @Synchronized
    fun dropped(count: Long = 1) {
        droppedInWindow += count
    }

    /** Adds one detected frame; returns a summary when the window is full, else null. */
    @Synchronized
    fun record(sample: FrameSample, nowMs: Long): StatsSummary? {
        if (windowStartMs < 0) windowStartMs = nowMs
        samples += sample
        if (samples.size < window) return null
        val seconds = ((nowMs - windowStartMs).coerceAtLeast(1)) / 1000.0
        val withHand = samples.filter { it.handPresent }
        val summary = StatsSummary(
            frames = samples.size,
            trackedFps = samples.size / seconds,
            cameraFps = capturedInWindow / seconds,
            latencyAvgMs = samples.map { it.captureToLandmarkMs }.average(),
            latencyMaxMs = samples.maxOf { it.captureToLandmarkMs },
            decodeAvgMs = samples.map { it.decodeMs }.average(),
            inferenceAvgMs = samples.map { it.inferenceMs }.average(),
            presence = withHand.size.toDouble() / samples.size,
            scoreAvg = if (withHand.isEmpty()) 0.0 else withHand.map { it.score.toDouble() }.average(),
            dropped = droppedInWindow,
            delegate = delegateLabel(samples, delegate),
        )
        last = summary
        samples.clear()
        windowStartMs = nowMs
        capturedInWindow = 0
        droppedInWindow = 0
        return summary
    }

    companion object {
        const val GPU = "GPU"
        const val CPU = "CPU"

        /** 0.5.25: the idle palm search's own CPU landmarker, while the Space has the GPU. */
        const val CPU_SEARCH = "CPU palm search"

        /**
         * 0.5.25: the window's delegate label from what ran each inference: the one name
         * when every frame ran on the same, else each with its frame count, the most first (`CPU palm search 18
         * and GPU 12`). A frame that does not say counts as [fallback], the runner's delegate, as before.
         */
        fun delegateLabel(samples: List<FrameSample>, fallback: String): String {
            if (samples.isEmpty()) return fallback
            val counts = samples.groupingBy { it.ranOn ?: fallback }.eachCount()
            if (counts.size == 1) return counts.keys.first()
            return counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .joinToString(" and ") { "${it.key} ${it.value}" }
        }
    }

    @Synchronized
    fun reset() {
        samples.clear()
        windowStartMs = -1
        capturedInWindow = 0
        droppedInWindow = 0
        last = null
    }
}
