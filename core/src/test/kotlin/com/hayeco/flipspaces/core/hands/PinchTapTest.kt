package com.hayeco.flipspaces.core.hands

import com.hayeco.flipspaces.core.pointer.Point
import com.hayeco.flipspaces.core.pointer.PointerSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * 0.5.5: the tap rule and the aim lock before every press, on Mike's 0.5.4 log:
 * "Pinch down at 1221,714 (locked 34 ms before) ... Pinch up at 1330,718 after 131 ms (drag),
 * hand drift 26 px", and "Pinch down ... (locked 0 ms before), drift 0 px" followed by "Pinch up
 * at 68,187 after 267 ms (drag) ... drift 1128 px".
 */
class PinchTapTest {
    private val size = 0.2f
    private val s = PinchLockSettings()

    private fun handAt(p: Point, gap: Float, t: Long): HandFrame {
        // TestHands puts the steady point 0.04 hand sizes right of and 0.7 above the wrist.
        val cx = p.x / 1919f - 0.04f * size
        val cy = p.y / 1079f + 0.7f * size
        return TestHands.hand(cx, cy, size, extended = listOf(true, true, true, true), thumbGap = gap, timestampMs = t)
    }

    private class Replay(val r: HandGestureRecognizer = HandGestureRecognizer(1920, 1080, AirBox(0f, 0f, 1f, 1f))) {
        val signals = mutableListOf<PointerSignal>()
        val traces = mutableListOf<Pair<Int, PinchTrace>>()
        var t = 0L
        var frames = 0

        fun frame(hand: HandFrame) {
            val u = r.update(hand, t, 37f)
            signals += u.signals
            u.traces.forEach { traces += frames to it }
            t += 33
            frames++
        }

        inline fun <reified T : PinchTrace> all(): List<Pair<Int, T>> = traces.filter { it.second is T }.map { it.first to it.second as T }
    }

    private val rnd = Random(7)
    private fun j() = rnd.nextFloat() * 4f - 2f

    /** Aims at [target], closes, holds with the hand at [held] offsets, opens at [releaseAt]. */
    private fun Replay.pinch(target: Point, held: List<Point>, releaseAt: Point) {
        repeat(30) { frame(handAt(Point(target.x + j(), target.y + j()), 0.8f, t)) }
        for (gap in listOf(0.62f, 0.44f, 0.24f)) frame(handAt(Point(target.x + j(), target.y + j()), gap, t))
        for (o in held) frame(handAt(target + o, 0.12f, t))
        frame(handAt(target + releaseAt, 0.69f, t))
        repeat(5) { frame(handAt(target + releaseAt, 0.8f, t)) }
    }

    private fun downUp(signals: List<PointerSignal>): Triple<PointerSignal.Down, PointerSignal.Up, List<PointerSignal>> {
        val d = signals.indexOfFirst { it is PointerSignal.Down }
        val u = signals.indexOfFirst { it is PointerSignal.Up }
        assertTrue("no press in $signals", d >= 0 && u > d)
        return Triple(signals[d] as PointerSignal.Down, signals[u] as PointerSignal.Up, signals.subList(d + 1, u))
    }

    @Test
    fun theLoggedFigures() {
        // 131 ms with 26 px of drift: a tap.
        assertTrue(PinchTap.isTap(131, 26f, 0.10f, s))
        // 267 ms with 1128 px: not a tap.
        assertFalse(PinchTap.isTap(267, 1128f, 0.10f, s))
        // Long enough to be a hold, even without drift.
        assertFalse(PinchTap.isTap(250, 0f, 0.10f, s))
        // 45 px is too far for a hand at the reference size, but fine for a closer, bigger one.
        assertFalse(PinchTap.isTap(131, 45f, 0.10f, s))
        assertTrue(PinchTap.isTap(131, 45f, 0.15f, s))
    }

    @Test
    fun theDriftLimitScalesWithTheHandButStaysBetween40And80Px() {
        assertEquals(40f, PinchTap.driftLimitPx(0.05f, s), 0.01f)
        assertEquals(40f, PinchTap.driftLimitPx(0.10f, s), 0.01f)
        assertEquals(60f, PinchTap.driftLimitPx(0.15f, s), 0.01f)
        assertEquals(80f, PinchTap.driftLimitPx(0.50f, s), 0.01f)
    }

    @Test
    fun noDragStartsWithinTheTapTime() {
        assertFalse(PinchTap.dragAllowed(131, s))
        assertFalse(PinchTap.dragAllowed(249, s))
        assertTrue(PinchTap.dragAllowed(250, s))
    }

    @Test
    fun aShortPinchThatSwingsAndComesBackIsATap() {
        // Mike's pinch: the hand swings out mid-pinch (enough for 0.5.4's sweep rule) and is
        // back within 26 px when the fingers open, 131 ms after the press.
        val replay = Replay()
        replay.pinch(
            target = Point(1221f, 714f),
            held = listOf(Point(60f, 2f), Point(150f, 4f), Point(70f, 4f), Point(26f, 4f)),
            releaseAt = Point(26f, 4f),
        )
        val (down, up, between) = downUp(replay.signals)
        assertEquals(down.at, up.at)
        assertTrue("cursor moved while pinched: $between", between.none { it is PointerSignal.MoveTo })
        assertTrue(replay.all<PinchTrace.DragUnlock>().isEmpty())
        val release = replay.all<PinchTrace.Release>().single().second
        assertFalse(release.line(), release.dragged)
        assertTrue(release.line(), release.heldMs < s.tapMaxMs)
    }

    @Test
    fun aHeldPinchThatMovesStillDrags() {
        val replay = Replay()
        val path = (1..14).map { Point(it * 20f, 0f) }
        replay.pinch(Point(900f, 500f), path, path.last())
        val (down, up, _) = downUp(replay.signals)
        assertTrue(replay.all<PinchTrace.DragUnlock>().isNotEmpty())
        assertTrue(replay.all<PinchTrace.Release>().single().second.dragged)
        assertTrue("up ${up.at} should be far right of down ${down.at}", up.at!!.x - down.at!!.x > 150f)
    }

    @Test
    fun aQuickFlickThatStaysAwayIsADrag() {
        // Under the tap time but the hand ends 250 px away: a flick, so a drag to where it went.
        val replay = Replay()
        replay.pinch(Point(900f, 500f), listOf(Point(80f, 0f), Point(170f, 0f), Point(250f, 0f), Point(250f, 0f)), Point(250f, 0f))
        val (down, up, between) = downUp(replay.signals)
        val release = replay.all<PinchTrace.Release>().single().second
        assertTrue(release.line(), release.dragged)
        assertTrue(release.line(), release.heldMs < s.tapMaxMs)
        assertTrue(between.any { it is PointerSignal.MoveTo })
        assertTrue("up ${up.at} should be right of down ${down.at}", up.at!!.x - down.at!!.x > 150f)
    }

    @Test
    fun aHandFoundAlreadyPinchedLocksBeforeItPresses() {
        // No history at all: the hand shows up with the fingers together. 0.5.4 pressed on the
        // first frame, "locked 0 ms before", on wherever that first frame put the aim.
        // 0.5.11: a hand that comes into view already closed (a fist, fingers resting together)
        // never presses: a press needs a real closing ([PressGate]). It opens, then pinches.
        val replay = Replay()
        val at = Point(700f, 400f)
        repeat(8) { replay.frame(handAt(Point(at.x + j(), at.y + j()), 0.05f, replay.t)) }
        assertTrue("pressed with no closing: ${replay.signals}", replay.signals.none { it is PointerSignal.Down })
        repeat(5) { replay.frame(handAt(at, 0.8f, replay.t)) }
        repeat(4) { replay.frame(handAt(at, 0.05f, replay.t)) }
        repeat(5) { replay.frame(handAt(at, 0.8f, replay.t)) }
        val (down, _, _) = downUp(replay.signals)
        val press = replay.all<PinchTrace.Press>().single()
        val locked = replay.all<PinchTrace.Locked>().single()
        assertTrue("press on frame ${press.first}, lock on ${locked.first}", press.first > locked.first)
        assertTrue(press.second.line(), press.second.lockedMs >= 33)
        // Not before the hand had been seen for the select look back.
        assertTrue("locked on frame ${locked.first}", locked.first >= 8)
        assertEquals(at.x, down.at!!.x, 6f)
        assertEquals(at.y, down.at!!.y, 6f)
    }

    @Test
    fun everyPressInAQuickPinchFollowsItsLock() {
        // A pinch so fast the ratio goes from open to the fast press in one frame, with no
        // closing onset: still a lock first, then the press a frame later.
        val replay = Replay()
        val at = Point(1000f, 600f)
        repeat(30) { replay.frame(handAt(Point(at.x + j(), at.y + j()), 0.8f, replay.t)) }
        repeat(3) { replay.frame(handAt(at, 0.04f, replay.t)) }
        repeat(5) { replay.frame(handAt(at, 0.8f, replay.t)) }
        downUp(replay.signals)
        for ((_, p) in replay.all<PinchTrace.Press>()) assertTrue(p.line(), p.lockedMs >= 33)
    }
}
