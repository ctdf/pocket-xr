package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.55 (findings-choppiness-vc70-vc74 9.3 item 3): while the late warp runs the margin before the deadline is sized
 * from the final pass as measured (planned wake to GPU done, p99 + 0.5 ms, at least 3 ms), not the draws' p90 + 1 ms
 * up to the half frame cap; the misses' back-off and the cap stay. And [RollingStats] sorts without a new array.
 */
class FinalPass0555Test {
    private val period = 16_666_667L
    private fun ms(v: Double) = (v * 1e6).toLong()

    /** 0.5.54's budget's inputs in the forced SBS test: draws p50 3, p90 about 8; a pass of 0.7 ms GPU behind waits. */
    private fun fed(passes: (Int) -> Double, draws: (Int) -> Double = { if (it % 5 == 0) 8.5 else 3.0 }): DrawBudget {
        val b = DrawBudget()
        repeat(600) {
            b.add(ms(draws(it)))
            b.addPass(ms(passes(it)))
        }
        return b
    }

    @Test
    fun withoutTheWarpTheBudgetIsAsBefore() {
        val b = fed({ 1.5 })
        // p90 of the draws (8.5, one in five) + 1 ms, at the half frame cap.
        assertEquals(period / 2 / 1e6, b.budgetNs(period) / 1e6, 0.01)
        assertTrue(b.passLine(period).startsWith("final pass sizing off"))
    }

    @Test
    fun aShortPassGivesAShortMarginNeverUnderTheFloor() {
        val b = fed({ 1.0 + (it % 7) * 0.1 })
        b.passSized = true
        // Turning it on starts the passes over: 30 needed first, the old budget until then.
        assertEquals(period / 2 / 1e6, b.budgetNs(period) / 1e6, 0.01)
        repeat(100) { b.addPass(ms(1.0 + (it % 7) * 0.1)) }
        // p99 1.6 + 0.5 = 2.1, under the 3 ms floor.
        assertEquals(3.0, b.budgetNs(period) / 1e6, 0.01)
        assertTrue(b.passLine(period), b.passLine(period).startsWith("final pass sized: wake to GPU done"))
    }

    @Test
    fun theMarginFollowsThePassesP99() {
        val b = DrawBudget()
        b.passSized = true
        repeat(600) {
            b.add(ms(3.0))
            // 1 in 50 passes waits 4 ms behind other GPU work: p99 5.0.
            b.addPass(ms(if (it % 50 == 0) 5.0 else 2.0))
        }
        assertEquals(5.5, b.budgetNs(period) / 1e6, 0.01)
    }

    @Test
    fun theThermalPressureIsLeftOutButTheCapStays() {
        val b = DrawBudget()
        b.passSized = true
        repeat(600) { b.add(ms(3.0)); b.addPass(ms(4.0)) }
        b.pressureMs = 1f
        assertEquals(4.5, b.budgetNs(period) / 1e6, 0.01)
        // A tail past the cap: never over 0.5.54's cap with its pressure (8.33 + 1).
        repeat(600) { b.addPass(ms(14.0)) }
        repeat(15) { b.addPass(ms(14.0)) }
        assertEquals(period / 2 / 1e6 + 1.0, b.budgetNs(period) / 1e6, 0.01)
    }

    @Test
    fun missesStillAddToTheMargin() {
        val b = DrawBudget()
        b.passSized = true
        repeat(600) { b.add(ms(2.0)); b.addPass(ms(2.0)) }
        val before = b.budgetNs(period)
        // 5 % missing for 120 frames: two judgements, each +0.5 ms.
        repeat(120) { b.outcome(it % 20 == 0, ms(2.0)) }
        assertTrue(b.extraMs > 0f)
        assertEquals(before / 1e6 + b.extraMs, b.budgetNs(period) / 1e6, 0.01)
    }

    @Test
    fun theSchedulerFeedsThePassFromThePlannedWake() {
        val s = LateDrawScheduler()
        s.budget.passSized = true
        var v = 1_000_000_000L
        repeat(60) {
            s.onVsync(VsyncSample(v, period, v + ms(10.0), v + ms(20.0)))
            val plan = s.plan(v)!!
            // Woke 1 ms late, the pass done 2 ms after that: 3 ms from the planned wake.
            s.drawn(plan, plan.wakeNs + ms(1.0), plan.wakeNs + ms(3.0))
            v += period
        }
        assertEquals(3.0, s.budget.passP99Ms, 0.01)
        assertTrue(s.line(), s.line().contains("; final pass sized: wake to GPU done p50 3.00 p99 3.00 ms"))
    }

    @Test
    fun percentilesAreTheSameWithoutTheCopy() {
        val r = RollingStats(240)
        val ref = ArrayList<Double>()
        val rnd = java.util.Random(55)
        repeat(500) {
            val x = rnd.nextGaussian() * 3 + 5
            r.add(x)
            ref.add(x)
            if (ref.size > 240) ref.removeAt(0)
            if (it % 37 == 0) {
                val sorted = ref.sorted()
                for (p in doubleArrayOf(0.1, 0.5, 0.9, 0.99)) {
                    val rank = kotlin.math.ceil(p * sorted.size).toInt().coerceIn(1, sorted.size) - 1
                    assertEquals(sorted[rank], r.percentile(p), 0.0)
                }
            }
        }
    }
}
