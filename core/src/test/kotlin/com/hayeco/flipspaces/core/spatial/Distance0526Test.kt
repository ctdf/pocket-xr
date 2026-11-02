package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.pointer.CoverRepeat
import com.hayeco.flipspaces.core.pointer.CoverWindowStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.abs
import kotlin.math.tan

/**
 * 0.5.26 (Mike: "push windows even further away and bring them even closer"): 0.3 to 15 m on every path, the
 * distance-squared feel kept, a window's apparent size kept at any distance, exact hit tests, no near clip, sharp text
 * and comfortable stereo up close.
 */
class Distance0526Test {
    private val panel = SpacePanel(1, "Twitch", "tv.twitch.android.app", "Main", yawDeg = 0f, pitchDeg = 0f)
    private val frustum = GlassesScreen.AURA_2D.frustum(1920, 1080)

    @Test
    fun theLimitsAreMuchWiderThanBefore() {
        assertEquals(0.3f, PanelLimits.MIN_DISTANCE, 0f)
        assertEquals(15f, PanelLimits.MAX_DISTANCE, 0f)
        assertEquals(PanelLimits.MIN_DISTANCE, PadWindowMath.APP_MIN_M, 0f)
        assertEquals(PanelLimits.MAX_DISTANCE, PadWindowMath.APP_MAX_M, 0f)
        // 0.5.25: hands 0.6 to 4 m, trackpads 1.3 to 3.8 m; now wider than 0.5 to 10 m.
        assertTrue(PanelLimits.MIN_DISTANCE < 0.5f && PanelLimits.MAX_DISTANCE > 10f)
        // A saved layout keeps a distance within the new range, and clamps one outside it.
        val saved = LayoutCodec.decode(LayoutCodec.encode(SpaceLayout(listOf(panel.copy(distanceM = 12f), panel.copy(id = 2, distanceM = 0.35f)))))
        assertEquals(12f, saved.panel(1)!!.distanceM, 1e-4f)
        assertEquals(0.35f, saved.panel(2)!!.distanceM, 1e-4f)
    }

    @Test
    fun aWindowKeepsItsSizeInTheRoomAndItsDisplayAtAnyDistance() {
        // 0.5.27: 0.5.26 kept the angles, so a depth move changed nothing in the picture.
        // Now the metres are kept: it looks smaller further away. Its display keeps its pixels and density.
        val display = PanelDisplaySize.of(panel)
        for (d in listOf(0.6f, 1.4f, 4f, 10f)) {
            val p = PadWindowMath.atDistance(panel, d)
            assertEquals("width at $d m", panel.widthM, p.widthM, 1e-4f)
            assertEquals("height at $d m", panel.heightM, p.heightM, 1e-4f)
            assertEquals("shape at $d m", PanelDisplaySize.aspect(panel), PanelDisplaySize.aspect(p), 1e-4f)
            assertEquals("display at $d m", display, PanelDisplaySize.of(p))
        }
        // At 15 m it would be 2.1 degrees tall: it stops at the 3 degree depth minimum, the shape kept.
        assertEquals(PanelLimits.DEPTH_MIN_SIZE_DEG, PadWindowMath.atDistance(panel, 15f).heightDeg, 0.01f)
        assertEquals(display, PanelDisplaySize.of(PadWindowMath.atDistance(panel, 15f)))
        assertTrue(PadWindowMath.atDistance(panel, 0.6f).widthDeg > 70f)
        // At 0.3 m the default window would be over 100 degrees wide: both sides shrink together, the shape kept.
        val near = PadWindowMath.atDistance(panel, 0.3f)
        assertEquals(PanelLimits.MAX_WIDTH_DEG, near.widthDeg, 0.01f)
        assertEquals(PanelDisplaySize.aspect(panel), PanelDisplaySize.aspect(near), 1e-3f)
        assertEquals(display, PanelDisplaySize.of(near))
        // A resize still changes it.
        val resized = PadWindowMath.scaled(panel, 1.5f, ViewFrustum(1920, 1080, 120f))
        assertTrue(resized.widthDeg > panel.widthDeg)
    }

    @Test
    fun theDistanceSquaredFeelIsKeptFarMovesQuickNearMovesFine() {
        // A 0.02 pad unit two finger swipe at each distance.
        val near = PadWindowMath.pushPull(panel.copy(distanceM = 0.3f), 0.02f).distanceM - 0.3f
        val mid = PadWindowMath.pushPull(panel.copy(distanceM = 1.4f), 0.02f).distanceM - 1.4f
        val far = PadWindowMath.pushPull(panel.copy(distanceM = 10f), 0.02f).distanceM - 10f
        println(String.format(Locale.ROOT, "0.02 pad units: %.4f m at 0.3 m, %.4f m at 1.4 m, %.3f m at 10 m", near, mid, far))
        assertEquals(0.02f * 3f * 0.09f / 6f, near, 1e-6f)
        assertEquals(0.0196f, mid, 1e-5f)
        // 0.5.27: d^2 up to 1.4 m, then only d, so 10 m is 10 / 1.4 times 1.4 m's step.
        assertEquals(10f / 1.4f, far / mid, 0.01f * (far / mid))
        // One move is never more than 10 % of the distance, so a flick at 10 m cannot leap past the range.
        assertEquals(11f, PadWindowMath.pushPull(panel.copy(distanceM = 10f), 1f).distanceM, 1e-5f)
        assertEquals(9f, PadWindowMath.pushPull(panel.copy(distanceM = 10f), -1f).distanceM, 1e-5f)
        // Spatial Mouse's linear depth swipe gets the same cap.
        assertEquals(0.04f, PadWindowMath.limitStep(PadWindowMath.mouseDepthStep(1f), 0.4f), 1e-6f)
    }

    @Test
    fun furtherAndCloserCrossTheWholeRangeInAboutThreeSecondsOfHolding() {
        fun hold(from: Float, further: Boolean): Long {
            val r = CoverRepeat()
            var d = PadWindowMath.clampDistance(from, CoverWindowStep.distance(from, further, r.down(0L)))
            var t = 0L
            val target = if (further) PanelLimits.MAX_DISTANCE else PanelLimits.MIN_DISTANCE
            while (abs(d - target) > 1e-4f && t < 20_000L) {
                t += 10
                for (step in r.tick(t)) d = PadWindowMath.clampDistance(d, CoverWindowStep.distance(d, further, step))
            }
            return t
        }
        val out = hold(0.3f, true)
        val back = hold(15f, false)
        println("Further held from 0.3 m reaches 15 m after $out ms; Closer from 15 m reaches 0.3 m after $back ms")
        assertTrue("out in $out ms", out in 2_000L..4_000L)
        assertTrue("back in $back ms", back in 2_000L..4_000L)
        // The first second of a hold is 0.5.25's (a short hold is as fine as before).
        assertEquals(1.6f, CoverRepeat.stepAt(CoverRepeat.DELAY_MS + CoverRepeat.RAMP_MS), 1e-6f)
        assertEquals(CoverRepeat.MAX_STEP, CoverRepeat.stepAt(CoverRepeat.DELAY_MS + 2 * CoverRepeat.RAMP_MS), 1e-6f)
    }

    @Test
    fun hitTestsAreExactAtTheNearAndFarLimits() {
        for (d in listOf(0.3f, 15f)) {
            val p = PadWindowMath.atDistance(panel, d)
            // A ray from the head through a point a quarter in and a third down on the window.
            val target = p.centre + p.right * (p.widthM * (0.25f - 0.5f)) + p.up * (p.heightM * (0.5f - 1f / 3f))
            val hit = PanelHitTester.hit(Ray(Vec3.ZERO, target.normalized()), p)!!
            assertEquals("u at $d m", 0.25f, hit.u, 1e-4f)
            assertEquals("v at $d m", 1f / 3f, hit.v, 1e-4f)
            assertEquals("distance at $d m", target.length, hit.distance, 1e-3f * d)
            // A hand's ray from 0.3 m below and right of the head lands on the same point.
            val origin = Vec3(0.15f, -0.3f, -0.1f)
            val fromHand = PanelHitTester.hit(Ray(origin, (target - origin).normalized()), p)!!
            assertEquals(0.25f, fromHand.u, 1e-3f)
            assertEquals(1f / 3f, fromHand.v, 1e-3f)
        }
    }

    @Test
    fun aCloseWindowIsNotCutByTheNearClipAndAFarOneNotByTheFar() {
        val proj = frustum.projection()
        for (d in listOf(PanelLimits.MIN_DISTANCE, PanelLimits.MAX_DISTANCE)) {
            // The biggest window the limits allow, at the distance.
            val p = PadWindowMath.atDistance(panel.copy(widthDeg = PanelLimits.MAX_WIDTH_DEG, heightDeg = PanelLimits.MAX_HEIGHT_DEG), d)
            for (sx in listOf(-0.5f, 0.5f)) for (sy in listOf(-0.5f, 0.5f)) {
                val corner = p.centre + p.right * (p.widthM * sx) + p.up * (p.heightM * sy)
                val c = proj.transform(corner)
                assertTrue("corner at $d m in front of the eye", c[3] > 0f)
                assertTrue("corner at $d m inside the depth range: z/w ${c[2] / c[3]}", c[2] / c[3] > -1f && c[2] / c[3] < 1f)
            }
        }
        // The eyes' offsets (half the IPD) never put a 0.3 m window behind the near plane either.
        assertTrue(PanelLimits.MIN_DISTANCE - StereoLayout.MAX_IPD_MM / 2000f > 0.05f)
    }

    @Test
    fun sideBySideStaysComfortableUpClose() {
        val atComfort = StereoComfort.convergenceDeg(StereoComfort.COMFORT_M)
        // 0.3 m without it would be 12 degrees of convergence; with it, the same as at 0.6 m.
        val raw = 2f * kotlin.math.atan(0.0315f / 0.3f) * RAD
        val held = StereoComfort.convergenceDeg(0.3f)
        println(String.format(Locale.ROOT, "convergence: %.1f deg at 0.3 m without, %.1f deg with, %.1f deg at 0.6 m; 1 m %.1f deg", raw, held, atComfort, StereoComfort.convergenceDeg(1f)))
        assertEquals(12f, raw, 0.1f)
        assertEquals(atComfort, held, 0.01f)
        assertEquals(atComfort, StereoComfort.convergenceDeg(0.45f), 0.01f)
        // From the comfort distance on, nothing changes (the eyes' offsets are the real IPD).
        assertEquals(1f, StereoComfort.eyeScale(0.6f), 0f)
        assertEquals(1f, StereoComfort.eyeScale(15f), 0f)
        assertEquals(0.5f, StereoComfort.eyeScale(0.3f), 1e-6f)
        // It is continuous at the comfort distance (no jump as a window comes through it).
        assertEquals(StereoComfort.convergenceDeg(0.6f), StereoComfort.convergenceDeg(0.5999f), 0.01f)
        // The window still grows in view as it comes closer (its size is drawn at its real distance).
        val near = PadWindowMath.atDistance(panel, 0.6f)
        assertEquals(near.widthDeg, 2f * kotlin.math.atan(near.widthM / 2f / 0.6f) * RAD, 1e-3f)
        assertTrue(near.widthDeg > panel.widthDeg)
        assertTrue(tan(near.widthDeg * DEG / 2f) > 0f)
    }
}
