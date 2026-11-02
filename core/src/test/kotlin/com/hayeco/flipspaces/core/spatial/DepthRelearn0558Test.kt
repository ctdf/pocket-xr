package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToLong

/**
 * 0.5.58: the glasses frame clock re-learns a one refresh step in the commits' depth from each
 * frame's own present ([GlassesFrameClock.onShown]) within about a quarter second, where vc78's two 2 s tables in a row
 * took 4 to 10 s (22 times in 22 minutes, 58 % of all faults).
 *
 * The simulation: the clock locked on a 60 Hz grid, the phone's commits at 0.50 of the glasses' period, one frame planned
 * per refresh as the render thread does. SurfaceFlinger shows each frame [trueDepth] refreshes later than the clock's
 * model; the clock poses it [GlassesFrameClock.Plan.depth] later, so it is shown `trueDepth - depth` refreshes off its
 * pose. Each frame's present comes back [lagRefreshes] refreshes after it is shown (the EGL frame timestamps are read
 * once a frame). This is a model of the phone, not a test on it.
 */
class DepthRelearn0558Test {
    private val per = 16_666_700.0
    private val perL = per.roundToLong()

    private class Frame(val plannedNs: Long, val plan: GlassesFrameClock.Plan, val shownNs: Long, val knownNs: Long, val off: Int)

    // 0.5.58's behaviour: a deeper need is followed at once (0.5.62's drain is tested in DepthDrain0562Test).
    private fun lockedClock(): GlassesFrameClock {
        val c = GlassesFrameClock(drainUp = false)
        c.reset(16_666_667L)
        for (i in 0 until 60) c.onPresent((i * per).roundToLong())
        assertTrue(c.locked)
        return c
    }

    private fun simulate(
        clock: GlassesFrameClock,
        seconds: Double,
        trueDepth: (Long) -> Int,
        lagRefreshes: Double = 2.0,
        noise: (Int) -> Int = { 0 },
        perFrame: Boolean = true,
    ): List<Frame> {
        var now = (60 * per).roundToLong() + 1_000_000L
        val end = now + (seconds * 1e9).roundToLong()
        var commitK = 60L
        val frames = ArrayList<Frame>()
        val pending = ArrayList<Frame>()
        while (now < end) {
            // The frame timelines bring each commit about a period ahead.
            while (commitK * per + 0.5 * per <= now + per) {
                val d = (commitK * per + 0.5 * per).roundToLong()
                clock.onCommit(d, d + 7_200_000L, 16_669_800L)
                commitK++
            }
            if (perFrame) {
                val known = pending.filter { it.knownNs <= now }
                for (f in known) clock.onShown(f.plan, f.shownNs)
                pending.removeAll(known.toSet())
            }
            val p = clock.plan(now, 4_000_000L)
            assertNotNull("the clock plans once locked", p)
            p!!
            clock.taken(p)
            val off = trueDepth(p.commitNs) - p.depth + noise(frames.size)
            val shown = p.poseNs + off * perL
            val f = Frame(now, p, shown, shown + (lagRefreshes * per).roundToLong(), off)
            frames += f
            pending += f
            now = maxOf(now + 1, p.readyNs - 1_000_000L)
        }
        return frames
    }

    /** From the first frame shown off its pose after [stepNs] to the first frame shown on it again, ms; and the frames off. */
    private fun correction(frames: List<Frame>, stepNs: Long): Pair<Double, Int> {
        val after = frames.filter { it.plan.commitNs >= stepNs - 2 * perL }
        val firstOff = after.first { it.off != 0 }
        val back = after.first { it.shownNs > firstOff.shownNs && it.off == 0 }
        val offFrames = after.count { it.off != 0 && it.shownNs < back.shownNs }
        return (back.shownNs - firstOff.shownNs) / 1e6 to offFrames
    }

    @Test
    fun aOneRefreshStepLaterIsCorrectedWithinAQuarterSecond() {
        val clock = lockedClock()
        val step = (60 * per).roundToLong() + 3_000_000_000L
        val frames = simulate(clock, 6.0, { c -> if (c >= step) 1 else 0 })
        val (ms, off) = correction(frames, step)
        println("one refresh later: frames on their pose again %.0f ms after the first off one, %d frames off (vc78: 4 to 10 s)".format(ms, off))
        assertTrue("corrected in $ms ms", ms <= 250.0)
        assertEquals(1, clock.depthFix)
        assertEquals("one move, not two", 1, clock.fastMoves)
        // After the move every frame is shown on its pose.
        val tail = frames.filter { it.shownNs > frames.first { f -> f.plan.depth == 1 }.shownNs }
        assertTrue(tail.all { it.off == 0 })
    }

    @Test
    fun aStepEarlierAndBackAreEachCorrectedWithinAQuarterSecond() {
        val clock = lockedClock()
        val t0 = (60 * per).roundToLong()
        // Locked at +1 first (as vc78 ran a third of its locked time), then a step to +0, then +1 again: a creep's start
        // and end both move the depth (seen in the vc78 log).
        val up = t0 + 1_000_000_000L
        val down = t0 + 3_000_000_000L
        val upAgain = t0 + 5_000_000_000L
        val frames = simulate(clock, 7.0, { c -> if (c >= upAgain) 1 else if (c >= down) 0 else if (c >= up) 1 else 0 })
        for ((name, at) in listOf("later" to up, "earlier" to down, "later again" to upAgain)) {
            val (ms, off) = correction(frames, at)
            println("one refresh $name: on their pose again %.0f ms after the first off frame, %d frames off".format(ms, off))
            assertTrue("$name: corrected in $ms ms", ms <= 250.0)
        }
        assertEquals(1, clock.depthFix)
        assertEquals(3, clock.fastMoves)
    }

    @Test
    fun aPresentKnownOneRefreshAfterItIsShownCorrectsSooner() {
        val clock = lockedClock()
        val step = (60 * per).roundToLong() + 2_000_000_000L
        val frames = simulate(clock, 4.0, { c -> if (c >= step) 1 else 0 }, lagRefreshes = 1.0)
        val (ms, _) = correction(frames, step)
        println("present known a refresh after it is shown: corrected in %.0f ms".format(ms))
        assertTrue("corrected in $ms ms", ms <= 220.0)
    }

    @Test
    fun scatteredFramesOffTheirPoseDoNotMoveTheDepth() {
        // Judder, not a depth: 3 of every 16 frames shown a refresh off (more than vc78's 0.7 % locked pose jumps by far).
        val clock = lockedClock()
        simulate(clock, 10.0, { 0 }, noise = { i -> if (i % 16 in setOf(2, 7, 11)) (if (i % 2 == 0) 1 else -1) else 0 })
        assertEquals(0, clock.depthFix)
        assertEquals(0, clock.fastMoves)
        // Frames held behind a stall (5 refreshes off) are no depth either.
        val stalled = lockedClock()
        simulate(stalled, 2.0, { 0 }, noise = { i -> if (i % 4 == 0) 5 else 0 })
        assertEquals(0, stalled.depthFix)
    }

    @Test
    fun withoutPerFramePresentsTheTablesStillMoveTheDepth() {
        // The fallback (a driver without EGL_ANDROID_get_frame_timestamps): 0.5.57's two tables in a row, as before.
        val clock = lockedClock()
        val frames = simulate(clock, 3.0, { 1 }, perFrame = false)
        for (f in frames) clock.record(f.plan.stampNs, f.plan.poseNs, f.plannedNs)
        val line = clock.line(frames.last().plannedNs)
        assertTrue(line, line.contains("depth from the 2 s tables"))
        val half = frames.size / 2
        for (part in listOf(frames.subList(0, half), frames.subList(half, frames.size))) {
            clock.onTable(SfLatency(16_666_667L, part.map { SfFrame(it.plan.stampNs, it.shownNs, it.plan.readyNs) }))
        }
        assertEquals(1, clock.depthFix)
        assertEquals(0, clock.fastMoves)
    }

    @Test
    fun theTablesDoNotMoveTheDepthASecondTimeWhilePerFramePresentsComeBack() {
        val clock = lockedClock()
        val step = (60 * per).roundToLong() + 1_000_000_000L
        val frames = simulate(clock, 4.0, { c -> if (c >= step) 1 else 0 })
        assertEquals(1, clock.depthFix)
        for (f in frames) clock.record(f.plan.stampNs, f.plan.poseNs, f.plannedNs)
        // Two tables of frames posed before the move, all shown a refresh off: 0.5.57 would move the poses to +2.
        val before = frames.filter { it.plan.depth == 0 }.takeLast(60)
        val fake = before.map { SfFrame(it.plan.stampNs, it.plan.poseNs + perL, it.plan.readyNs) }
        clock.onTable(SfLatency(16_666_667L, fake + listOf(SfFrame(frames.last().plan.stampNs, frames.last().shownNs, frames.last().plan.readyNs))))
        clock.onTable(SfLatency(16_666_667L, fake + listOf(SfFrame(frames.last().plan.stampNs, frames.last().shownNs, frames.last().plan.readyNs))))
        assertEquals(1, clock.depthFix)
        val line = clock.line(frames.last().knownNs)
        assertTrue(line, line.contains("depth from each frame's present"))
        assertTrue(line, line.contains("poses moved +1 refresh from each frame's present"))
    }
}
