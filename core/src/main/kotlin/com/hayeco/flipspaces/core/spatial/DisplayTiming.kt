package com.hayeco.flipspaces.core.spatial

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 0.5.12: the glasses' own display delay (from the phone's display output to light
 * on the glasses' panels), measured instead of 0.5.11's 8 ms guess.
 *
 * Nothing on the phone can see the glasses' photons and the One's stream does not report its
 * latency, so the calibration is guided: the Space shows
 * a line fixed in the room, Mike turns his head gently left and right and moves the delay until the
 * line stops swimming, then saves the run. A wrong delay e leaves a lag of about w x e during turns
 * (0.2 degrees at 40 deg/s and 5 ms), which is what the eye sees as swimming. Three runs make a
 * result: their median, kept only when every run is within [toleranceMs] of it.
 */
class DisplayDelayCalibration(
    val toleranceMs: Float = 2f,
    val runsNeeded: Int = 3,
    val minMs: Float = 0f,
    val maxMs: Float = 25f,
) {
    private val runs = ArrayList<Float>()

    val all: List<Float> get() = runs.toList()

    fun record(ms: Float) {
        runs += ms.coerceIn(minMs, maxMs)
        while (runs.size > runsNeeded) runs.removeAt(0)
    }

    fun clear() = runs.clear()

    /** The calibrated delay, or null until [runsNeeded] runs agree. */
    fun result(): Result? {
        if (runs.size < runsNeeded) return null
        val sorted = runs.sorted()
        val median = sorted[sorted.size / 2]
        val spread = runs.maxOf { abs(it - median) }
        return Result(median, spread, spread <= toleranceMs, runs.toList())
    }

    data class Result(val ms: Float, val spreadMs: Float, val repeatable: Boolean, val runs: List<Float>) {
        val wholeMs: Int get() = ms.roundToInt()

        fun line(): String = String.format(
            Locale.ROOT, "display delay %.1f ms from runs %s: %s", ms, runs.joinToString { "%.0f".format(Locale.ROOT, it) },
            if (repeatable) "repeatable within %.1f ms, saved".format(Locale.ROOT, spreadMs) else "runs %.1f ms apart, not saved; try again".format(Locale.ROOT, spreadMs),
        )
    }

    companion object {
        /** 0.5.11's guess, used until a calibration is saved. */
        const val GUESS_MS = 8

        /** Runs as stored ("8,9,8"); bad entries are skipped. */
        fun parse(stored: String?): List<Float> = stored.orEmpty().split(',').mapNotNull { it.trim().toFloatOrNull() }

        fun format(runs: List<Float>): String = runs.joinToString(",") { "%.1f".format(Locale.ROOT, it) }
    }
}

/**
 * 0.5.13: which glasses a display delay belongs to, from the display's EDID
 * (Display.getDeviceProductInfo: the monitor name and product id). Mike's glasses say "XREAL 1 Ultra",
 * product id 16656. A calibration made on one pair is never applied to another.
 */
data class GlassesId(val name: String, val productId: String) {
    /** The key the calibration is saved under; separators that the store uses are taken out. */
    val key: String get() = "${clean(name)}#${clean(productId)}"

    fun label(): String = if (productId.isBlank()) name else "$name (product id $productId)"

    companion object {
        private fun clean(s: String) = s.trim().replace('|', '/').replace('#', '/').replace('=', '/').replace(';', '/')

        /** From a key written by [key]; null if it is not one. */
        fun fromKey(key: String): GlassesId? {
            val i = key.lastIndexOf('#')
            if (i <= 0) return null
            return GlassesId(key.substring(0, i), key.substring(i + 1))
        }
    }
}

/**
 * 0.5.13: the display delay calibration saved per glasses ([GlassesId]): its runs
 * and, once three agree ([DisplayDelayCalibration]), the calibrated value. Stored as one string,
 * "key=runs;ms|key=runs;ms" with ms empty until calibrated, so it fits the app's preferences.
 */
class GlassesDelayProfiles(entries: Map<String, Profile> = emptyMap()) {
    data class Profile(val runs: List<Float>, val calibratedMs: Float?)

    private val map = LinkedHashMap(entries)

    val ids: List<GlassesId> get() = map.keys.mapNotNull { GlassesId.fromKey(it) }

    fun profile(id: GlassesId): Profile? = map[id.key]

    fun runs(id: GlassesId): List<Float> = map[id.key]?.runs.orEmpty()

    /** The calibrated delay for [id], or null: then the caller uses the guess. */
    fun calibratedMs(id: GlassesId): Float? = map[id.key]?.calibratedMs

    /**
     * Adds one run for [id]. Returns the calibration's result (null until [DisplayDelayCalibration.runsNeeded]
     * runs) and the log line: "display delay run saved at … ms" with the glasses named.
     */
    fun record(id: GlassesId, ms: Float, calibration: DisplayDelayCalibration = DisplayDelayCalibration(toleranceMs = TOLERANCE_MS)): Pair<DisplayDelayCalibration.Result?, String> {
        runs(id).forEach(calibration::record)
        calibration.record(ms)
        val r = calibration.result()
        val old = map[id.key]?.calibratedMs
        map[id.key] = Profile(calibration.all, if (r != null && r.repeatable) r.ms else old)
        val saved = String.format(Locale.ROOT, "display delay run saved at %.1f ms for %s (%d of %d)", ms.coerceIn(calibration.minMs, calibration.maxMs), id.label(), calibration.all.size, calibration.runsNeeded)
        return r to (if (r == null) saved else "$saved; ${r.line()}")
    }

    fun clearRuns(id: GlassesId) {
        val p = map[id.key] ?: return
        map[id.key] = p.copy(runs = emptyList())
    }

    /** Forgets [id]'s calibration (a delay set by hand): it is the guess again until calibrated. */
    fun forget(id: GlassesId) {
        val p = map[id.key] ?: return
        map[id.key] = p.copy(calibratedMs = null)
    }

    fun encode(): String = map.entries.joinToString("|") { (k, p) ->
        "$k=${DisplayDelayCalibration.format(p.runs)};${p.calibratedMs?.let { "%.1f".format(Locale.ROOT, it) } ?: ""}"
    }

    companion object {
        /** Three runs within 1 ms of their median (0.5.12 allowed 2). */
        const val TOLERANCE_MS = 1f

        fun decode(s: String?): GlassesDelayProfiles {
            val out = LinkedHashMap<String, Profile>()
            for (entry in s.orEmpty().split('|')) {
                val eq = entry.indexOf('=')
                if (eq <= 0) continue
                val key = entry.substring(0, eq)
                if (GlassesId.fromKey(key) == null) continue
                val parts = entry.substring(eq + 1).split(';')
                val runs = DisplayDelayCalibration.parse(parts.getOrNull(0))
                val ms = parts.getOrNull(1)?.trim()?.toFloatOrNull()?.takeIf { it.isFinite() }
                out[key] = Profile(runs, ms)
            }
            return GlassesDelayProfiles(out)
        }
    }
}

/**
 * 0.5.13, settled by a capture of the display modes the glasses offer: the glasses offer
 * the Flip 1920x1080 at 120 Hz in 2D and 3840x1080 at 60 Hz in SBS, with no 1200-row mode, so the
 * Space keeps 1920x1080 per eye at the fastest rate offered. Nothing is asked of the glasses; this is
 * the logged decision.
 */
object GlassesModeDecision {
    fun line(modes: List<Triple<Int, Int, Float>>, currentIndex: Int): String {
        val cur = modes.getOrNull(currentIndex)
        val all = modes.joinToString { "${it.first}x${it.second}@%.0f".format(Locale.ROOT, it.third) }
        val tall = modes.filter { it.second == 1200 || it.second == 2400 }
        val sbs = cur != null && cur.first >= 2 * cur.second
        val fastest = cur?.let { c -> modes.filter { it.first == c.first && it.second == c.second }.maxOf { it.third } }
        return buildString {
            append("mode decision: ")
            if (cur == null) {
                append("no current mode among $all")
                return@buildString
            }
            append("${cur.first}x${cur.second}@%.0f in %s".format(Locale.ROOT, cur.third, if (sbs) "SBS" else "2D"))
            append(if (fastest != null && cur.third + 0.5f >= fastest) ", the fastest offered at this size" else ", %.0f Hz is offered at this size".format(Locale.ROOT, fastest))
            append(
                if (tall.isEmpty()) "; no 1200-row mode is offered, so 1920x1080 per eye is kept and the glasses scale it (checked by eye with the 1-px checkerboard)"
                else "; a 1200-row mode is offered (${tall.joinToString { "${it.first}x${it.second}@%.0f".format(Locale.ROOT, it.third) }}), not used in 0.5.13",
            )
            append(" (modes $all)")
        }
    }
}

/**
 * 0.5.12: the highest refresh the glasses offer at the picture size in use. A mode
 * switch blanks the glasses for a moment, so it is asked once, and only when it raises the rate.
 */
object RefreshChoice {
    /**
     * Index into [modes] (width, height, Hz) of the same size as [currentIndex] at the highest
     * refresh, or null when the current mode already is the fastest at its size.
     */
    fun fastest(modes: List<Triple<Int, Int, Float>>, currentIndex: Int): Int? {
        val cur = modes.getOrNull(currentIndex) ?: return null
        val best = modes.withIndex().filter { it.value.first == cur.first && it.value.second == cur.second }
            .maxByOrNull { it.value.third } ?: return null
        return best.index.takeIf { best.value.third > cur.third + 0.5f }
    }

    /**
     * The mode to keep asked for: the fastest at the current size, the current one included once
     * Android has switched to it (so the request stays put); null when the size has one rate only.
     */
    fun fastestAtSize(modes: List<Triple<Int, Int, Float>>, currentIndex: Int): Int? {
        val cur = modes.getOrNull(currentIndex) ?: return null
        val same = modes.withIndex().filter { it.value.first == cur.first && it.value.second == cur.second }
        if (same.map { it.value.third.toInt() }.distinct().size < 2) return null
        return same.maxByOrNull { it.value.third }!!.index
    }

    /** The log line on the glasses' modes and what was asked. */
    fun line(modes: List<Triple<Int, Int, Float>>, currentIndex: Int, picked: Int?): String {
        val cur = modes.getOrNull(currentIndex)
        val all = modes.joinToString { "${it.first}x${it.second}@%.0f".format(Locale.ROOT, it.third) }
        return when {
            cur == null -> "refresh: no current mode among $all"
            picked == null -> "refresh: %.0f Hz is the fastest the glasses offer at ${cur.first}x${cur.second} (modes $all)".format(Locale.ROOT, cur.third)
            else -> "refresh: asking for %.0f Hz at ${cur.first}x${cur.second} (was %.0f Hz; modes $all)".format(Locale.ROOT, modes[picked].third, cur.third)
        }
    }
}

/** 0.5.12: one line on the head chain's timing, logged with the perf line: what items 1, 2, 3 and 5 are judged by. */
data class HeadTimingLine(
    val refreshHz: Float,
    val lateDraw: Boolean,
    val poseToPhotonsP50Ms: Double,
    val poseToPhotonsP95Ms: Double,
    val imuAgeP50Ms: Double,
    val imuAgeP95Ms: Double,
    val wakeLateP99Ms: Double,
    val glassesDelayMs: Float,
    val calibrated: Boolean,
    /** 0.5.13: why the frame is drawn at its start when it is ([GlassesVsync.reason]). */
    val offReason: String? = null,
    /** 0.5.13, item 24: the glasses the calibration was saved for. */
    val glasses: String? = null,
    /** 0.5.13, item 23: where the vsyncs come from ([GlassesVsync.Route]). */
    val vsyncRoute: String? = null,
) {
    fun line(): String = String.format(
        Locale.ROOT,
        "head timing: %.0f Hz, %s; pose to photons p50 %.1f ms p95 %.1f ms (glasses delay %.1f ms, %s); newest IMU sample at the pose p50 %.1f ms p95 %.1f ms old; wake late p99 %.2f ms",
        refreshHz,
        if (lateDraw) "late draw" + (vsyncRoute?.let { " ($it)" } ?: "") else "drawn at frame start (${offReason ?: "no vsync timeline for this display"})",
        poseToPhotonsP50Ms, poseToPhotonsP95Ms,
        glassesDelayMs, if (calibrated) "calibrated" + (glasses?.let { " for $it" } ?: "") else "the 8 ms guess", imuAgeP50Ms, imuAgeP95Ms, wakeLateP99Ms,
    )
}

/**
 * 0.5.12: how the IMU reaches the reader. The One sends a report a millisecond; the
 * shell pipe delivered them in reads about 16 ms apart in 0.5.11. Each read that brought reports
 * is noted here, and the line says whether the reader sees a steady stream or bursts.
 */
class ImuReadGaps(window: Int = 2000) {
    private val gaps = RollingStats(window)
    private val perRead = RollingStats(window)
    private var lastNs = 0L

    fun add(nowNs: Long, reports: Int) {
        if (lastNs != 0L) gaps.add((nowNs - lastNs) / 1e6)
        lastNs = nowNs
        perRead.add(reports.toDouble())
    }

    val gapP95Ms: Double get() = gaps.percentile(0.95)

    /** A steady stream: reads come at most 3 ms apart, 95 % of the time. */
    val steady: Boolean get() = gaps.count > 0 && gapP95Ms <= 3.0

    fun line(): String = String.format(
        Locale.ROOT,
        "a read every %.1f ms (p50), %.1f ms (p95), at most %.1f ms; %.0f reports a read (p50), %.0f (p95): %s",
        gaps.median(), gapP95Ms, gaps.percentile(1.0), perRead.median(), perRead.percentile(0.95),
        if (steady) "a steady stream" else "bursts",
    )

    fun reset() {
        gaps.clear()
        perRead.clear()
        lastNs = 0L
    }
}
