package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.SpaceTasks
import com.hayeco.flipspaces.core.hands.FrameSample
import com.hayeco.flipspaces.core.hands.TrackingStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ceil

/**
 * 0.5.25: the vc45 test's problems. The stale Space layers; the panel service's lines relayed through the app; a
 * press on nothing names the nearest control; the `Tracking:` line's delegate; the split callback's frames on the
 * glasses' 120 Hz grid.
 */
class Fixes0525Test {

    // ---- Item 6: stale Space layers ----

    /** vc45's sf-list.txt, the lines round the two stale Space layers and the live one. */
    private val sfList = """
        RequestedLayerState{Task=9474#26527 z=2}
        RequestedLayerState{Task=9479(Desk)#26650 z=2}
        RequestedLayerState{Task=9480(MinimizedDesk_9479)#26663 z=1}
        RequestedLayerState{Task=9434#25641 parentId=42 z=5}
        RequestedLayerState{Task=9482(FlipSpaces Space)#26698 z=1}
        RequestedLayerState{Task=9437(Desk)#25690 z=2}
        RequestedLayerState{Task=9475(FlipSpaces Space)#26532 z=1}
        RequestedLayerState{Task=9472(Desk)#26484 z=2}
        RequestedLayerState{Task=9486(Desk)#26901 parentId=26880 z=2}
        RequestedLayerState{Task=9489(FlipSpaces Space)#26949 parentId=26880 z=3}
    """.trimIndent()

    @Test
    fun aStaleSpaceLayerWithNoParentIsOffscreenAndCountedWithDexsOwn() {
        val left = SpaceTasks.leftoverLayers(sfList, setOf(9475))
        assertEquals(listOf("RequestedLayerState{Task=9475(FlipSpaces Space)#26532 z=1}"), left)
        assertTrue(SpaceTasks.offscreen(left.single()))
        assertFalse("the live Space has a parent", SpaceTasks.offscreen("RequestedLayerState{Task=9489(FlipSpaces Space)#26949 parentId=26880 z=3}"))
        assertEquals(9475, SpaceTasks.layerTaskId(left.single()))
        // 9474, 9479 Desk, 9480 MinimizedDesk, 9437 Desk, 9472 Desk: offscreen and not a Space's. 9434 and 9486 have parents.
        assertEquals(5, SpaceTasks.otherOffscreenTaskLayers(sfList))
    }

    @Test
    fun theLastCheckRemovesOnlyItsOwnTasksThatTheWindowManagerStillHas() {
        val left = SpaceTasks.leftoverLayers(sfList, setOf(9475, 9482))
        // 9475 still in the window manager (named in its dump), 9482 gone.
        val plan = SpaceTasks.leftoverPlan(left, setOf(9475, 9482), inWindowManager = setOf(9475, 9489))
        assertEquals(listOf(9475), plan.remove)
        assertEquals(listOf(9482), plan.gone)
        // Never a task outside the service's own, never the holder in use now.
        assertTrue(SpaceTasks.leftoverPlan(left, setOf(9482), inWindowManager = setOf(9475, 9482), inUse = setOf(9482)).remove.isEmpty())
        assertTrue(SpaceTasks.leftoverPlan(SpaceTasks.leftoverLayers(sfList, setOf(9489)), setOf(9475), setOf(9489)).remove.isEmpty())

        val line = SpaceTasks.leftoverLine(114, plan, mapOf(9475 to "removed (removeTask returned true)"), left, SpaceTasks.otherOffscreenTaskLayers(sfList))
        assertTrue(line, line.startsWith("display 114 released: Space task 9475 was still in the window manager, so the panel service removed it: removed (removeTask returned true)"))
        assertTrue(line, line.contains("Space task 9482 is gone from the window manager; its layer is offscreen (no parent, so no display composes it) and another process holds it"))
        assertTrue(line, line.endsWith("5 other task layers are left offscreen the same way (DeX's Desk among them)"))

        // vc45 as it was: both tasks already deleted at the release.
        val vc45 = SpaceTasks.leftoverPlan(SpaceTasks.leftoverLayers(sfList, setOf(9475)), setOf(9475), inWindowManager = emptySet())
        assertTrue(vc45.remove.isEmpty())
        assertEquals(listOf(9475), vc45.gone)
        assertTrue(SpaceTasks.afterRemovalLine(114, listOf(9475), emptyList(), SpaceTasks.AFTER_REMOVAL_MS).contains("are gone from SurfaceFlinger 1.5 s after the removal"))
        assertTrue("the recheck runs inside the hold", SpaceTasks.LAYER_CHECKS_MS.last() + SpaceTasks.AFTER_REMOVAL_MS < SpaceTasks.LAYER_HOLD_MS)
    }

    // ---- Item 13: the panel service's lines through the app ----

    @Test
    fun thePanelServicesLinesAreBatchedAndBounded() {
        val relay = PanelLogRelay(capacity = 10, perBatch = 4)
        assertTrue(relay.take().isEmpty())
        relay.add("display 114 released: 1 FlipSpaces Space layer still in SurfaceFlinger 5 s later")
        relay.add("two\nlines")
        assertEquals(listOf("display 114 released: 1 FlipSpaces Space layer still in SurfaceFlinger 5 s later", "two lines"), relay.take())
        assertTrue(relay.take().isEmpty())
        // A burst: 15 lines into 10 kept, the 5 oldest dropped and said so first; 4 lines a batch.
        repeat(15) { relay.add("line $it") }
        val first = relay.take()
        assertEquals(4, first.size)
        assertEquals("5 earlier lines were dropped before the app took them (the service keeps 10)", first[0])
        assertEquals(listOf("line 5", "line 6", "line 7"), first.drop(1))
        assertEquals(listOf("line 8", "line 9", "line 10", "line 11"), relay.take())
        assertEquals(3, relay.pending)
        assertEquals("panel service: display 116 released", PanelLogRelay.appLine("display 116 released"))
        assertEquals("FlipSpaces/Panels", PanelLogRelay.TAG)
        // A few lines a second at most.
        assertTrue(PanelLogRelay.PER_BATCH * 1000 / PanelLogRelay.EVERY_MS <= 5)
        relay.add("x".repeat(5000))
        val tail = relay.take()
        assertEquals(listOf("line 12", "line 13", "line 14"), tail.dropLast(1))
        assertTrue("a long line is cut", tail.last().length < 2100)
    }

    // ---- Section 3: a press on nothing ----

    /** Twitch's vc45 window: 55.1 by 31.0 degrees, flat, straight ahead. */
    private val twitch = SpacePanel(1, "Twitch", "tv.twitch.android.app", "tv.twitch.ViewerLandingActivity", 0f, 0f, widthDeg = 55.1f, heightDeg = 31f).sizedByUser()

    @Test
    fun aPressOnNothingNamesTheWindowBodyItPassedAndHowFar() {
        // 13.5 degrees left of the body's left edge, level with its middle (vc45: 13.5 deg outside its body).
        val ray = Ray(Vec3.ZERO, Vec3.direction(-(55.1f / 2f + 13.5f), 0f))
        assertNull(PanelHitTester.nearest(ray, listOf(twitch), 1f))
        val n = PressMiss.nearest(ray, listOf(twitch))!!
        assertEquals(PressMiss.BODY, n.name)
        assertEquals(13.5f, n.offDeg, 0.05f)
        assertEquals("press on nothing: nearest control: window body on panel 1 Twitch, 13.5 deg away", PressMiss.line(n))
        val report = PressReport.of(ray, null, listOf(twitch))
        assertEquals(PressMiss.line(n), report.missLine)
        // A press that hits has no such line.
        val hit = PanelHitTester.nearest(Ray(Vec3.ZERO, Vec3.FORWARD), listOf(twitch))
        assertNull(PressReport.of(Ray(Vec3.ZERO, Vec3.FORWARD), hit, listOf(twitch)).missLine)
        assertEquals("press on nothing: no window in the Space", PressMiss.line(PressMiss.nearest(ray, emptyList())))
    }

    @Test
    fun aPressJustUnderFitVideoNamesFitVideo() {
        val place = WindowControls.layout(twitch).of(WindowControls.Control.FIT_VIDEO)
        // The middle of Fit video's bottom edge, then 3 degrees under it.
        val edge = PanelCurve.point(twitch, place.xCentre, 0.5f - place.v1)
        val a = Angles.of(edge)
        val ray = Ray(Vec3.ZERO, Vec3.direction(a.yawDeg, a.pitchDeg - 3f))
        assertNull(PanelHitTester.nearest(ray, listOf(twitch), 1f))
        val n = PressMiss.nearest(ray, listOf(twitch))!!
        assertEquals("Fit video", n.name)
        assertEquals(3f, n.offDeg, 0.1f)
        assertTrue(PressMiss.line(n), PressMiss.line(n).startsWith("press on nothing: nearest control: Fit video on panel 1 Twitch, 3.0 deg away"))
        // A curved window: the same names, the angle along its cylinder.
        val curved = twitch.copy(curve = 1f)
        val cp = WindowControls.layout(curved).of(WindowControls.Control.CLOSE)
        val top = Angles.of(PanelCurve.point(curved, cp.xCentre, 0.5f - cp.v0))
        val n2 = PressMiss.nearest(Ray(Vec3.ZERO, Vec3.direction(top.yawDeg, top.pitchDeg + 2f)), listOf(curved))!!
        assertEquals("Close", n2.name)
        assertEquals(2f, n2.offDeg, 0.1f)
    }

    // ---- Item 8: the Tracking line's delegate ----

    @Test
    fun theTrackingLineSaysWhatRanEachInference() {
        fun window(vararg on: String?): String {
            val stats = TrackingStats(window = on.size).apply { delegate = "GPU" }
            var s: com.hayeco.flipspaces.core.hands.StatsSummary? = null
            on.forEachIndexed { i, r -> s = stats.record(FrameSample(150, 20, 141, false, 0f, ranOn = r), i * 33L) }
            return s!!.delegate
        }
        // vc45: the idle palm search on the CPU while the runner's delegate is the GPU: it said GPU.
        assertEquals("CPU palm search", window(*Array(30) { TrackingStats.CPU_SEARCH }))
        assertEquals("GPU", window(*Array(30) { TrackingStats.GPU }))
        assertEquals("CPU palm search 18 and GPU 12", window(*(Array(12) { TrackingStats.GPU } + Array(18) { TrackingStats.CPU_SEARCH })))
        // Frames that do not say take the runner's delegate, as before.
        assertEquals("GPU", window(*arrayOfNulls<String>(30)))
        assertEquals("CPU", TrackingStats.delegateLabel(emptyList(), "CPU"))
    }

    // ---- Item 9: the split callback's frames on the glasses' grid ----

    private val p = 8_333_333L
    private val pd = 9_333_333L

    /**
     * 2D at 120 Hz from 60 Hz Choreographer callbacks that follow another display: the glasses' vsyncs sit [phase0]
     * to [phase1] after the split vsyncs (vc45's 2D-118: +3.87 to +4.17 ms), drifting, with 0.15 ms of callback
     * jitter. SurfaceFlinger presents a frame at the first glasses vsync at or after its stamp that it was ready a
     * presentation deadline before. Returns (frames, frames shown at another vsync than their pose's, of those the
     * second frames of a callback, frames shown where planned with [fixed]).
     */
    private fun run(fixed: Boolean, phase0: Double = 3.6e6, phase1: Double = 4.7e6, callbacks: Int = 240): IntArray {
        val rnd = java.util.Random(25)
        val gv = GlassesVsync()
        val late = LateDrawScheduler()
        val grid = SplitGridStamp()
        val base = 1_000_000_000L
        // The glasses' grid; the Choreographer's 60 Hz drifts against it from phase0 to phase1.
        val g0 = base + 2 * p + phase0.toLong()
        val drift = ((phase1 - phase0) / callbacks).toLong()
        val snap: (Long) -> Long = { t -> g0 + Math.round((t - g0).toDouble() / p) * p }
        var frames = 0
        var off = 0
        var offSecond = 0
        for (n in 0 until callbacks) {
            val v = base + n * (2 * p - drift) + ((rnd.nextDouble() - 0.5) * 300_000).toLong()
            gv.convert(VsyncSample(v, 2 * p - drift, v + 2 * p - pd, v + 2 * p), true, p, p, pd).forEach(late::onVsync)
            assertEquals(GlassesVsync.Route.SPLIT, gv.route)
            for (j in 0..1) {
                val plan = late.plan(v + j * p + 500_000L)!!
                val m = plan.presentNs
                val place = if (fixed) grid.place(m, p, gv.split, snap) else null
                val pose = place?.presentNs ?: snap(m)
                val stamp = place?.stampNs ?: plan.stampNs
                val ready = plan.deadlineNs + (place?.wakeShiftNs ?: 0L) - (rnd.nextDouble() * 4e6).toLong()
                var g = g0 + ceil((stamp - g0).toDouble() / p).toLong() * p
                while (ready > g - pd) g += p
                frames++
                if (g != pose) {
                    off++
                    if (j == 1) offSecond++
                }
            }
        }
        return intArrayOf(frames, off, offSecond)
    }

    @Test
    fun twoDAt120HzFromA60HzCallbackPresentsEveryFrameAtItsOwnGridVsync() {
        val before = run(fixed = false)
        // Before: the stamp sits on a glasses vsync, so whether a frame goes out there or a vsync later is chance.
        assertTrue("before: ${before[1]} of ${before[0]} frames off their pose's vsync", before[1] > before[0] / 4)
        assertTrue("before: second frames off too (${before[2]})", before[2] > 0)
        val after = run(fixed = true)
        assertEquals("after: every frame at the glasses vsync its pose is for", 0, after[1])
        // Far from half a period (in phase, or a quarter off) it holds too, with no flip between vsyncs.
        for ((a, b) in listOf(-0.4e6 to 0.4e6, 1.5e6 to 2.5e6, 7.0e6 to 8.0e6)) assertEquals("phase $a to $b", 0, run(fixed = true, phase0 = a, phase1 = b)[1])
    }

    @Test
    fun theSplitGridStampHoldsItsSideNearAVsyncAndNeverDrawsLate() {
        val g = SplitGridStamp(holdNs = 1_000_000L)
        // The glasses' vsync 0.05 ms after the plan's present, then the phase wanders 0.3 ms either side of it.
        var last: Long? = null
        for (i in 0 until 200) {
            val jitter = ((i % 7) - 3) * 100_000L
            val m = 1_000_000_000L + i * p
            val gridAt = m + 50_000L + jitter
            val pl = g.place(m, p, 2) { t -> gridAt + Math.round((t - gridAt).toDouble() / p) * p }!!
            assertTrue("never more than the hold before the plan's present: ${pl.shiftNs}", pl.shiftNs >= -1_000_000L)
            assertTrue("never a whole period late", pl.shiftNs < p)
            last?.let { assertTrue("no flip: $it to ${pl.shiftNs}", abs(pl.shiftNs - it) < 1_000_000L) }
            last = pl.shiftNs
            assertEquals(pl.presentNs - p / 2, pl.stampNs)
            assertEquals(minOf(pl.shiftNs, 0L), pl.wakeShiftNs)
        }
        // No fitted grid yet: the plan as it is.
        assertNull(g.place(1_000_000_000L, p, 2) { it })
    }

    @Test
    fun sideBySideAt60HzIsExactlyAsBefore() {
        // SBS: 60 Hz callbacks, the glasses at 60 Hz: one glasses vsync per callback, the frame timeline as it is.
        val gv = GlassesVsync()
        val period = 16_666_667L
        val s = VsyncSample(2_000_000_000L, period, 2_000_000_000L + 7_000_000L, 2_000_000_000L + 2 * period)
        val out = gv.convert(s, true, period, 0L, 9_000_000L)
        assertEquals(listOf(s), out)
        assertEquals(1, gv.split)
        val g = SplitGridStamp()
        val late = LateDrawScheduler().apply { out.forEach(::onVsync) }
        val plan = late.plan(s.vsyncNs + 1_000_000L)!!
        // Whatever the glasses' grid, a frame from an unsplit callback is not placed: the renderer keeps
        // plan.stampNs and fence.snap(present), as before.
        for (phase in listOf(0L, 3_000_000L, 8_333_333L, 12_000_000L)) {
            assertNull(g.place(plan.presentNs, period, gv.split) { t -> phase + Math.round((t - phase).toDouble() / period) * period })
        }
        assertEquals(0L, g.placed)
        assertNotNull(plan)
    }
}
