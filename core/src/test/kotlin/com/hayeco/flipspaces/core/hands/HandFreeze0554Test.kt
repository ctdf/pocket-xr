package com.hayeco.flipspaces.core.hands

import com.hayeco.flipspaces.core.pointer.Point
import com.hayeco.flipspaces.core.pointer.PointerSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.54 (Mike: hand tracking "freezes for a second or two" at random moments). Two causes in the 0.5.53 logs:
 * the not worn guard paused the hand model on a worn head rolled 61 to 69 degrees (0.1 to 5.5 s), and a half closed
 * hand's strength lock held the cursor 0.9 s while the hand moved on.
 */
class HandFreeze0554Test {
    private val hands = SyntheticHands(1920, 1080, AirBox())

    /** Head poses about ten times a second, as the head tracker sends them, from [fromMs] for [forMs]. */
    private fun PhoneHandling.pose(roll: Float, pitch: Float, fromMs: Long, forMs: Long): Long {
        var t = fromMs
        while (t <= fromMs + forMs) {
            glasses(roll, pitch, static = false, nowMs = t)
            t += 100
        }
        return t - 100
    }

    @Test
    fun aWornHeadRolled62DegreesKeepsTheHandModelRunning() {
        val h = PhoneHandling()
        // Roll -62 for 5.5 s with a hand in use. 0.5.53 paused at once.
        val end = h.pose(-62f, -5f, 0, 5_500)
        assertNull(h.notWorn(end))
        // Pitch -78 for 0.1 s while looking down.
        val end2 = h.pose(4f, -78f, end + 100, 100)
        assertNull(h.notWorn(end2))
    }

    @Test
    fun glassesOffAHeadStillPauseIt() {
        val h = PhoneHandling()
        // Roll -99 for minutes: paused once the tilt has held 1.5 s.
        h.pose(-99f, 3f, 0, 1_000)
        assertNull(h.notWorn(1_000))
        val end = h.pose(-99f, 3f, 1_100, 600)
        assertNotNull(h.notWorn(end))
        // Pitch -81, glasses hanging face down.
        val p = PhoneHandling()
        val pEnd = p.pose(2f, -81f, 0, 1_600)
        assertTrue(p.notWorn(pEnd)!!.contains("pitch"))
        // Upside down on the table (the 0.5.19 case, roll 173.6) counts at once.
        val u = PhoneHandling()
        u.glasses(173.6f, 2f, static = false, nowMs = 10_000)
        assertTrue(u.notWorn(10_050)!!.contains("upside down"))
    }

    @Test
    fun aTiltWithABreakStartsItsHoldAgain() {
        val h = PhoneHandling()
        h.pose(-95f, 0f, 0, 1_000)
        h.glasses(-20f, 0f, static = false, nowMs = 1_100)
        val end = h.pose(-95f, 0f, 1_200, 1_000)
        assertNull(h.notWorn(end))
    }

    /** Open hand at [start], fingers closing half way (strength lock, no ratio onset) while the hand sweeps right at [pxPerFrame]. */
    private fun halfClosedSweep(settings: PinchLockSettings, pxPerFrame: Float): Pair<List<PinchTrace>, List<Point>> {
        val r = HandGestureRecognizer(1920, 1080, AirBox(), lock = settings)
        var t = 0L
        var x = 500f
        val traces = mutableListOf<PinchTrace>()
        val shown = mutableListOf<Point>()
        val signals = mutableListOf<PointerSignal>()
        repeat(20) { r.update(hands.hand(Point(x, 500f), 0.9f, t), t); t += 33 }
        // Closes to 0.5 (strength over 0.4, ratio above the 0.45 onset) and stays there while the hand moves on.
        for (ratio in listOf(0.8f, 0.7f, 0.6f, 0.52f) + List(30) { 0.5f }) {
            val u = r.update(hands.hand(Point(x, 500f), ratio, t), t)
            traces += u.traces
            signals += u.signals
            shown += u.status.cursor!!
            x += pxPerFrame
            t += 33
        }
        assertTrue(signals.none { it is PointerSignal.Down })
        return traces to shown
    }

    @Test
    fun aHalfClosedHandMovingOnLetsTheCursorGoWithinAThirdOfASecond() {
        val (traces, shown) = halfClosedSweep(PinchLockSettings(), 12f)
        val lockAt = traces.indexOfFirst { it is PinchTrace.Locked }
        assertTrue("no strength lock: $traces", lockAt >= 0)
        val aborted = traces.filterIsInstance<PinchTrace.ClosingAborted>().first()
        assertTrue(aborted.line(), aborted.line().contains("moved on"))
        // The longest run of frames the cursor stood still: under 0.4 s (12 frames), not 0.9 s.
        var run = 0
        var longest = 0
        for (i in 1 until shown.size) {
            run = if (shown[i].distanceTo(shown[i - 1]) < 0.5f) run + 1 else 0
            longest = maxOf(longest, run)
        }
        assertTrue("frozen $longest frames", longest < 12)
        // 0.5.53 held it the whole 0.9 s.
        val (oldTraces, _) = halfClosedSweep(PinchLockSettings(strengthLockMoveDeg = null), 12f)
        assertTrue(oldTraces.filterIsInstance<PinchTrace.ClosingAborted>().first().line().contains("half closed"))
    }

    @Test
    fun aStillHalfClosedHandKeepsItsLock() {
        val (traces, _) = halfClosedSweep(PinchLockSettings(), 0f)
        val aborted = traces.filterIsInstance<PinchTrace.ClosingAborted>()
        assertEquals(1, aborted.size)
        assertTrue(aborted.first().line().contains("stayed half closed"))
    }
}
