package com.hayeco.flipspaces.core.hands

import com.hayeco.flipspaces.core.pointer.PointerSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.50 (Mike: "if the user turns their palm and holds the pinch, then it activates the Recentre feature"): as on Meta
 * Quest, a short palm pinch opens the launcher when it lets go, and one held about two thirds of a second recentres.
 */
class PalmHoldRecentre0550Test {
    /** A palm toward the glasses (TestHands drawn palm on, named "Right"), pinched for [heldFrames] frames of 33 ms. */
    private fun run(heldFrames: Int): Pair<List<PointerSignal>, List<Float>> {
        val r = HandGestureRecognizer(1920, 1080, AirBox(), readiness = PinchReadiness.OFF).apply { palmPinchOpensLauncher = true }
        val out = mutableListOf<PointerSignal>()
        val holds = mutableListOf<Float>()
        var t = 0L
        fun frame(gap: Float) {
            val u = r.update(TestHands.hand(extended = listOf(true, true, true, true), thumbGap = gap, timestampMs = t).copy(rightHand = true), t)
            out += u.signals
            holds += u.status.palmHold
            t += 33
        }
        repeat(12) { frame(1.0f) }
        listOf(0.7f, 0.4f, 0.2f).forEach { frame(it) }
        repeat(heldFrames) { frame(0.05f) }
        repeat(6) { frame(1.0f) }
        return out to holds
    }

    @Test
    fun aShortPalmPinchOpensTheLauncherAndAHeldOneRecentres() {
        val (short, _) = run(heldFrames = 5)
        assertEquals(1, short.count { it == PointerSignal.ShowDock })
        assertEquals(0, short.count { it == PointerSignal.Recentre })
        val (held, holds) = run(heldFrames = 30)
        assertEquals(1, held.count { it == PointerSignal.Recentre })
        assertEquals("no launcher after a recentre", 0, held.count { it == PointerSignal.ShowDock })
        assertTrue(held.none { it is PointerSignal.Down || it is PointerSignal.Up })
        // The dot's fill climbs toward the recentre, then clears.
        val rising = holds.filter { it > 0f }
        assertTrue(rising.size >= 10 && rising.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(0f, holds.last(), 0f)
    }
}
