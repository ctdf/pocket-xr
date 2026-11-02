package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.RotatingLogFile
import com.hayeco.flipspaces.core.pointer.PadSignal
import com.hayeco.flipspaces.core.pointer.Phase
import com.hayeco.flipspaces.core.pointer.PointerSignal
import com.hayeco.flipspaces.core.pointer.WindowAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

/**
 * 0.5.28: Curve on a wrapped Space (section 1), the SBS prediction's tremor cap, the 8 ms guess with the
 * per-row pose and the late warp's earlier start (section 2), the app's own log file (section 3 item 1) and the other
 * fixes of section 3.
 */
class Fixes0528Test {
    private val frustum = ViewFrustum(1920, 1080, 70f)

    // ---- section 1: Curve on a wrapped Space ----

    private var layout = SpaceWrap.on(
        SpaceLayout(
            listOf(
                SpacePanel(1, "Twitch", "tv.twitch.android.app", "Main", yawDeg = 0f, pitchDeg = 0f),
                SpacePanel(2, "Maps", "com.google.android.apps.maps", "Main", yawDeg = 50f, pitchDeg = 0f, curve = 0.3f),
            ),
        ),
    )
    private var committed: SpaceLayout? = null

    private fun SpatialPointer.send(s: PointerSignal): List<SpaceAction> = handle(s, Quat.IDENTITY, layout, { Pair(1280, 720) }).also { out ->
        out.filterIsInstance<SpaceAction.Layout>().lastOrNull()?.let {
            layout = it.layout
            if (it.commit) committed = it.layout
        }
    }

    @org.junit.Ignore("0.5.36: the wraparound is retired (Mike: Curve and Further/Closer on the selected window only); a saved wrap is undone at load")
    @Test
    fun curveOnAWrappedSpaceBendsTheWholeWrapAndSaysSoOnTheGlassesOnceAGesture() {
        assertEquals(1f, layout.wrap!!.curve, 0f)
        val p = SpatialPointer(frustum)
        p.frame(Quat.IDENTITY, layout, 16)
        p.send(PadSignal.Window(WindowAction.CURVE))
        assertEquals("WRAP CURVE 100 %", p.holdGuide())
        // A swipe left of 0.6 pad units: 0.3 of the range off, every window with it, the wrap's own curve too.
        val outs = (1..6).map { p.send(PadSignal.Move(-0.1f, 0f, 0f, 0f)) }
        for (w in layout.panels) assertEquals("window ${w.id}", 0.7f, w.curve, 1e-4f)
        assertEquals(0.7f, layout.wrap!!.curve, 1e-4f)
        assertNotNull("still wrapped", layout.wrap)
        assertEquals("WRAP CURVE 70 %", p.holdGuide())
        // Said once on the glasses for the gesture, and the old refusal is gone (it had logged 289 lines in 26 s).
        val all = outs.flatten()
        assertEquals(1, all.filterIsInstance<SpaceAction.Notice>().size)
        assertEquals(WRAP_CURVE_NOTICE, all.filterIsInstance<SpaceAction.Notice>().single().text)
        assertTrue(all.filterIsInstance<SpaceAction.Note>().none { "not curved on its own" in it.line })
        assertTrue("the note says how to unwrap", "Unwrap" in WRAP_CURVE_NOTICE)
        // Dropped: saved with the wrap's curve. A new Curve press says it again.
        p.send(PadSignal.Window(WindowAction.CURVE))
        assertEquals(0.7f, committed!!.wrap!!.curve, 1e-4f)
        p.send(PadSignal.Window(WindowAction.CURVE))
        val again = p.send(PadSignal.Move(-0.1f, 0f, 0f, 0f))
        assertEquals(1, again.filterIsInstance<SpaceAction.Notice>().size)
        // A slide along the Curve key on a wrapped Space bends it too, said once a slide.
        val q = SpatialPointer(frustum)
        q.frame(Quat.IDENTITY, layout, 16)
        q.send(PadSignal.Curve(0f, Phase.BEGIN))
        val slide = (1..3).map { q.send(PadSignal.Curve(CurveControl.PER_MM, Phase.UPDATE)) }.flatten()
        q.send(PadSignal.Curve(0f, Phase.END))
        assertEquals(1, slide.filterIsInstance<SpaceAction.Notice>().size)
        assertTrue(layout.panels.all { it.curve == layout.wrap!!.curve })
    }

    @org.junit.Ignore("0.5.36: the wraparound is retired (Mike: Curve and Further/Closer on the selected window only); a saved wrap is undone at load")
    @Test
    fun theWrapsCurveIsKeptByMovesNewWindowsAndTheSavedLayout() {
        val bent = SpaceWrap.curveTo(layout, 0.4f)
        // A push moves the wrap and keeps its curve; a new window joins at it.
        val kept = SpaceWrap.keep(bent, bent.replace(PadWindowMath.atDistance(bent.panel(2)!!, 2.5f)))
        assertTrue(kept.panels.all { it.curve == 0.4f && it.distanceM == 2.5f })
        val added = kept.add("Clock", "com.google.android.deskclock", "Main", aroundYaw = -60f, curve = 0f)!!.first
        assertEquals(0.4f, added.panels.last().curve, 0f)
        // Saved and read back with its curve; a 0.5.27 save (no fourth field) reads fully curved.
        val back = LayoutCodec.decode(LayoutCodec.encode(kept))
        assertEquals(0.4f, back.wrap!!.curve, 1e-6f)
        assertEquals(kept.wrap!!.saved, back.wrap!!.saved)
        val old = LayoutCodec.encode(SpaceWrap.on(layout.let { SpaceWrap.off(it) })).lines().joinToString("\n") { if (it.startsWith("wrap\t")) it.substringBeforeLast('\t') else it }
        assertEquals(1f, LayoutCodec.decode(old).wrap!!.curve, 0f)
        // Off: each window back at its own curve, whatever the wrap's was.
        val off = SpaceWrap.off(bent)
        assertEquals(0f, off.panel(1)!!.curve, 0f)
        assertEquals(0.3f, off.panel(2)!!.curve, 0f)
        // Not wrapped: curveTo changes nothing.
        assertEquals(off, SpaceWrap.curveTo(off, 0.9f))
    }

    // ---- section 2: the SBS prediction's horizon ----

    /** A worn head: sway, a 4 to 12 Hz tremor and the gyro's noise, 1 kHz, [seconds] long. */
    private fun wornHead(t: TremorPredictor, seconds: Double = 4.0) {
        val rnd = java.util.Random(7)
        val n = (seconds * 1000).toInt()
        for (i in 0 until n) {
            val s = i / 1000.0
            fun tremor(phase: Double) = 0.004 * sin(2 * PI * 4.5 * s + phase) + 0.006 * sin(2 * PI * 8.0 * s + phase * 2) + 0.003 * sin(2 * PI * 11.5 * s + phase * 3)
            val sway = 0.02 * sin(2 * PI * 0.6 * s)
            val w = Vec3(
                (sway + tremor(0.0) + rnd.nextGaussian() * 0.002).toFloat(),
                (sway * 0.5 + tremor(1.0) + rnd.nextGaussian() * 0.002).toFloat(),
                (tremor(2.0) * 0.3 + rnd.nextGaussian() * 0.002).toFloat(),
            )
            t.add(i * 1_000_000L, w)
        }
    }

    /** Full SBS's pose horizon p50 in vc48 (34 ms), and what 0.5.28 takes off it there: the +4 ms scan guess. */
    private val sbs0527Ms = 34.0
    private val p60 = 16_666_667L
    private val sbs0528Ms = sbs0527Ms - (ModeGlassesDelay.ms(8, false, true, p60, rowPose = false) - ModeGlassesDelay.ms(8, false, true, p60, rowPose = true))

    @Test
    fun sbsPredictsLessFarSoItsGainAt8And12HzFalls() {
        assertEquals(30.0, sbs0528Ms, 0.2)
        // The learned predictor on a worn head, and the prior alone (what the predictor falls back to on a head it has
        // not learned; vc48's own figures, 141 % and 559 % at 34 ms, are close to the prior's).
        val learned = TremorPredictor.worn().also { wornHead(it) }
        val prior = TremorPredictor(learn = false).also { p -> for (i in 0 until 1000) p.add(i * 1_000_000L, Vec3.ZERO) }
        val lines = mutableListOf<String>()
        for ((name, t) in listOf("learned" to learned, "prior" to prior)) {
            for (hz in listOf(8.0, 12.0)) {
                val before = t.leftShare(hz, sbs0527Ms)
                val after = t.leftShare(hz, sbs0528Ms)
                lines += String.format(Locale.ROOT, "%s %.0f Hz: %.0f %% at %.0f ms -> %.0f %% at %.0f ms", name, hz, before * 100, sbs0527Ms, after * 100, sbs0528Ms)
                // The gain at 8 and 12 Hz falls with the horizon, by at least a sixth.
                assertTrue("$name $hz Hz: $before -> $after", after < before * 0.85)
            }
            // And the head's sway (1 Hz) is still carried as well as before, within a point.
            assertTrue(t.leftShare(1.0, sbs0528Ms) <= t.leftShare(1.0, sbs0527Ms) + 0.01)
        }
        println("SBS horizon: " + lines.joinToString("; "))
    }

    /** On screen, in time: [rate] (plus the gyro's [noise]) fed to the worn predictor; the RMS (rad) of the pose shown [h] ms ahead against where the head then is. */
    private fun onScreen(h: Double, rate: (Double) -> Double, noise: Double, seconds: Double = 6.0): Double {
        val n = (seconds * 1000).toInt()
        val ahead = h.toInt()
        val angle = DoubleArray(n + ahead + 1)
        val rates = DoubleArray(n + ahead + 1) { rate(it / 1000.0) }
        for (i in 1 until angle.size) angle[i] = angle[i - 1] + rates[i] * 0.001
        val t = TremorPredictor.worn()
        val rnd = java.util.Random(11)
        var e = 0.0
        var count = 0
        for (i in 0 until n) {
            t.add(i * 1_000_000L, Vec3((rates[i] + rnd.nextGaussian() * noise).toFloat(), (rnd.nextGaussian() * noise).toFloat(), (rnd.nextGaussian() * noise).toFloat()))
            if (i < 3000) continue
            val p = t.turn(h) ?: continue
            val truth = angle[i + ahead] - angle[i]
            e += (p.x - truth) * (p.x - truth)
            count++
        }
        return kotlin.math.sqrt(e / count)
    }

    @Test
    fun onScreenTheShorterHorizonHelpsATremorAndAStillHead() {
        val deg = 180 / PI
        val rnd = java.util.Random(3)
        val drift = DoubleArray(8000).also { a -> for (i in 1 until a.size) a[i] = a[i - 1] + rnd.nextGaussian() * 0.02 }
        // An irregular 11.5 Hz tremor (its rhythm wanders) with sway, and a still worn head (a faint sway, the gyro's noise).
        val tremor = { s: Double -> 0.02 * sin(2 * PI * 0.5 * s) + 0.02 * sin(2 * PI * 11.5 * s + drift[(s * 1000).toInt().coerceIn(0, drift.size - 1)] * 6) }
        val still = { s: Double -> 0.002 * sin(2 * PI * 0.4 * s) }
        val t0 = onScreen(sbs0527Ms, tremor, 0.002)
        val t1 = onScreen(sbs0528Ms, tremor, 0.002)
        val s0 = onScreen(sbs0527Ms, still, 0.003)
        val s1 = onScreen(sbs0528Ms, still, 0.003)
        println(String.format(Locale.ROOT, "SBS on screen: tremor %.4f -> %.4f deg RMS, still head %.5f -> %.5f deg RMS", t0 * deg, t1 * deg, s0 * deg, s1 * deg))
        assertTrue("tremor: $t0 -> $t1", t1 < t0)
        assertTrue("still head: $s0 -> $s1", s1 <= s0)
    }

    @Test
    fun sideBySideTakesThe8MsGuessWhenThePerRowPoseIsOn() {
        val p60 = 16_666_667L
        assertEquals(8.0, ModeGlassesDelay.ms(8, calibrated = false, sbs = true, periodNs = p60, rowPose = true), 0.0)
        assertTrue(ModeGlassesDelay.ms(8, calibrated = false, sbs = true, periodNs = p60, rowPose = false) > 11.0)
        assertEquals(8.0, ModeGlassesDelay.ms(8, calibrated = false, sbs = false, periodNs = 8_333_333L, rowPose = true), 0.0)
        assertTrue("per-row pose" in ModeGlassesDelay.line(8, false, true, p60, rowPose = true))
    }

    @Test
    fun theLateWarpStartsAMillisecondEarlierUnderPressure() {
        val b = DrawBudget()
        repeat(60) { b.add(5_000_000L) }
        val p60 = 16_666_667L
        val plain = b.budgetNs(p60)
        b.pressureMs = 1f
        assertEquals((plain + 1_000_000L).toDouble(), b.budgetNs(p60).toDouble(), 1_000.0)
        // Never past a period less the spare.
        repeat(240) { b.add(16_000_000L) }
        assertTrue(b.budgetNs(p60) <= p60 - 1_000_000L + 1_000L)
    }

    // ---- section 3 ----

    @Test
    fun theLogFileRotatesThroughItsFiles() {
        val dir = File(System.getProperty("java.io.tmpdir"), "fs-log-test-${System.nanoTime()}")
        try {
            val f = RotatingLogFile(dir, maxBytes = 1000, files = 3)
            repeat(100) { f.write("line %03d %s".format(Locale.ROOT, it, "x".repeat(40))) }
            f.close()
            val names = dir.list()!!.sorted()
            assertEquals(listOf("flipspaces.1.log", "flipspaces.2.log", "flipspaces.log"), names)
            for (i in 0 until 3) assertTrue(f.file(i).length() <= 1000)
            // The newest holds the last line, the oldest kept file an earlier one, nothing past the three files.
            assertTrue(f.file(0).readText().trimEnd().endsWith("line 099 " + "x".repeat(40)))
            assertTrue(f.file(2).readText().startsWith("line "))
            // A new start appends to the newest.
            val g = RotatingLogFile(dir, maxBytes = 1000, files = 3)
            val before = g.file(0).length()
            g.write("again")
            g.close()
            assertTrue(g.file(0).length() > before || g.file(1).exists())
            assertEquals(8L * 1024 * 1024, RotatingLogFile.MAX_BYTES)
            assertEquals(8, RotatingLogFile.FILES)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aHeldWindowsDisplayIsResizedOnlyForChangesOverFivePercent() {
        val gate = PanelResizeGate(PanelResizeGate.LIVE_GAP_MS)
        gate.opened(1, 2000, 1125, 320)
        // While held: under 5 % waits for the release.
        gate.request(1, 2060, 1150, 320, live = true)
        assertFalse(gate.pending(1))
        // Over 5 %: taken, but no faster than every 250 ms.
        gate.request(1, 2200, 1240, 320, live = true)
        assertTrue(gate.pending(1))
        assertEquals(1, gate.due(0L).size)
        gate.request(1, 2600, 1460, 320, live = true)
        assertTrue(gate.due(100L).isEmpty())
        assertEquals(1, gate.due(260L).size)
        // The release sets the exact final size, however small the change.
        gate.request(1, 2610, 1465, 320, live = false)
        assertEquals(listOf(1 to (2610 to 1465)), gate.due(600L))
    }

    @Test
    fun aDepthPushIsLoggedOnlyWhenTheWindowMoved() {
        // At 15 m (the farthest) one way cannot move the window and the other can: a line only for the move.
        for (dy in listOf(-0.2f, 0.2f)) {
            var l = SpaceLayout(listOf(SpacePanel(1, "Twitch", "tv.twitch.android.app", "Main", yawDeg = 0f, pitchDeg = 0f, distanceM = PanelLimits.MAX_DISTANCE)))
            val p = SpatialPointer(frustum)
            p.frame(Quat.IDENTITY, l, 16)
            fun send(s: PointerSignal) = p.handle(s, Quat.IDENTITY, l, { Pair(1280, 720) }).also { out -> out.filterIsInstance<SpaceAction.Layout>().lastOrNull()?.let { l = it.layout } }
            send(PadSignal.Window(WindowAction.RESIZE))
            val out = mutableListOf<SpaceAction>()
            out += send(PadSignal.TwoFinger(0f, 0f, 0f, 0f, 1f, Phase.BEGIN))
            repeat(5) { out += send(PadSignal.TwoFinger(0f, dy, 0f, dy, 1f, Phase.UPDATE)) }
            out += send(PadSignal.TwoFinger(0f, 0f, 0f, 0f, 1f, Phase.END))
            val moved = kotlin.math.abs(l.panel(1)!!.distanceM - PanelLimits.MAX_DISTANCE) > DEPTH_LOG_M
            assertEquals("swipe $dy: moved $moved", moved, out.filterIsInstance<SpaceAction.Note>().any { "pushed to" in it.line })
        }
    }

    @Test
    fun theSpaceIdlesAfterTenMinutesWithTheGlassesLyingStill() {
        val idle = OffHeadIdle()
        assertNull(idle.update(true, 0L))
        assertNull(idle.update(true, 9 * 60_000L))
        assertFalse(idle.idle)
        assertEquals(60, idle.capFps(60))
        // A worn head is never lying still: the clock starts again.
        assertNull(idle.update(false, 9 * 60_000L + 1))
        assertNull(idle.update(true, 10 * 60_000L))
        assertFalse(idle.idle)
        assertNotNull(idle.update(true, 20 * 60_000L))
        assertTrue(idle.idle)
        assertEquals(OffHeadIdle.IDLE_FPS, idle.capFps(60))
        // Any motion wakes it at once.
        assertNotNull(idle.update(false, 20 * 60_000L + 16))
        assertEquals(60, idle.capFps(60))
    }
}
