package com.hayeco.flipspaces.core.hands

import com.hayeco.flipspaces.core.Bounds
import com.hayeco.flipspaces.core.pointer.Point
import com.hayeco.flipspaces.core.pointer.PointerAction
import com.hayeco.flipspaces.core.pointer.PointerController
import com.hayeco.flipspaces.core.pointer.PointerSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Closing onset lock, frozen press, drag unlock and release (0.3.5), replayed on hands shaped
 * like the two pinches in Mike's 0.3.3 pinch log: 30 fps, quick pinches whose aim
 * drifts while the fingers close, press at a ratio of 0.23 to 0.24, release at 0.58 to 0.69.
 */
class PinchLockTest {
    private val size = 0.2f

    /** A hand whose steady point (wrist and middle, ring, pinky knuckles) lands on display pixel [px], [py]. */
    private fun handAt(px: Float, py: Float, gap: Float, t: Long): HandFrame {
        // TestHands puts the steady point 0.04 hand sizes right of and 0.7 above the wrist.
        val cx = px / 1919f - 0.04f * size
        val cy = py / 1079f + 0.7f * size
        return TestHands.hand(cx, cy, size, extended = listOf(true, true, true, true), thumbGap = gap, timestampMs = t)
    }

    private class Replay(val r: HandGestureRecognizer = HandGestureRecognizer(1920, 1080, AirBox(0f, 0f, 1f, 1f))) {
        val signals = mutableListOf<PointerSignal>()
        val traces = mutableListOf<PinchTrace>()
        val cursors = mutableListOf<Point>()
        var t = 0L

        fun frame(hand: HandFrame): HandUpdate {
            val u = r.update(hand, t, 37f)
            signals += u.signals
            traces += u.traces
            cursors += u.status.cursor!!
            t += 33
            return u
        }
    }

    /**
     * Aims at [target] for a second with +-2 px of landmark jitter, then pinches: [closing] ratios
     * while the aim drifts by [drift] px a frame, [heldFrames] pinched frames that wobble by
     * [wobble] px, then [opening] ratios that drift again. Returns the cursor shown on the last
     * frame before the fingers started closing.
     */
    private fun Replay.pinch(
        target: Point,
        closing: List<Float>,
        drift: Point,
        heldFrames: Int,
        wobble: Float,
        opening: List<Float>,
        seed: Long,
    ): Point {
        val rnd = Random(seed)
        fun j() = (rnd.nextFloat() * 4f - 2f)
        repeat(30) { frame(handAt(target.x + j(), target.y + j(), 0.8f + 0.02f * j(), t)) }
        val aimed = cursors.last()
        var at = target
        for (gap in closing) {
            at += drift
            frame(handAt(at.x + j(), at.y + j(), gap, t))
        }
        repeat(heldFrames) { i ->
            val w = if (i % 2 == 0) wobble else -wobble
            frame(handAt(at.x + w + j(), at.y + j(), 0.12f, t))
        }
        for (gap in opening) {
            at += drift
            frame(handAt(at.x + j(), at.y + j(), gap, t))
        }
        repeat(5) { frame(handAt(at.x + j(), at.y + j(), 0.8f, t)) }
        return aimed
    }

    private fun downAndUp(signals: List<PointerSignal>): Triple<PointerSignal.Down, PointerSignal.Up, List<PointerSignal>> {
        val d = signals.indexOfFirst { it is PointerSignal.Down }
        val u = signals.indexOfFirst { it is PointerSignal.Up }
        assertTrue("no press in $signals", d >= 0 && u > d)
        return Triple(signals[d] as PointerSignal.Down, signals[u] as PointerSignal.Up, signals.subList(d + 1, u))
    }

    @Test
    fun loggedPinchOneClicksWhereTheCursorWasBeforeClosing() {
        // Pinch 1: down at 1140,802 and up 127 ms later, 20 px to the right. Here the closing frames
        // pull the aim 10 px a frame (30 px before the press) and the held frames wobble 10 px.
        val replay = Replay()
        val aimed = replay.pinch(
            target = Point(1140f, 802f),
            closing = listOf(0.62f, 0.44f, 0.24f, 0.2f),
            drift = Point(10f, 1f),
            heldFrames = 2,
            wobble = 10f,
            opening = listOf(0.45f, 0.69f),
            seed = 1,
        )
        val (down, up, between) = downAndUp(replay.signals)
        assertEquals(aimed.x, down.at!!.x, 4f)
        assertEquals(aimed.y, down.at!!.y, 4f)
        assertEquals(down.at, up.at)
        assertTrue("cursor moved while pinched: $between", between.none { it is PointerSignal.MoveTo })
        assertTrue(replay.traces.none { it is PinchTrace.DragUnlock })
        // The press came after the lock, which came at the closing onset.
        val kinds = replay.traces.map { it::class.simpleName }
        assertEquals(listOf("ClosingOnset", "Locked", "Press", "Release"), kinds)
        val press = replay.traces.filterIsInstance<PinchTrace.Press>().single()
        // The hand moved on while the fingers closed (0.5.4: the damped, dead zoned cursor follows less of it).
        assertTrue("drift at press ${press.raw.distanceTo(press.locked)}", press.raw.distanceTo(press.locked) > 8f)
    }

    @Test
    fun loggedPinchTwoClicksWhereTheCursorWasBeforeClosing() {
        // Pinch 2: down at 1209,847, held 195 ms, released at 0.58. Drift up and left this time.
        val replay = Replay()
        val aimed = replay.pinch(
            target = Point(1209f, 847f),
            closing = listOf(0.7f, 0.5f, 0.36f, 0.23f, 0.2f),
            drift = Point(-6f, -5f),
            heldFrames = 4,
            wobble = 14f,
            opening = listOf(0.4f, 0.58f, 0.66f),
            seed = 2,
        )
        val (down, up, between) = downAndUp(replay.signals)
        assertTrue("press ${down.at} vs aimed $aimed", down.at!!.distanceTo(aimed) < 4f)
        assertEquals(down.at, up.at)
        assertTrue(between.none { it is PointerSignal.MoveTo })
    }

    @Test
    fun theSharedPointerSeesATapAtTheAimedSpot() {
        val replay = Replay()
        val aimed = replay.pinch(Point(1140f, 802f), listOf(0.62f, 0.44f, 0.24f, 0.2f), Point(10f, 1f), 3, 12f, listOf(0.45f, 0.69f), seed = 3)
        val pointer = PointerController(Bounds(0, 0, 1920, 1080))
        val actions = replay.signals.flatMap { pointer.handle(it, 0, emptyList()) }
        val tap = actions.filterIsInstance<PointerAction.Tap>().single()
        assertTrue("tap ${tap.at} vs aimed $aimed", tap.at.distanceTo(aimed) < 4f)
        assertTrue(actions.none { it is PointerAction.DragStart })
    }

    @Test
    fun theCursorStopsAtTheClosingOnsetBeforeThePress() {
        val replay = Replay()
        repeat(20) { replay.frame(handAt(900f, 500f, 0.8f, replay.t)) }
        replay.frame(handAt(910f, 500f, 0.6f, replay.t))
        // 0.44: below the onset (0.45), above the press threshold (0.42 before calibration).
        val onset = replay.frame(handAt(920f, 500f, 0.44f, replay.t))
        assertTrue(onset.status.locked)
        assertFalse(onset.status.pinched)
        assertEquals(900f, onset.status.cursor!!.x, 2f)
        val lock = onset.traces.filterIsInstance<PinchTrace.Locked>().single()
        assertTrue(lock.rewoundMs in 33..250)
        // Still closing, still not pinched by the detector: the cursor does not move.
        val next = replay.frame(handAt(930f, 500f, 0.435f, replay.t))
        assertTrue(next.signals.none { it is PointerSignal.MoveTo })
        assertEquals(onset.status.cursor, next.status.cursor)
    }

    @Test
    fun aClosingThatStopsShortLetsTheCursorGoAndGlidesBack() {
        val replay = Replay()
        repeat(20) { replay.frame(handAt(900f, 500f, 0.8f, replay.t)) }
        replay.frame(handAt(900f, 500f, 0.6f, replay.t))
        assertTrue(replay.frame(handAt(900f, 500f, 0.44f, replay.t)).status.locked)
        // Fingers open again without pinching, and the hand moves on.
        val back = replay.frame(handAt(960f, 500f, 0.7f, replay.t))
        assertTrue(back.traces.single() is PinchTrace.ClosingAborted)
        val first = back.signals.filterIsInstance<PointerSignal.MoveTo>().single()
        assertEquals(900f, first.x, 2f)
        val later = (0 until 15).map { replay.frame(handAt(960f, 500f, 0.8f, replay.t)) }
        assertEquals(960f, later.last().status.cursor!!.x, 3f)
        assertTrue(replay.signals.none { it is PointerSignal.Down })
        // Gliding, not jumping: each step is a fraction of the way.
        val xs = later.map { it.status.cursor!!.x }
        assertTrue(xs.zipWithNext().all { (a, b) -> b - a < 40f })
    }

    @Test
    fun aLockWhoseFingersStopClosingPresses() {
        // 0.3.6: fingers that stop just short of the press threshold are a press, not a timeout.
        val replay = Replay()
        repeat(20) { replay.frame(handAt(900f, 500f, 0.8f, replay.t)) }
        replay.frame(handAt(900f, 500f, 0.6f, replay.t))
        replay.frame(handAt(900f, 500f, 0.44f, replay.t))
        val frames = (0 until 3).map { replay.frame(handAt(900f, 500f, 0.44f, replay.t)) }
        val press = replay.traces.filterIsInstance<PinchTrace.Press>().single()
        assertTrue(press.how, press.how.startsWith("stable"))
        assertTrue(frames.last().status.pinched)
        assertTrue(replay.traces.none { it is PinchTrace.ClosingAborted })
    }

    @Test
    fun aLockLeftHalfOpenEndsAtTheTimeout() {
        val replay = Replay()
        repeat(20) { replay.frame(handAt(900f, 500f, 0.8f, replay.t)) }
        replay.frame(handAt(900f, 500f, 0.6f, replay.t))
        replay.frame(handAt(900f, 500f, 0.44f, replay.t))
        // Not closed, not reopened: half open until the lock's 400 ms run out.
        val frames = (0 until 15).map { replay.frame(handAt(900f, 500f, 0.5f, replay.t)) }
        assertTrue(frames.last().signals.any { it is PointerSignal.MoveTo })
        assertFalse(frames.last().status.locked)
        val abort = replay.traces.filterIsInstance<PinchTrace.ClosingAborted>().single()
        assertEquals("fingers stayed half open", abort.reason)
        assertTrue(replay.signals.none { it is PointerSignal.Down })
    }

    @Test
    fun aDeliberateDragUnlocksAndStartsFromTheLock() {
        val replay = Replay()
        repeat(20) { replay.frame(handAt(900f, 500f, 0.8f, replay.t)) }
        for (gap in listOf(0.6f, 0.4f, 0.2f, 0.15f)) replay.frame(handAt(905f, 500f, gap, replay.t))
        val down = replay.signals.filterIsInstance<PointerSignal.Down>().single()
        // Held still for a moment, then dragged 15 px a frame to the right.
        repeat(6) { replay.frame(handAt(905f, 500f, 0.12f, replay.t)) }
        val drag = (1..20).map { i -> replay.frame(handAt(905f + 15f * i, 500f, 0.12f, replay.t)) }
        val unlock = replay.traces.filterIsInstance<PinchTrace.DragUnlock>().single()
        assertTrue(unlock.movedPx > 48f && unlock.movedPx < 140f)
        val moves = drag.flatMap { it.signals.filterIsInstance<PointerSignal.MoveTo>() }
        assertTrue(moves.isNotEmpty())
        assertEquals(down.at!!.x, moves.first().x, 1f)
        assertTrue(moves.zipWithNext().all { (a, b) -> b.x - a.x < 60f })
        assertTrue(moves.last().x > down.at!!.x + 200f)
        assertFalse(drag.last().status.locked)
    }

    @Test
    fun aQuickWobbleShortOfTheDragTimeDoesNotUnlock() {
        val replay = Replay()
        repeat(20) { replay.frame(handAt(900f, 500f, 0.8f, replay.t)) }
        for (gap in listOf(0.6f, 0.4f, 0.2f, 0.15f)) replay.frame(handAt(900f, 500f, gap, replay.t))
        // 60 px within 130 ms: past dragStartPx but not held long enough, and short of dragFarPx.
        val held = listOf(30f, 60f, 60f, 30f).map { dx -> replay.frame(handAt(900f + dx, 500f, 0.12f, replay.t)) }
        assertTrue(held.all { it.signals.none { s -> s is PointerSignal.MoveTo } })
        assertTrue(replay.traces.none { it is PinchTrace.DragUnlock })
    }

    @Test
    fun aFastBigMoveUnlocksOnceItCannotBeATap() {
        // 0.5.5: no drag before the tap time (250 ms), then at once. 0.5.46's flick start (a drag at once past 5 degrees)
        // is off here: this is the tap time's own rule.
        val replay = Replay(HandGestureRecognizer(1920, 1080, AirBox(0f, 0f, 1f, 1f), lock = PinchLockSettings(flickStartDeg = null)))
        repeat(20) { replay.frame(handAt(900f, 500f, 0.8f, replay.t)) }
        for (gap in listOf(0.6f, 0.4f, 0.2f, 0.15f)) replay.frame(handAt(900f, 500f, gap, replay.t))
        repeat(4) { i -> replay.frame(handAt(900f + 120f * (i + 1), 500f, 0.12f, replay.t)) }
        assertTrue(replay.traces.none { it is PinchTrace.DragUnlock })
        repeat(6) { replay.frame(handAt(1380f, 500f, 0.12f, replay.t)) }
        val unlock = replay.traces.filterIsInstance<PinchTrace.DragUnlock>().singleOrNull()
        assertNotNull(unlock)
        assertTrue(unlock!!.line(), unlock.heldMs >= PinchLockSettings().tapMaxMs)
    }

    @Test
    fun releaseJitterStaysAtTheLockedSpot() {
        val replay = Replay()
        repeat(20) { replay.frame(handAt(900f, 500f, 0.8f, replay.t)) }
        for (gap in listOf(0.6f, 0.4f, 0.2f, 0.15f)) replay.frame(handAt(900f, 500f, gap, replay.t))
        repeat(8) { replay.frame(handAt(900f, 500f, 0.12f, replay.t)) }
        // Opening the fingers yanks the aim 25 px a frame for three frames, after the drag time.
        for ((i, gap) in listOf(0.3f, 0.45f, 0.6f).withIndex()) replay.frame(handAt(900f + 25f * (i + 1), 500f, gap, replay.t))
        val (down, up, between) = downAndUp(replay.signals)
        assertEquals(down.at, up.at)
        assertTrue(between.none { it is PointerSignal.MoveTo })
    }

    @Test
    fun steadyPointingNeverLocksOrLags() {
        // Fingers relaxed at 0.6 to 0.9 with ratio noise, hand sweeping: never locked, always moving.
        // From 600 px: further left this hand's thumb is outside the picture, which is not ready (0.5.0).
        val replay = Replay()
        val rnd = Random(7)
        val updates = (0 until 90).map { i ->
            replay.frame(handAt(600f + 10f * i, 500f, 0.75f + (rnd.nextFloat() - 0.5f) * 0.12f, replay.t))
        }
        assertTrue(updates.none { it.status.locked })
        assertTrue(updates.all { u -> u.signals.any { it is PointerSignal.MoveTo } })
        assertTrue(replay.traces.isEmpty())
    }

    @Test
    fun theFilterSmoothsHarderOnlyWhileTheGapCloses() {
        val plain = OneEuroFilter(1.2f, 0.05f, 2.5f)
        val damped = OneEuroFilter(1.2f, 0.05f, 2.5f)
        plain.filter(0f, 0)
        damped.filter(0f, 0)
        val a = plain.filter(30f, 33)
        val b = damped.filter(30f, 33, cutoffScale = 0.3f)
        assertTrue("plain $a, damped $b", b < a * 0.75f)
    }

    @Test
    fun tracesReadWellInTheLog() {
        val press = PinchTrace.Press(Point(1140f, 802f), Point(1160f, 804f), 0.24f, 33, true)
        assertEquals("Pinch down at 1140,802 (locked 33 ms before), hand at 1160,804 (drift 20 px), ratio 0.24 (world)", press.line())
        val lock = PinchTrace.Locked(Point(1140f, 802f), Point(1150f, 802f), 99)
        assertEquals("Pinch aim locked at 1140,802, rewound 99 ms, hand at 1150,802 (drift 10 px)", lock.line())
        assertTrue(PinchTrace.ClosingOnset(Point(1f, 2f), 0.4f, 0.8f).line().startsWith("Pinch closing at 1,2, ratio 0.40"))
        assertTrue(PinchTrace.Release(Point(1f, 2f), Point(1f, 2f), Point(4f, 6f), 0.6f, 127, false).line().contains("after 127 ms, lock 1,2, hand at 4,6 (drift 5 px)"))
        assertTrue(PinchTrace.DragUnlock(Point(1f, 2f), Point(4f, 6f), 60f, 250).line().startsWith("Pinch drag unlocked after 250 ms"))
    }
}
