package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.12: the render thread wakes a measured budget before each frame's latch
 * deadline, and the present time it predicts the pose for is checked against what each vsync
 * reports, with the queue depth moving in whole frames.
 */
class LateDrawTest {
    private val ms = 1_000_000L

    /** A 60 Hz timeline as Android 13+ reports it: deadline 15 ms after the vsync, present 15 ms after that. */
    private fun vsync(i: Long, periodNs: Long = 16_666_667L, jitterNs: Long = 0L, presentAfterDeadline: Long = 15 * ms): VsyncSample {
        val v = 1_000 * ms + i * periodNs + jitterNs
        return VsyncSample(v, periodNs, v + 15 * ms, v + 15 * ms + presentAfterDeadline)
    }

    @Test
    fun noPlanBeforeTheFirstVsync() {
        assertNull(LateDrawScheduler().plan(5 * ms))
    }

    @Test
    fun theFrameIsDrawnABudgetBeforeItsDeadline() {
        // 0.5.15: a 4 ms start set by hand (the default is now half a frame, SbsLateDrawTest).
        val s = LateDrawScheduler(DrawBudget(startMs = 4f, startShare = null))
        val v = vsync(0)
        s.onVsync(v)
        val plan = s.plan(v.vsyncNs + 1 * ms)!!
        assertEquals(v.vsyncNs, plan.vsyncNs)
        assertEquals(v.deadlineNs - 4 * ms, plan.wakeNs)
        assertEquals(v.presentNs, plan.presentNs)
        // Too late for this vsync's deadline: the next one.
        val later = s.plan(v.deadlineNs - 3 * ms)!!
        assertEquals(v.vsyncNs + v.periodNs, later.vsyncNs)
        assertTrue(later.wakeNs >= v.deadlineNs - 3 * ms)
    }

    @Test
    fun theBudgetIsTheMeasuredP99PlusAMillisecond() {
        // 0.5.14's rule, kept as DrawBudget.v0514() (0.5.15's is in SbsLateDrawTest).
        val b = DrawBudget.v0514()
        assertEquals(4 * ms, b.budgetNs(16_666_667L))
        repeat(99) { b.add(1_500_000L) }
        b.add(2_400_000L)
        repeat(20) { b.add(1_500_000L) }
        // p99 of 120 draws: the slow one is the top 1 %, so p99 is 1.5 ms and the budget 2.5 ms.
        assertEquals(2.5, b.budgetNs(16_666_667L) / 1e6, 0.01)
        // Never more than a period less 1 ms.
        repeat(240) { b.add(30 * ms) }
        assertEquals(7.33, b.budgetNs(8_333_333L) / 1e6, 0.01)
    }

    @Test
    fun theStampIsBeforeThePresentItIsMeantFor() {
        for (hz in listOf(30.0, 60.0, 120.0)) {
            val p = (1e9 / hz).toLong()
            val s = LateDrawScheduler()
            val v = vsync(0, p)
            s.onVsync(v)
            val plan = s.plan(v.vsyncNs)!!
            assertTrue(plan.stampNs < plan.presentNs)
            assertTrue(plan.stampNs > plan.presentNs - p)
        }
    }

    /**
     * Runs [seconds] of frames: each vsync is reported at its time, the thread wakes at the plan,
     * draws for [drawMs], and the frame is late when [late] says so: done 2 ms after the deadline
     * of the vsync it was drawn for, as when SurfaceFlinger holds it one vsync.
     */
    private fun run(s: LateDrawScheduler, seconds: Double, late: (Int) -> Boolean, drawMs: Double = 1.5, jitter: (Int) -> Long = { 0L }, from: Int = 0): Int {
        val n = (seconds * 60).toInt()
        var depthAt = -1
        for (i in from until from + n) {
            val v = vsync(i.toLong(), jitterNs = jitter(i))
            s.onVsync(v)
            val plan = s.plan(v.vsyncNs) ?: continue
            val sample = plan.wakeNs
            val done = if (late(i)) plan.deadlineNs + 2 * ms else sample + (drawMs * ms).toLong()
            s.drawn(plan, sample, done)
            if (depthAt < 0 && s.depth > 0) depthAt = i - from
        }
        return depthAt
    }

    @Test
    fun aDisplayThatHoldsMostFramesOneVsyncGetsADepthOfOneWithinTwoSeconds() {
        val s = LateDrawScheduler()
        val rnd = java.util.Random(5)
        val at = run(s, 3.0, late = { rnd.nextFloat() < 0.85f })
        assertEquals(1, s.depth)
        assertTrue("depth 1 after $at frames", at in 0..120)
        // Once frames make the deadline again, the depth comes back down.
        run(s, 2.0, late = { false }, from = 180)
        assertEquals(0, s.depth)
    }

    @Test
    fun anOccasionalLateFrameNeverRaisesTheDepth() {
        val s = LateDrawScheduler()
        val rnd = java.util.Random(6)
        run(s, 10.0, late = { rnd.nextFloat() < 0.1f })
        assertEquals(0, s.depth)
    }

    @Test
    fun thePredictedPresentIsWithinAMillisecondOfTheMeasuredOne() {
        val s = LateDrawScheduler()
        val rnd = java.util.Random(7)
        // Vsync times jitter by up to 0.3 ms around the period; 2 % of frames miss their deadline.
        run(s, 60.0, late = { rnd.nextFloat() < 0.02f }, jitter = { (rnd.nextGaussian() * 0.15 * ms).toLong() })
        println(s.line())
        assertTrue("median ${s.errorP50Ms}", s.errorP50Ms <= 1.0)
        assertTrue("p95 ${s.errorP95Ms}", s.errorP95Ms <= 3.0)
        assertTrue(s.measured > 60)
    }

    @Test
    fun aConstantOffsetIsLearnedAndCorrected() {
        val s = LateDrawScheduler()
        // Each frame is planned one vsync ahead of the newest report, whose period reads 1.2 ms
        // short of the real spacing: every present comes 1.2 ms after the extrapolated one.
        val real = 16_666_667L + 1_200_000L
        repeat(300) { i ->
            val v = 1_000 * ms + i * real
            s.onVsync(VsyncSample(v, 16_666_667L, v + 15 * ms, v + 30 * ms))
            val plan = s.plan(v + 15 * ms - 1 * ms)!!
            s.drawn(plan, plan.wakeNs, plan.wakeNs + 1_500_000L)
        }
        assertEquals(1.2, s.correctionNs / 1e6, 0.05)
        assertTrue("median ${s.errorP50Ms}", s.errorP50Ms <= 0.1)
        assertNotNull(s.newest)
    }

    @Test
    fun rollingStatsPercentiles() {
        val r = RollingStats(100)
        (1..100).forEach { r.add(it.toDouble()) }
        assertEquals(50.0, r.median(), 0.0)
        assertEquals(95.0, r.percentile(0.95), 0.0)
        assertEquals(99.0, r.percentile(0.99), 0.0)
        (1..50).forEach { r.add(1000.0) }
        assertEquals(1000.0, r.percentile(0.95), 0.0)
    }

    @Test
    fun schedulingCostsMicrosecondsAFrame() {
        val s = LateDrawScheduler()
        val p = HeadPredictor()
        // Warm up, then time 20 000 frames of vsync, plan, a pose, drawn.
        fun frames(n: Int, from: Int) {
            for (i in from until from + n) {
                val v = vsync(i.toLong())
                s.onVsync(v)
                val plan = s.plan(v.vsyncNs)!!
                p.add(Quat.IDENTITY, Vec3(0.1f, 0f, 0f), plan.wakeNs - 1_000_000L)
                p.predict(plan.presentNs)
                s.drawn(plan, plan.wakeNs, plan.wakeNs + 1_500_000L)
            }
        }
        frames(5_000, 0)
        val t0 = System.nanoTime()
        frames(20_000, 5_000)
        val us = (System.nanoTime() - t0) / 1e3 / 20_000
        println("late draw bookkeeping: %.1f us a frame on this JVM".format(java.util.Locale.ROOT, us))
        assertTrue("$us us", us < 100.0)
    }
}
