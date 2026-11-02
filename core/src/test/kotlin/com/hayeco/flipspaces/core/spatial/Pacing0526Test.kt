package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.pointer.PadSignal
import com.hayeco.flipspaces.core.pointer.PointerSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp

/**
 * 0.5.26 (Mike: "everything is a bit choppy, not just videos; the whole picture"): the pacing logic. Each cause the
 * vc46 logs named is run through the code before and after: the window copy gate, the heat rules, the render thread's
 * wait for the GPU, the trackpad's 60 Hz moves on a 120 Hz Space, the late warp's choice at warp time, the glasses'
 * 60 into 90 Hz cadence and the hand model's GPU guard.
 */
class Pacing0526Test {
    private val ms = 1_000_000L
    private val p120 = 8_333_333L
    private val p60 = 16_666_667L

    // ---- the window copy gate (vc46: `91 held to the cap` a 5 s line in 2D with no heat) ----

    /** 0.5.25's gate, for the before figures: an image under 1000 / cap ms after the last copy waits. */
    private fun oldPlan(hasNew: Boolean, capFps: Int, nowMs: Long, lastCopyMs: Long): Boolean {
        if (!hasNew) return false
        val interval = if (capFps <= 0) Long.MAX_VALUE else 1000L / capFps
        return lastCopyMs <= 0L || nowMs - lastCopyMs >= interval
    }

    private class Shown(val distinctPerSecond: Double, val intervalsInRefreshes: Map<Long, Int>, val replaced: Int, val seconds: Double) {
        val unevenShare: Double get() {
            val total = intervalsInRefreshes.values.sum().toDouble()
            val mode = intervalsInRefreshes.maxByOrNull { it.value }?.value ?: 0
            return if (total == 0.0) 0.0 else 1.0 - mode / total
        }
    }

    /**
     * [appFps] frames from an app, each [arrivalOffsetMs] after its own vsync (plus up to 0.5 ms of jitter), on a Space
     * refreshing every [spacePeriodNs] whose frames start 1 to 3 ms after each refresh. Each Space frame copies the
     * newest image if the gate lets it; a newer image replaces one not copied. The shown intervals are counted in refreshes.
     */
    /**
     * [appFps] pictures from an app, each stamped with its own vsync time and arriving [arrivalOffsetMs] later with up
     * to +-2 ms of jitter, on a Space refreshing every [spacePeriodNs] whose frames start 1 to 3 ms after a refresh and
     * are shown two refreshes on. [old] is 0.5.25: the newest picture copied when the 16 ms gate allows, one waiting
     * at most. Else 0.5.26: the real [PanelImageMailbox] (two waiting) and [PanelLatch] (each picture on the refresh its
     * timestamp says) behind the real [PanelCopyPolicy]. The shown intervals are counted in refreshes.
     */
    private fun windowSim(appFps: Double, spacePeriodNs: Long, old: Boolean, seconds: Double = 60.0, capFps: Int = ThermalGuard.FULL_FPS, arrivalOffsetMs: Double = 4.0, seed: Long = 7): Shown {
        val rnd = java.util.Random(seed)
        val appPeriod = (1e9 / appFps).toLong()
        val start = 1_000 * ms
        val end = start + (seconds * 1e9).toLong()
        var nextStamp = start
        var nextArrival = nextStamp + (arrivalOffsetMs * ms).toLong()
        val box = PanelImageMailbox<Long>(close = {}, maxWaiting = if (old) 1 else 2)
        val latch = PanelLatch()
        var lastCopyMs = 0L
        var lastShownRefresh = -1L
        var distinct = 0
        val intervals = HashMap<Long, Int>()
        var refresh = 0L
        while (true) {
            val refreshNs = start + refresh * spacePeriodNs
            if (refreshNs > end) break
            val frameStart = refreshNs + ms + (rnd.nextDouble() * 2 * ms).toLong()
            while (nextArrival <= frameStart) {
                box.makeRoom()
                box.offer(nextStamp)
                latch.arrived(nextStamp, nextArrival)
                nextStamp += appPeriod
                nextArrival = nextStamp + (arrivalOffsetMs * ms).toLong() + ((rnd.nextDouble() - 0.5) * 4 * ms).toLong()
            }
            val nowMs = frameStart / ms
            val presentNs = refreshNs + 2 * spacePeriodNs
            val copy = if (old) oldPlan(box.hasNew, capFps, nowMs, lastCopyMs) else PanelCopyPolicy.plan(box.hasNew, true, capFps, nowMs, lastCopyMs) == CopyPlan.COPY
            val leased = if (!copy) null else if (old) box.lease() else box.lease { w -> latch.pick(w, presentNs, frameStart) }
            if (leased != null) {
                box.retire(leased, null)
                box.reap { true }
                lastCopyMs = nowMs
                distinct++
                val shownOn = refresh + 2
                if (lastShownRefresh >= 0) intervals.merge(shownOn - lastShownRefresh, 1, Int::plus)
                lastShownRefresh = shownOn
            }
            refresh++
        }
        return Shown(distinct / seconds, intervals, box.dropped.toInt(), seconds)
    }

    @Test
    fun aSixtyFpsVideoShowsSixtyDistinctEvenlyPacedFramesASecondWithNoDropsOverAMinute() {
        for ((label, period) in listOf("2D at 120 Hz" to p120, "side by side at 60 Hz" to p60)) {
            val before = windowSim(60.0, period, old = true)
            val after = windowSim(60.0, period, old = false)
            println(String.format(Locale.ROOT, "60 fps video, %s, one minute: 0.5.25 %.1f distinct a second, %d replaced unseen, intervals %s; 0.5.26 %.1f, %d replaced, intervals %s",
                label, before.distinctPerSecond, before.replaced, before.intervalsInRefreshes.toSortedMap(), after.distinctPerSecond, after.replaced, after.intervalsInRefreshes.toSortedMap()))
            assertEquals("$label: 60 distinct a second", 60.0, after.distinctPerSecond, 0.1)
            assertEquals("$label: no frame dropped in a minute", 0, after.replaced)
            val each = if (period == p120) 2L else 1L
            assertEquals("$label: every frame shown for the same time", setOf(each), after.intervalsInRefreshes.keys)
        }
        // 0.5.25's 16 ms gate on the 120 Hz Space: frames held a refresh, some never shown, the steps uneven.
        val old = windowSim(60.0, p120, old = true)
        assertTrue("0.5.25 lost frames: ${old.distinctPerSecond}", old.distinctPerSecond < 59.0 || old.unevenShare > 0.05)
    }

    @Test
    fun aThirtyFpsVideoShowsThirtyEvenlyPacedFrames() {
        val after = windowSim(30.0, p120, old = false)
        assertEquals(30.0, after.distinctPerSecond, 0.1)
        assertEquals(0, after.replaced)
        assertEquals(setOf(4L), after.intervalsInRefreshes.keys)
        val sbs = windowSim(30.0, p60, old = false)
        assertEquals(setOf(2L), sbs.intervalsInRefreshes.keys)
    }

    @Test
    fun noThirtyHertzRuleOnVisibleWindowsBelowSevereHeat() {
        for (status in listOf(ThermalGuard.NONE, ThermalGuard.LIGHT, ThermalGuard.MODERATE)) for (focused in listOf(true, false)) {
            assertEquals("copy cap at $status focused $focused", ThermalGuard.FULL_FPS, ThermalGuard.panelFps(status, focused))
        }
        // Every window's display is made at 60 Hz, whatever the heat or the focus (it can never change after).
        for (status in 0..6) for (focused in listOf(true, false)) assertEquals(60f, ThermalGuard.requestedFps(status, focused), 0f)
        // Severe heat still eases off the windows not in focus, evenly.
        assertEquals(60, ThermalGuard.panelFps(ThermalGuard.SEVERE, true))
        assertEquals(30, ThermalGuard.panelFps(ThermalGuard.SEVERE, false))
        val capped = windowSim(60.0, p120, old = false, capFps = 30)
        assertEquals(30.0, capped.distinctPerSecond, 0.5)
        assertTrue("capped copies evenly paced: ${capped.intervalsInRefreshes}", capped.unevenShare < 0.02)
    }

    // ---- the render thread's wait for the GPU (vc46: 2D missed 10 to 13 %, 20 to 50 % of refreshes repeated) ----

    private class LoopResult(val pacing: String, val repeatShare: Double, val missShare: Double, val frames: Long)

    /**
     * The Space at 120 Hz with the real [LateDrawScheduler]. Each frame: [preMs] of CPU (input, copies, the plan), the
     * plan, the sleep to its wake, [drawMs] of CPU to submit the eye pass, then the GPU: the frame's 0.3 ms queued
     * behind other work (SurfaceFlinger's window composition, the hand model) of a log-normal [waitMedianMs] median
     * (0.6 spread: p90 3.0, p99 5.7 ms, vc46's 2D draws less the CPU). [blocking] is 0.5.25: the render thread waits for
     * the GPU before it swaps and starts the next frame; 0.5.26 swaps at once and a watcher reports the GPU's finish.
     */
    private fun renderLoop(blocking: Boolean, seconds: Double = 60.0, preMs: Double = 4.5, drawMs: Double = 0.8, waitMedianMs: Double = 1.4, seed: Long = 26): LoopResult {
        val rnd = java.util.Random(seed)
        val s = LateDrawScheduler()
        val pacing = RefreshPacing()
        val t0 = 1_000 * ms
        val end = t0 + (seconds * 1e9).toLong()
        var nextV = t0
        var now = t0
        var gpuFree = 0L
        var frames = 0L
        var missed = 0L
        fun feed() {
            while (nextV <= now) {
                s.onVsync(VsyncSample.fromVsyncOnly(nextV, p120, 1 * ms))
                nextV += p120
            }
        }
        while (now < end) {
            feed()
            now += (preMs * ms).toLong()
            feed()
            val plan = s.plan(now)
            if (plan == null) {
                now = nextV
                continue
            }
            if (plan.wakeNs > now) now = plan.wakeNs
            val sample = now
            val submit = now + (drawMs * ms).toLong()
            val wait = waitMedianMs * exp(rnd.nextGaussian() * 0.6)
            val done = maxOf(submit, gpuFree) + (0.3 * ms).toLong() + (wait.coerceAtMost(30.0) * ms).toLong()
            gpuFree = done
            s.drawn(plan, sample, done)
            val miss = done > plan.deadlineNs + plan.depth * plan.periodNs
            if (miss) missed++
            pacing.frame(plan.vsyncNs, plan.periodNs, miss)
            frames++
            // Then the swap (0.3 ms), and the next frame starts.
            now = (if (blocking) done else submit) + (0.3 * ms).toLong()
        }
        val share = pacing.repeatShare
        return LoopResult(pacing.line(), share, missed.toDouble() / frames.coerceAtLeast(1), frames)
    }

    @Test
    fun theRenderThreadNoLongerWaitsForTheGpuSoItKeepsEveryRefresh() {
        val before = renderLoop(blocking = true)
        val after = renderLoop(blocking = false)
        println("2D at 120 Hz, vc46's GPU waits, 0.5.25 (waits for the GPU): ${before.frames} frames in 60 s, ${"%.1f".format(Locale.ROOT, before.missShare * 100)} % missed; ${before.pacing}")
        println("2D at 120 Hz, vc46's GPU waits, 0.5.26 (fence watched off the thread): ${after.frames} frames in 60 s, ${"%.1f".format(Locale.ROOT, after.missShare * 100)} % missed; ${after.pacing}")
        assertTrue("0.5.25 repeated refreshes: ${before.repeatShare}", before.repeatShare > 0.05)
        assertTrue("0.5.26 fewer repeats: ${after.repeatShare} against ${before.repeatShare}", after.repeatShare < before.repeatShare * 0.7)
        assertTrue("0.5.26 draws more frames", after.frames > before.frames)
        // What repeats is only the frames that missed (the GPU's other work past the budget), no refresh skipped.
        assertTrue("repeats ${after.repeatShare} come from misses ${after.missShare}", after.repeatShare <= after.missShare + 0.005)
        // With the GPU's other work under the budget (the hand model off it, a cool phone), under 1 % repeats.
        val quiet = renderLoop(blocking = false, waitMedianMs = 0.5)
        println("2D at 120 Hz, GPU waits 0.5 ms median: ${quiet.pacing}")
        assertTrue("under 1 % repeats with a quiet GPU: ${quiet.repeatShare}", quiet.repeatShare < RefreshPacing.REPEAT_TARGET)
        // 0.5.25 with the same quiet GPU still repeats, from the wait alone.
        val quietBefore = renderLoop(blocking = true, waitMedianMs = 0.5)
        println("2D at 120 Hz, GPU waits 0.5 ms median, 0.5.25: ${quietBefore.pacing}")
    }

    @Test
    fun refreshPacingCountsFreshRepeatedAndDroppedRefreshes() {
        val p = RefreshPacing()
        for (i in 0 until 120) p.frame(i * p120, p120)
        assertEquals(120, p.refreshes)
        assertEquals(0, p.repeats)
        // A miss: the frame is shown a refresh late, the refresh before shows the last frame again.
        p.frame(120 * p120, p120, missed = true)
        assertEquals(1, p.repeats)
        // The next frame for the refresh the late one took: two frames for one refresh, the first never seen.
        p.frame(121 * p120, p120)
        assertEquals(1, p.drops)
        // A gap of three refreshes: two repeated.
        p.frame(124 * p120, p120)
        assertEquals(3, p.repeats)
        assertTrue(p.line(), p.line().startsWith("space pacing: "))
        assertEquals(0, p.refreshes)
    }

    @Test
    fun aWindowsPacingLineSaysWhatTheAppSentAndWhatWasShown() {
        val w = PanelPacing()
        for (i in 0 until 300) {
            w.arrived(i * p60)
            w.shownAt(i * p60 + 2 * p120)
        }
        val line = w.line("1 Twitch", 5.0)
        assertTrue(line, line.contains("the app sent 60.0 frames a second (interval p50 16.7 ms"))
        assertTrue(line, line.contains("60.0 distinct shown a second (interval p50 16.7 ms p95 16.7, 0 % more than a quarter off)"))
    }

    // ---- the trackpad's moves (vc46: 60 Hz touches, the cursor moved on every other 120 Hz refresh) ----

    @Test
    fun trackpadMovesAtSixtyHertzMoveTheCursorOnEveryRefreshOfA120HertzSpace() {
        val spread = MoveSpreader()
        val steps = ArrayList<Float>()
        var total = 0f
        var sent = 0f
        for (refresh in 0 until 240) {
            val now = 1_000 * ms + refresh * p120
            // A move each 16.7 ms (every other refresh), 0.01 pad units each.
            val incoming = if (refresh % 2 == 0) listOf<PointerSignal>(PadSignal.Move(0.01f, 0f, 0.5f, 0f)) else emptyList()
            if (incoming.isNotEmpty()) sent += 0.01f
            val out = spread.take(incoming, now, p120)
            val dx = out.filterIsInstance<PadSignal.Move>().sumOf { it.dx.toDouble() }.toFloat()
            total += dx
            if (refresh >= 20) steps += dx
        }
        assertEquals("the 60 Hz period is measured", p60.toDouble(), spread.inputPeriodNs.toDouble(), 0.05 * p60)
        assertTrue("the cursor moves on every refresh: ${steps.count { it == 0f }} still", steps.none { it == 0f })
        val mean = steps.average()
        val worst = steps.maxOf { abs(it - mean) / mean }
        assertTrue("even steps (worst %.0f %% off the mean)".format(Locale.ROOT, worst * 100), worst < 0.35)
        // Nothing lost: what is still waiting goes once the finger stops.
        val tail = spread.take(emptyList(), 1_000 * ms + 300 * p120, p120)
        total += tail.filterIsInstance<PadSignal.Move>().sumOf { it.dx.toDouble() }.toFloat()
        assertEquals(sent, total, 1e-4f)
    }

    @Test
    fun aPressOrReleaseFirstReleasesEveryMoveBeforeItAndSideBySideIsAsBefore() {
        val spread = MoveSpreader()
        for (refresh in 0 until 10) spread.take(if (refresh % 2 == 0) listOf(PadSignal.Move(0.01f, 0.01f, 0.5f, 0.5f)) else emptyList(), refresh * p120, p120)
        // A non-move signal (here an absolute cursor place): everything waiting goes first, in order.
        val other = PointerSignal.MoveTo(1f, 1f)
        val out = spread.take(listOf(PadSignal.Move(0.02f, 0f, 1f, 0f), other), 11 * p120, p120)
        assertTrue(out.last() === other)
        assertFalse(spread.waiting)
        assertTrue(out.first() is PadSignal.Move)
        // Side by side: moves at 60 Hz on a 60 Hz Space go through whole.
        val sbs = MoveSpreader()
        for (refresh in 0 until 20) {
            val m = PadSignal.Move(0.01f, 0f, 0.5f, 0f)
            val o = sbs.take(listOf(m), refresh * p60, p60)
            if (refresh > 3) assertEquals(listOf<PointerSignal>(m), o)
        }
    }

    // ---- the late warp (it waited for its own scene) ----

    @Test
    fun theWarpShowsTheFinishedSceneBeforeTheNewestWhenTheNewestIsStillDrawing() {
        val w = LateWarpFrames()
        val a = w.begin(newestDone = true)!!
        w.submitted(a, Quat.IDENTITY, 1L, 1L)
        // The first scene: nothing older, so it is the one.
        assertEquals(a.index, w.forWarp { false }!!.index)
        val b = w.begin(newestDone = true)!!
        w.submitted(b, Quat.IDENTITY, 2L, 2L)
        // B is still on the GPU at warp time: A, finished, is turned instead (no wait behind B).
        assertEquals(a.index, w.forWarp { it != b.index }!!.index)
        // B done: B.
        assertEquals(b.index, w.forWarp { true }!!.index)
        assertTrue(w.line(), w.line().contains("at warp time 2 of 3 showed the newest scene, 1 the one before it (33.3 %"))
    }

    // ---- the glasses' 60 into 90 Hz (their log's `fps:60_90`) ----

    @Test
    fun sixtyIntoNinetyHertzIsUnevenAndThirtyIsEven() {
        assertEquals(listOf(2, 1), GlassesCadence.pattern(60, 90))
        assertFalse(GlassesCadence.even(60, 90))
        assertEquals(30, GlassesCadence.contentHz(60, 90))
        assertEquals(listOf(3), GlassesCadence.pattern(30, 90))
        assertTrue(GlassesCadence.even(60, 60))
        assertTrue(GlassesCadence.even(120, 120))
        assertTrue(GlassesCadence.even(60, 120))
        assertEquals(2, CadenceGate.every(60, 30))
        assertEquals(1, CadenceGate.every(60, 60))
        // Every other 60 Hz refresh takes a picture: 30 a second, each shown for 3 of the glasses' 90 Hz refreshes.
        val due = (0 until 60).count { CadenceGate.due(it * p60, p60, 2) }
        assertEquals(30, due)
        assertTrue(GlassesCadence.line(60, 90), GlassesCadence.line(60, 90).contains("unevenly (2/1 refreshes a frame); content paced at 30 Hz so each frame is shown for 3"))
    }

    @Test
    fun theGlassesStatusLineGivesTheirRatesAndScreen() {
        val sbs = GlassesLogEvents.parse("[2027-01-25 00:09:38.223] [23687] [DEBUG] [Flinger] space:1 scene:1 plane:0 percep:100 dp_in:1 fps:60_90 duty:0.90 src/read_19/34995:3840x1078 quad:3.92x2.20(0.0112,0.0086,-4.00) fov:(0.95,0.95,0.95)") as GlassesLogEvents.Event.Status
        assertEquals(1, sbs.scene)
        assertEquals(60, sbs.inputHz)
        assertEquals(90, sbs.panelHz)
        assertEquals(3840, sbs.srcW)
        assertEquals(1078, sbs.srcH)
        assertEquals(4.0f, sbs.depthM, 1e-6f)
        assertEquals(0.95f, sbs.sizeFactor!!, 0.01f)
        val flat = GlassesLogEvents.parse("[2027-01-25 00:00:37.611] [1498] [DEBUG] [Flinger] space:1 scene:0 plane:0 percep:2 dp_in:0 fps:120_120 duty:0.90 src/read_3884/3998:1520x856 quad:0.82x0.46(0.0023,0.0018,-1.00) fov:(0.20,0.80,0.80)") as GlassesLogEvents.Event.Status
        assertEquals(0, flat.scene)
        assertEquals(120, flat.panelHz)
        assertEquals(0.80f, flat.sizeFactor!!, 0.01f)
        assertEquals(0.8f, (GlassesLogEvents.parse("[x] [1] [DEBUG] [Flinger] GetDisplayDiagonalFov fov_factor:0.8") as GlassesLogEvents.Event.FovFactor).factor, 1e-6f)
    }

    // ---- the hand model's GPU (vc46: inference 133 to 156 ms while the Space fell to 22 fps) ----

    @Test
    fun theHandModelLeavesTheGpuWhenTheSpaceWaitsBehindIt() {
        val g = SpaceGpuGuard()
        assertNull(g.window(0.10, 4.0, handModelOnGpu = true))
        assertTrue(g.window(0.12, 5.0, handModelOnGpu = true)!!.contains("moves to the CPU"))
        assertTrue(g.moved)
        assertNull("decided once", g.window(0.2, 9.0, true))
        // Misses with the hand model on the CPU, or a GPU not busy, are not its doing.
        val h = SpaceGpuGuard()
        repeat(5) { assertNull(h.window(0.2, 9.0, handModelOnGpu = false)) }
        repeat(5) { assertNull(h.window(0.2, 0.4, handModelOnGpu = true)) }
        repeat(5) { assertNull(h.window(0.01, 9.0, handModelOnGpu = true)) }
        // One bad line between good ones does not move it.
        val k = SpaceGpuGuard()
        assertNull(k.window(0.1, 5.0, true))
        assertNull(k.window(0.0, 0.0, true))
        assertNull(k.window(0.1, 5.0, true))
        assertFalse(k.moved)
    }
}
