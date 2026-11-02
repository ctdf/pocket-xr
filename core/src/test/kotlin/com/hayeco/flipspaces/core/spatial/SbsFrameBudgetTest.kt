package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln

/**
 * 0.5.18 (the vc38 test: 15.65 % of SBS frames missed, draw p99 24.5 ms, prepare 13.5
 * ms with no panel copies). The late draw's scheduler ([LateDrawScheduler]) run on frame times drawn
 * from the 112 SBS windows of the vc38 log (gpu passes and late draw lines, medians):
 *
 * - 0.5.17: the draw, pose to fence, p50 8.2 ms, p90 16.5, p99 25.1. Its eye passes alone took p50
 *   0.57 ms (both eyes), p99 22.1: the rest of the draw is the window clear and the panel copies,
 *   recorded before the sleep but only submitted after the pose, and the GPU waiting there for the
 *   window buffer SurfaceFlinger still composes on the GPU (13.4 ms prepare p50 with 0 copies).
 * - 0.5.18 flushes the clear and the copies before the sleep, so the draw is the eye passes, and
 *   asks for hardware composition (an opaque window, the surface on top), which takes
 *   SurfaceFlinger's 3840x1080 GPU pass off the GPU the eye passes share.
 *
 * Each draw time is a lognormal through the logged p50 and p99. Not a device measurement: the next
 * test's perf lines say what the Flip does.
 */
class SbsFrameBudgetTest {
    private val periodNs = 16_666_667L

    /** A lognormal's sample with this median and 99th percentile, ms. */
    private fun draw(rnd: java.util.Random, p50: Double, p99: Double): Double {
        val sigma = ln(p99 / p50) / 2.326
        return p50 * exp(sigma * rnd.nextGaussian())
    }

    /** Frames over [seconds] through the scheduler; returns the missed share and the budget at the end. */
    private fun run(p50: Double, p99: Double, seconds: Double = 120.0, seed: Long = 3): Pair<Double, Double> {
        val rnd = java.util.Random(seed)
        val late = LateDrawScheduler()
        var now = 0L
        var nextVsync = 0L
        val end = (seconds * 1e9).toLong()
        fun vsyncsUntil(t: Long) {
            while (nextVsync <= t) {
                late.onVsync(VsyncSample(nextVsync, periodNs, nextVsync + 7_333_333L, nextVsync + 15_666_667L))
                nextVsync += periodNs
            }
        }
        vsyncsUntil(0)
        while (now < end) {
            vsyncsUntil(now)
            val plan = late.plan(now) ?: break
            val wake = maxOf(now, plan.wakeNs)
            vsyncsUntil(wake)
            val done = wake + (draw(rnd, p50, p99) * 1e6).toLong()
            late.drawn(plan, wake, done)
            // The CPU part of a frame after the GPU fence: a few tenths of a millisecond.
            now = done + 300_000L
        }
        return late.missedShare to late.budget.budgetNs(periodNs) / 1e6
    }

    @Test
    fun theDrawWithoutTheWindowBufferWaitMissesFarFewerFrames() {
        val (old, oldBudget) = run(8.2, 25.1)
        val (eyes, eyesBudget) = run(0.57, 22.1)
        val (composed, composedBudget) = run(0.57, 11.0)
        println(
            ("SBS at 60 Hz through the late draw: 0.5.17's draw (p50 8.2, p99 25.1 ms) misses %.1f %% (the log: 15.65 %%), budget %.1f ms; " +
                "the eye passes alone (p50 0.57, p99 22.1) %.1f %%, budget %.1f ms; with their tail halved by hardware composition (p99 11) %.1f %%, budget %.1f ms").format(
                Locale.ROOT, old * 100, oldBudget, eyes * 100, eyesBudget, composed * 100, composedBudget,
            ),
        )
        // The tail the eye passes may have for under 1 % missed: what the next test's gpu passes line must show.
        var limit = 22.1
        while (limit > 1.0 && run(0.57, limit).first >= 0.01) limit -= 0.5
        println("under 1 %% missed needs the eye passes' p99 at or under %.1f ms (vc38: 22.1 ms, of which SurfaceFlinger's GPU composition and MediaPipe share the GPU)".format(Locale.ROOT, limit))
        // The model is near the log's misses.
        assertTrue("0.5.17 model $old", old in 0.06..0.25)
        // Taking the window buffer wait and the copies out of the draw halves the misses; a shorter tail cuts them again.
        assertTrue("eyes $eyes against $old", eyes <= old * 0.6)
        assertTrue("composed $composed against $eyes", composed <= eyes * 0.6)
        assertTrue("limit $limit", limit >= 5.0)
    }
}
