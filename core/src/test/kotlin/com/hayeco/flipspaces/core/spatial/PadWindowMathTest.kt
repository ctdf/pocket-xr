package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.pointer.SwipeDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** 0.5.17: the window maths from the trackpad: limits, step sizes and speeds per finger movement. */
class PadWindowMathTest {
    private val panel = SpacePanel(1, "Chrome", "com.android.chrome", "Main", yawDeg = 0f, pitchDeg = 0f)

    @Test
    fun moveIsAbout57DegreesAPadUnit() {
        assertEquals(57.2958f, PadWindowMath.orbit(0f, 0f, 1f, 0f).first, 1e-3f)
        assertEquals(28.6479f, PadWindowMath.orbit(0f, 0f, 0.5f, 0f).first, 1e-3f)
        assertEquals(-5.72958f, PadWindowMath.orbit(0f, 0f, -0.1f, 0f).first, 1e-4f)
        // Up is up.
        assertEquals(28.6479f, PadWindowMath.orbit(0f, 0f, 0f, 0.5f).second, 1e-3f)
        // A full portrait width (2 units) orbits 114.6 degrees, wrapped.
        assertEquals(wrapDegrees(170f + 114.5916f), PadWindowMath.orbit(170f, 0f, 2f, 0f).first, 1e-3f)
        // Pitch stays within the panel limit, 70 degrees.
        assertEquals(PanelLimits.MAX_PITCH, PadWindowMath.orbit(0f, 60f, 0f, 1f).second, 0f)
    }

    @Test
    fun twoFingersTheSameWayNeedDotOver0707AndEachFinger001() {
        assertEquals(true, PadWindowMath.sameWay(0f, 0.02f, 0f, 0.02f))
        assertEquals(false, PadWindowMath.sameWay(-0.02f, 0f, 0.02f, 0f))
        fun at(deg: Double) = PadWindowMath.sameWay(0f, 0.02f, (0.02 * sin(Math.toRadians(deg))).toFloat(), (0.02 * cos(Math.toRadians(deg))).toFloat())
        assertEquals(true, at(44.0))
        assertEquals(false, at(46.0))
        // Each finger at least 0.01 units first.
        assertNull(PadWindowMath.sameWay(0f, 0.0099f, 0f, 0.02f))
        assertNull(PadWindowMath.sameWay(0f, 0.02f, 0f, 0.0099f))
        assertNotNull(PadWindowMath.sameWay(0f, 0.01f, 0f, 0.01f))
    }

    @Test
    fun depthStepIsDyTimesIncrementTimesDSquaredTimesDtTimes10() {
        assertEquals(0.2f, PadWindowMath.depthStep(0.1f, 3f, 2f, 1f / 60f), 1e-6f)
        assertEquals(0.1f * 3f * 1.69f * (1f / 60f) * 10f, PadWindowMath.depthStep(0.1f, 3f, 1.3f, 1f / 60f), 1e-6f)
        assertEquals(3f, PadWindowMath.INCREMENT, 0f)
        assertEquals(1f / 60f, PadWindowMath.DT, 0f)
    }

    @Test
    fun pushPullUpIsFurtherAndFasterWhenFar() {
        val at2 = panel.copy(distanceM = 2f)
        // 0.5.27: past 1.4 m the d^2 step is damped by 1.4 / d: 0.2 m x 0.7 at 2 m.
        assertEquals(2.14f, PadWindowMath.pushPull(at2, 0.1f).distanceM, 1e-5f)
        assertEquals(1.86f, PadWindowMath.pushPull(at2, -0.1f).distanceM, 1e-5f)
        // Per pad unit of two finger travel at 2 m: 2 m by the d^2 rule itself.
        assertEquals(0.5f * 4f, PadWindowMath.depthStep(1f, 3f, 2f, PadWindowMath.DT), 1e-5f)
        // At 1.4 m, 0.02 units: 0.02 x 3 x 1.96 / 6 = 0.0196 m.
        assertEquals(1.4196f, PadWindowMath.pushPull(panel, 0.02f).distanceM, 1e-5f)
        // 0.5.27: its size in metres stays, so it looks smaller further away (0.5.26 kept the angles).
        val pushed = PadWindowMath.pushPull(at2, 0.1f)
        assertEquals(at2.widthM, pushed.widthM, 1e-4f)
        assertEquals(at2.heightM, pushed.heightM, 1e-4f)
        assertTrue(pushed.widthDeg < at2.widthDeg)
        // A 1 pad unit swipe in 0.04 unit moves: about 1 m at 1.4 m, a few metres at 9 m, never the whole range.
        fun swipe(from: Float, dy: Float): Float {
            var p = panel.copy(distanceM = from)
            repeat(25) { p = PadWindowMath.pushPull(p, dy) }
            return kotlin.math.abs(p.distanceM - from)
        }
        assertTrue("1.4 m out: ${swipe(1.4f, 0.04f)}", swipe(1.4f, 0.04f) in 0.8f..1.6f)
        assertTrue("9 m in: ${swipe(9f, -0.04f)}", swipe(9f, -0.04f) in 2f..6f)
        assertTrue("5 m out: ${swipe(5f, 0.04f)}", swipe(5f, 0.04f) in 2f..6f)
    }

    @Test
    fun appWindowLimitsAre13To38Metres() {
        // 0.5.26 (Mike: further and closer): 0.3 to 15 m on every path (0.5.17 to 0.5.25: 1.3 to 3.8 m).
        assertEquals(0.3f, PadWindowMath.APP_MIN_M, 0f)
        assertEquals(15f, PadWindowMath.APP_MAX_M, 0f)
        assertEquals(15f, PadWindowMath.pushPull(panel.copy(distanceM = 14.9f), 1f).distanceM, 0f)
        // 0.5.27: one move steps at most 10 % of the distance (0.5.26: half), so a long pull takes many moves to reach the near limit.
        var d = panel
        repeat(40) { d = PadWindowMath.pushPull(d, -1f) }
        assertEquals(0.3f, d.distanceM, 0f)
        assertEquals(1.26f, PadWindowMath.pushPull(panel, -1f).distanceM, 1e-5f)
        // A panel already outside the limits (an old save) goes only back towards them, never jumps.
        val close = panel.copy(distanceM = 0.25f)
        assertEquals(0.25f, PadWindowMath.pushPull(close, -0.5f).distanceM, 0f)
        assertEquals(0.25f + 0.01f * 3f * 0.0625f / 6f, PadWindowMath.pushPull(close, 0.01f).distanceM, 1e-6f)
    }

    /** 0.5.41 (Mike: trackpad resizing felt limited): the corner handle's limits, the shape kept. */
    @Test
    fun pinchScaleGoesToTheCornerHandlesLimitsKeepingTheShape() {
        val view = ViewFrustum(1920, 1080, 70f)
        assertEquals(1f, PadWindowMath.scaleOf(panel), 1e-4f)
        assertEquals(2f, PadWindowMath.scaleOf(PadWindowMath.scaled(panel, 2f, view)), 1e-3f)
        // Past 0.5.40's 80 % of the view: a window can grow to the corner handle's 100 by 70 degrees.
        val big = PadWindowMath.scaled(panel, 20f, view)
        assertTrue(big.widthDeg > 0.8f * view.horizontalFovDeg + 1f)
        assertTrue(big.widthDeg <= PanelLimits.MAX_WIDTH_DEG + 1e-3f && big.heightDeg <= PanelLimits.MAX_HEIGHT_DEG + 1e-3f)
        assertTrue(abs(big.widthDeg - PanelLimits.MAX_WIDTH_DEG) < 0.05f || abs(big.heightDeg - PanelLimits.MAX_HEIGHT_DEG) < 0.05f)
        assertEquals("shape kept at the limit", panel.heightM / panel.widthM, big.heightM / big.widthM, 1e-3f)
        // And down to 12 degrees on its shorter side.
        val small = PadWindowMath.scaled(panel, 0.01f, view)
        assertEquals(PanelLimits.MIN_SIZE_DEG, minOf(small.widthDeg, small.heightDeg), 0.05f)
        assertEquals(panel.heightM / panel.widthM, small.heightM / small.widthM, 1e-3f)
    }

    @Test
    fun facingEasesBySlerpFactor12() {
        assertEquals(12f, PadWindowMath.FACE_RATE, 0f)
        assertEquals(0.2f, PadWindowMath.faceFraction(1f / 60f), 1e-6f)
        assertEquals(1f, PadWindowMath.faceFraction(0.1f), 0f)
        val (y, p) = PadWindowMath.faceStep(0f, 0f, 10f, -5f, 1f / 60f)
        assertEquals(2f, y, 1e-5f)
        assertEquals(-1f, p, 1e-5f)
        // The short way round.
        assertEquals(174f, PadWindowMath.faceStep(170f, 0f, -170f, 0f, 1f / 60f).first, 1e-3f)
    }

    @Test
    fun dropRules() {
        assertTrue(PadWindowMath.dropsOnTap(0, 0f, 2))
        assertTrue(PadWindowMath.dropsOnTap(299, 0.15f, 2))
        assertFalse(PadWindowMath.dropsOnTap(300, 0f, 5))
        assertFalse(PadWindowMath.dropsOnTap(100, 0.16f, 5))
        // At least two frames after the pick up.
        assertFalse(PadWindowMath.dropsOnTap(0, 0f, 1))
    }

    @Test
    fun resetIs02MBelowEyeLevelInFront() {
        val far = panel.copy(yawDeg = 70f, pitchDeg = 30f, distanceM = 2f)
        val r = PadWindowMath.resetPose(far, 15f)
        assertEquals(15f, r.yawDeg, 1e-4f)
        assertEquals(-0.2f, r.centre.y, 1e-4f)
        assertEquals(2f, r.distanceM, 0f)
        assertEquals(-5.739f, r.pitchDeg, 1e-3f)
        assertEquals(500L, PadWindowMath.TWEEN_MS)
    }

    @Test
    fun tweenIsOutQuadOver05S() {
        val to = panel.copy(yawDeg = 40f)
        val t = PanelTween(panel, to, 1000)
        assertEquals(0f, t.at(1000).yawDeg, 1e-5f)
        assertEquals(30f, t.at(1250).yawDeg, 1e-4f)
        assertFalse(t.done(1499))
        assertTrue(t.done(1500))
        assertEquals(40f, t.at(1500).yawDeg, 1e-5f)
    }

    @Test
    fun spatialMouseSwipesFollow381() {
        assertTrue(PadWindowMath.mouseSwipeIsDepth(0.1f, 0.06f))
        assertFalse(PadWindowMath.mouseSwipeIsDepth(0.1f, 0.04f))
        // About 4.0 m a pad unit; about 1.16 scale a pad unit.
        assertEquals(4f, PadWindowMath.mouseDepthStep(1f), 1e-4f)
        assertEquals(1.1556f, PadWindowMath.mouseScaleStep(1f), 1e-3f)
    }

    @Test
    fun arrangeCollectsAt378MetresAndCircleAt3() {
        val a = panel.copy(id = 1, yawDeg = -60f, widthDeg = 60f, heightDeg = 40f)
        val b = panel.copy(id = 2, yawDeg = 80f, distanceM = 2f, widthDeg = 60f, heightDeg = 40f)
        // Pocket XR's panels are never under 12 degrees: a default panel collected at 3.78 m stops there.
        assertEquals(PanelLimits.MIN_SIZE_DEG, Arrange.collect(listOf(panel), 0f).single().heightDeg, 0f)
        val out = Arrange.collect(listOf(a, b), 0f)
        assertEquals(2, out.size)
        out.forEach { assertEquals(3.78f, it.distanceM, 0f) }
        // 0.5.21: 0.72 times, unless the short side would go under 12 degrees: then both sides stop there
        // together, so the window (and its app, PanelDisplaySize) keeps its shape.
        for ((o, src) in listOf(out[0] to a, out[1] to b)) {
            assertTrue(o.widthM >= src.widthM * 0.72f - 1e-3f)
            assertTrue(o.heightDeg >= PanelLimits.MIN_SIZE_DEG - 1e-3f)
            assertEquals(src.widthM / src.heightM, o.widthM / o.heightM, 0.01f)
        }
        // A window big enough to stay over 12 degrees at 3.78 m is exactly 0.72 times its size.
        val big = a.copy(widthDeg = 80f, heightDeg = 60f)
        assertEquals(big.widthM * 0.72f, Arrange.collect(listOf(big), 0f).single().widthM, 1e-3f)
        // Two equal windows sit either side of the gaze.
        val pair = Arrange.collect(listOf(a, a.copy(id = 3)), 20f)
        assertEquals(20f - pair[0].yawDeg, pair[1].yawDeg - 20f, 1e-3f)
        // 0.3 m between them along the arc.
        val gap = (out[1].yawDeg - out[0].yawDeg) * DEG * 3.78f - (out[0].widthM + out[1].widthM) / 2f
        assertEquals(0.3f, gap, 0.02f)
        // Four windows: two rows, 0.37 m apart.
        val four = Arrange.collect((1..4).map { panel.copy(id = it) }, 0f)
        assertTrue(four[0].pitchDeg > 0f && four[3].pitchDeg < 0f)
        val c = Arrange.circle(listOf(a, b), 10f, 2)
        val main = c.first { it.id == 2 }
        assertEquals(3f, main.distanceM, 0f)
        assertEquals(10f, main.yawDeg, 1e-4f)
        assertEquals(1.4f, PadWindowMath.scaleOf(main), 0.01f)
    }

    @Test
    fun titleBarsFade18SThen07S() {
        val f = BarFade()
        assertEquals(0f, f.alpha(1, 0), 0f)
        f.update(listOf(1), listOf(1), 1000)
        assertEquals(1f, f.alpha(1, 1000), 0f)
        assertEquals(1f, f.alpha(1, 2800), 0f)
        assertEquals(0.5f, f.alpha(1, 3150), 1e-3f)
        assertEquals(0f, f.alpha(1, 3500), 0f)
        f.update(emptyList(), emptyList(), 4000)
        assertEquals(0f, f.alpha(1, 4000), 0f)
    }

    @Test
    fun holdFramesFadeIn06SOut03S() {
        val h = HoldFrameFade()
        h.update(true, 0)
        assertEquals(0.25f, h.update(true, 150), 1e-3f)
        assertEquals(0.5f, h.update(true, 300), 1e-3f)
        assertEquals(0.75f, h.update(true, 450), 1e-3f)
        assertEquals(1f, h.update(true, 600), 1e-3f)
        assertEquals(0.5f, h.update(false, 750), 1e-3f)
        assertEquals(0f, h.update(false, 900), 1e-3f)
        assertEquals(com.hayeco.flipspaces.core.Look.HELD, HoldFrameFade.COLOR)
    }

    @Test
    fun reticleHoverTweens015SPressScales07() {
        val r = ReticleAnim()
        assertEquals(1f, r.at(false, false, 0).dotScale, 0f)
        val half = r.at(true, false, 75)
        assertEquals(0.5f, half.ringAlpha, 1e-3f)
        assertEquals(0.8f, half.ringScale, 1e-3f)
        assertEquals(1f, r.at(true, false, 150).ringScale, 1e-3f)
        assertEquals(0.7f, r.at(true, true, 160).dotScale, 0f)
        assertEquals(2.5f, ReticleAnim.DEFAULT_DISTANCE_M, 0f)
    }

    @Test
    fun followModes() {
        val f = FollowView()
        val turned = Quat.fromYawPitch(40f, 0f)
        assertEquals(turned, f.relative(turned))
        f.mode = FollowMode.SMOOTH_FOLLOW
        f.update(turned, 0)
        // Starts where the head is drawn now, no jump.
        assertEquals(40f, f.relative(turned)!!.yawPitchRoll().first, 1e-3f)
        var t = 0L
        repeat(300) { t += 16; f.update(turned, t) }
        // 0.5.38 (Mike: switched on, everything moved far right): held there, the view stays as it was at the switch.
        assertEquals(40f, f.relative(turned)!!.yawPitchRoll().first, 0.1f)
        // A turn of 30 deg more: the Space comes along, back to the view at the switch.
        val further = Quat.fromYawPitch(70f, 0f)
        repeat(300) { t += 16; f.update(further, t) }
        assertEquals(40f, f.relative(further)!!.yawPitchRoll().first, 0.1f)
        // Off and on again facing 70: again no jump.
        f.mode = FollowMode.BODY_ANCHOR
        f.update(further, t + 16)
        f.mode = FollowMode.SMOOTH_FOLLOW
        f.update(further, t + 32)
        assertEquals(70f, f.relative(further)!!.yawPitchRoll().first, 1e-3f)
        assertFalse(FollowMode.SPACE_LOCKED.available)
        assertTrue(FollowMode.BODY_ANCHOR.available)
        assertTrue(FollowMode.SMOOTH_FOLLOW.available)
    }

    @Test
    fun phoneAimFromTheRotationMatrix() {
        assertEquals(Pair(0f, 0f), PhoneAim.angles(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)))
        val east = PhoneAim.angles(floatArrayOf(0f, 1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f))!!
        assertEquals(90f, east.first, 1e-4f)
        val c = cos(Math.toRadians(30.0)).toFloat()
        val s = sin(Math.toRadians(30.0)).toFloat()
        val up = PhoneAim.angles(floatArrayOf(1f, 0f, 0f, 0f, c, -s, 0f, s, c))!!
        assertEquals(30f, up.second, 1e-3f)
        assertNull(PhoneAim.angles(FloatArray(4)))
    }

    @Test
    fun threeFingerSwipesOpenAndHide() {
        assertEquals(SwipeRoute.OPEN_LAUNCHER, ThreeFingerRoute.route(SwipeDirection.UP, false, false))
        assertEquals(SwipeRoute.CLOSE_CONTROLS, ThreeFingerRoute.route(SwipeDirection.UP, false, true))
        assertEquals(SwipeRoute.OPEN_CONTROLS, ThreeFingerRoute.route(SwipeDirection.DOWN, false, false))
        assertEquals(SwipeRoute.CLOSE_LAUNCHER, ThreeFingerRoute.route(SwipeDirection.DOWN, true, false))
        assertEquals(SwipeRoute.BACK, ThreeFingerRoute.route(SwipeDirection.LEFT, false, false))
    }

    @Test
    fun controlPanelButtons() {
        val c = SpaceControlPanel()
        assertTrue(c.tiles(FollowMode.BODY_ANCHOR).isEmpty())
        c.open(0f, 0f)
        val tiles = c.tiles(FollowMode.BODY_ANCHOR)
        // 0.5.21: seven buttons and the Glasses warp (beta) switch. 0.5.36: the switch is gone.
        assertEquals(7, tiles.size)
        fun rayAt(t: ControlTile) = Ray(Vec3.ZERO, Vec3.direction(t.yawDeg, t.pitchDeg))
        val smooth = tiles.first { it.kind == ControlKind.Follow(FollowMode.SMOOTH_FOLLOW) }
        assertEquals(ControlAction.SetFollow(FollowMode.SMOOTH_FOLLOW), c.press(rayAt(smooth), FollowMode.BODY_ANCHOR))
        assertTrue(c.isOpen)
        val locked = tiles.first { it.kind == ControlKind.Follow(FollowMode.SPACE_LOCKED) }
        assertFalse(locked.enabled)
        assertEquals(ControlAction.Disabled, c.press(rayAt(locked), FollowMode.BODY_ANCHOR))
        val arrange = tiles.first { it.kind == ControlKind.Layout(ArrangeKind.COLLECT) }
        assertEquals(ControlAction.ArrangeAll(ArrangeKind.COLLECT), c.press(rayAt(arrange), FollowMode.BODY_ANCHOR))
        assertFalse(c.isOpen)
        c.open(0f, 0f)
        assertEquals(ControlAction.Closed, c.press(Ray(Vec3.ZERO, Vec3.direction(90f, 0f)), FollowMode.BODY_ANCHOR))
        assertFalse(c.isOpen)
    }

    @Test
    fun titleBarHasResetAndCloseAtItsRightEnd() {
        fun hitAt(x: Float, y: Float): PanelZone? {
            val p = panel.centre + panel.right * (x * panel.widthM) + panel.up * (y * panel.heightM)
            return PanelHitTester.hit(Ray(Vec3.ZERO, p.normalized()), panel)?.zone
        }
        // 0.5.20: the controls float above the window (no title bar): Close at the right end, Reset
        // left of it, Move in the middle, Back at the left end.
        val l = WindowControls.layout(panel)
        val barY = l.of(WindowControls.Control.MOVE).yCentre
        assertEquals(PanelZone.CLOSE, hitAt(l.of(WindowControls.Control.CLOSE).xCentre, barY))
        assertEquals(PanelZone.RESET, hitAt(l.of(WindowControls.Control.RESET).xCentre, barY))
        assertEquals(PanelZone.TITLE_BAR, hitAt(0f, barY))
        assertEquals(PanelZone.BACK, hitAt(l.of(WindowControls.Control.BACK).xCentre, barY))
        assertTrue(l.of(WindowControls.Control.RESET).xCentre < l.of(WindowControls.Control.CLOSE).xCentre)
    }
}
