package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.pointer.HandSide
import com.hayeco.flipspaces.core.pointer.Point
import com.hayeco.flipspaces.core.pointer.PointerSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 0.5.12: the click freeze. Pinch taps replayed through [SpatialPointer], with the
 * hand ray's wobble as the fingers close (a ramp of 0.3 to 2.5 degrees over the first part of the
 * tap, plus frame to frame tracking noise), taps of 80 to 260 ms. Synthetic traces: the repo holds
 * no recorded per frame hand rays from 0.5.11, so the wobble is modelled; the device check in
 * DESIGN 27 confirms it on real pinches.
 */
class ClickFreezeTest {
    private val frustum = ViewFrustum(1920, 1080, 70f)
    private val panel = SpacePanel(1, "Chrome", "b", "B", yawDeg = 0f, pitchDeg = 0f)
    private val layout = SpaceLayout(listOf(panel))
    private val pixels = { _: Int -> Pair(1760, 990) }

    private fun dir(yawDeg: Double, pitchDeg: Double) = Vec3.direction(yawDeg.toFloat(), pitchDeg.toFloat())
    private fun hand(s: PointerSignal, d: Vec3) = PointerSignal.ForHand(HandSide.RIGHT, s, Vec3.ZERO, d)

    private class Gesture(val travelDeg: Double, val scrolled: Boolean, val downAtPress: Boolean)

    /** Presses at the panel centre, moves the ray along [path] (yaw, pitch degrees, one per 16 ms), lets go. */
    private fun replay(path: List<Pair<Double, Double>>, settings: PointerSettings): Gesture {
        val p = SpatialPointer(frustum, settings)
        var t = 10_000L
        p.frame(Quat.IDENTITY, layout, t)
        p.handle(hand(PointerSignal.MoveTo(0f, 0f), dir(0.0, 0.0)), Quat.IDENTITY, layout, pixels)
        val down = p.handle(hand(PointerSignal.Down(Point(0f, 0f), pinch = true), dir(0.0, 0.0)), Quat.IDENTITY, layout, pixels)
        val out = mutableListOf<SpaceAction>()
        out += down
        for ((y, pi) in path) {
            t += 16
            out += p.frame(Quat.IDENTITY, layout, t)
            out += p.handle(hand(PointerSignal.MoveTo(0f, 0f), dir(y, pi)), Quat.IDENTITY, layout, pixels)
        }
        val last = path.lastOrNull() ?: (0.0 to 0.0)
        out += p.handle(hand(PointerSignal.Up(Point(0f, 0f)), dir(last.first, last.second)), Quat.IDENTITY, layout, pixels)
        val touches = out.filterIsInstance<SpaceAction.Touch>()
        val first = touches.first()
        val end = touches.last()
        val du = (end.u - first.u) * panel.widthDeg
        val dv = (end.v - first.v) * panel.heightDeg
        return Gesture(
            sqrt((du * du + dv * dv).toDouble()),
            out.filterIsInstance<SpaceAction.Note>().any { "hand scroll" in it.line },
            down.filterIsInstance<SpaceAction.Touch>().any { it.phase == TouchPhase.DOWN },
        )
    }

    /** A pinch tap's ray: the closing fingers pull it [driftDeg] along [angle], plus noise. */
    private fun tap(rnd: java.util.Random): List<Pair<Double, Double>> {
        val durMs = 80 + rnd.nextInt(181)
        val drift = (0.3 + abs(rnd.nextGaussian()) * 0.7).coerceAtMost(2.5)
        val angle = rnd.nextDouble() * 2 * PI
        val rampMs = minOf(durMs * 0.6, 120.0)
        return (1..durMs / 16).map { i ->
            val k = (i * 16 / rampMs).coerceAtMost(1.0)
            val s = k * k * (3 - 2 * k)
            (drift * s * cos(angle) + rnd.nextGaussian() * 0.04) to (drift * s * sin(angle) + rnd.nextGaussian() * 0.04)
        }
    }

    private fun taps(settings: PointerSettings, n: Int = 400): List<Gesture> {
        val rnd = java.util.Random(12)
        return (0 until n).map { replay(tap(rnd), settings) }
    }

    private fun p95(xs: List<Double>) = xs.sorted()[(xs.size * 0.95).toInt() - 1]

    @Test
    fun tapsStayPutAndAreNeverDrags() {
        val before = taps(PointerSettings(clickFreezeMs = 0))
        val after = taps(PointerSettings())
        val falseBefore = before.count { it.scrolled } * 100.0 / before.size
        val falseAfter = after.count { it.scrolled } * 100.0 / after.size
        println(
            "click freeze, 400 pinch taps: travel p95 %.2f deg and %.1f %% false drags without it; p95 %.2f deg and %.1f %% with it"
                .format(java.util.Locale.ROOT, p95(before.map { it.travelDeg }), falseBefore, p95(after.map { it.travelDeg }), falseAfter),
        )
        assertTrue("p95 ${p95(after.map { it.travelDeg })}", p95(after.map { it.travelDeg }) <= 0.3)
        assertTrue("false drags $falseAfter %", falseAfter <= 2.0)
        // The press goes down at once, as before: no added tap to click latency.
        assertTrue(after.all { it.downAtPress })
    }

    @Test
    fun dragsAndScrollsStillFollowTheHand() {
        val rnd = java.util.Random(13)
        var worst = 1.0
        repeat(100) {
            // 5 to 15 degrees in 200 to 500 ms: a scroll.
            val len = 5 + rnd.nextDouble() * 10
            val frames = (200 + rnd.nextInt(301)) / 16
            val path = (1..frames).map { i -> 0.0 to -len * i / frames }
            val g = replay(path, PointerSettings())
            assertTrue("scroll of $len deg", g.scrolled)
            worst = minOf(worst, g.travelDeg / len)
        }
        println("click freeze, 100 scrolls: the touch travels at least %.0f %% of the hand's path".format(java.util.Locale.ROOT, worst * 100))
        assertTrue("worst share $worst", worst >= 0.9)
    }

    @Test
    fun aMoveOverThreeDegreesEndsTheHoldAtOnce() {
        val p = SpatialPointer(frustum)
        var t = 10_000L
        p.frame(Quat.IDENTITY, layout, t)
        p.handle(hand(PointerSignal.MoveTo(0f, 0f), dir(0.0, 0.0)), Quat.IDENTITY, layout, pixels)
        p.handle(hand(PointerSignal.Down(Point(0f, 0f), pinch = true), dir(0.0, 0.0)), Quat.IDENTITY, layout, pixels)
        t += 16
        p.frame(Quat.IDENTITY, layout, t)
        val small = p.handle(hand(PointerSignal.MoveTo(0f, 0f), dir(0.0, -2.0)), Quat.IDENTITY, layout, pixels)
        assertTrue("held", small.filterIsInstance<SpaceAction.Touch>().isEmpty())
        t += 16
        p.frame(Quat.IDENTITY, layout, t)
        val big = p.handle(hand(PointerSignal.MoveTo(0f, 0f), dir(0.0, -4.0)), Quat.IDENTITY, layout, pixels)
        val move = big.filterIsInstance<SpaceAction.Touch>().single()
        // The touch goes where the ray is: no absorbed offset after a real move.
        assertEquals(0.5 + 4.0 / panel.heightDeg, move.v.toDouble(), 0.02)
    }
}
