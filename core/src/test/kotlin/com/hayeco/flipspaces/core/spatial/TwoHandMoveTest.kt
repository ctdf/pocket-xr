package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.pointer.HandSide
import com.hayeco.flipspaces.core.pointer.Point
import com.hayeco.flipspaces.core.pointer.PointerSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.9: "move the window while two-hand pinching" (Mike, 0.5.8). During a two hand stretch the
 * panel follows the midpoint of the two hands (yaw and pitch around the user, always facing them)
 * while it resizes live, as on Quest and Android XR.
 */
class TwoHandMoveTest {
    private val frustum = ViewFrustum(1920, 1080, 70f)
    private val panel = SpacePanel(1, "Chat app", "com.example.chat", "Main", yawDeg = 0f, pitchDeg = 0f)
    private val layout = SpaceLayout(listOf(panel))
    private val pixels = { _: Int -> Pair(1280, 720) }

    private fun dirTo(p: SpacePanel, u: Float, v: Float): Vec3 =
        (p.centre + p.right * ((u - 0.5f) * p.widthM) + p.up * ((0.5f - v) * p.heightM)).normalized()

    private fun hand(side: HandSide, s: PointerSignal, dir: Vec3) = PointerSignal.ForHand(side, s, Vec3.ZERO, dir)

    private fun turned(dir: Vec3, yaw: Float, pitch: Float = 0f): Vec3 {
        val a = Angles.of(dir)
        return Vec3.direction(a.yawDeg + yaw, a.pitchDeg + pitch)
    }

    /** Both hands pinched on the panel at u 0.3 and 0.7, then [steps] frames of both hands moving. */
    private fun stretchAndMove(
        settings: PointerSettings = PointerSettings(),
        steps: Int = 20,
        left: (Int) -> Vec3,
        right: (Int) -> Vec3,
    ): Pair<SpaceLayout, List<SpaceAction.Layout>> {
        val p = SpatialPointer(frustum, settings)
        var l = layout
        val all = mutableListOf<SpaceAction.Layout>()
        fun apply(out: List<SpaceAction>) {
            out.filterIsInstance<SpaceAction.Layout>().forEach { l = it.layout; all += it }
        }
        apply(p.handle(hand(HandSide.LEFT, PointerSignal.Down(Point(0f, 0f), pinch = true), left(0)), Quat.IDENTITY, l, pixels))
        apply(p.handle(hand(HandSide.RIGHT, PointerSignal.Down(Point(0f, 0f), pinch = true), right(0)), Quat.IDENTITY, l, pixels))
        assertEquals(PanelZone.CORNER, p.grabOn(1))
        for (i in 1..steps) {
            apply(p.handle(hand(HandSide.LEFT, PointerSignal.MoveTo(0f, 0f), left(i)), Quat.IDENTITY, l, pixels))
            apply(p.handle(hand(HandSide.RIGHT, PointerSignal.MoveTo(0f, 0f), right(i)), Quat.IDENTITY, l, pixels))
            apply(p.frame(Quat.IDENTITY, l, i * 16L))
        }
        apply(p.handle(hand(HandSide.RIGHT, PointerSignal.Up(Point(0f, 0f)), right(steps)), Quat.IDENTITY, l, pixels))
        return l to all
    }

    @Test
    fun bothHandsMovingTogetherCarryThePanelAtItsSize() {
        val l0 = dirTo(panel, 0.3f, 0.5f)
        val r0 = dirTo(panel, 0.7f, 0.5f)
        // Both hands sweep 20 degrees right and 8 up over 20 frames, the same distance apart.
        val (end, layouts) = stretchAndMove(left = { turned(l0, it * 1f, it * 0.4f) }, right = { turned(r0, it * 1f, it * 0.4f) })
        val moved = end.panel(1)!!
        assertEquals(20f, moved.yawDeg, 0.5f)
        assertEquals(8f, moved.pitchDeg, 0.5f)
        // Raised 8 degrees, the same yaw apart is a hair less angle apart (cos 8 degrees): within 1%.
        assertEquals(panel.widthDeg, moved.widthDeg, 0.6f)
        assertEquals(panel.heightDeg, moved.heightDeg, 0.4f)
        // It kept facing the user and kept its distance.
        assertEquals(-1f, moved.normal dot moved.centreDir, 1e-4f)
        assertEquals(panel.distanceM, moved.distanceM, 1e-6f)
        // Live: the layout followed while held, and the release committed once where it ended.
        assertTrue("${layouts.count { !it.commit }} live layouts", layouts.count { !it.commit } >= 15)
        assertEquals(1, layouts.count { it.commit })
        assertEquals(moved, layouts.last { it.commit }.layout.panel(1))
    }

    @Test
    fun pullingApartWhileMovingResizesAndMovesAtOnce() {
        val l0 = dirTo(panel, 0.3f, 0.5f)
        val r0 = dirTo(panel, 0.7f, 0.5f)
        // The left hand goes 2 degrees left, the right 8 right: the midpoint moves 3 right, the hands spread 10.
        val (end, _) = stretchAndMove(left = { turned(l0, -it * 0.1f) }, right = { turned(r0, it * 0.4f) })
        val p = end.panel(1)!!
        assertEquals(3f, p.yawDeg, 0.5f)
        val startApart = StretchMath.apartDeg(l0, r0)!!
        val endApart = StretchMath.apartDeg(turned(l0, -2f), turned(r0, 8f))!!
        assertEquals(panel.widthDeg * endApart / startApart, p.widthDeg, 0.5f)
    }

    @Test
    fun theOldStretchStaysPutWhenTheMoveIsOff() {
        val l0 = dirTo(panel, 0.3f, 0.5f)
        val r0 = dirTo(panel, 0.7f, 0.5f)
        val (end, _) = stretchAndMove(PointerSettings(twoHandMove = false), left = { turned(l0, it * 1f) }, right = { turned(r0, it * 1f) })
        assertEquals(0f, end.panel(1)!!.yawDeg, 1e-3f)
    }

    @Test
    fun aHandThatLosesItsRayKeepsThePanelWhereItIs() {
        val l0 = dirTo(panel, 0.3f, 0.5f)
        val r0 = dirTo(panel, 0.7f, 0.5f)
        val nan = Vec3(Float.NaN, Float.NaN, Float.NaN)
        val (end, _) = stretchAndMove(steps = 10, left = { if (it in 4..6) nan else turned(l0, it * 1f) }, right = { turned(r0, it * 1f) })
        val p = end.panel(1)!!
        assertTrue(p.yawDeg.isFinite() && p.pitchDeg.isFinite() && p.widthDeg.isFinite())
        assertEquals(10f, p.yawDeg, 0.6f)
    }

    @Test
    fun theMidpointIsBetweenTheRaysAndRefusesBadOnes() {
        val m = StretchMath.midpoint(Vec3.direction(-10f, 0f), Vec3.direction(10f, 6f))!!
        assertEquals(0f, Angles.of(m).yawDeg, 0.05f)
        assertEquals(3f, Angles.of(m).pitchDeg, 0.1f)
        assertNull(StretchMath.midpoint(Vec3.ZERO, Vec3.FORWARD))
        assertNull(StretchMath.midpoint(Vec3(Float.NaN, 0f, 0f), Vec3.FORWARD))
        // Rays pointing apart have no midpoint.
        assertNull(StretchMath.midpoint(Vec3.FORWARD, Vec3.FORWARD * -1f))
        assertNotNull(StretchMath.midpoint(Vec3.FORWARD * 3f, Vec3.direction(1f, 0f)))
    }
}
