package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.LaunchFailure
import com.hayeco.flipspaces.core.hands.DetectionCycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/** 0.5.20: the fixes for Mike's 0.5.19 test (vc40), on his own vc40 data where there is some. */
class Fixes0520Test {
    private val vc40Latency by lazy { Fixes0520Test::class.java.getResource("/sf/vc40-latency-space.txt")!!.readText() }

    // ---- section 3 item 2: the present fence reader ----

    @Test
    fun theLayerNameIsTakenOutOfRequestedLayerState() {
        // Mike's vc40 `dumpsys SurfaceFlinger --list` line for the Space.
        val line = "RequestedLayerState{2d4b00b SurfaceView[com.hayeco.flipspaces/com.hayeco.flipspaces.spatial.SpaceActivity]@0(BLAST)#9894 parentId=9893}"
        assertTrue(SurfaceFlingerLayers.isSpace(line, "com.hayeco.flipspaces", "SpaceActivity"))
        assertEquals("2d4b00b SurfaceView[com.hayeco.flipspaces/com.hayeco.flipspaces.spatial.SpaceActivity]@0(BLAST)#9894", SurfaceFlingerLayers.latencyName(line))
        // With more fields after it, and the old bare form, kept as it is.
        assertEquals("Bounds for - com.hayeco.flipspaces/com.hayeco.flipspaces.spatial.SpaceActivity@0#9892",
            SurfaceFlingerLayers.latencyName("RequestedLayerState{Bounds for - com.hayeco.flipspaces/com.hayeco.flipspaces.spatial.SpaceActivity@0#9892 parentId=9890 z=-3}"))
        val bare = "751f23f SurfaceView[com.hayeco.flipspaces/com.hayeco.flipspaces.spatial.SpaceActivity]@0(BLAST)#7787"
        assertEquals(bare, SurfaceFlingerLayers.latencyName("  $bare "))
    }

    @Test
    fun mikesVc40TableSaysTheModelPresentIsRight() {
        val t = SfLatency.parse(vc40Latency)!!
        assertEquals(16_666_667L, t.periodNs)
        assertTrue("frames ${t.frames.size}", t.frames.size >= 120)
        // Stamped as the late draw stamps them: half a period before the present the pose is for.
        val fence = PresentFence(minFrames = 30, skipFirstMs = 0)
        for (f in t.frames) fence.record(f.desiredNs, f.desiredNs + t.periodNs / 2)
        val r = fence.measure(t)
        println("vc40's fences against the model: ${fence.line()}")
        // Measured in vc40: actual less stamp p50 8.34 ms, the half period: the model within 0.1 ms.
        assertTrue(r.usable)
        assertEquals(0.0, r.offsetMs, 0.15)
        // And the table proves the Space was presented: its frames' actual presents, in the last second of the table.
        val newest = t.frames.filter { it.presented }.maxOf { it.actualNs }
        assertTrue(PresentedProof.shows(t, newest))
        assertEquals(0, PresentedProof.presentedFrames(t, newest + 5_000_000_000L))
        assertFalse(PresentedProof.shows(null, newest))
    }

    // ---- section 3 item 1: the hold after the glasses' own 2D to 3D switch ----

    @Test
    fun aNewDisplaysNotComposedSaysNothingForSixSeconds() {
        val c = VisibleCheck()
        c.start(0L, VisibleCheck.SETTLE_MS)
        assertEquals(6_000L, c.deadlineNowMs)
        // vc40: not composed at 0.7 s, 1.2 s ... 3 s while DeX set up display 54: no give up.
        var t = 600L
        var step = c.result(t, OnScreenKind.NOT_COMPOSED)
        while (step is VisibleCheck.Step.Again && t < 5_500L) {
            t += step.inMs
            step = c.result(t, if (t < 5_000L) OnScreenKind.NOT_COMPOSED else OnScreenKind.COMPOSED)
        }
        assertEquals(VisibleCheck.Step.Confirmed, step)
        // Only after the settle time does a "not composed" count.
        val d = VisibleCheck()
        d.start(0L, VisibleCheck.SETTLE_MS)
        var s: VisibleCheck.Step = d.result(600L, OnScreenKind.NOT_COMPOSED)
        var u = 600L
        while (s is VisibleCheck.Step.Again) {
            u += s.inMs
            s = d.result(u, OnScreenKind.NOT_COMPOSED)
        }
        assertTrue(s is VisibleCheck.Step.GiveUp)
        assertTrue(u >= 6_000L)
        // Without a settle (a Space long running) it is 0.5.19's 3 s.
        val e = VisibleCheck()
        e.start(0L)
        assertEquals(3_000L, e.deadlineNowMs)
    }

    // ---- section 3 item 13: the start through the pointer service ----

    @Test
    fun aSuccessfulStartIsNotTheServiceGoingAway() {
        var launched = 0
        // 0.5.19's `host?.let { launch(it) } ?: NoHost` read this success (null) as no host.
        assertNull(LaunchFailure.through("service") { launched++; null })
        assertEquals(1, launched)
        val refused = LaunchFailure.classify("SecurityException", "Permission Denial")
        assertEquals(refused, LaunchFailure.through("service") { refused })
        assertEquals("the pointer service went away", LaunchFailure.through<String>(null) { error("not called") }!!.message)
    }

    // ---- section 2 item 9 and section 3 item 4: the hand model idles, and rests off the head ----

    @Test
    fun theGlassesCameraIdlesAfterTwoSecondsWithoutAHand() {
        val c = DetectionCycle.glassesCamera()
        var sent = 0
        var late = 0
        var t = 0L
        repeat(120) {
            if (c.shouldSend(t)) {
                c.sent(t)
                c.record(false)
                sent++
                if (t >= 2_000L) late++
            }
            t += 33
        }
        // 2 s at 30, then (0.5.21) 5 a second, every 200 ms or the frame after.
        assertTrue("sent $sent", sent in 66..74)
        assertTrue("after 2 s $late", late in 7..11)
        // A hand: every frame at once.
        c.record(true)
        c.record(true)
        var after = 0
        repeat(10) { if (c.shouldSend(t)) after++; t += 33 }
        assertEquals(10, after)
        // 0.5.19's glasses camera cycle (everyFrame) sent all 120.
        val old = DetectionCycle.everyFrame()
        var oldSent = 0
        repeat(120) { i -> if (old.shouldSend(i * 33L)) { old.sent(i * 33L); old.record(false); oldSent++ } }
        assertEquals(120, oldSent)
    }

    @Test
    fun theHandModelSendsNothingWhileTheGlassesAreOffAHead() {
        val c = DetectionCycle.glassesCamera()
        c.notWorn = "roll 176 deg (over 60): the glasses are not upright"
        var sent = 0
        repeat(90) { i -> if (c.shouldSend(i * 33L)) sent++ }
        assertEquals(0, sent)
        assertEquals(90L, c.notWornSkipped)
        c.notWorn = null
        assertTrue(c.shouldSend(3_000L))
    }

    // ---- section 2 item 13, section 3 item 7: the late draw through a Choreographer wobble ----

    @Test
    fun aShortWobbleKeepsTheLateDrawOnAndIsSaidOnce() {
        val g = LateDrawGate()
        val refusal = "Choreographer runs at 57.0 Hz, the glasses at 60.0 Hz, not a whole multiple (it follows another display)"
        // vc40: 117 ms of 57 to 58 Hz callbacks.
        var t = 1_000_000_000L
        assertNull(g.decide(t, null, null, 60.0))
        t += 16_000_000L
        for (hz in listOf(57.0, 57.5, 58.1, 57.2, 57.9, 58.0, 57.4)) {
            assertNull("on through the wobble", g.decide(t, null, refusal, hz))
            assertNull(g.takeWobbleLine())
            t += 17_000_000L
        }
        assertNull(g.decide(t, null, null, 60.0))
        val line = g.takeWobbleLine()!!
        println(line)
        assertTrue(line.contains("57.0 to 58.1 Hz") && line.contains("(1 this session)"))
        assertNull(g.takeWobbleLine())
        // Over a second off: off, with the reason.
        val off = (0..70).map { i -> g.decide(t + i * 17_000_000L, null, refusal, 57.0) }
        assertNull(off.first())
        assertTrue(off.last()!!.startsWith(refusal))
        // A hard reason at once.
        assertEquals("off in the settings", LateDrawGate().decide(0L, "off in the settings", null, 60.0))
    }

    // ---- section 3 item 12: the head chain's horizon ----

    @Test
    fun theHeadChainSaysTheHorizonsP50() {
        val line = HeadChain(1000.0, 840.0, 0.6f, 36.9f, "pacer", 37.4f).line()
        assertTrue(line, line.contains("pose predicted p50 36.9 ms p95 37.4 ms ahead"))
        assertTrue(HeadChain(1000.0, 840.0, 0.6f, 48f, "pacer").line().contains("pose predicted 48 ms ahead"))
    }

    // ---- the tremor measured, not modelled ----

    @Test
    fun theTremorBandsMeasureAHeadsTremor() {
        val b = TremorBands()
        // 0.01 deg at 8 Hz on y, 0.1 deg at 1 Hz on x (rates), 1 kHz, 3 s.
        val a8 = Math.toRadians(0.01)
        val a1 = Math.toRadians(0.1)
        for (i in 0 until 3000) {
            val t = i / 1000.0
            val wy = a8 * 2 * PI * 8 * kotlin.math.cos(2 * PI * 8 * t)
            val wx = a1 * 2 * PI * 1 * kotlin.math.cos(2 * PI * 1 * t)
            b.add(i * 1_000_000L, Vec3(wx.toFloat(), wy.toFloat(), 0f))
        }
        assertTrue(b.ready)
        val d = b.degrees()
        println(b.line())
        val rms8 = 0.01 / kotlin.math.sqrt(2.0)
        // The 8 to 12 Hz band (8 Hz at its lower edge, about 0.7 of the peak) and the 4 to 8 Hz band each see part of it, x not at all.
        val tremorY = kotlin.math.sqrt(d[1][1] * d[1][1] + d[2][1] * d[2][1])
        assertEquals(rms8, tremorY, rms8 * 0.35)
        assertTrue(d[1][0] < rms8 * 0.5 && d[2][0] < rms8 * 0.2)
        // The 1 Hz sway is in the sway band.
        assertTrue(d[0][0] > 0.03)
        assertTrue(b.line().startsWith("head tremor"))
    }
}
