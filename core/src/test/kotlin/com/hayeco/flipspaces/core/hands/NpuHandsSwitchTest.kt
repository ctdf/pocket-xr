package com.hayeco.flipspaces.core.hands

import com.hayeco.flipspaces.core.hands.NpuHandsSwitch.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.1.15: Mike's 1.1.14 log (vc145, 11-05): hands started with the NPU on at 15:18:33, he switched "Hands on the NPU" off
 * at 15:19:03.236, and the NPU ran every frame to the end of the log (15:24:29.696): 748 ENN ExecuteModel calls, NPU
 * checks at 15:19:15.910 to 15:20:34.969, "MediaPipe on NPU" on every Camera line. The camera reconnects at 15:20:01.829
 * and 15:20:04.105 kept the same runner, and hands never started again.
 */
class NpuHandsSwitchTest {
    private fun t(clock: String): Long {
        val (h, m, s) = clock.split(":")
        return (h.toLong() * 3600 + m.toLong() * 60) * 1000 + Math.round(s.toDouble() * 1000)
    }

    /** The runner's state as [Step]s change it; [build] steps take [buildMs] on their own thread. */
    private class Runner(var npu: Boolean, var gpu: Boolean, val buildMs: Long = 400, val buildWorks: Boolean = true) {
        var built = false
        var buildingUntil: Long? = null
        var failed = false
        var npuFrames = 0
        var mediaPipeFrames = 0
        var lastNpuFrameAt = -1L
        val steps = mutableListOf<Step>()

        fun frame(now: Long, wanted: Boolean) {
            buildingUntil?.let { if (now >= it) { buildingUntil = null; if (buildWorks) built = true else failed = true } }
            val step = NpuHandsSwitch.step(wanted, npu, built, buildingUntil != null, failed, gpu)
            if (step != Step.KEEP) steps += step
            when (step) {
                Step.DROP -> npu = false
                Step.DISCARD -> built = false
                Step.MEDIAPIPE_TO_CPU -> gpu = false
                Step.BUILD -> buildingUntil = now + buildMs
                Step.INSTALL -> { npu = true; built = false }
                Step.KEEP -> if (!wanted) failed = false
            }
            // Every frame is tracked by one of the two: the switch never leaves a gap.
            if (npu) { npuFrames++; lastNpuFrameAt = now } else mediaPipeFrames++
        }
    }

    private fun session(wantedAt: (Long) -> Boolean, readOnlyAtStart: Boolean): Runner {
        val start = t("15:18:38.900")
        val r = Runner(npu = wantedAt(start), gpu = false)
        var now = start
        while (now <= t("15:24:29.696")) {
            if (!readOnlyAtStart) r.frame(now, wantedAt(now)) else if (r.npu) { r.npuFrames++; r.lastNpuFrameAt = now } else r.mediaPipeFrames++
            now += 33
        }
        return r
    }

    @Test
    fun offWhileHandsRunStopsTheNpuAtTheNextFrame() {
        val off = t("15:19:03.236")
        val wanted = { now: Long -> now < off }
        // 1.1.14: the setting read only at the hands start: the NPU ran to the end of the log.
        val old = session(wanted, readOnlyAtStart = true)
        assertTrue(old.lastNpuFrameAt > t("15:24:29.000"))
        // 1.1.15: the first frame after the switch goes to MediaPipe, and nothing more to the NPU.
        val now = session(wanted, readOnlyAtStart = false)
        assertTrue(now.lastNpuFrameAt in off - 33 until off)
        assertEquals(listOf(Step.DROP), now.steps)
        val frames = (t("15:24:29.696") - t("15:18:38.900")) / 33 + 1
        assertEquals(frames, (now.npuFrames + now.mediaPipeFrames).toLong())
    }

    @Test
    fun onWhileHandsRunMovesMediaPipeOffTheGpuThenSwapsTheNpuInWithoutAGap() {
        val r = Runner(npu = false, gpu = true)
        var now = 0L
        while (now < 2_000) { r.frame(now, wanted = true); now += 33 }
        assertEquals(listOf(Step.MEDIAPIPE_TO_CPU, Step.BUILD, Step.INSTALL), r.steps)
        assertTrue(r.npu)
        // MediaPipe tracked every frame until the NPU's models were built (400 ms).
        assertTrue(r.mediaPipeFrames in 12..14)
        assertEquals(2_000L / 33 + 1, (r.npuFrames + r.mediaPipeFrames).toLong())
    }

    @Test
    fun offDuringTheBuildNeverUsesTheNpu() {
        val r = Runner(npu = false, gpu = false)
        r.frame(0, wanted = true)
        r.frame(33, wanted = false)
        r.frame(500, wanted = false)
        assertEquals(listOf(Step.BUILD, Step.DISCARD), r.steps)
        assertEquals(0, r.npuFrames)
    }

    @Test
    fun aPhoneWithoutTheNpuTriesOncePerSwitchOn() {
        val r = Runner(npu = false, gpu = false, buildWorks = false)
        var now = 0L
        while (now < 2_000) { r.frame(now, wanted = true); now += 33 }
        assertEquals(listOf(Step.BUILD), r.steps)
        r.frame(now, wanted = false)
        r.frame(now + 33, wanted = true)
        assertEquals(listOf(Step.BUILD, Step.BUILD), r.steps)
        assertEquals(0, r.npuFrames)
    }

    @Test
    fun offNeverBuildsOrInstallsTheNpuInAnyState() {
        val b = listOf(false, true)
        for (active in b) for (built in b) for (building in b) for (failed in b) for (gpu in b) {
            val step = NpuHandsSwitch.step(false, active, built, building, failed, gpu)
            val state = "active=$active built=$built building=$building failed=$failed gpu=$gpu: $step"
            assertTrue(state, step in setOf(Step.KEEP, Step.DROP, Step.DISCARD))
            assertEquals(state, active, step == Step.DROP)
        }
    }

    @Test
    fun offFromTheHandsStartSendsNothingToTheNpuAllSession() {
        // Off at the start (no NPU engine made) and left off: no step at all, every frame on MediaPipe.
        val r = session({ false }, readOnlyAtStart = false)
        assertTrue(r.steps.isEmpty())
        assertEquals(0, r.npuFrames)
        assertEquals(-1L, r.lastNpuFrameAt)
    }

    @Test
    fun theTrackingLineNamesTheNpu() {
        val npu = List(30) { FrameSample(20, 8, 6, true, 0.98f, ranOn = TrackingStats.NPU) }
        assertEquals("NPU", TrackingStats.delegateLabel(npu, TrackingStats.CPU))
    }
}
