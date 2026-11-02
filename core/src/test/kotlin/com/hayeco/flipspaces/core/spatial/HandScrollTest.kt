package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.pointer.HandSide
import com.hayeco.flipspaces.core.pointer.Point
import com.hayeco.flipspaces.core.pointer.PointerSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.11: Mike's "scrolling is hard". A hand pinches a panel's content and drags: the touch must
 * follow the hand 1:1 once past a small slop, never become a tap, and lift with the hand's speed
 * so the app flings. 0.5.10 aimed the lift at the recognizer's release point (where the cursor was
 * before the fingers started opening), which pulled the touch back and stopped the fling.
 */
class HandScrollTest {
    private val frustum = ViewFrustum(1920, 1080, 70f)
    private val panel = SpacePanel(1, "Chrome", "b", "B", yawDeg = 0f, pitchDeg = 0f)
    private val layout = SpaceLayout(listOf(panel))
    private val pixels = { _: Int -> Pair(1760, 990) }

    private fun dirTo(u: Float, v: Float): Vec3 =
        (panel.centre + panel.right * ((u - 0.5f) * panel.widthM) + panel.up * ((0.5f - v) * panel.heightM)).normalized()

    private fun hand(s: PointerSignal, dir: Vec3) = PointerSignal.ForHand(HandSide.RIGHT, s, Vec3.ZERO, dir)

    private class Run(val touches: List<SpaceAction.Touch>, val notes: List<String>)

    /**
     * The hand presses at v 0.5, moves its ray through [vs] (one per 16 ms frame), then lets go;
     * the recognizer's release point is [upV] (the cursor before the fingers opened).
     */
    private fun run(vs: List<Float>, upV: Float): Run {
        val p = SpatialPointer(frustum)
        val out = mutableListOf<SpaceAction>()
        var t = 10_000L
        out += p.frame(Quat.IDENTITY, layout, t)
        out += p.handle(hand(PointerSignal.MoveTo(0f, 0f), dirTo(0.5f, 0.5f)), Quat.IDENTITY, layout, pixels)
        out += p.handle(hand(PointerSignal.Down(Point(0f, 0f), pinch = true), dirTo(0.5f, 0.5f)), Quat.IDENTITY, layout, pixels)
        for (v in vs) {
            t += 16
            out += p.handle(hand(PointerSignal.MoveTo(0f, 0f), dirTo(0.5f, v)), Quat.IDENTITY, layout, pixels)
            out += p.frame(Quat.IDENTITY, layout, t)
        }
        t += 16
        out += p.frame(Quat.IDENTITY, layout, t)
        out += p.handle(hand(PointerSignal.Up(Point(0f, 0f)), dirTo(0.5f, upV)), Quat.IDENTITY, layout, pixels)
        // 0.5.45: a moving scroll's touch is thrown on for a few frames before it lifts.
        repeat(8) {
            t += 16
            out += p.frame(Quat.IDENTITY, layout, t)
        }
        return Run(out.filterIsInstance<SpaceAction.Touch>(), out.filterIsInstance<SpaceAction.Note>().map { it.line })
    }

    @Test
    fun aScrollFollowsTheHandAndLiftsAlongItsSpeed() {
        // Up the page at a steady 0.4 of the panel a second for 300 ms, released at the cursor of 60 ms before.
        val vs = (1..19).map { 0.5f - 0.4f * it * 0.016f }
        val r = run(vs, upV = vs[vs.size - 4])
        assertEquals(TouchPhase.DOWN, r.touches.first().phase)
        assertEquals(TouchPhase.UP, r.touches.last().phase)
        val moves = r.touches.filter { it.phase == TouchPhase.MOVE }
        // Never back: every move goes the way the hand went.
        moves.zipWithNext().forEach { (a, b) -> assertTrue("${a.v} then ${b.v}", b.v <= a.v + 1e-4f) }
        // 1:1 with the hand, then thrown on along its speed (0.5.45); the lift is where the last move is.
        assertTrue("last move ${moves.last().v} vs hand ${vs.last()}", moves.last().v < vs.last() - 0.001f)
        assertEquals(moves.last().v, r.touches.last().v, 1e-5f)
        assertTrue(r.notes.any { "follows the hand" in it })
        assertTrue(r.notes.joinToString(), r.notes.any { "flings" in it })
    }

    @Test
    fun aScrollThatStoppedLiftsWhereItIsWithoutAFling() {
        val vs = (1..12).map { 0.5f - 0.4f * it * 0.016f } + List(12) { 0.5f - 0.4f * 12 * 0.016f }
        val r = run(vs, upV = vs.last() + 0.02f)
        val moves = r.touches.filter { it.phase == TouchPhase.MOVE }
        assertEquals(vs.last(), r.touches.last().v, 0.002f)
        moves.zipWithNext().forEach { (a, b) -> assertTrue(b.v <= a.v + 1e-4f) }
        assertTrue(r.notes.joinToString(), r.notes.any { "no fling" in it })
    }

    @Test
    fun aTapIsNeverAScroll() {
        // The ray wobbles 0.2 degrees while the fingers pinch: a tap, lifted where the recognizer says.
        val vs = listOf(0.501f, 0.502f, 0.5005f)
        val r = run(vs, upV = 0.5f)
        assertTrue(r.notes.isEmpty())
        assertEquals(TouchPhase.UP, r.touches.last().phase)
        assertEquals(0.5f, r.touches.last().v, 0.001f)
    }

    @Test
    fun theHandsDeadZoneIsSmall() {
        assertEquals(2f, com.hayeco.flipspaces.core.hands.PinchLockSettings().dragStartDeg, 0f)
    }
}
