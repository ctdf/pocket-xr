package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.pointer.HandSide
import com.hayeco.flipspaces.core.pointer.Point
import com.hayeco.flipspaces.core.pointer.PointerSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan

/**
 * 0.5.10: "when I use two hand pinch on a window for resizing or moving, I should also be able to
 * move it closer and further from me" (Mike). A synthetic pair of hands in front of the user: each
 * hand's ray points at it from the head, and its palm looks [PALM] / distance big on the camera's
 * image plane, with a few percent of noise (MediaPipe and the hand turning). Pure resize, pure move
 * and pure push or pull must each stay separate.
 */
class TwoHandDepthTest {
    private val frustum = ViewFrustum(1920, 1080, 70f)
    private val panel = SpacePanel(1, "Chat app", "com.example.chat", "Main", yawDeg = 0f, pitchDeg = 0f)
    private val pixels = { _: Int -> Pair(1280, 720) }

    /** Both hands at [distM] in front, [apartM] apart, their midpoint turned [yawDeg]. */
    private data class Hands(val distM: Float, val apartM: Float, val yawDeg: Float = 0f)

    private fun dir(h: Hands, side: Int): Vec3 {
        val a = atan(side * h.apartM / 2f / h.distM) * RAD
        return Vec3.direction(h.yawDeg + a, 0f)
    }

    private data class Run(val end: SpacePanel, val depthLayouts: Int, val commits: Int)

    private fun run(start: Hands, end: Hands, noise: Float = 0.03f, settings: PointerSettings = PointerSettings(), seed: Long = 4): Run {
        val rnd = java.util.Random(seed)
        val p = SpatialPointer(frustum, settings)
        var l = SpaceLayout(listOf(panel))
        var commits = 0
        var depthLayouts = 0
        fun apply(out: List<SpaceAction>) = out.filterIsInstance<SpaceAction.Layout>().forEach {
            if (it.layout.panel(1)!!.distanceM != l.panel(1)!!.distanceM) depthLayouts++
            l = it.layout
            if (it.commit) commits++
        }
        fun size(h: Hands) = PALM / h.distM * (1f + (rnd.nextGaussian() * noise).toFloat())
        fun signal(side: HandSide, s: PointerSignal, h: Hands) =
            PointerSignal.ForHand(side, s, Vec3.ZERO, dir(h, if (side == HandSide.LEFT) -1 else 1), size(h))
        apply(p.handle(signal(HandSide.LEFT, PointerSignal.Down(Point(0f, 0f), pinch = true), start), Quat.IDENTITY, l, pixels))
        apply(p.handle(signal(HandSide.RIGHT, PointerSignal.Down(Point(0f, 0f), pinch = true), start), Quat.IDENTITY, l, pixels))
        val steps = 90
        val hold = 90
        var h = start
        for (i in 1..steps + hold) {
            val t = (i.toFloat() / steps).coerceAtMost(1f)
            h = Hands(start.distM + (end.distM - start.distM) * t, start.apartM + (end.apartM - start.apartM) * t, start.yawDeg + (end.yawDeg - start.yawDeg) * t)
            apply(p.handle(signal(HandSide.LEFT, PointerSignal.MoveTo(0f, 0f), h), Quat.IDENTITY, l, pixels))
            apply(p.handle(signal(HandSide.RIGHT, PointerSignal.MoveTo(0f, 0f), h), Quat.IDENTITY, l, pixels))
            apply(p.frame(Quat.IDENTITY, l, i * 16L))
        }
        apply(p.handle(signal(HandSide.RIGHT, PointerSignal.Up(Point(0f, 0f)), h), Quat.IDENTITY, l, pixels))
        return Run(l.panel(1)!!, depthLayouts, commits)
    }

    private val rest = Hands(0.45f, 0.20f)

    @Test
    fun pushingBothHandsAwaySendsTheWindowBackAtItsSize() {
        val r = run(rest, Hands(0.60f, 0.20f))
        println("push both hands 0.45 to 0.60 m: window %.2f m -> %.2f m, %.1f by %.1f deg (%.2f m wide, was %.2f)".format(
            panel.distanceM, r.end.distanceM, r.end.widthDeg, r.end.heightDeg, r.end.widthM, panel.widthM,
        ))
        assertTrue("went back to ${r.end.distanceM}", r.end.distanceM > panel.distanceM * 1.4f)
        // 0.5.27: the same metres again, so it looks smaller (0.5.26 kept the angles); it stayed where it was.
        assertEquals(panel.widthM, r.end.widthM, panel.widthM * 0.06f)
        assertTrue(r.end.widthDeg < panel.widthDeg / 1.3f)
        assertEquals(0f, r.end.yawDeg, 0.3f)
        assertEquals(1, r.commits)
    }

    @Test
    fun pullingBothHandsInBringsItCloserWithinTheLimit() {
        val r = run(rest, Hands(0.36f, 0.20f))
        assertTrue("came to ${r.end.distanceM}", r.end.distanceM < panel.distanceM * 0.8f)
        // 0.5.27: its metres kept, so it looks bigger as it comes.
        assertEquals(panel.widthM, r.end.widthM, panel.widthM * 0.06f)
        assertTrue(r.end.widthDeg > panel.widthDeg)
        // A big pull stops at the nearest distance, 0.3 m since 0.5.26 (was 0.6).
        val far = run(rest, Hands(0.22f, 0.20f))
        assertEquals(PanelLimits.MIN_DISTANCE, far.end.distanceM, 1e-3f)
        // 0.30 to 0.70 m of hands sends it well past 0.5.25's 4 m; a bigger push stops at 15 m.
        val pushFar = run(Hands(0.30f, 0.20f), Hands(0.70f, 0.20f))
        assertTrue("pushed to ${pushFar.end.distanceM}", pushFar.end.distanceM > 8f)
        val pushFurther = run(Hands(0.30f, 0.20f), Hands(0.90f, 0.20f))
        assertEquals(PanelLimits.MAX_DISTANCE, pushFurther.end.distanceM, 1e-3f)
    }

    @Test
    fun pureResizeDoesNotChangeTheDistance() {
        val r = run(rest, Hands(0.45f, 0.32f), noise = 0.05f)
        assertEquals(panel.distanceM, r.end.distanceM, 1e-6f)
        assertEquals(0, r.depthLayouts)
        val startApart = StretchMath.apartDeg(dir(rest, -1), dir(rest, 1))!!
        val endApart = StretchMath.apartDeg(dir(Hands(0.45f, 0.32f), -1), dir(Hands(0.45f, 0.32f), 1))!!
        assertEquals(panel.widthDeg * endApart / startApart, r.end.widthDeg, 0.3f)
    }

    @Test
    fun pureMoveDoesNotChangeTheDistanceOrSize() {
        val r = run(rest, Hands(0.45f, 0.20f, yawDeg = 18f), noise = 0.05f)
        assertEquals(panel.distanceM, r.end.distanceM, 1e-6f)
        assertEquals(18f, r.end.yawDeg, 0.5f)
        assertEquals(panel.widthDeg, r.end.widthDeg, 0.1f)
    }

    @Test
    fun noiseAloneNeverPushes() {
        for (seed in 1L..5L) {
            val r = run(rest, rest, noise = 0.06f, seed = seed)
            assertEquals("seed $seed", panel.distanceM, r.end.distanceM, 1e-6f)
        }
    }

    @Test
    fun withoutSizesOrWithTheSettingOffNothingChanges() {
        val off = run(rest, Hands(0.60f, 0.20f), settings = PointerSettings(twoHandDepth = false))
        assertEquals(panel.distanceM, off.end.distanceM, 1e-6f)
        val d = TwoHandDepth()
        assertTrue(!d.start(null, 0.1f, 0))
        assertEquals(0f, d.update(0.1f, 0.1f, 16), 0f)
    }

    @Test
    fun aPanelAtAnotherDistanceKeepsItsSizeInMetres() {
        // 0.5.27: its metres again (0.5.26 kept the angles, so a push looked like nothing).
        // The 3 degree depth minimum (not the 12 degree resize one) lets it shrink all the way out to 15 m.
        val far = TwoHandDepth.atDistance(panel, 2.5f)
        assertEquals(2.5f, far.distanceM, 1e-6f)
        assertEquals(panel.widthM, far.widthM, 1e-4f)
        assertTrue(far.widthDeg < panel.widthDeg)
        assertEquals(panel, TwoHandDepth.atDistance(panel, Float.NaN))
        val further = TwoHandDepth.atDistance(panel, 8f)
        assertEquals(panel.heightM, further.heightM, 1e-4f)
        assertTrue(further.heightDeg > PanelLimits.DEPTH_MIN_SIZE_DEG && further.heightDeg < PanelLimits.MIN_SIZE_DEG)
        assertEquals(panel.widthM / panel.heightM, further.widthM / further.heightM, 0.01f)
        // Its display keeps its pixels.
        assertEquals(PanelDisplaySize.panelPx(panel), PanelDisplaySize.panelPx(further))
        // Past the limits: clamped to 0.3 and 15 m.
        assertEquals(15f, TwoHandDepth.atDistance(panel, 40f).distanceM, 1e-6f)
        assertEquals(0.3f, TwoHandDepth.atDistance(panel, 0.1f).distanceM, 1e-6f)
    }

    private companion object {
        /** A palm's longest bone (about 8.5 cm) at 1 m on the image plane. */
        const val PALM = 0.085f
    }
}
