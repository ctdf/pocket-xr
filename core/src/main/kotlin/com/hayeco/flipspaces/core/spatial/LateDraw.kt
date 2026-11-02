package com.hayeco.flipspaces.core.spatial

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

/**
 * 0.5.12: the last [capacity] values of a measurement, for its percentiles in the log lines.
 * A ring buffer, sorted on demand: a few hundred values a second costs nothing.
 */
class RollingStats(private val capacity: Int = 240) {
    private val values = DoubleArray(capacity)
    private var next = 0

    var count = 0
        private set

    fun add(v: Double) {
        values[next] = v
        next = (next + 1) % capacity
        if (count < capacity) count++
    }

    /** 0.5.55: the sort's own buffer, kept, so a percentile allocates nothing (it copied the values every call). */
    private val sorted = DoubleArray(capacity)

    /** The [p] quantile (0..1) of what is kept, nearest rank; 0 when empty. */
    fun percentile(p: Double): Double {
        val n = count
        if (n == 0) return 0.0
        synchronized(sorted) {
            System.arraycopy(values, 0, sorted, 0, n)
            java.util.Arrays.sort(sorted, 0, n)
            val rank = ceil(p * n).toInt().coerceIn(1, n) - 1
            return sorted[rank]
        }
    }

    fun median(): Double = percentile(0.5)

    fun clear() {
        count = 0
        next = 0
    }
}

/**
 * 0.5.12: one vsync of the display the Space is shown on, as Choreographer reports it: the
 * vsync's time, the display period and, from the frame timeline (API 33), the deadline by which a
 * frame for it must be queued and the time SurfaceFlinger expects to present it. All System.nanoTime.
 */
data class VsyncSample(val vsyncNs: Long, val periodNs: Long, val deadlineNs: Long, val presentNs: Long) {
    companion object {
        /**
         * Before API 33 (no frame timeline): the deadline is the next vsync less the display's
         * presentation deadline, and the frame is presented one period after it is latched.
         */
        fun fromVsyncOnly(vsyncNs: Long, periodNs: Long, presentationDeadlineNs: Long): VsyncSample {
            val deadline = vsyncNs + periodNs - presentationDeadlineNs.coerceIn(0L, periodNs / 2)
            return VsyncSample(vsyncNs, periodNs, deadline, vsyncNs + 2 * periodNs)
        }
    }
}

/**
 * 0.5.14: the glasses display's timing as the Space uses it: period, appVsyncOffset, presentation
 * deadline and rows, from the mode in use. [changed] says when a new mode (side by side at 60 Hz
 * after 2D at 120) means the late draw starts over ([LateDrawScheduler.reset]).
 */
data class ModeTiming(val periodNs: Long, val appVsyncOffsetNs: Long, val presentationDeadlineNs: Long, val rows: Int, val sbs: Boolean) {
    val hz: Double get() = 1e9 / periodNs.coerceAtLeast(1L)

    /** True when [o] is another mode: another rate (beyond 2 %), size or split. */
    fun changed(o: ModeTiming?): Boolean = o == null || kotlin.math.abs(o.periodNs - periodNs) > periodNs / 50 || o.rows != rows || o.sbs != sbs

    fun line(): String = String.format(
        Locale.ROOT,
        "display timing for the late draw and prediction: %s at %.1f Hz (period %.2f ms, appVsyncOffset %.2f ms, presentation deadline %.2f ms, %d rows); pose to photons is about budget + %.1f ms + the glasses' delay",
        if (sbs) "side by side 3D" else "2D", hz, periodNs / 1e6, appVsyncOffsetNs / 1e6, presentationDeadlineNs / 1e6, rows, presentationDeadlineNs / 1e6,
    )
}

/**
 * 0.5.20 (9 `late draw off` and 44 `late draw on` lines in 5 minutes,
 * each off under 0.2 s, at 57 to 62 Hz): whether the late draw runs. 0.5.19 held a Choreographer
 * rate off the glasses' for 1 s, but not [GlassesVsync]'s own refusal of such a rate (its
 * "not a whole multiple" reason), which is what the first callback of a wobble gives. Now both are
 * soft: the late draw stays on at the glasses' rate unless one lasts over [holdNs]. The hard reasons
 * (off in the settings, no vsync yet, vsyncs stopped) act at once. A held wobble is said once, when
 * it ends ([takeWobbleLine]), not as an off and an on line.
 */
class LateDrawGate(val holdNs: Long = 1_000_000_000L) {
    private var softSinceNs = 0L
    private var softWhy: String? = null
    private var lowHz = Double.MAX_VALUE
    private var highHz = 0.0
    private var wobbleLine: String? = null

    /** Wobbles held since the start. */
    var wobbles = 0
        private set

    /**
     * The reason the late draw is off at [nowNs], or null (on). [hard] is a reason that acts at once;
     * [soft] one that is held (the vsync route refused, or Choreographer's rate off the glasses');
     * [choreographerHz] the callback rate now, for the wobble's line.
     */
    fun decide(nowNs: Long, hard: String?, soft: String?, choreographerHz: Double): String? {
        if (hard != null) {
            softSinceNs = 0L
            softWhy = null
            return hard
        }
        if (soft == null) {
            if (softSinceNs != 0L) {
                wobbles++
                wobbleLine = String.format(
                    Locale.ROOT,
                    "late draw: a Choreographer wobble of %.0f ms (%.1f to %.1f Hz: %s) held at the glasses' rate (%d this session)",
                    (nowNs - softSinceNs) / 1e6, lowHz, highHz, softWhy, wobbles,
                )
            }
            softSinceNs = 0L
            softWhy = null
            return null
        }
        if (softSinceNs == 0L) {
            softSinceNs = nowNs
            softWhy = soft
            lowHz = choreographerHz
            highHz = choreographerHz
        }
        lowHz = minOf(lowHz, choreographerHz)
        highHz = maxOf(highHz, choreographerHz)
        return if (nowNs - softSinceNs > holdNs) "$soft (for %.1f s)".format(Locale.ROOT, (nowNs - softSinceNs) / 1e9) else null
    }

    /** True while a soft reason is being held. */
    val holding: Boolean get() = softSinceNs != 0L

    /** The last held wobble's line, once. */
    fun takeWobbleLine(): String? = wobbleLine.also { wobbleLine = null }
}

/** 0.5.12: when to wake, sample the pose and draw one frame, and when it is due on the display. */
data class DrawPlan(
    val vsyncNs: Long,
    val periodNs: Long,
    /** Sample the pose and draw from here. */
    val wakeNs: Long,
    /** The frame must be finished (GPU done) by here to be latched for [vsyncNs]. */
    val deadlineNs: Long,
    /** SurfaceFlinger's expected present time for the frame, plus whole frames of [LateDrawScheduler.depth]. */
    val presentNs: Long,
    val depth: Int,
    /** The measured correction already in [presentNs]. */
    val correctionNs: Long = 0L,
    /** 0.5.24: the present fences' lock already in [wakeNs], [deadlineNs] and [presentNs] ([PresentFence.lockNs]). */
    val shiftNs: Long = 0L,
    /** 0.5.57: the stamp the glasses frame clock chose ([GlassesFrameClock.Plan.stampNs]); MIN_VALUE for the rule below. */
    val stampAtNs: Long = Long.MIN_VALUE,
    /** 0.5.57: planned on the glasses frame clock, not on Choreographer's timeline (not measured against its vsyncs). */
    val glassesClock: Boolean = false,
) {
    /** What eglPresentationTimeANDROID is given: half a period before the present, so the frame is never held past it. */
    val stampNs: Long get() = if (stampAtNs != Long.MIN_VALUE) stampAtNs else presentNs - periodNs / 2

    companion object {
        /** 0.5.57: a frame on the glasses frame clock, its deadline the time it must be done for its commit. */
        fun of(p: GlassesFrameClock.Plan) = DrawPlan(
            vsyncNs = p.refreshNs, periodNs = p.periodNs, wakeNs = p.wakeNs, deadlineNs = p.readyNs, presentNs = p.poseNs,
            depth = 0, stampAtNs = p.stampNs, glassesClock = true,
        )
    }
}

/**
 * 0.5.12: the draw budget, the measured p99 of how long a frame takes from the pose
 * sample to the GPU being done, plus [marginMs], within [minMs] and a period less [periodSpareMs].
 *
 * 0.5.15: in SBS at 60 Hz the p99 (20 to 23 ms) pinned the
 * budget at 15.7 ms, so the pose was sampled almost a whole frame early (pose to photons 31.9 ms).
 * Now the budget is the [percentile] (p90) plus [marginMs], at most [maxShare] of the period (half a
 * frame), and it starts at [startShare] of the period after a reset (0.5.14: 4 ms, which made 8 of
 * the first 15 SBS frames miss). An occasional miss is accepted rather than drawing every frame
 * early, but the misses are watched ([outcome]): over [missTarget] of a [missWindow] frame window
 * adds [missStepMs] to the margin (up to the cap), under a quarter of it takes [missStepMs] / 2 off;
 * and when, even at the cap, over [liftShare] of the window misses (a draw the cut did not make
 * small enough), the cap lifts to a period less [periodSpareMs] until misses fall under
 * [missTarget] again, so the picture stays steady at the cost of latency. Null shares are 0.5.14's.
 */
class DrawBudget(
    val marginMs: Float = 1f,
    val minMs: Float = 2f,
    val startMs: Float = 4f,
    val periodSpareMs: Float = 1f,
    window: Int = 240,
    val percentile: Double = 0.90,
    val maxShare: Double? = 0.5,
    val startShare: Double? = 0.5,
    val missTarget: Double = 0.01,
    val missWindow: Int = 120,
    val missStepMs: Float = 0.5f,
    /**
     * 0.5.21 (the cap lifted on any burst of misses, adding 7.4 ms to every frame
     * for one miss in 20, while a 5 % miss rate costs less on a still head than the longer horizon):
     * 15 % (was 5 %).
     */
    val liftShare: Double = 0.15,
    /**
     * 0.5.22 (bursts of over 15 % in 120 frames, while Twitch's panel updated, lifted the
     * cap for most of the 3D session though the session missed 5 to 8 %): the lift also needs over
     * [liftShare] of the last [liftWindow] frames (10 s at 60 Hz) to have missed, so a burst alone does not
     * lift it. 0 is 0.5.21 (the 120 frame window alone).
     */
    val liftWindow: Int = 600,
    /**
     * 0.5.22 (the same finding: lifted, the budget went to 15.7 ms, nearly the whole 16.7 ms frame, and pose to
     * photons from 24.7 to 32 ms): lifted, the cap is the draws' p90 plus [marginMs] (at least the half frame
     * cap, at most a period less [periodSpareMs]) rather than the whole frame. False is 0.5.21.
     */
    val liftToP90: Boolean = true,
    /**
     * 0.5.23 (lifted, the budget went straight to the ceiling, 15.7 ms of 16.67 in 3D and 7.3
     * of 8.33 in 2D, for minutes, with 0 to 3 % missing and the draws' p90 at 8.4 to 10 ms; pose to photons 32 to
     * 40 ms): a budget at the draws' p90 always misses a few frames, so 0.5.22's climb while over [missTarget]
     * (1 %) never stopped. Lifted, the cap now climbs [missStepMs] a judgement only while over [liftClimbShare]
     * (5 %), eases back [missStepMs] a judgement (about a second) while under [missTarget], holds between, and is
     * never over the draws' p99 + [marginMs] ([liftToP99]). False is 0.5.22.
     */
    val liftToP99: Boolean = true,
    val liftClimbShare: Double = 0.05,
    /**
     * 0.5.24 (lifted, the budget still climbed to 15.7 ms of 16.67 and stayed there for 2 minutes,
     * with 2.5 to 6.7 % missing and p99 17 to 28 ms): the misses at that budget were draws longer than the whole
     * frame, which miss at any budget, so the share over 5 % never fell. Now a miss counts toward the lift and its
     * climb only when its draw was no longer than the ceiling (a period less [periodSpareMs]): a miss the budget could
     * have saved. False is 0.5.23 (every miss counts).
     */
    val saveableOnly: Boolean = true,
    /** 0.5.24 (the same finding): lifted, the cap is at most the draws' p90 + [marginMs] + this, rather than their p99 + [marginMs]. */
    val liftOverP90Ms: Float = 2f,
    /** 0.5.55: [passSized]'s margin over the passes' p99, ms. */
    val passMarginMs: Float = 0.5f,
    /** 0.5.55: [passSized]'s floor, ms: the warp pass (0.7 ms of GPU) plus its CPU side and the flush, with room. */
    val passFloorMs: Float = 3f,
) {
    /**
     * 0.5.55 (Mike's OK; the budget sat at 9.3 ms, the half
     * frame cap plus the thermal 1 ms, while the late warp's final pass needs about 0.7 ms of GPU): on while the late warp
     * runs, the budget is sized from the final pass as measured, the time from the planned wake to its GPU done
     * ([addPass]: wake lateness, the pose, the pass's CPU side, its wait behind other GPU work and its GPU time), at its
     * p99 plus [passMarginMs] and never under [passFloorMs]. The misses' back-off ([extraMs]) and the cap stay; the
     * thermal [pressureMs] is left out, as the p99 already follows a slower GPU. Turning it on or off starts the passes over.
     */
    var passSized = false
        set(value) {
            if (field != value) {
                passes.clear()
                passSinceSort = 15
            }
            field = value
        }
    private val passes = RollingStats(600)
    private var passP99 = 0.0
    private var passSinceSort = 15

    /** 0.5.55: one frame's planned wake to GPU done, ns. */
    fun addPass(ns: Long) {
        passes.add(ns / 1e6)
        if (passSinceSort < 15) passSinceSort++
    }

    /** 0.5.55: the passes' p99, ms, re-sorted every 15 frames as [budgetPercentileMs]. */
    val passP99Ms: Double
        get() {
            if (passSinceSort >= 15) {
                passP99 = passes.percentile(0.99)
                passSinceSort = 0
            }
            return passP99
        }

    /** 0.5.55: whether the budget is sized from the passes now ([passSized] and 30 of them measured). */
    val passSizing: Boolean get() = passSized && passes.count >= 30

    /** 0.5.55: the budget the passes ask for before the back-off and the cap, ms. */
    val passBudgetMs: Double get() = maxOf(passFloorMs.toDouble(), passP99Ms + passMarginMs)

    /** 0.5.55: for the late draw line. */
    fun passLine(periodNs: Long): String = if (!passSized) "final pass sizing off (no late warp)"
    else if (!passSizing) "final pass sizing: %d of 30 passes measured".format(Locale.ROOT, passes.count)
    else String.format(
        Locale.ROOT,
        "final pass sized: wake to GPU done p50 %.2f p99 %.2f ms + %.1f ms margin (floor %.1f ms) + %.1f ms the misses added = margin %.2f ms before the deadline (at most %.2f ms)",
        passes.median(), passP99Ms, passMarginMs, passFloorMs, extraMs, budgetNs(periodNs) / 1e6, capMs(periodNs) + pressureMs,
    )

    /** 0.5.24: the last [missWindow] and [liftWindow] frames' saveable misses ([saveableOnly]). */
    private val saveable = ArrayDeque<Boolean>()
    private var saveableCount = 0
    private val longSaveable = ArrayDeque<Boolean>()
    private var longSaveableCount = 0

    /** 0.5.24: misses since the last [clear] whose draw was longer than the ceiling, which no budget could save. */
    var unsaveableMisses = 0L
        private set

    /** 0.5.24: the share of the last [missWindow] frames that missed with a draw the budget could have saved. */
    val saveableShare: Double get() = if (saveable.isEmpty()) 0.0 else saveableCount.toDouble() / saveable.size
    private val longSaveableShare: Double get() = if (liftWindow <= 0 || longSaveable.isEmpty()) saveableShare else longSaveableCount.toDouble() / longSaveable.size

    /** 0.5.24: the ceiling the last [capMs] worked out (a period less [periodSpareMs]), ms; 0 before the first. */
    private var ceilingMs = 0.0
    /** 0.5.22: the last [liftWindow] frames' outcomes, for the lift. */
    private val longMisses = ArrayDeque<Boolean>()
    private var longMissCount = 0

    /** 0.5.22: the share of the last [liftWindow] frames that missed (the short window's share when it is off). */
    val longMissShare: Double get() = if (liftWindow <= 0 || longMisses.isEmpty()) missShare else longMissCount.toDouble() / longMisses.size
    private val draws = RollingStats(window)
    private var cachedP = 0.0
    private var sinceSort = 15

    fun add(drawNs: Long) {
        draws.add(drawNs / 1e6)
        if (sinceSort < 15) sinceSort++
    }

    /** Re-sorted every 15 draws, not every frame: a quarter second at 60 Hz is soon enough. */
    val budgetPercentileMs: Double
        get() {
            if (sinceSort >= 15) {
                cachedP = draws.percentile(percentile)
                sinceSort = 0
            }
            return cachedP
        }
    val p99Ms: Double get() = draws.percentile(0.99)
    val p90Ms: Double get() = draws.percentile(0.90)
    val p50Ms: Double get() = draws.median()

    /** 0.5.15: the margin the misses added, ms, and whether the half frame cap is lifted. */
    var extraMs = 0f
        private set
    var capLifted = false
        private set
    private val misses = ArrayDeque<Boolean>()
    private var missCount = 0
    private var sinceJudged = 0
    private var atCap = false

    /** 0.5.15: the share of the last [missWindow] frames that missed their deadline. */
    val missShare: Double get() = if (misses.isEmpty()) 0.0 else missCount.toDouble() / misses.size

    /**
     * 0.5.15: one frame's outcome; the margin and the cap follow the misses, judged every half window. 0.5.24: with
     * [drawNs] (the frame's pose to GPU done), a miss whose draw was longer than the ceiling is counted apart
     * ([unsaveableMisses]) and left out of the lift ([saveableOnly]); without it every miss counts, as before.
     */
    fun outcome(missed: Boolean, drawNs: Long? = null) {
        if (maxShare == null) return
        misses.addLast(missed)
        if (missed) missCount++
        while (misses.size > missWindow) if (misses.removeFirst()) missCount--
        if (liftWindow > 0) {
            longMisses.addLast(missed)
            if (missed) longMissCount++
            while (longMisses.size > liftWindow) if (longMisses.removeFirst()) longMissCount--
        }
        val tooLong = missed && drawNs != null && ceilingMs > 0.0 && drawNs / 1e6 > ceilingMs
        if (tooLong) unsaveableMisses++
        val save = missed && !tooLong
        saveable.addLast(save)
        if (save) saveableCount++
        while (saveable.size > missWindow) if (saveable.removeFirst()) saveableCount--
        if (liftWindow > 0) {
            longSaveable.addLast(save)
            if (save) longSaveableCount++
            while (longSaveable.size > liftWindow) if (longSaveable.removeFirst()) longSaveableCount--
        }
        if (++sinceJudged < missWindow / 2 || misses.size < missWindow / 2) return
        sinceJudged = 0
        // 0.5.24: the lift and its climb judge only the misses a budget could have saved.
        val share = if (saveableOnly) saveableShare else missShare
        val longShare = if (saveableOnly) longSaveableShare else longMissShare
        when {
            share > liftShare && longShare > liftShare && atCap && !capLifted -> {
                capLifted = true
                lifts++
                capEvent = String.format(
                    Locale.ROOT,
                    "late draw: half frame cap lifted (%d this mode): %.1f %% of the last %d frames and %.1f %% of the last %d missed with the budget at the cap %.1f ms (over %.0f %%); draw p50 %.1f, p90 %.1f, p99 %.1f ms; now at most %s",
                    lifts, share * 100, misses.size, longShare * 100, longMisses.size, halfCapMs, liftShare * 100, p50Ms, p90Ms, p99Ms,
                    if (liftToP90) "the draws' p90 + %.1f ms".format(Locale.ROOT, marginMs) else "a period less %.1f ms".format(Locale.ROOT, periodSpareMs),
                )
            }
            share > liftShare && atCap && !capLifted && liftWindow > 0 -> {
                if (burstNoted < 20) {
                    burstNoted++
                    capEvent = String.format(
                        Locale.ROOT,
                        "late draw: half frame cap kept: a burst of %.1f %% missed in the last %d frames, but %.1f %% of the last %d (lifts over %.0f %% of both)",
                        share * 100, misses.size, longShare * 100, longMisses.size, liftShare * 100,
                    )
                }
            }
            // 0.5.22: lifted, the cap climbs from the draws' p90 + margin while misses stay over the target,
            // and eases back as they fall, so it settles at the least that keeps the picture steady.
            // 0.5.23: climbs only over [liftClimbShare], eases back by a whole step under [missTarget], and holds between.
            capLifted && liftToP90 && liftToP99 && share > liftClimbShare -> liftExtraMs = (liftExtraMs + missStepMs).coerceAtMost(liftHeadroomMs())
            capLifted && liftToP90 && liftToP99 && share < missTarget -> liftExtraMs = (liftExtraMs - missStepMs).coerceAtLeast(0f)
            capLifted && liftToP90 && liftToP99 -> Unit
            capLifted && liftToP90 && share > missTarget -> liftExtraMs += 2 * missStepMs
            capLifted && liftToP90 && share < missTarget / 4 -> liftExtraMs = (liftExtraMs - missStepMs / 2).coerceAtLeast(0f)
            share > missTarget && !atCap -> extraMs += missStepMs
            share < missTarget / 4 -> extraMs = (extraMs - missStepMs / 2).coerceAtLeast(0f)
        }
        // Back under the half frame cap once the draws themselves fit under it again.
        if (capLifted && share < missTarget && budgetPercentileMs + marginMs < halfCapMs) {
            capLifted = false
            extraMs = 0f
            liftExtraMs = 0f
            capEvent = String.format(
                Locale.ROOT,
                "late draw: half frame cap back: %.1f %% missed, draw p90 %.1f ms + %.1f under the cap %.1f ms",
                share * 100, budgetPercentileMs, marginMs, halfCapMs,
            )
        }
    }

    /** 0.5.16: times the cap lifted since the last [clear], and the last lift or return, for the log. */
    var lifts = 0
        private set
    private var capEvent: String? = null

    /** The cap's last change, once; null when nothing changed since the last call. */
    fun takeCapEvent(): String? = capEvent.also { capEvent = null }

    private var halfCapMs = 0.0

    /** 0.5.22: bursts that did not lift the cap, logged at most 20 times a mode. */
    private var burstNoted = 0

    /** 0.5.22: how far the lifted cap has climbed over the draws' p90 + margin ([liftToP90]). */
    var liftExtraMs = 0f
        private set

    /** 0.5.14: forget the draws (a new display mode: one eye at 120 Hz is not two eyes at 60). */
    fun clear() {
        draws.clear()
        cachedP = 0.0
        sinceSort = 15
        misses.clear()
        missCount = 0
        longMisses.clear()
        longMissCount = 0
        saveable.clear()
        saveableCount = 0
        longSaveable.clear()
        longSaveableCount = 0
        unsaveableMisses = 0L
        burstNoted = 0
        liftExtraMs = 0f
        sinceJudged = 0
        extraMs = 0f
        capLifted = false
        atCap = false
        halfCapMs = 0.0
        lifts = 0
        capEvent = null
        passes.clear()
        passSinceSort = 15
    }

    /** The most the budget may be for [periodNs], ms. */
    fun capMs(periodNs: Long): Double {
        val period = periodNs / 1e6
        val full = (period - periodSpareMs).coerceAtLeast(minMs.toDouble())
        ceilingMs = full
        val share = maxShare ?: return full
        halfCapMs = (period * share).coerceIn(minMs.toDouble(), full)
        if (!capLifted) return halfCapMs
        if (!liftToP90) return full
        // 0.5.23: never over the draws' p99 + margin; a lift past what the slowest draws need only adds latency.
        // 0.5.24: and never over p90 + margin + [liftOverP90Ms]: the draws past that are the ones no budget saves.
        val top = when {
            saveableOnly -> minOf(full, budgetPercentileMs + marginMs + liftOverP90Ms).coerceAtLeast(halfCapMs)
            liftToP99 -> minOf(full, p99Ms + marginMs).coerceAtLeast(halfCapMs)
            else -> full
        }
        return (budgetPercentileMs + marginMs + liftExtraMs).coerceIn(halfCapMs, top)
    }

    /** 0.5.23: how far the lifted cap may climb over p90 + margin: up to the draws' p99 + margin. 0.5.24: at most [liftOverP90Ms]. */
    private fun liftHeadroomMs(): Float {
        val p99 = (p99Ms - budgetPercentileMs).coerceAtLeast(0.0).toFloat()
        return if (saveableOnly) minOf(p99, liftOverP90Ms) else p99
    }

    /** The budget in ns for a display of [periodNs]. Until 30 frames are measured, [startShare] of the period ([startMs] in 0.5.14). */
    fun budgetNs(periodNs: Long): Long {
        val cap = capMs(periodNs)
        val pass = passSizing
        val ms = if (draws.count < 30 && !pass) {
            startShare?.let { periodNs / 1e6 * it } ?: startMs.toDouble()
        } else if (capLifted && liftToP90) {
            // 0.5.22: lifted, the budget is the climbing cap itself (the draws' p90 + margin + what the misses added).
            cap
        } else if (pass) {
            // 0.5.55: the final pass's own p99 + margin, and what the misses added.
            passBudgetMs + extraMs
        } else {
            budgetPercentileMs + marginMs + extraMs
        }
        atCap = (draws.count >= 30 || pass) && ms >= cap
        // 0.5.28: [pressureMs] on top of both, never past the ceiling (a period less [periodSpareMs]).
        // 0.5.55: not on a budget sized from the passes; the cap keeps it, so it is never over 0.5.54's.
        val p = pressureMs.coerceAtLeast(0f).toDouble()
        if (pass && !(capLifted && liftToP90)) {
            return (ms.coerceIn(minMs.toDouble(), (cap + p).coerceAtMost(ceilingMs.takeIf { it > 0.0 } ?: (cap + p))) * 1e6).toLong()
        }
        return ((ms + p).coerceIn(minMs.toDouble(), (cap + p).coerceAtMost(ceilingMs.takeIf { it > 0.0 } ?: (cap + p))) * 1e6).toLong()
    }

    /**
     * 0.5.28 (full SBS missed the late warp's deadline 5.8 % of frames, bursts to
     * 21.7 %, draw p99 12.5 ms over its 8.3 ms budget, with the phone's thermal status light): the draw starts this much
     * earlier, the half frame cap moved with it. The Space sets 1 ms while its late warp runs and the phone is
     * thermally light or worse (a slower GPU), 0 otherwise.
     */
    var pressureMs = 0f

    companion object {
        /** 0.5.14's budget (p99 + 1 ms up to a period less 1 ms, 4 ms to start), for the before and after tests. */
        fun v0514() = DrawBudget(percentile = 0.99, maxShare = null, startShare = null)
    }
}

/**
 * 0.5.12: draw late, every vsync, and close the loop on the present time.
 *
 * Up to 0.5.11 GLSurfaceView drew straight after the previous swap and the pose was predicted
 * 41.3 ms ahead at 60 Hz (frame start + 2 periods + the glasses' delay). Pocket XR draws a few
 * quads, so instead of a second resample (an asynchronous warp) it draws the whole frame
 * in the last few ms before the frame's latch deadline: [plan] picks the first vsync whose deadline
 * less the [budget] is still ahead, the render thread sleeps to [DrawPlan.wakeNs], then samples the
 * pose for [DrawPlan.presentNs] and draws.
 *
 * The present time is SurfaceFlinger's own expected present from the Choreographer frame timeline,
 * extrapolated from the newest vsync. [resolve] compares each frame's predicted present with what
 * the vsync it was shown on reports when it arrives (its real time and expected present, one
 * period later if the frame missed its deadline). The running median of that error corrects the
 * next predictions ([correctionNs]), and the queue [depth] moves in whole frames with
 * hysteresis: up by one when more than [staleShare] of a [windowFrames] window missed their
 * deadline, down by one when more than [earlyShare] would have made the deadline one frame sooner.
 */
class LateDrawScheduler(
    val budget: DrawBudget = DrawBudget(),
    val windowFrames: Int = 60,
    val staleShare: Float = 0.8f,
    val earlyShare: Float = 0.9f,
    val maxDepth: Int = 3,
    /** Depth changes no more often than this many frames (half a second at 60 Hz). */
    val depthHoldFrames: Int = 30,
    /**
     * 0.5.14 measured missed frames against the later vsync they were shown on and fed that
     * error to the correction, so at the SBS start (8 of 15 frames missed) the correction became
     * +16.64 ms and every pose was predicted a frame too far. 0.5.15: false, missed frames are left
     * out of the correction and the present error.
     */
    val missedInCorrection: Boolean = false,
) {
    companion object {
        /** The most vsyncs one Choreographer callback is split into ([GlassesVsync.maxSplit]). */
        const val MAX_SPLIT = 4

        /** 0.5.14's late draw (p99 budget, missed frames in the correction), for the before and after tests. */
        fun v0514() = LateDrawScheduler(DrawBudget.v0514(), missedInCorrection = true)
    }

    /** 0.5.15: frames left out of the correction because they missed their deadline. */
    var missedNotMeasured = 0L
        private set
    private var last: VsyncSample? = null
    private val pending = ArrayDeque<Pair<DrawPlan, Long>>()
    private val errors = RollingStats(120)
    private val outcomes = ArrayDeque<Outcome>()
    private var sinceDepthChange = 0

    private enum class Outcome { EARLY, ON_TIME, STALE }

    var depth = 0
        private set

    /** Running median of (measured present - predicted present), ns; added to [plan]'s present. */
    var correctionNs = 0L
        private set

    var frames = 0L
        private set
    var stale = 0L
        private set

    /** |predicted - measured present| over the last two seconds, ms. */
    val errorP50Ms: Double get() = errors.median()
    val errorP95Ms: Double get() = errors.percentile(0.95)
    val measured: Int get() = errors.count

    val newest: VsyncSample? get() = last

    /** A vsync arrived ([VsyncSample]). Resolves the frames it can; returns their errors in ns. */
    fun onVsync(s: VsyncSample): List<Long> {
        last = s
        history.addLast(s)
        while (history.size > 16) history.removeFirst()
        return resolve()
    }

    /** The newest vsyncs, for measuring each frame against the report of the vsync it was shown on. */
    private val history = ArrayDeque<VsyncSample>()

    /**
     * Each pending frame is measured against the vsync it was shown on: the one it was planned for
     * plus its depth, one period later per missed deadline. That vsync's own report says when its
     * frame reaches the display; the frame waits until that report is in.
     */
    private fun resolve(): List<Long> {
        val out = ArrayList<Long>(2)
        while (pending.isNotEmpty()) {
            val (plan, doneNs) = pending.first()
            val missed = missedFrames(plan, doneNs)
            if (missed > 0 && !missedInCorrection) {
                pending.removeFirst()
                missedNotMeasured++
                continue
            }
            val due = plan.vsyncNs + (plan.depth.toLong() + missed) * plan.periodNs
            val newest = history.lastOrNull() ?: break
            if (newest.vsyncNs < due - newest.periodNs / 2) break
            pending.removeFirst()
            val shown = history.minByOrNull { abs(it.vsyncNs - due) } ?: continue
            // Too old to be in the history any more: not measured.
            if (abs(shown.vsyncNs - due) > shown.periodNs / 2) continue
            // 0.5.23 (the Choreographer ran at 62.1 to 62.8 Hz following another display while
            // the glasses ran at 60, and the present error p95 read 8 ms): with [glassesGrid] set, the error is counted
            // on the glasses' own vsync grid, the one the pose was predicted for, not on that timeline.
            val grid = glassesGrid
            // 0.5.24: on the frame timeline's terms, the fences' lock taken out ([DrawPlan.shiftNs]).
            val planned = plan.presentNs - plan.shiftNs
            val err = if (grid != null) grid(shown.presentNs) - grid(planned) else shown.presentNs - planned
            out += err
            errors.add(abs(err) / 1e6)
            if (grid != null) onGrid++
            // The model's own error, before any correction: its median is the next correction.
            raw.add((shown.presentNs - (planned - plan.correctionNs)).toDouble())
            correctionNs = raw.median().toLong()
        }
        return out
    }

    private val raw = RollingStats(60)

    /**
     * 0.5.23: the glasses' own present for a present on the frame timeline (the fence's fitted vsync grid and
     * offset), set while the Choreographer follows another display; null counts the error on the timeline.
     */
    var glassesGrid: ((Long) -> Long)? = null

    /** 0.5.23: frames whose present error was counted on the glasses' grid since the last reset. */
    var onGrid = 0L
        private set

    private fun missedFrames(plan: DrawPlan, doneNs: Long): Long =
        if (doneNs <= plan.deadlineNs + plan.depth * plan.periodNs) 0L else ceil((doneNs - plan.deadlineNs - plan.depth * plan.periodNs).toDouble() / plan.periodNs).toLong()

    /**
     * The plan for the next frame drawn at or after [nowNs], or null before the first vsync.
     * The present carries the measured [correctionNs]; the glasses' own delay is the caller's to
     * add to the pose time, never to the stamp.
     */
    fun plan(nowNs: Long, shiftNs: Long = 0L): DrawPlan? {
        val s = last ?: return null
        val period = s.periodNs.coerceAtLeast(1L)
        // 0.5.24: [shiftNs] is the present fences' lock ([PresentFence.lockNs]): the deadline and
        // present the glasses really have, against the frame timeline's. The whole plan moves by it.
        val deadlineOff = s.deadlineNs - s.vsyncNs + shiftNs
        val presentOff = s.presentNs - s.vsyncNs + shiftNs
        val b = budget.budgetNs(period)
        // 0.5.13, item 23: a split callback ([GlassesVsync]) makes the newest vsync one or more
        // periods ahead, so the frame may be for a vsync before it (k < 0), never more than a split
        // before. A newest vsync already past keeps 0.5.12's rule (k >= 0).
        val minK = if (s.vsyncNs > nowNs) -(MAX_SPLIT - 1).toLong() else 0L
        val k = ceil((nowNs + b - deadlineOff - s.vsyncNs).toDouble() / period).toLong().coerceAtLeast(minK)
        val vsync = s.vsyncNs + k * period
        return DrawPlan(
            vsyncNs = vsync,
            periodNs = period,
            wakeNs = vsync + deadlineOff - b,
            deadlineNs = vsync + deadlineOff,
            presentNs = vsync + presentOff + depth * period + correctionNs,
            depth = depth,
            correctionNs = correctionNs,
            shiftNs = shiftNs,
        )
    }

    /**
     * The frame of [plan] was finished (GPU done) at [doneNs] after sampling its pose at [sampleNs].
     * Feeds the budget, the stale and early counts and the depth.
     */
    fun drawn(plan: DrawPlan, sampleNs: Long, doneNs: Long) {
        frames++
        budget.add(doneNs - sampleNs)
        // 0.5.55: and from the planned wake, for a budget sized from the final pass ([DrawBudget.passSized]).
        budget.addPass(doneNs - plan.wakeNs)
        val deadline = plan.deadlineNs + plan.depth * plan.periodNs
        val outcome = when {
            doneNs > deadline -> Outcome.STALE
            plan.depth > 0 && doneNs <= deadline - plan.periodNs -> Outcome.EARLY
            plan.depth == 0 -> Outcome.EARLY
            else -> Outcome.ON_TIME
        }
        if (outcome == Outcome.STALE) stale++
        // 0.5.24: with the draw's length, so a draw longer than a frame is not a miss the budget chases.
        budget.outcome(outcome == Outcome.STALE, doneNs - sampleNs)
        outcomes.addLast(outcome)
        while (outcomes.size > windowFrames) outcomes.removeFirst()
        // 0.5.57: a frame on the glasses frame clock is measured by the fences ([GlassesFrameClock.onTable]), not against
        // Choreographer's vsyncs, so the timeline's correction stays its own.
        if (!plan.glassesClock) {
            pending.addLast(plan to doneNs)
            while (pending.size > 8) pending.removeFirst()
            resolve()
        }
        sinceDepthChange++
        if (outcomes.size >= windowFrames / 2 && sinceDepthChange >= depthHoldFrames) {
            val n = outcomes.size.toFloat()
            val staleN = outcomes.count { it == Outcome.STALE } / n
            val earlyN = outcomes.count { it == Outcome.EARLY } / n
            val next = when {
                staleN > staleShare && depth < maxDepth -> depth + 1
                earlyN > earlyShare && depth > 0 -> depth - 1
                else -> depth
            }
            if (next != depth) {
                depth = next
                sinceDepthChange = 0
                outcomes.clear()
            }
        }
    }

    /**
     * 0.5.14: the display's mode changed (2D at 120 Hz to side by side at 60 Hz, or back). Every
     * vsync, frame, correction, depth and draw time measured at the old rate is dropped, so the late
     * draw plans at the new period from its first vsync instead of carrying the old one's numbers.
     */
    fun reset() {
        last = null
        history.clear()
        pending.clear()
        errors.clear()
        raw.clear()
        outcomes.clear()
        sinceDepthChange = 0
        depth = 0
        correctionNs = 0L
        onGrid = 0L
        budget.clear()
    }

    /**
     * 0.5.15: pose to photons as the model has it for the next frame, ms: the pose is sampled a
     * budget before the latch deadline and the frame is presented at the timeline's present time
     * (plus depth and correction), then the glasses take [glassesMs]. Null before the first vsync.
     */
    fun modelPoseToPhotonsMs(glassesMs: Double): Double? {
        val s = last ?: return null
        val period = s.periodNs.coerceAtLeast(1L)
        return (budget.budgetNs(period) + (s.presentNs - s.deadlineNs) + depth * period + correctionNs) / 1e6 + glassesMs
    }

    /** 0.5.15: the share of frames that missed their deadline since the last reset. */
    val missedShare: Double get() = if (frames == 0L) 0.0 else stale.toDouble() / frames

    /** 0.5.55: the final pass's sizing ([DrawBudget.passLine]) at the line's end, after every figure the log scripts read. */
    fun line(): String = String.format(
        Locale.ROOT,
        "late draw: budget %.1f ms (draw p50 %.1f, p90 %.1f, p99 %.1f ms; p90 + %.1f ms, at most %.1f ms%s), depth %d, present correction %+.2f ms, present error p50 %.2f ms p95 %.2f ms over %d frames%s (%d missed frames left out), %d of %d frames missed their deadline (%.2f %%; last %d frames %.2f %%, %.2f %% that a budget could have saved; %d draws longer than a frame); %s",
        budget.budgetNs(last?.periodNs ?: 16_666_667L) / 1e6, budget.p50Ms, budget.p90Ms, budget.p99Ms, budget.marginMs + budget.extraMs,
        budget.capMs(last?.periodNs ?: 16_666_667L), if (budget.capLifted) ", half frame cap lifted: too many misses (%.1f ms over p90 + margin)".format(Locale.ROOT, budget.liftExtraMs) else "",
        depth, correctionNs / 1e6, errorP50Ms, errorP95Ms, measured, if (glassesGrid != null) " on the glasses' vsync grid (the Choreographer follows another display)" else "",
        missedNotMeasured, stale, frames, missedShare * 100, budget.missWindow, budget.missShare * 100, budget.saveableShare * 100, budget.unsaveableMisses,
        budget.passLine(last?.periodNs ?: 16_666_667L),
    )
}
