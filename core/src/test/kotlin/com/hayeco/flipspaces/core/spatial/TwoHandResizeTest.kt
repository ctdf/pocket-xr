package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.hands.HandGestureRecognizer
import com.hayeco.flipspaces.core.hands.HandsRecognizer
import com.hayeco.flipspaces.core.hands.ReplayScenarios
import com.hayeco.flipspaces.core.hands.SyntheticHands
import com.hayeco.flipspaces.core.pointer.HandSide
import com.hayeco.flipspaces.core.pointer.Point
import com.hayeco.flipspaces.core.pointer.PointerSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.2: Mike's 0.5.1 two hand resize froze the Space and SurfaceFlinger's watchdog reset the
 * phone's graphics. The stretch must survive a hand
 * dropping out, both dropping out, sizes past either limit, NaN and degenerate rays and its panel
 * going away while held, and the panel's display must only be resized at a calm rate.
 */
class TwoHandResizeTest {
    private val frustum = ViewFrustum(1920, 1080, 70f)
    private val panel = SpacePanel(1, "Amazon", "com.amazon", "Main", yawDeg = 0f, pitchDeg = 0f)
    private val layout = SpaceLayout(listOf(panel))
    private val pixels = { _: Int -> Pair(1280, 720) }

    private fun dirTo(p: SpacePanel, u: Float, v: Float): Vec3 =
        (p.centre + p.right * ((u - 0.5f) * p.widthM) + p.up * ((0.5f - v) * p.heightM)).normalized()

    private fun hand(side: HandSide, s: PointerSignal, dir: Vec3) = PointerSignal.ForHand(side, s, Vec3.ZERO, dir)

    private fun SpatialPointer.move(side: HandSide, dir: Vec3, l: SpaceLayout = layout) =
        handle(hand(side, PointerSignal.MoveTo(0f, 0f), dir), Quat.IDENTITY, l, pixels)

    private fun SpatialPointer.down(side: HandSide, dir: Vec3, l: SpaceLayout = layout) =
        handle(hand(side, PointerSignal.Down(Point(0f, 0f), pinch = true), dir), Quat.IDENTITY, l, pixels)

    private fun SpatialPointer.up(side: HandSide, dir: Vec3, l: SpaceLayout = layout) =
        handle(hand(side, PointerSignal.Up(Point(0f, 0f)), dir), Quat.IDENTITY, l, pixels)

    /** Both hands pinched on [panel] at u 0.3 and 0.7: a stretch. */
    private fun stretching(): SpatialPointer {
        val p = SpatialPointer(frustum)
        p.down(HandSide.LEFT, dirTo(panel, 0.3f, 0.5f))
        p.down(HandSide.RIGHT, dirTo(panel, 0.7f, 0.5f))
        assertEquals(PanelZone.CORNER, p.grabOn(1))
        return p
    }

    private fun assertSane(l: SpaceLayout) = l.panels.forEach { p ->
        assertTrue("width ${p.widthDeg}", p.widthDeg.isFinite() && p.widthDeg in PanelLimits.MIN_SIZE_DEG..PanelLimits.MAX_WIDTH_DEG)
        assertTrue("height ${p.heightDeg}", p.heightDeg.isFinite() && p.heightDeg in PanelLimits.MIN_SIZE_DEG..PanelLimits.MAX_HEIGHT_DEG)
        val (w, h) = p.pixelSize()
        assertTrue("$w x $h", w * h <= PanelLimits.MAX_AREA_PX && w >= PanelLimits.MIN_PX && h >= PanelLimits.MIN_PX)
    }

    @Test
    fun aHandDroppingMidStretchCommitsOnceAndFreesBothHands() {
        val p = stretching()
        val grown = p.move(HandSide.RIGHT, Quat.fromYawPitch(10f, 0f).rotate(dirTo(panel, 0.7f, 0.5f)))
            .filterIsInstance<SpaceAction.Layout>().last().layout
        val dropped = p.dropHand(HandSide.LEFT, Quat.IDENTITY, grown)
        val commits = dropped.filterIsInstance<SpaceAction.Layout>()
        assertEquals(1, commits.size)
        assertTrue(commits.single().commit)
        assertSane(commits.single().layout)
        assertFalse(p.busy(HandSide.RIGHT))
        assertNull(p.grabOn(1))
        // The hand still up moving on and then letting go changes and commits nothing more.
        val after = p.move(HandSide.RIGHT, Quat.fromYawPitch(20f, 0f).rotate(dirTo(panel, 0.7f, 0.5f)), grown) +
            p.up(HandSide.RIGHT, dirTo(panel, 0.7f, 0.5f), grown) + p.frame(Quat.IDENTITY, grown)
        assertTrue(after.none { it is SpaceAction.Layout })
    }

    @Test
    fun bothHandsLostEndsTheStretchWithOneCommit() {
        val p = stretching()
        val first = p.dropHand(HandSide.RIGHT, Quat.IDENTITY, layout)
        val second = p.dropHand(HandSide.LEFT, Quat.IDENTITY, layout)
        assertEquals(1, (first + second).count { it is SpaceAction.Layout && it.commit })
        assertTrue(p.activeHands.isEmpty())
    }

    @Test
    fun pullingPastTheLargestSizeStopsAtTheLimits() {
        val p = stretching()
        // Both hands swing 30 degrees out (to the edges of the view): far past what the panel may grow to.
        p.move(HandSide.LEFT, Quat.fromYawPitch(-30f, 0f).rotate(dirTo(panel, 0.3f, 0.5f)))
        val out = p.move(HandSide.RIGHT, Quat.fromYawPitch(30f, 0f).rotate(dirTo(panel, 0.7f, 0.5f)))
        val l = out.filterIsInstance<SpaceAction.Layout>().last().layout
        assertSane(l)
        assertEquals(PanelLimits.MAX_HEIGHT_DEG, l.panel(1)!!.heightDeg, 0.01f)
    }

    @Test
    fun squeezingBelowTheSmallestSizeStopsAtTheMinimum() {
        val p = stretching()
        // Both hands onto one ray: the angle apart goes to zero.
        val out = p.move(HandSide.RIGHT, dirTo(panel, 0.3f, 0.5f))
        val l = out.filterIsInstance<SpaceAction.Layout>().last().layout
        assertSane(l)
        assertEquals(PanelLimits.MIN_SIZE_DEG, l.panel(1)!!.heightDeg, 0.01f)
    }

    @Test
    fun aNanOrZeroRayKeepsTheLastSize() {
        val p = stretching()
        val good = p.move(HandSide.RIGHT, Quat.fromYawPitch(6f, 0f).rotate(dirTo(panel, 0.7f, 0.5f)))
            .filterIsInstance<SpaceAction.Layout>().last().layout
        for (bad in listOf(Vec3(Float.NaN, 0f, -1f), Vec3.ZERO, Vec3(Float.POSITIVE_INFINITY, 0f, 0f))) {
            val out = p.move(HandSide.RIGHT, bad, good) + p.frame(Quat.IDENTITY, good)
            out.filterIsInstance<SpaceAction.Layout>().forEach { assertSane(it.layout) }
        }
        val end = p.up(HandSide.LEFT, dirTo(panel, 0.3f, 0.5f), good)
        end.filterIsInstance<SpaceAction.Layout>().forEach { assertSane(it.layout) }
    }

    @Test
    fun theStretchMathRefusesUnusableRays() {
        assertNull(StretchMath.apartDeg(Vec3.ZERO, Vec3.FORWARD))
        assertNull(StretchMath.apartDeg(Vec3(Float.NaN, 0f, 0f), Vec3.FORWARD))
        assertEquals(StretchMath.MIN_START_DEG, StretchMath.startDeg(Vec3.FORWARD, Vec3.FORWARD)!!, 0f)
        assertEquals(StretchMath.MAX_FACTOR, StretchMath.factor(Vec3.FORWARD, Vec3.RIGHT, 2f)!!, 0f)
        assertEquals(StretchMath.MIN_FACTOR, StretchMath.factor(Vec3.FORWARD, Vec3.FORWARD, 40f)!!, 0f)
        assertNull(StretchMath.factor(Vec3.FORWARD, Vec3.RIGHT, Float.NaN))
        // A NaN factor never reaches the layout.
        assertEquals(panel, SpaceLayout.scale(panel, Float.NaN))
        assertEquals(panel, SpaceLayout.scale(panel, Float.POSITIVE_INFINITY))
    }

    @Test
    fun thePanelClosedWhileHeldEndsTheStretchQuietly() {
        val p = stretching()
        val empty = SpaceLayout(emptyList())
        val out = p.move(HandSide.RIGHT, Quat.fromYawPitch(10f, 0f).rotate(dirTo(panel, 0.7f, 0.5f)), empty) + p.frame(Quat.IDENTITY, empty)
        // Nothing scales a panel that is gone, and nothing commits it back.
        assertTrue(out.none { it is SpaceAction.Layout })
        assertNull(p.grabOn(1))
        assertTrue(p.up(HandSide.LEFT, dirTo(panel, 0.3f, 0.5f), empty).none { it is SpaceAction.Layout })
    }

    @Test
    fun theReplayOfAStretchWithDropoutsNeverLeavesTheLimits() {
        // The whole path: two hands through the recognizer, then the Space's pointer, with the
        // renderer's rule that a hand not seen lets go (dropHand).
        val hands = SyntheticHands(1920, 1080)
        val r = HandsRecognizer(HandGestureRecognizer(1920, 1080), second = HandGestureRecognizer(1920, 1080))
        r.legacyStretch = false
        val p = SpatialPointer(frustum)
        val gate = PanelResizeGate()
        gate.opened(1, 1280, 720)
        var l = SpaceLayout(listOf(panel.copy(widthDeg = 60f, heightDeg = 34f)))
        var commits = 0
        var resizes = 0
        var stretched = false
        for (f in ReplayScenarios.build("stretch-dropouts", emptyMap(), hands)) {
            val now = 10_000L + f.tMs
            val u = r.update(f.hands, now, 37f)
            val actions = u.signals.flatMap { p.handle(it, Quat.IDENTITY, l, pixels) }.toMutableList()
            for (side in p.activeHands) if (u.hands[side]?.present != true) actions += p.dropHand(side, Quat.IDENTITY, l)
            actions += p.frame(Quat.IDENTITY, l, now)
            for (a in actions.filterIsInstance<SpaceAction.Layout>()) {
                assertSane(a.layout)
                l = a.layout
                if (a.commit) {
                    commits++
                    val (w, h) = l.panel(1)!!.pixelSize()
                    gate.request(1, w, h)
                }
            }
            if (p.grabOn(1) == PanelZone.CORNER && p.activeHands.size == 2) stretched = true
            resizes += gate.due(now).size
        }
        resizes += gate.due(1_000_000L).size
        assertTrue("the replay never stretched", stretched)
        assertTrue("commits $commits", commits in 1..4)
        assertTrue("resizes $resizes", resizes in 1..commits)
        assertTrue(p.activeHands.none { p.busy(it) })
        assertSane(l)
    }

    @Test
    fun theResizeGateCoalescesQuickCommits() {
        val gate = PanelResizeGate(minGapMs = 400)
        gate.opened(7, 1280, 720)
        assertTrue(gate.due(0).isEmpty())
        gate.request(7, 1400, 800)
        assertEquals(listOf(7 to (1400 to 800)), gate.due(1_000))
        // Three commits within 300 ms: one more resize, to the last size, once the gap has passed.
        gate.request(7, 1500, 850)
        gate.request(7, 1600, 900)
        gate.request(7, 1650, 930)
        assertTrue(gate.due(1_200).isEmpty())
        assertEquals(listOf(7 to (1650 to 930)), gate.due(1_400))
        // The same size again sends nothing; a panel released mid way sends nothing more.
        gate.request(7, 1650, 930)
        assertTrue(gate.due(5_000).isEmpty())
        gate.request(7, 1000, 600)
        gate.forget(7)
        assertTrue(gate.due(10_000).isEmpty())
        assertFalse(gate.pending(7))
    }
}
