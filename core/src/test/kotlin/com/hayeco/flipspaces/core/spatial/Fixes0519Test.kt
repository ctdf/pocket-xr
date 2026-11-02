package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.hands.PhoneHandling
import com.hayeco.flipspaces.core.hands.PinchLockSettings
import com.hayeco.flipspaces.core.hands.PressGate
import com.hayeco.flipspaces.core.pointer.CoverButton
import com.hayeco.flipspaces.core.pointer.CoverLayoutMath
import com.hayeco.flipspaces.core.pointer.DpRect
import com.hayeco.flipspaces.core.pointer.StripSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** 0.5.19: the fixes for the vc39 test's problems, on Mike's own vc39 figures where they exist. */
class Fixes0519Test {
    // ---- item 1: the full screen hold never seen ----

    /** The glasses' HWC table in Mike's vc39 dumpsys SurfaceFlinger (names cut in the middle, as SurfaceFlinger prints them). */
    private val vc39Tables = """
Display 4633128672291735938 HWC layers (top to bottom):
-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
 Layer name
           Z |  Window Type |  Comp Type |  Transform |   Disp Frame (LTRB) |          Source Crop (LTRB) | DefaultFrameRateCompatibility |     Frame Rate (Explicit) (Seamlessness) [Focused] [FrameBooster]
-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
 VRI-com.hayeco.flipspaces/com.hayeco.flipspaces.TrackpadActivity${'$'}_8872#6974
          44 |            1 |     DEVICE |          0 |    0    0 1048  948 |    0.0    0.0 1572.0 1422.0 |                      Default |        Cat::NoPreference           Default    [ ]     [ ]
-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------


Display 4626964206039778820 HWC layers (top to bottom):
-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
 Layer name
           Z |  Window Type |  Comp Type |  Transform |   Disp Frame (LTRB) |          Source Crop (LTRB) | DefaultFrameRateCompatibility |     Frame Rate (Explicit) (Seamlessness) [Focused] [FrameBooster]
-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
 VRI-DexPrivacyDotOverlay${'$'}_2844#8314
          73 |         2024 |     CLIENT |          0 |    0 1024 3840 1080 |    0.0    0.0 3840.0   56.0 |                      Default |                                              [ ]     [ ]
-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
 751f23f SurfaceView[com.hayeco.flips[...]spatial.SpaceActivity]@0(BLAST)#7787
          66 |            0 |     DEVICE |          0 |    0    0 3840 1080 |    0.0    0.0 3840.0 1080.0 |                      Default |  60.00           Exact SeamedAndSeamless    [*]     [ ]
-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------

""".trimStart('\n')

    private val pkg = "com.hayeco.flipspaces"

    @Test
    fun theProbeFindsTheSpaceUnderSurfaceFlingersCutName() {
        val cut = "751f23f SurfaceView[com.hayeco.flips[...]spatial.SpaceActivity]@0(BLAST)#7787"
        // 0.5.18's rule needed the whole package in the name.
        assertFalse(cut.contains(pkg))
        assertTrue(SurfaceFlingerLayers.isSpace(cut, pkg, "SpaceActivity"))
        val r = SurfaceFlingerLayers.check(vc39Tables, pkg, "SpaceActivity", "4626964206039778820")
        assertTrue(r.line, r is OnScreen.Composed)
        // Another app's cut name, or another activity of Pocket XR, is not the Space.
        assertFalse(SurfaceFlingerLayers.isSpace("751f23f SurfaceView[com.hayeco.other[...]spatial.SpaceActivity]@0(BLAST)#1", pkg, "SpaceActivity"))
        assertFalse(SurfaceFlingerLayers.isSpace("VRI-com.hayeco.flipspaces/com.hayeco.flipspaces.TrackpadActivity\$_8872#6974", pkg, "SpaceActivity"))
        assertFalse(SurfaceFlingerLayers.isSpace("x SurfaceView[com.ha[...]spatial.SpaceActivity]#1", pkg, "SpaceActivity"))
    }

    @Test
    fun theGlassesLeftOutOfTheTablesIsNotANo() {
        // vc39: the probe's tables had no glasses display, and 0.5.18 said "not composed" from the others.
        val phoneOnly = vc39Tables.substringBefore("Display 4626964206039778820")
        val r = SurfaceFlingerLayers.check(phoneOnly, pkg, "SpaceActivity", "4626964206039778820")
        assertTrue(r.line, r is OnScreen.Unknown)
        assertEquals(OnScreenKind.UNKNOWN, OnScreen.kindOf(r.line))
        // Unknown answers never close the Space: the check ends undecided.
        val check = VisibleCheck()
        check.start(0)
        var step: VisibleCheck.Step = check.result(600, OnScreenKind.UNKNOWN)
        var t = 600L
        while (step is VisibleCheck.Step.Again) {
            t += step.inMs
            step = check.result(t, OnScreenKind.UNKNOWN)
        }
        assertTrue("$step", step is VisibleCheck.Step.Undecided)
        // With the composition list (One UI 8's layer dump) the Space's visible SurfaceView says yes.
        val list = phoneOnly + """
Composition list (top to bottom)
LayerStack=35
  Layer [7787] 751f23f SurfaceView[com.hayeco.flipspaces/com.hayeco.flipspaces.spatial.SpaceActivity]@0(BLAST)#7787
    visible reason= buffer=38104949850259 frame=13573 contentDirty
    bounds={0,0,1080,3840}

Input list
"""
        val r2 = SurfaceFlingerLayers.check(list, pkg, "SpaceActivity", "4626964206039778820")
        assertTrue(r2.line, r2 is OnScreen.Composed)
        val hidden = list.replace("visible reason= buffer", "invisible reason=nothing to draw buffer")
        assertTrue(SurfaceFlingerLayers.check(hidden, pkg, "SpaceActivity", "4626964206039778820") is OnScreen.Unknown)
    }

    // ---- item 3: frames presented later than the model ----

    /** The first 40 rows of Mike's `dumpsys SurfaceFlinger --latency` for the Space's layer (SBS, 60 Hz). */
    private val vc39Latency = """16666667
39045754461002	39045784549139	0
39045771127669	39045784549139	39045756724608
39045787794336	39045818228553	0
39045804477333	39045818228553	39045790431951
39045821144667	39045851413592	0
39045837844668	39045851413592	39045823304842
39045854511335	39045867948983	39045846568201
39045871178002	39045884637850	39045857001170
39045887844669	39045901207381	39045881280623
39045904511336	39045917959334	39045889340662
39045921178003	39045934666639	39045913950428
39045937733008	39045951300154	39045923416951
39045954455668	39045967963553	39045946144686
39045971122335	39045984655779	39045956552498
39045987789002	39046001317069	39045982506365
39046004455669	39046017951404	39045990053201
39046021122336	39046034633865	39046012945506
39046037800306	39046051290311	39046023372928
39046054466254	39046084631795	0
39046071126668	39046084631795	39046056851990
39046087793335	39046118016717	0
39046104460002	39046118016717	39046089322108
39046121126669	39046134604061	39046112835311
39046137793336	39046151292850	39046122567420
39046154460003	39046167985779	39046143150662
39046171201235	39046201322576	0
39046187867902	39046201322576	39046177921522
39046204456335	39046218018201	39046189344451
39046221123002	39046234718592	39046213133944
39046237789669	39046251388084	39046222658944
39046254456336	39046268152576	39046245951600
39046271110259	39046284741092	39046256054647
39046287774344	39046318061717	0
39046304458668	39046318061717	39046288936717
39046321125335	39046334669842	39046316128279
39046337792002	39046351387498	39046322515662
39046354458669	39046367971287	39046348664451
39046371125336	39046384718904	39046355799373
39046387792003	39046401453201	39046382299256
"""

    @Test
    fun thePresentModelIsCorrectedFromSurfaceFlingersFences() {
        val t = SfLatency.parse(vc39Latency)!!
        assertEquals(16_666_667L, t.periodNs)
        assertEquals(39, t.frames.size)
        // Each frame was stamped half a period before the present its pose was for ([DrawPlan.stampNs]).
        val fence = PresentFence(minFrames = 20, skipFirstMs = 0)
        for (f in t.frames) fence.record(f.desiredNs, f.desiredNs + t.periodNs / 2)
        val r = fence.measure(t)
        println("vc39's fences against the model: ${fence.line()}")
        // SurfaceFlinger's actual less desired is 13.4 ms; less the stamp's half period, the model was 5.1 ms early.
        assertTrue(r.usable)
        assertEquals(5.1, r.offsetMs, 0.3)
        assertEquals(r.offsetMs * 1e6, fence.offsetNs.toDouble(), 1e5)
        // The never latched frames (ready 0) and the frames a vsync late are not in the offset.
        assertEquals(t.frames.count { it.presented }, r.matched)
        assertTrue(r.spreadMs < 1.0)
        // The next frames carry it: their model present already holds the offset, and a second table agrees.
        val again = PresentFence(minFrames = 20, skipFirstMs = 0)
        for (f in t.frames) again.record(f.desiredNs, f.desiredNs + t.periodNs / 2 + 5_100_000L)
        assertEquals(0.0, again.measure(t).offsetMs, 0.3)
    }

    @Test
    fun aFenceLaterThanAnyScanOutIsNotFollowed() {
        val t = SfLatency.parse(vc39Latency)!!
        val fence = PresentFence(minFrames = 20, skipFirstMs = 0)
        // A model 20 ms early (or a fence stamped 20 ms late): refused, nothing applied.
        for (f in t.frames) fence.record(f.desiredNs, f.desiredNs + t.periodNs / 2 - 20_000_000L)
        val r = fence.measure(t)
        assertFalse(r.usable)
        assertEquals(0L, fence.offsetNs)
        // Frames that match no drawn frame say nothing.
        val other = PresentFence(minFrames = 20, skipFirstMs = 0)
        other.record(1L, 2L)
        assertFalse(other.measure(t).usable)
        assertNull(SfLatency.parse("unknown: no SurfaceView layer"))
    }

    // ---- item 7: an app that crashed on its panel ----

    @Test
    fun aCrashOnAPanelIsNamed() {
        val dump = """
  package: com.example.game
    Historical Process Exit for uid=10302
        ApplicationExitInfo #0:
          timestamp=2030-01-01 10:48:15.424 pid=31544 realUid=10302 packageUid=10302 definingUid=10302 user=0
          process=com.example.game reason=5 (CRASH NATIVE) subreason=0 (UNKNOWN) status=11
          importance=100 pss=0.00 rss=310MB state=empty trace=null
          description=crash
"""
        val e = AppExit.parse(dump)!!
        assertTrue(e.crashed)
        assertEquals("2030-01-01 10:48:15.424", e.timestamp)
        val back = AppExit.fromLine(e.line())!!
        assertEquals(5, back.reason)
        assertEquals("2030-01-01 10:48:15.424", back.timestamp)
        assertTrue(PanelCard.AppClosed("A game", "Crash native: crash").lines.contains("The app closed: it crashed"))
        // Pocket XR's own record in vc39 (an invalid start) is no crash.
        val own = AppExit.parse("        ApplicationExitInfo #0:\n          timestamp=2030-01-01 12:42:41.880 pid=6479\n          process=com.hayeco.flipspaces reason=13 (OTHER KILLS BY SYSTEM) subreason=13 (INVALID START) status=0\n          description=[INVALID START] No entry in mProcessNames;\n")!!
        assertFalse(own.crashed)
        assertNull(AppExit.fromLine("unknown: no exit record"))
    }

    // ---- item 6: presses from glasses off the head ----

    @Test
    fun noHandPressWhileTheGlassesAreNotWorn() {
        val h = PhoneHandling()
        // No head pose yet: nothing said.
        assertNull(h.notWorn(1_000))
        // vc39: roll 173.6 degrees, upside down on the table.
        h.glasses(173.6f, 2f, static = false, nowMs = 10_000)
        val why = h.notWorn(10_050)
        assertNotNull(why)
        val refusal = PressGate.refusal(PinchLockSettings(), 0.3f, 0.4f, 100L, false, 50L, 0f, 0.9f, 0.5f, notWorn = why)!!
        assertTrue(refusal, refusal.startsWith("not worn: roll 174 deg"))
        assertEquals("not worn", PressGate.kind(refusal))
        // Worn upright (head rolled a little): presses again.
        h.glasses(8f, -12f, static = false, nowMs = 11_000)
        assertNull(h.notWorn(11_050))
        // Upright but lying still (static) for over 5 s: not worn.
        h.glasses(2f, 0f, static = true, nowMs = 12_000)
        assertNull(h.notWorn(14_000))
        h.glasses(2f, 0f, static = true, nowMs = 17_100)
        assertTrue(h.notWorn(17_100)!!.contains("lain still"))
        // The pose stopped coming (head tracking off): nothing said.
        assertNull(h.notWorn(17_100 + 2_500))
    }

    // ---- item 5: the yaw pool ----

    private val trueBias = Vec3(-0.00030f, -0.00376f, -0.00390f)

    private fun quat(yawDeg: Double, pitchDeg: Double, rollDeg: Double): Quat =
        (Quat.axisAngle(Vec3.UP, (-yawDeg * PI / 180).toFloat()) * Quat.axisAngle(Vec3.RIGHT, (pitchDeg * PI / 180).toFloat()) *
            Quat.axisAngle(Vec3(0f, 0f, -1f), (rollDeg * PI / 180).toFloat())).normalized()

    /** As Fixes0517YawTest: 1 kHz, 0.003 rad/s gyro and 0.05 m/s2 accelerometer noise. */
    private fun run(settings: FusionSettings, seconds: Double, angles: (Double) -> DoubleArray, prepare: (HeadFusion) -> Unit): HeadFusion {
        val rnd = java.util.Random(11)
        val f = HeadFusion(settings)
        prepare(f)
        for (i in 0 until (seconds * 1000).toInt()) {
            val t = i / 1000.0
            val a0 = angles(t)
            val q = quat(a0[0], a0[1], a0[2])
            val a1 = angles(t + 0.001)
            val dq = (q.conjugate() * quat(a1[0], a1[1], a1[2])).normalized()
            val s = if (dq.w < 0) -1f else 1f
            val omega = Vec3(dq.x * s, dq.y * s, dq.z * s) * (2f / 0.001f)
            fun nz(sd: Double) = (rnd.nextGaussian() * sd).toFloat()
            f.update(omega + trueBias + Vec3(nz(0.003), nz(0.003), nz(0.003)), q.conjugate().rotate(Vec3(0f, 9.81f, 0f)) + Vec3(nz(0.05), nz(0.05), nz(0.05)), i * 1_000_000L)
        }
        return f
    }

    /** A restless worn head (Fixes0517YawTest's): a glance every 3 s, 0.8 s of rest between; [creepDegS] of slow yaw while it rests, taken back in the next glance. */
    private fun restless(creepDegS: Double = 0.0): (Double) -> DoubleArray = { t ->
        val k = kotlin.math.floor(t / 3.0)
        val u = t - k * 3.0
        val amp = (if (k.toLong() % 2 == 0L) 1 else -1) * (12.0 + 13.0 * ((k * 0.618) % 1.0))
        fun ease(x: Double) = 0.5 - 0.5 * cos(PI * x.coerceIn(0.0, 1.0))
        val g = when {
            u < 0.8 -> ease(u / 0.8)
            u < 1.4 -> 1.0
            u < 2.2 -> 1 - ease((u - 1.4) / 0.8)
            else -> 0.0
        }
        val creep = when {
            u >= 2.2 -> creepDegS * (u - 2.2)
            u < 0.8 -> creepDegS * 0.8 * (1 - ease(u / 0.8))
            else -> 0.0
        }
        doubleArrayOf(
            amp * g + creep + 0.3 * sin(2 * PI * 0.23 * t) + 0.05 * sin(2 * PI * 8 * t),
            -4.0 + 0.3 * sin(2 * PI * 0.19 * t + 1.0) + 0.04 * sin(2 * PI * 7 * t),
            0.2 * sin(2 * PI * 0.15 * t + 2.0),
        )
    }

    private fun alongUp(b: Vec3): Float = b dot quat(0.0, -4.0, 0.0).conjugate().rotate(Vec3.UP)

    @Test
    fun slowHeadYawInTheRestsMovesTheBiasAtMostTheCap() {
        // vc39: the rested means read about 0.0013 to 0.0023 rad/s off the static bias along up, a head
        // turning slowly while it "rests"; 0.5.18 moved the bias 0.0007 rad/s the wrong way (0.04 deg/s).
        val creep = 0.11 // deg/s while resting: 0.0019 rad/s
        val seeded: (HeadFusion) -> Unit = { it.seedBias(trueBias, trueBias) }
        val old = run(FusionSettings.v0518(), 300.0, restless(creep), seeded)
        val now = run(FusionSettings(), 300.0, restless(creep), seeded)
        val oldOff = alongUp(old.gyroBias) - alongUp(trueBias)
        val nowOff = alongUp(now.gyroBias) - alongUp(trueBias)
        println("slow yaw in the rests (0.11 deg/s), the static bias right: 0.5.18 moved it %.5f rad/s along up, 0.5.19 %.5f (%d used, %d held at the cap)".format(java.util.Locale.ROOT, oldOff, nowOff, now.poolUsed, now.poolHeld))
        assertTrue("0.5.18 off $oldOff", abs(oldOff) >= 0.0005f)
        assertTrue("0.5.19 off $nowOff", abs(nowOff) <= 0.00035f)
    }

    @Test
    fun upsideDownGlassesTeachNoYaw() {
        // Worn for 2 min, then 90 s handled upside down off the head (roll 174, small hand motion), as
        // in vc39; the bias is right from the start.
        val worn = restless()
        val handled: (Double) -> DoubleArray = { t ->
            if (t < 120.0) worn(t) else doubleArrayOf(
                30.0 + 1.5 * sin(2 * PI * 0.3 * t) + 0.3 * sin(2 * PI * 2.1 * t),
                5.0 + 1.0 * sin(2 * PI * 0.25 * t + 0.5),
                174.0 + 1.0 * sin(2 * PI * 0.2 * t),
            )
        }
        val seeded: (HeadFusion) -> Unit = { it.seedBias(trueBias, trueBias) }
        val now = run(FusionSettings(), 210.0, handled, seeded)
        val old = run(FusionSettings.v0518(), 210.0, handled, seeded)
        val nowOff = (now.gyroBias - trueBias).length
        val oldOff = (old.gyroBias - trueBias).length
        println("upside down after 2 min worn: bias off %.5f rad/s in 0.5.18, %.5f in 0.5.19; last pool verdict: %s".format(java.util.Locale.ROOT, oldOff, nowOff, now.yawLine().substringAfter("rested moments pooled").take(160)))
        assertTrue("0.5.19 off $nowOff", nowOff <= 0.00035f)
        assertTrue(now.yawLine(), now.yawLine().contains("not upright") || now.yawLine().contains("held"))
    }

    // ---- item 8: panels opened lazily ----

    @Test
    fun panelsInViewOpenFirstAndTheRestOneAtATime() {
        // In view: at once, whatever else opens.
        assertTrue(PanelOpenOrder.mayOpen(nearView = true, openingNow = 3, nearWaiting = 2, sinceStartMs = 0))
        // Out of view: not while others open or wait in view, not before 15 s.
        assertFalse(PanelOpenOrder.mayOpen(false, 0, 0, 5_000))
        assertFalse(PanelOpenOrder.mayOpen(false, 1, 0, 20_000))
        assertFalse(PanelOpenOrder.mayOpen(false, 0, 1, 20_000))
        assertTrue(PanelOpenOrder.mayOpen(false, 0, 0, 15_000))
        // The vc39 layout: four panels round the head; with the head ahead, the two within 25 degrees of the view open first.
        val f = ViewFrustum(3840 / 2, 1080, 70f)
        val panels = listOf(0f, 45f, 95f, -140f).mapIndexed { i, yaw -> SpacePanel(i + 1, "p$i", "pkg$i", "A", yaw, 0f) }
        val near = panels.filter { PanelCulling.visible(it, Quat.IDENTITY, f, PanelOpenOrder.NEAR_MARGIN_DEG) }.map { it.id }
        assertEquals(listOf(1, 2), near)
    }

    // ---- 0.5.19's Curve on a smaller cover ----

    @Test
    fun aCoverTooShortForFiveRailButtonsPutsCurveInTheTopRow() {
        val l = CoverLayoutMath.compute(399f, 380f, listOf(DpRect(180f, 300f, 399f, 380f)), 40f, StripSide.RIGHT)
        assertEquals(emptyList<String>(), CoverLayoutMath.problems(l))
        val curve = l.buttons.getValue(CoverButton.CURVE)
        assertEquals(l.buttons.getValue(CoverButton.MENU).top, curve.top, 0.01f)
        // On the Flip7 it is the rail's fifth button.
        val flip = CoverLayoutMath.flip7()
        assertEquals(flip.buttons.getValue(CoverButton.MOVE).left, flip.buttons.getValue(CoverButton.CURVE).left, 0.01f)
    }
}
