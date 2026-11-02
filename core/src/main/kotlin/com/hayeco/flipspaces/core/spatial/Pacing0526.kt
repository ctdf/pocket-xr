package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.pointer.PadSignal
import com.hayeco.flipspaces.core.pointer.PointerSignal
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * 0.5.26 (Mike: "everything is a bit choppy, not just videos; the whole picture"): how evenly the Space's frames reach
 * the glasses' refreshes. Each frame drawn gives the refresh it is shown on (its planned present, a refresh later when
 * it missed its deadline). A refresh with no new frame shows the one before again (a repeat); two frames for one
 * refresh means the first was never seen (a drop). The 5 s line is the target's measure: a fresh frame every refresh
 * with even intervals, under [REPEAT_TARGET] repeats.
 */
class RefreshPacing {
    private var lastIndex = Long.MIN_VALUE
    private var periodNs = 0L

    var refreshes = 0L
        private set
    var fresh = 0L
        private set
    var repeats = 0L
        private set
    var drops = 0L
        private set
    private var longestGap = 0L

    /** A frame for the refresh at [presentNs] on a display of [periodNs]; [missed] puts it a refresh later. */
    fun frame(presentNs: Long, periodNs: Long, missed: Boolean = false) {
        if (periodNs <= 0L) return
        if (periodNs != this.periodNs) {
            this.periodNs = periodNs
            lastIndex = Long.MIN_VALUE
        }
        val index = (presentNs.toDouble() / periodNs).roundToLong() + if (missed) 1 else 0
        val last = lastIndex
        lastIndex = maxOf(index, last)
        if (last == Long.MIN_VALUE) {
            refreshes++
            fresh++
            return
        }
        val gap = index - last
        when {
            gap <= 0L -> drops++
            gap > MAX_GAP -> {
                // A pause (the Space away, a new mode): not counted as repeats.
                refreshes++
                fresh++
            }
            else -> {
                refreshes += gap
                fresh++
                repeats += gap - 1
                longestGap = maxOf(longestGap, gap)
            }
        }
    }

    val repeatShare: Double get() = if (refreshes == 0L) 0.0 else repeats.toDouble() / refreshes

    fun line(): String {
        val s = String.format(
            Locale.ROOT,
            "space pacing: %d refreshes, %d with a fresh frame, %d repeated (%.1f %%; target under %.0f %%), %d frames dropped (two for one refresh), the longest wait %d refreshes",
            refreshes, fresh, repeats, repeatShare * 100, REPEAT_TARGET * 100, drops, longestGap,
        )
        refreshes = 0
        fresh = 0
        repeats = 0
        drops = 0
        longestGap = 0
        return s
    }

    companion object {
        const val REPEAT_TARGET = 0.01
        const val MAX_GAP = 30L
    }
}

/**
 * 0.5.26: one window's frames: when its app's frames arrived (each image's own timestamp) and when the Space showed
 * them. The 5 s line gives the app's rate, the distinct frames shown, the frames replaced before a refresh showed them,
 * and the shown intervals' evenness (p50, p95, the share more than a quarter off the p50). A 60 fps video should read
 * 60 shown, about 16.7 ms apart; a 30 fps one 30, 33.3 apart.
 */
class PanelPacing {
    private var lastArrivalNs = 0L
    private var lastShownNs = 0L
    private val arrivals = RollingStats(600)
    private val shown = ArrayList<Double>()
    private var arrived = 0L
    private var shownN = 0L

    /** An image from the app, its own timestamp [ns]. */
    fun arrived(ns: Long) {
        if (lastArrivalNs != 0L && ns > lastArrivalNs) arrivals.add((ns - lastArrivalNs) / 1e6)
        lastArrivalNs = ns
        arrived++
    }

    /** The Space showed a new image of this window on the refresh at [presentNs]. */
    fun shownAt(presentNs: Long) {
        if (lastShownNs != 0L && presentNs > lastShownNs && shown.size < MAX_KEPT) shown.add((presentNs - lastShownNs) / 1e6)
        lastShownNs = presentNs
        shownN++
    }

    private fun shownPercentile(p: Double): Double {
        if (shown.isEmpty()) return 0.0
        val sorted = shown.sorted()
        return sorted[(kotlin.math.ceil(p * sorted.size).toInt().coerceIn(1, sorted.size)) - 1]
    }

    /** The share of shown intervals more than a quarter off their median. */
    fun unevenShare(): Double {
        val m = shownPercentile(0.5)
        if (shown.isEmpty() || m <= 0.0) return 0.0
        return shown.count { abs(it - m) > m / 4 }.toDouble() / shown.size
    }

    /** Distinct pictures shown since the last [line]. */
    val shownCount: Long get() = shownN

    fun line(label: String, seconds: Double): String {
        val s = seconds.coerceAtLeast(1e-3)
        val out = String.format(
            Locale.ROOT,
            "window %s pacing: the app sent %.1f frames a second (interval p50 %.1f ms p95 %.1f), %.1f distinct shown a second (interval p50 %.1f ms p95 %.1f, %.0f %% more than a quarter off)",
            label, arrived / s, arrivals.median(), arrivals.percentile(0.95), shownN / s, shownPercentile(0.5), shownPercentile(0.95), unevenShare() * 100,
        )
        arrived = 0
        shownN = 0
        arrivals.clear()
        shown.clear()
        return out
    }

    companion object {
        const val MAX_KEPT = 4000
    }
}

/**
 * 0.5.26: which waiting picture of a window each refresh shows, by the picture's own timestamp.
 *
 * A window's pictures come evenly spaced in their own time (the app's vsync, 16.7 ms at 60 Hz) but reach the Space a
 * few milliseconds later, with jitter. Copying the newest on each frame races that jitter against the moment the Space
 * looks: when the two are close, a picture lands one refresh early or late at random, and a 60 fps video on a 120 Hz
 * Space shows for 1, 2 or 3 refreshes instead of always 2. Here each refresh at [pick]'s presentNs shows the newest
 * picture with a timestamp at least [delayNs] before it; the delay is the longest the pictures have needed to arrive
 * (p95, plus [MARGIN_NS]) plus how far ahead of its refresh the Space takes them, raised at once when more is needed
 * and eased down slowly, so pictures fall on the refreshes in their own even rhythm. Timestamps on another clock (more
 * than a second off) fall back to the newest, as before.
 */
class PanelLatch {
    private val lagsMs = RollingStats(240)
    private var sinceSort = 0
    private var lagP95Ns = 0L

    /** The delay in use, ns: a picture is due on the first refresh at least this long after its timestamp. */
    var delayNs = 0L
        private set

    /** Drain thread: a picture with [timestampNs] (its own time) arrived at [arrivedNs] (System.nanoTime). */
    fun arrived(timestampNs: Long, arrivedNs: Long) {
        val lag = arrivedNs - timestampNs
        if (lag !in 0L..MAX_LAG_NS) return
        lagsMs.add(lag / 1e6)
        if (++sinceSort >= 15 || lagsMs.count < 15) {
            sinceSort = 0
            lagP95Ns = (lagsMs.percentile(0.95) * 1e6).toLong()
        }
    }

    /**
     * Render thread, at [latchNs]: of the waiting pictures' [timestamps] (oldest first), the index to show on the refresh
     * at [presentNs], or null to keep the one shown (none is due yet).
     */
    fun pick(timestamps: List<Long>, presentNs: Long, latchNs: Long): Int? {
        if (timestamps.isEmpty()) return null
        if (timestamps.any { abs(latchNs - it) > CLOCK_LIMIT_NS } || lagsMs.count == 0) return timestamps.size - 1
        val need = ((presentNs - latchNs).coerceAtLeast(0L) + lagP95Ns + MARGIN_NS).coerceAtMost(MAX_DELAY_NS)
        delayNs = if (need > delayNs) need else maxOf(need, delayNs - FALL_NS)
        val dueBy = presentNs - delayNs
        val i = timestamps.indexOfLast { it <= dueBy }
        return i.takeIf { it >= 0 }
    }

    companion object {
        const val MARGIN_NS = 1_000_000L
        const val FALL_NS = 50_000L
        const val MAX_LAG_NS = 200_000_000L
        const val MAX_DELAY_NS = 100_000_000L
        const val CLOCK_LIMIT_NS = 1_000_000_000L
    }
}

/**
 * 0.5.26 (the trackpad's moves come at the cover screen's 60 Hz, so on a 120 Hz Space the cursor and a held
 * window moved on every other refresh, unevenly): the trackpad's relative moves spread over the Space's refreshes.
 *
 * Moves ([PadSignal.Move], [PointerSignal.MoveBy]) are summed as they come; each refresh releases the share of what
 * waits that brings it to nothing by the time the next move is due, so a 60 Hz finger on a 120 Hz Space moves the
 * cursor half each refresh instead of all of it every other one. Nothing is lost or reordered: any other signal (a
 * press, a release, a scroll) first releases every move before it. When moves come as often as the refreshes (side by
 * side at 60 Hz), or the finger stops for [STOP_NS], everything waiting goes at once. Adds at most one move's period of
 * delay (8 ms on average at 60 Hz input).
 */
class MoveSpreader {
    private var padDx = 0f
    private var padDy = 0f
    private var padYaw = 0f
    private var padPitch = 0f
    private var byDx = 0f
    private var byDy = 0f
    private var lastMoveNs = 0L
    private val intervals = ArrayDeque<Long>()

    /** The moves' period, measured (the median of the last few), or 0 before any. */
    var inputPeriodNs = 0L
        private set

    val waiting: Boolean get() = padDx != 0f || padDy != 0f || padYaw != 0f || padPitch != 0f || byDx != 0f || byDy != 0f

    /**
     * The signals [incoming] (in arrival order, taken this refresh at [nowNs]) as this refresh applies them, on a Space
     * refreshing every [framePeriodNs].
     */
    fun take(incoming: List<PointerSignal>, nowNs: Long, framePeriodNs: Long): List<PointerSignal> {
        val out = ArrayList<PointerSignal>(incoming.size + 1)
        var moved = false
        for (s in incoming) when (s) {
            is PadSignal.Move -> {
                padDx += s.dx; padDy += s.dy; padYaw += s.yawDeg; padPitch += s.pitchDeg
                moved = true
            }
            is PointerSignal.MoveBy -> {
                byDx += s.dx; byDy += s.dy
                moved = true
            }
            else -> {
                flush(out, 1f)
                out += s
            }
        }
        if (moved) {
            if (lastMoveNs != 0L) {
                val gap = nowNs - lastMoveNs
                if (gap in 1..STOP_NS) {
                    intervals.addLast(gap)
                    while (intervals.size > 8) intervals.removeFirst()
                    inputPeriodNs = intervals.sorted()[intervals.size / 2]
                }
            }
            lastMoveNs = nowNs
        }
        if (!waiting) return out
        val period = inputPeriodNs
        val share = when {
            framePeriodNs <= 0L || period <= 0L -> 1f
            period <= framePeriodNs * 6 / 5 -> 1f
            nowNs - lastMoveNs >= minOf(STOP_NS, period * 2) -> 1f
            else -> {
                val left = (lastMoveNs + period - nowNs).coerceAtLeast(framePeriodNs)
                (framePeriodNs.toFloat() / left).coerceIn(0f, 1f)
            }
        }
        flush(out, share)
        return out
    }

    private fun flush(out: MutableList<PointerSignal>, share: Float) {
        if (padDx != 0f || padDy != 0f || padYaw != 0f || padPitch != 0f) {
            out += PadSignal.Move(padDx * share, padDy * share, padYaw * share, padPitch * share)
            padDx -= padDx * share; padDy -= padDy * share; padYaw -= padYaw * share; padPitch -= padPitch * share
            if (share >= 1f) { padDx = 0f; padDy = 0f; padYaw = 0f; padPitch = 0f }
        }
        if (byDx != 0f || byDy != 0f) {
            out += PointerSignal.MoveBy(byDx * share, byDy * share)
            byDx -= byDx * share; byDy -= byDy * share
            if (share >= 1f) { byDx = 0f; byDy = 0f }
        }
    }

    fun reset() {
        padDx = 0f; padDy = 0f; padYaw = 0f; padPitch = 0f; byDx = 0f; byDy = 0f
        lastMoveNs = 0L
        intervals.clear()
        inputPeriodNs = 0L
    }

    companion object {
        /** A finger still this long has stopped: what waits goes at once. */
        const val STOP_NS = 40_000_000L
    }
}

/**
 * 0.5.26 (in scene mode 1 the glasses ask `fps:60_90`, a 60 Hz DisplayPort input on 90 Hz panels):
 * how a phone rate lands on the glasses' panel rate. 60 frames on 90 refreshes show each frame for 2, then 1, then 2
 * refreshes (22.2 and 11.1 ms): even when every frame is on time, anything moving on the picture judders. The even rate
 * is the largest that both divide ([contentHz], 30 for 60 into 90: every frame for exactly 3 refreshes). The glasses'
 * own warp turns each refresh to their newest pose, so the head stays smooth at 90 Hz whatever this rate is.
 */
object GlassesCadence {
    /** How many of the glasses' refreshes each of the phone's frames is shown for, over one whole cycle. */
    fun pattern(inputHz: Int, panelHz: Int): List<Int> {
        if (inputHz <= 0 || panelHz <= 0) return emptyList()
        val g = gcd(inputHz, panelHz)
        val frames = inputHz / g
        val refreshes = panelHz / g
        // Frame k is shown from the first refresh at or after its arrival to the first at or after the next one's.
        return (0 until frames).map { k ->
            val start = ceilDiv(k.toLong() * refreshes, frames.toLong())
            val end = ceilDiv((k + 1).toLong() * refreshes, frames.toLong())
            (end - start).toInt()
        }
    }

    fun even(inputHz: Int, panelHz: Int): Boolean = pattern(inputHz, panelHz).distinct().size <= 1

    /** The largest content rate up to [inputHz] that shows every frame for the same number of the glasses' refreshes. */
    fun contentHz(inputHz: Int, panelHz: Int): Int = if (inputHz <= 0 || panelHz <= 0) inputHz else gcd(inputHz, panelHz)

    fun line(inputHz: Int, panelHz: Int): String {
        val p = pattern(inputHz, panelHz)
        return if (even(inputHz, panelHz)) "the glasses show the phone's %d Hz on their %d Hz panels evenly (%d refreshes a frame)".format(Locale.ROOT, inputHz, panelHz, p.firstOrNull() ?: 1)
        else "the glasses show the phone's %d Hz on their %d Hz panels unevenly (%s refreshes a frame); content paced at %d Hz so each frame is shown for %d".format(
            Locale.ROOT, inputHz, panelHz, p.joinToString("/"), contentHz(inputHz, panelHz), panelHz / contentHz(inputHz, panelHz),
        )
    }

    private fun gcd(a: Int, b: Int): Int = if (b == 0) abs(a) else gcd(b, a % b)
    private fun ceilDiv(a: Long, b: Long): Long = -Math.floorDiv(-a, b)
}

/**
 * 0.5.26: a window's copies held to an even content rate ([GlassesCadence.contentHz]) on the phone's refreshes, for
 * the glasses' warp at 90 Hz: a copy is due on every [every]-th refresh of the phone's grid, so each new picture is
 * shown for the same number of the glasses' refreshes. [every] 1 copies on every refresh, as with no cadence.
 */
object CadenceGate {
    fun every(phoneHz: Int, contentHz: Int): Int = if (contentHz <= 0 || phoneHz <= contentHz) 1 else (phoneHz / contentHz).coerceAtLeast(1)

    /** Whether the refresh at [presentNs] on a [periodNs] grid may take a new picture. */
    fun due(presentNs: Long, periodNs: Long, every: Int): Boolean {
        if (every <= 1 || periodNs <= 0L) return true
        val index = (presentNs.toDouble() / periodNs).roundToLong()
        return Math.floorMod(index, every.toLong()) == 0L
    }
}

/**
 * 0.5.26 (Mike: "the GPU never stalls the Space behind the hand model"; GPU inference of 133 to 156 ms for
 * 5 s while the Space fell to 22 fps and its GPU p99 to 46 ms): the hand model leaves the GPU when the
 * Space misses its deadlines waiting for a busy GPU. Two 5 s lines in a row with over [MISS_LIMIT] of the frames
 * missed and the Space's draws waiting over [WAIT_LIMIT_MS] (p90) for other GPU work, while the hand model runs on the
 * GPU, move it to the CPU for the rest of the camera session (one MediaPipe rebuild). It is decided once; misses with
 * the hand model on the CPU are not its doing.
 *
 * 0.5.29 (it moved the hand model on the first timing line, with 2 to 3 % missed, as
 * the Space misses thermally moderate anyway, and the hands ran at 4 to 5 fps on the CPU all session): the misses must be
 * the hand model's. The miss share seen while it is off the GPU is the baseline ([baseline]); a line counts as bad only
 * over that by [MARGIN] (at least [MISS_LIMIT]), or over [FIRST_MISS_LIMIT] before any baseline. And it is no longer
 * once a session: off the GPU it is armed again, for [HandGpuReturn]'s way back.
 */
class SpaceGpuGuard(private val windows: Int = 2) {
    private var bad = 0

    var moved = false
        private set

    /** 0.5.29: the miss share with the hand model off the GPU (a running mean), or null before any such line. */
    var baseline: Double? = null
        private set

    /** One timing line's figures. Returns the log line when the hand model should leave the GPU now, else null. */
    fun window(missShare: Double, gpuWaitP90Ms: Double, handModelOnGpu: Boolean): String? {
        if (!handModelOnGpu) {
            bad = 0
            moved = false
            baseline = baseline?.let { it + (missShare - it) * BASELINE_GAIN } ?: missShare
            return null
        }
        if (moved) return null
        val limit = baseline?.let { maxOf(MISS_LIMIT, it + MARGIN) } ?: FIRST_MISS_LIMIT
        bad = if (missShare > limit && gpuWaitP90Ms > WAIT_LIMIT_MS) bad + 1 else 0
        if (bad < windows) return null
        moved = true
        bad = 0
        return String.format(
            Locale.ROOT,
            "the Space missed %.1f %% of its frames waiting %.1f ms (p90) for a GPU busy with other work, %d lines in a row, with the hand model on the GPU (over %.1f %%, %s): it moves to the CPU so the Space gets the GPU",
            missShare * 100, gpuWaitP90Ms, windows, limit * 100,
            baseline?.let { String.format(Locale.ROOT, "%.1f %% missed with it off the GPU", it * 100) } ?: "no baseline yet",
        )
    }

    fun reset() {
        bad = 0
        moved = false
    }

    companion object {
        const val MISS_LIMIT = 0.02
        const val WAIT_LIMIT_MS = 1.0

        /** 0.5.29: the misses the hand model must add over the baseline, and the limit before one is known. */
        const val MARGIN = 0.015
        const val FIRST_MISS_LIMIT = 0.05
        const val BASELINE_GAIN = 0.3
    }
}
