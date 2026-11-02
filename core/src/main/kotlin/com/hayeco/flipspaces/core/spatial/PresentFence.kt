package com.hayeco.flipspaces.core.spatial

import java.util.Locale

/**
 * 0.5.19: `dumpsys SurfaceFlinger --latency <layer>`: the display period,
 * then one line per frame of desired present (the frame's eglPresentationTimeANDROID stamp), actual
 * present (the present fence; 0 or INT64_MAX while pending) and frame ready, all CLOCK_MONOTONIC ns.
 */
data class SfFrame(val desiredNs: Long, val actualNs: Long, val readyNs: Long) {
    /**
     * Presented on its own: a fence time, and a ready time (a frame with ready 0 was never latched;
     * SurfaceFlinger lists the next frame's present against it, 20 of 143 in Mike's vc39 table).
     */
    val presented: Boolean get() = actualNs in 1 until Long.MAX_VALUE / 2 && readyNs > 0
}

data class SfLatency(val periodNs: Long, val frames: List<SfFrame>) {
    companion object {
        fun parse(text: String?): SfLatency? {
            if (text.isNullOrBlank()) return null
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("layer ") }
            val period = lines.firstOrNull()?.toLongOrNull()?.takeIf { it > 0 } ?: return null
            val frames = lines.drop(1).mapNotNull { l ->
                val f = l.split(Regex("""\s+"""))
                if (f.size < 3) return@mapNotNull null
                val d = f[0].toLongOrNull() ?: return@mapNotNull null
                val a = f[1].toLongOrNull() ?: return@mapNotNull null
                val r = f[2].toLongOrNull() ?: return@mapNotNull null
                if (d <= 0L) null else SfFrame(d, a, r)
            }
            return SfLatency(period, frames)
        }
    }
}

/**
 * 0.5.19 ("frames are presented about 13 ms after the time they are stamped
 * for"): the present time model corrected from SurfaceFlinger's own present fences.
 *
 * The late draw predicts each frame's present ([DrawPlan.presentNs], the frame timeline's expected
 * present plus its own correction) and stamps the frame half a period before it ([DrawPlan.stampNs]),
 * so SurfaceFlinger's actual minus desired present is not the model's error: in Mike's vc39 SBS table
 * it was 13.4 ms, of which 8.3 ms is that half period. The error that matters is each frame's actual
 * present less the present its pose was predicted for. Every drawn frame is [record]ed (its stamp and
 * modelled present); each `--latency` table is matched to them by stamp ([measure]).
 *
 * Only frames presented on time count: those within half a period of the earliest error (a frame
 * that missed its vsync is presented a period later, and the late draw counts those as misses). The
 * on-time errors' median is the offset; it is used when at least [minFrames] frames and [minShare]
 * of the matched ones are on time and their p10 to p90 spread is under [maxSpreadMs], within
 * [minMs] to [maxMs] (a fence stamped later than any real scan-out could be, or a model that is
 * early, is refused, not followed). Each usable table moves the applied [offsetNs] half way.
 */
class PresentFence(
    val keep: Int = 600,
    val minFrames: Int = 30,
    val minShare: Double = 0.5,
    val maxSpreadMs: Double = 3.0,
    val minMs: Double = -3.0,
    val maxMs: Double = 16.7,
    /** 0.5.21: frames this long after a reset are not measured. */
    val skipFirstMs: Long = 2_000L,
    /** 0.5.21: how long a fitted vsync grid is used after its table. */
    val gridFreshMs: Long = 6_000L,
    /** 0.5.24: the late draw is locked to the measured fences ([lockNs]); false is 0.5.23 (the offset moves the pose's present only). */
    val lockPhase: Boolean = true,
    val lockMinMs: Double = 0.5,
    /**
     * 0.5.25 (in SBS-114 the offset drifted +8.05 to +10.23 ms, the lock followed it to
     * +7.64 ms and wrapped to -8.24 ms, a whole frame's move, with the present error p95 at 6.6 to 8.2 ms while
     * locked): no lock while the offset sits within this of half a period; the in phase plan is kept there, the
     * offset going on the pose's present only, as SBS-106 and SBS-116 did. 0 is 0.5.24.
     */
    val nearHalfMs: Double = 1.0,
    /**
     * 0.5.29 (33 of 232 tables read `model present +16.66` or `+16.67 ms off` and
     * the rest about 0, switching between the two): an on time median within this of a whole period (not 0) is taken
     * back by that period. A frame presented a whole refresh after its model is one that missed its vsync (the late
     * draw counts those as misses), not the model's error; following it predicted every pose a frame too far. The
     * lock is the same either way (it is taken round the period). 0 is 0.5.28.
     */
    val wholePeriodTolMs: Double = 1.0,
    /**
     * 0.5.50 (Mike, vc70: moving the head between windows "looks choppy but then it gets smooth again randomly"; his log:
     * the offset crept +7.50, +7.94, +8.32, +8.70, +9.08 ms a table, 0.07 ms a second, the glasses' 60 Hz against the
     * phone's cover screen's that the Choreographer follows; within [nearHalfMs] of half a period the lock was off, pose to
     * photons went from 25.7 to 34 to 42 ms and frames "two for one refresh" from 10 to 35 in 5 s, for about half a
     * minute each time the phase went by): the lock keeps to the side it is on as the offset crosses half a period, and
     * wraps only once it is this far past, one frame's move once instead of the in phase plan for the whole crossing.
     * 0.5.24's flapping (+7.64 then -8.24 ms table after table) cannot come back: a table a little either side of half a
     * period stays on the side the lock was on. 0 is 0.5.25 to 0.5.49 ([nearHalfMs] off the lock).
     */
    val wrapHysteresisMs: Double = 2.5,
    /**
     * 0.5.56 (locked, the offset reads 0.00 to 0.01 ms; in a creep it jumps to +3.9 to +7.6 ms and
     * rises 0.075 ms a second to +12 to +13 ms): an offset at least this far from the glasses' refresh is a creep ([creeping]).
     */
    val creepMinMs: Double = 1.0,
) {
    private val frames = ArrayDeque<LongArray>()

    /** The offset added to every frame's modelled present, ns (0 until a table was usable). */
    var offsetNs: Long = 0L
        private set

    var tables = 0
        private set
    var used = 0
        private set
    private var last: String = "no SurfaceFlinger table read yet"

    /** A frame stamped [stampNs] (System.nanoTime) whose pose was predicted for the present [presentNs], [offsetNs] included. */
    fun record(stampNs: Long, presentNs: Long) {
        if (firstStampNs == Long.MIN_VALUE) firstStampNs = stampNs
        frames.addLast(longArrayOf(stampNs, presentNs - offsetNs))
        while (frames.size > keep) frames.removeFirst()
    }

    /** 0.5.21: the first frame's stamp since the last reset: the first [skipFirstMs] of frames are not measured. */
    private var firstStampNs = Long.MIN_VALUE

    data class Result(val matched: Int, val onTime: Int, val offsetMs: Double, val spreadMs: Double, val usable: Boolean, val why: String)

    /**
     * One `--latency` table: what it says of the model, and the offset moved when it is usable.
     *
     * 0.5.21 (the model present drifted against the glasses' vsync for 40 to 90 s
     * at a time, and a table straddling two vsyncs read -7.9 ms where the truth was +8.8; the first 2D
     * table applied +16.68 ms while the queue filled):
     * - frames from the first [skipFirstMs] after a reset are left out (the queue filling);
     * - the errors are taken round the period: each is moved by whole periods to lie nearest the offset
     *   applied now, so a drift that crosses a vsync goes on smoothly instead of wrapping to the far side;
     * - a first offset (none applied since a reset or a wobble) over half a period is refused: that is
     *   whole vsyncs of queue, not the model's error (later tables are taken round the period, so they
     *   follow a drift on past half a period without a wrap);
     * - each table also fits the glasses' own vsync grid from the presents ([snap]).
     */
    fun measure(t: SfLatency, modePeriodNs: Long = 0L): Result {
        tables++
        // 0.5.24 (on a 1920x1080@120 display the table's period read 16.67 ms, so the fitted grid and
        // the range's top were 16.67, and only 64 to 78 of 127 frames were on time): the glasses' own mode period, when
        // given, is the period; the table's is a fallback. A table at twice the mode's period is said once, not used.
        val periodNs = if (modePeriodNs > 0L) modePeriodNs else t.periodNs
        tableNote = if (modePeriodNs > 0L && kotlin.math.abs(t.periodNs - 2 * modePeriodNs) < modePeriodNs / 20)
            "the table's period %.2f ms is twice the glasses' %.2f ms (SurfaceFlinger reports another display's), so the glasses' is used".format(Locale.ROOT, t.periodNs / 1e6, modePeriodNs / 1e6)
        else null
        modePeriod = periodNs
        fitGrid(t, periodNs)
        val byStamp = HashMap<Long, Long>(frames.size * 2)
        val from = if (firstStampNs == Long.MIN_VALUE) Long.MIN_VALUE else firstStampNs + skipFirstMs * 1_000_000L
        for (f in frames) if (f[0] >= from) byStamp[f[0] / TOLERANCE_NS] = f[1]
        val period = periodNs / 1e6
        val ref = offsetNs / 1e6
        val errors = ArrayList<Double>()
        for (f in t.frames) {
            if (!f.presented) continue
            val model = byStamp[f.desiredNs / TOLERANCE_NS] ?: byStamp[f.desiredNs / TOLERANCE_NS - 1] ?: byStamp[f.desiredNs / TOLERANCE_NS + 1] ?: continue
            val e = (f.actualNs - model) / 1e6
            // Round the period, nearest the offset applied now; a first table (nothing applied yet) is taken as it is.
            errors += if (period > 0.0 && used > 0) e - period * kotlin.math.round((e - ref) / period) else e
        }
        if (errors.isEmpty()) return done(Result(0, 0, 0.0, 0.0, false, if (byStamp.isEmpty()) "the first ${skipFirstMs / 1000} s of frames are not measured (the queue fills)" else "no frame of the table matched a drawn frame's stamp"))
        // 0.5.22 (-5.63 ms was refused for 15 s as "outside -3.0 to +16.7 ms", while on the
        // glasses' 16.667 ms vsync grid it is +11.04): a median outside the range is wrapped onto the grid
        // first, by whole fitted periods, to the in range value nearest the offset applied now.
        // 0.5.23 (at 120 Hz the offset climbed from +0.38 to +13.19 ms, past a whole 8.33 ms
        // period, and only 63 to 72 of 127 frames were on time): the range's top is one period of the display, so
        // at 120 Hz an offset over 8.33 ms wraps as the 3D one does over 16.7.
        // 0.5.24: the range's top and the wrap from the mode's period; the fitted grid is used for its phase only ([snap]).
        val gridMs = if (modePeriodNs > 0L) period else gridPeriodNs.takeIf { it > 0L }?.let { it / 1e6 } ?: period
        val top = topMs(gridMs)
        val wrapBy = wrapPeriods(errors.sorted().let { it[it.size / 2] }, ref, gridMs, top)
        if (wrapBy != 0.0) for (i in errors.indices) errors[i] += wrapBy
        // 0.5.29: a whole refresh off is a missed vsync, not the model's error ([wholePeriodTolMs]).
        var refreshBack = 0.0
        if (wholePeriodTolMs > 0.0 && gridMs > 0.0) {
            val m = errors.sorted().let { it[it.size / 2] }
            val k = kotlin.math.round(m / gridMs)
            val rest = m - k * gridMs
            if (k != 0.0 && kotlin.math.abs(rest) < wholePeriodTolMs && rest in minMs..top) {
                refreshBack = -k * gridMs
                for (i in errors.indices) errors[i] += refreshBack
            }
        }
        val sorted = errors.sorted()
        val median = sorted[sorted.size / 2]
        val half = period / 2
        val onTime = sorted.filter { kotlin.math.abs(it - median) <= half / 2 }
        val spread = if (onTime.isEmpty()) 0.0 else onTime[(onTime.size * 9 / 10).coerceAtMost(onTime.size - 1)] - onTime[onTime.size / 10]
        val why = when {
            onTime.size < minFrames -> "only %d frames near the median (needs %d)".format(Locale.ROOT, onTime.size, minFrames)
            onTime.size < minShare * errors.size -> "only %d of %d matched frames near the median".format(Locale.ROOT, onTime.size, errors.size)
            spread > maxSpreadMs -> "the frames spread %.1f ms (over %.1f)".format(Locale.ROOT, spread, maxSpreadMs)
            median !in minMs..top -> "an offset of %+.1f ms is outside %+.1f to %+.1f ms: not followed (is the fence stamped late?)".format(Locale.ROOT, median, minMs, top)
            used == 0 && kotlin.math.abs(median) > half -> "a first offset of %+.2f ms is over half a period (%.2f ms): whole vsyncs of queue, not a model error; not followed".format(Locale.ROOT, median, half)
            else -> null
        }
        val usedWhy = (if (wrapBy == 0.0) "used" else "used (wrapped %+.2f ms onto the glasses' vsync grid from %+.2f ms)".format(Locale.ROOT, wrapBy, median - wrapBy - refreshBack)) +
            if (refreshBack != 0.0) " (taken back %+.2f ms: a whole refresh late is a missed vsync, not the model's error)".format(Locale.ROOT, refreshBack) else ""
        val r = Result(errors.size, onTime.size, median, spread, why == null, why ?: usedWhy)
        if (r.usable) {
            used++
            val target = (median * 1e6).toLong()
            offsetNs = if (used == 1) target else (offsetNs + target) / 2
        }
        return done(r)
    }

    /**
     * 0.5.24 (in 3D the fence's offset jumped to +6.61 ms and crept to +12.51 ms, the frames
     * presented that long after the frame timeline said, until a Choreographer wobble put it back to 0; pose to photons
     * went from 32 to 44 ms): the late draw is locked to the measured fences. The whole plan (wake, deadline, present)
     * moves by the offset taken round the period into -half to +half a period, so each frame is drawn for the glasses'
     * vsync the fences say it reaches, as late as when in phase; past half a period that is the vsync before. 0 while
     * the lock is off, before a usable table, or under [lockMinMs].
     */
    val lockNs: Long
        get() {
            if (!lockPhase || used == 0) return 0L.also { lockSide = null }
            val p = modePeriod
            if (p <= 0L) return 0L.also { lockSide = null }
            var o = offsetNs % p
            if (o > p / 2) o -= p
            if (o <= -p / 2) o += p
            val hyst = (wrapHysteresisMs * 1e6).toLong()
            val side = lockSide
            if (hyst > 0L && side != null) {
                // 0.5.50: the whole period shift nearest the lock it had, as long as that stays within the hysteresis past half.
                val near = longArrayOf(o - p, o, o + p).minBy { kotlin.math.abs(it - side) }
                if (kotlin.math.abs(near) <= p / 2 + hyst) o = near
            } else if (hyst <= 0L && nearHalfMs > 0.0 && kotlin.math.abs(kotlin.math.abs(o) - p / 2) < (nearHalfMs * 1e6).toLong()) {
                return 0L
            }
            lockSide = o
            return if (kotlin.math.abs(o) < (lockMinMs * 1e6).toLong()) 0L else o
        }

    /** 0.5.50: the lock last given (before the [lockMinMs] floor), the side the hysteresis keeps; null before one. */
    private var lockSide: Long? = null

    /** 0.5.25: the offset sits within [nearHalfMs] of half a period, so the late draw is not locked to it ([lockNs]). */
    val nearHalf: Boolean
        get() {
            if (!lockPhase || used == 0 || nearHalfMs <= 0.0 || wrapHysteresisMs > 0.0) return false
            val p = modePeriod
            if (p <= 0L) return false
            var o = offsetNs % p
            if (o > p / 2) o -= p
            if (o <= -p / 2) o += p
            return kotlin.math.abs(kotlin.math.abs(o) - p / 2) < (nearHalfMs * 1e6).toLong()
        }

    /** 0.5.24: the period the last table was read with (the mode's when given), ns. */
    private var modePeriod = 0L
    private var tableNote: String? = null

    /**
     * 0.5.22: the whole periods (ms, a multiple of [periodMs]) that bring [medianMs] into [minMs] to [maxMs],
     * nearest [refMs]; 0 when it is in range already or no multiple brings it in.
     */
    fun wrapPeriods(medianMs: Double, refMs: Double, periodMs: Double, topMs: Double = maxMs): Double {
        if (medianMs in minMs..topMs || periodMs <= 0.0) return 0.0
        var best: Double? = null
        for (k in -3..3) {
            if (k == 0) continue
            val v = medianMs + k * periodMs
            if (v !in minMs..topMs) continue
            if (best == null || kotlin.math.abs(v - refMs) < kotlin.math.abs(medianMs + best - refMs)) best = k * periodMs
        }
        return best ?: 0.0
    }

    /** 0.5.23: the top of the followed range for a display of [periodMs]: one period, at most [maxMs] (16.7 ms at 60 Hz as before, 8.33 at 120). */
    fun topMs(periodMs: Double): Double = if (periodMs > 0.0 && periodMs < maxMs - 0.1) periodMs else maxMs

    /**
     * 0.5.21 ("reset the offset to 0 on every Choreographer wobble, not by halves"):
     * the frame timeline jumped back into phase, so the offset goes to 0 at once and the next usable
     * table applies in full.
     */
    fun wobble() {
        if (offsetNs != 0L) wobbleResets++
        offsetNs = 0L
        used = 0
        lockSide = null
    }

    var wobbleResets = 0
        private set

    // ---- 0.5.21: the glasses' own vsync grid ----

    private var gridAnchorNs = 0L
    private var gridPeriodNs = 0L
    private var gridAtNs = Long.MIN_VALUE

    /** The fitted vsync period, ns (0 before a table), and the time of the last fit (the table's newest present). */
    val gridPeriod: Long get() = gridPeriodNs

    /**
     * The glasses' vsyncs from a table's actual presents: each present is a vsync of the display the
     * Space is on; a least squares line through (vsync count, present time) gives their period and phase.
     */
    private fun fitGrid(t: SfLatency, periodNs: Long = t.periodNs) {
        val p0 = periodNs.toDouble()
        if (p0 <= 0.0) return
        val presents = t.frames.filter { it.presented }.map { it.actualNs }.distinct().sorted()
        if (presents.size < MIN_GRID_PRESENTS) return
        val a0 = presents.first()
        val n = presents.map { Math.round((it - a0) / p0).toDouble() }
        val y = presents.map { (it - a0).toDouble() }
        val mn = n.average()
        val my = y.average()
        var sxx = 0.0
        var sxy = 0.0
        for (i in n.indices) {
            sxx += (n[i] - mn) * (n[i] - mn)
            sxy += (n[i] - mn) * (y[i] - my)
        }
        if (sxx <= 0.0) return
        val period = sxy / sxx
        if (kotlin.math.abs(period - p0) > p0 * 0.02) return
        gridPeriodNs = period.toLong()
        gridAnchorNs = a0 + (my - period * mn).toLong()
        gridAtNs = presents.last()
    }

    /**
     * A modelled present [presentNs] put on the nearest of the glasses' own vsyncs ([fitGrid]), so the pose is
     * predicted for when the light really comes; unchanged before a fit, or [gridFreshMs] after the last one.
     */
    fun snap(presentNs: Long): Long {
        val p = gridPeriodNs
        if (p <= 0L || gridAtNs == Long.MIN_VALUE || presentNs - gridAtNs > gridFreshMs * 1_000_000L) return presentNs
        val k = Math.round((presentNs - gridAnchorNs).toDouble() / p)
        return gridAnchorNs + k * p
    }

    /** 0.5.56: a fitted grid that [snap] still uses at [atNs]. */
    fun gridFresh(atNs: Long): Boolean = gridPeriodNs > 0L && gridAtNs != Long.MIN_VALUE && atNs - gridAtNs <= gridFreshMs * 1_000_000L

    /**
     * 0.5.56: the Space creeps on the phone's clock: a fresh grid ([gridFresh]) and a used offset at least [creepMinMs] off
     * the glasses' refresh, taken round the period ([GlassesRefreshGate] acts only then).
     */
    fun creeping(atNs: Long): Boolean {
        if (used == 0 || !gridFresh(atNs)) return false
        val p = if (modePeriod > 0L) modePeriod else gridPeriodNs
        var o = offsetNs % p
        if (o > p / 2) o -= p
        if (o <= -p / 2) o += p
        return kotlin.math.abs(o) >= (creepMinMs * 1e6).toLong()
    }

    private fun done(r: Result): Result {
        last = "%d frames matched, %d on time, model present %+.2f ms off (p10 to p90 %.2f ms): %s".format(Locale.ROOT, r.matched, r.onTime, r.offsetMs, r.spreadMs, r.why)
        return r
    }

    fun reset() {
        frames.clear()
        offsetNs = 0L
        used = 0
        lockSide = null
        firstStampNs = Long.MIN_VALUE
        gridPeriodNs = 0L
        gridAtNs = Long.MIN_VALUE
    }

    fun line(): String = "present fences: %d tables read, %d used; the last: %s; offset applied %+.2f ms; the late draw locked %+.2f ms to the fences%s; the glasses' vsync grid %s%s; %d resets at a Choreographer wobble".format(
        Locale.ROOT, tables, used, last, offsetNs / 1e6, lockNs / 1e6,
        if (nearHalf) " (not locked: the offset is within %.1f ms of half a period, where a lock wraps by a whole frame; the in phase plan, the offset on the pose's present)".format(Locale.ROOT, nearHalfMs) else "",
        if (gridPeriodNs > 0L) "%.4f ms".format(Locale.ROOT, gridPeriodNs / 1e6) else "not fitted yet",
        tableNote?.let { " ($it)" } ?: "", wobbleResets,
    )

    companion object {
        /** Stamps match within a tenth of a millisecond (SurfaceFlinger keeps the stamp exactly). */
        const val TOLERANCE_NS = 100_000L

        /** 0.5.21: presents a table needs for a vsync grid. */
        const val MIN_GRID_PRESENTS = 20
    }
}

/**
 * 0.5.19: a package's last exit record from `dumpsys activity exit-info`
 * (`reason=5 (CRASH NATIVE)`, `description=crash`), for a panel whose app closed.
 */
data class AppExit(val reason: Int, val reasonName: String, val description: String, val timestamp: String) {
    /** A crash (Java or native) or an ANR, rather than the app finishing itself. */
    val crashed: Boolean get() = reason == CRASH || reason == CRASH_NATIVE || reason == ANR

    fun line(): String = "reason=$reason ($reasonName) at $timestamp: $description"

    companion object {
        const val ANR = 6
        const val CRASH = 4
        const val CRASH_NATIVE = 5

        private val REASON = Regex("""reason=(\d+)\s*\(([^)]*)\)""")
        private val TIMESTAMP = Regex("""timestamp=(\S+ \S+)""")

        fun parse(text: String?): AppExit? {
            if (text.isNullOrBlank()) return null
            val lines = text.lines()
            val start = lines.indexOfFirst { it.trim().startsWith("ApplicationExitInfo #0") }
            if (start < 0) return null
            val block = lines.subList(start + 1, minOf(lines.size, start + 8))
            val reason = block.firstNotNullOfOrNull { REASON.find(it) } ?: return null
            val ts = block.firstNotNullOfOrNull { TIMESTAMP.find(it)?.groupValues?.get(1) } ?: "an unknown time"
            val desc = block.firstOrNull { it.trim().startsWith("description=") }?.trim()?.removePrefix("description=")?.take(160) ?: ""
            return AppExit(reason.groupValues[1].toInt(), reason.groupValues[2].trim(), desc, ts)
        }

        /** The kind of a reply line ([line]'s form), or null for "unknown: ...". */
        fun fromLine(reply: String?): AppExit? {
            if (reply == null || !reply.startsWith("reason=")) return null
            val r = REASON.find(reply) ?: return null
            return AppExit(r.groupValues[1].toInt(), r.groupValues[2], reply.substringAfter(": ", ""), reply.substringAfter(" at ").substringBefore(": "))
        }
    }
}
