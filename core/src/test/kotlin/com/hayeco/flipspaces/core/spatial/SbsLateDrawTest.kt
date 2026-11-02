package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.ln

/**
 * 0.5.15, from the 0.5.14 test: SBS at 60 Hz. 13 % of frames missed (49 fps of 60), draw p50
 * 8.9 ms and p99 20.2 ms pinned the late draw's budget at 15.7 ms, so pose to photons was 31.9 ms,
 * and at the SBS start missed frames made a +16.64 ms present correction.
 *
 * The display here is the glasses' SBS timeline as the 0.5.14 test measured it: 60 Hz, the frame due
 * 8.2 ms after its latch deadline (31.9 ms pose to photons = 15.7 budget + 8.2 + the 8 ms glasses
 * delay). Draw times are log normal, fitted to a p50 and a p99. What the draw costs after 0.5.15's
 * cuts (mipmaps and display resizes out of the eye pass, one filter tap in SBS) can only be measured
 * on the glasses; the "after" draws here are the target, p99 under 8 ms.
 */
class SbsLateDrawTest {
    private val ms = 1_000_000L
    private val period = 16_666_667L
    private val glassesMs = 8.0

    private fun vsync(i: Long): VsyncSample {
        val v = 1_000 * ms + i * period
        val deadline = v + 15 * ms
        return VsyncSample(v, period, deadline, deadline + 8_200_000L)
    }

    /** Log normal draw times with this [p50] and [p99], ms. */
    private class Draws(val p50: Double, val p99: Double, seed: Long) {
        private val rnd = java.util.Random(seed)
        private val sigma = ln(p99 / p50) / 2.326
        fun next(): Long = (p50 * exp(sigma * rnd.nextGaussian()) * 1e6).toLong()
    }

    data class Run(val missedShare: Double, val poseToPhotonsP50: Double, val budgetMs: Double, val correctionMs: Double, val s: LateDrawScheduler)

    /**
     * [seconds] of frames. Each vsync is reported; the thread wakes at the plan, samples the pose,
     * draws for the next draw time; pose to photons is the plan's present plus the glasses' delay
     * less the sample time, one period later for a missed frame (it is shown a vsync late).
     */
    private fun run(s: LateDrawScheduler, draws: Draws, seconds: Double, from: Int = 0, skipFirst: Int = 120): Run {
        val n = (seconds * 60).toInt()
        val p2p = RollingStats(10_000)
        var missed = 0
        var counted = 0
        for (i in from until from + n) {
            val v = vsync(i.toLong())
            s.onVsync(v)
            val plan = s.plan(v.vsyncNs) ?: continue
            val sample = plan.wakeNs
            val done = sample + draws.next()
            s.drawn(plan, sample, done)
            if (i - from < skipFirst) continue
            counted++
            val late = done > plan.deadlineNs + plan.depth * plan.periodNs
            if (late) missed++
            val shown = plan.presentNs + (if (late) plan.periodNs else 0L)
            p2p.add((shown - sample) / 1e6 + glassesMs)
        }
        return Run(missed.toDouble() / counted.coerceAtLeast(1), p2p.median(), s.budget.budgetNs(period) / 1e6, s.correctionNs / 1e6, s)
    }

    private fun Run.line(name: String) = "%s: %.2f %% of frames missed, pose to photons p50 %.1f ms (model %.1f), budget %.1f ms, correction %+.2f ms".format(
        java.util.Locale.ROOT, name, missedShare * 100, poseToPhotonsP50, s.modelPoseToPhotonsMs(glassesMs) ?: 0.0, budgetMs, correctionMs,
    )

    @Test
    fun the0514BudgetReproducesTheMeasuredTiming() {
        val r = run(LateDrawScheduler.v0514(), Draws(8.9, 20.2, 3), 60.0)
        println("  " + r.line("0.5.14 rules, 0.5.14 draws"))
        // Budget pinned at a period less 1 ms, pose to photons about 32 ms.
        assertEquals(15.67, r.budgetMs, 0.05)
        assertEquals(31.9, r.poseToPhotonsP50, 0.6)
    }

    @Test
    fun theNewBudgetMeetsTheTargetsOnTheTargetDraws() {
        // The target for the cut draw: p99 under 8 ms (p50 5).
        val r = run(LateDrawScheduler(), Draws(5.0, 7.5, 4), 120.0)
        val old = run(LateDrawScheduler.v0514(), Draws(5.0, 7.5, 4), 120.0)
        println("  " + r.line("0.5.15 rules, target draws"))
        println("  " + old.line("0.5.14 rules, target draws"))
        println("  " + r.s.line())
        assertTrue("missed ${r.missedShare}", r.missedShare < 0.01)
        assertTrue("pose to photons ${r.poseToPhotonsP50}", r.poseToPhotonsP50 < 25.0)
        assertTrue("model ${r.s.modelPoseToPhotonsMs(glassesMs)}", r.s.modelPoseToPhotonsMs(glassesMs)!! < 25.0)
        // Never more than half a frame.
        assertTrue(r.budgetMs <= period / 2e6 + 1e-6)
    }

    @Test
    fun drawsTheCutDidNotShrinkLiftTheCapRatherThanJudder() {
        // If the draw stays at 0.5.14's 8.9 / 20.2 ms, half a frame is too little: the misses lift
        // the cap and the picture stays steady, at 0.5.14's latency. Logged as such.
        val r = run(LateDrawScheduler(), Draws(8.9, 20.2, 5), 60.0)
        val old = run(LateDrawScheduler.v0514(), Draws(8.9, 20.2, 5), 60.0)
        println("  " + r.line("0.5.15 rules, 0.5.14 draws"))
        println("  " + r.s.line())
        assertTrue(r.s.budget.capLifted)
        assertTrue(r.s.line(), r.s.line().contains("half frame cap lifted"))
        // No worse than 0.5.14 on the same draws (its p99 is over a period: some still miss). 0.5.24: within 2 % (was 1):
        // lifted, the budget climbs only while over 5 % of frames miss with a draw it could have saved, so it stays at
        // the draws' p90 + 1 ms (15.0 here) rather than the 15.7 ms ceiling, for about 1.5 % more misses.
        assertTrue("missed ${r.missedShare} against ${old.missedShare}", r.missedShare < old.missedShare + 0.02)
        assertTrue("the misses a budget could have saved stay under the climb line: ${r.s.budget.saveableShare}", r.s.budget.saveableShare <= 0.06)
    }

    /** Draws that are mostly quick with an occasional slow one (a texture build), at [spikeShare]. */
    private class Spiky(val base: Draws, val spikeShare: Double, val spikeMs: Double, seed: Long) {
        private val rnd = java.util.Random(seed)
        fun next(): Long = if (rnd.nextDouble() < spikeShare) (spikeMs * 1e6).toLong() else base.next()
    }

    @Test
    fun rareSlowDrawsNoLongerPinTheBudget() {
        // 2 % of frames take 14 ms (in 0.5.14 a mipmap build inside the eye pass did this): the p99
        // rule pins the budget at 15 ms for every frame; the p90 rule misses those 2 % and samples
        // the pose 7 ms later on the rest.
        fun go(s: LateDrawScheduler): Run {
            val d = Spiky(Draws(5.0, 7.5, 8), 0.02, 14.0, 9)
            val p2p = RollingStats(10_000)
            var missed = 0
            for (i in 0 until 7200) {
                val v = vsync(i.toLong())
                s.onVsync(v)
                val plan = s.plan(v.vsyncNs) ?: continue
                val done = plan.wakeNs + d.next()
                s.drawn(plan, plan.wakeNs, done)
                if (i < 120) continue
                val late = done > plan.deadlineNs + plan.depth * plan.periodNs
                if (late) missed++
                p2p.add((plan.presentNs + (if (late) plan.periodNs else 0L) - plan.wakeNs) / 1e6 + glassesMs)
            }
            return Run(missed / 7080.0, p2p.median(), s.budget.budgetNs(period) / 1e6, s.correctionNs / 1e6, s)
        }
        val new = go(LateDrawScheduler())
        val old = go(LateDrawScheduler.v0514())
        println("  rare 14 ms draws: " + new.line("0.5.15") + "; " + old.line("0.5.14"))
        assertTrue("0.5.14 budget ${old.budgetMs}", old.budgetMs > 14.0)
        assertTrue("0.5.15 budget ${new.budgetMs}", new.budgetMs <= 8.34)
        assertTrue("pose to photons ${new.poseToPhotonsP50}", new.poseToPhotonsP50 < 25.0)
        assertTrue("missed ${new.missedShare}", new.missedShare < 0.03)
    }

    @Test
    fun aDrawJustOverHalfAFrameGrowsTheMarginNotTheMisses() {
        // p50 6, p99 9.5: p90 + 1 ms is near the cap; the miss feedback keeps misses near 1 %.
        val r = run(LateDrawScheduler(), Draws(6.0, 8.6, 6), 120.0)
        println("  " + r.line("0.5.15 rules, p50 6 p99 8.6 draws"))
        assertTrue("missed ${r.missedShare}", r.missedShare < 0.02)
        assertTrue("pose to photons ${r.poseToPhotonsP50}", r.poseToPhotonsP50 < 25.0)
    }

    @Test
    fun missedFramesAtTheStartMakeNoCorrection() {
        // The SBS start: draws of 9 to 22 ms against the starting budget. 0.5.14 learned +16.64 ms.
        val draws = Draws(14.0, 22.0, 7)
        val old = LateDrawScheduler.v0514()
        val new = LateDrawScheduler()
        for (i in 0 until 15) {
            for (s in listOf(old, new)) {
                val v = vsync(i.toLong())
                s.onVsync(v)
                val plan = s.plan(v.vsyncNs)!!
                s.drawn(plan, plan.wakeNs, plan.wakeNs + draws.next())
            }
        }
        // Let the last vsyncs in so every frame is resolved.
        for (i in 15 until 20) {
            old.onVsync(vsync(i.toLong()))
            new.onVsync(vsync(i.toLong()))
        }
        println("  SBS start: 0.5.14 correction %+.2f ms (%d of 15 missed), 0.5.15 %+.2f ms (%d of 15 missed, %d left out); 0.5.15 starts at a %.1f ms budget".format(
            java.util.Locale.ROOT, old.correctionNs / 1e6, old.stale, new.correctionNs / 1e6, new.stale, new.missedNotMeasured, DrawBudget().budgetNs(period) / 1e6,
        ))
        assertTrue("0.5.14 ${old.correctionNs / 1e6}", old.correctionNs / 1e6 > 10.0)
        assertEquals(0.0, new.correctionNs / 1e6, 0.5)
        // The new start budget is half a frame, not 4 ms.
        assertEquals(8.33, DrawBudget().budgetNs(period) / 1e6, 0.01)
    }

    @Test
    fun theBudgetIsP90PlusAMillisecondCappedAtHalfAFrame() {
        val b = DrawBudget()
        repeat(90) { b.add(3 * ms) }
        repeat(10) { b.add(12 * ms) }
        // p90 3 ms + 1: the slow tenth no longer sets it (0.5.14: p99 12 + 1, capped at 15.7).
        assertEquals(4.0, b.budgetNs(period) / 1e6, 0.01)
        val old = DrawBudget.v0514()
        repeat(90) { old.add(3 * ms) }
        repeat(10) { old.add(12 * ms) }
        assertEquals(13.0, old.budgetNs(period) / 1e6, 0.01)
        // Capped at half a frame.
        repeat(240) { b.add(30 * ms) }
        assertEquals(8.33, b.budgetNs(period) / 1e6, 0.01)
        assertEquals(4.17, b.budgetNs(8_333_333L) / 1e6, 0.01)
    }
}
