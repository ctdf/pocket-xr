package com.hayeco.flipspaces.core.hands

import com.hayeco.flipspaces.core.pointer.PointerSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.46 (Mike: "users should be able to open the app launcher by turning their hand toward them and pinching, exactly
 * like how meta quest does it"): a pinch with the palm turned to the glasses opens the launcher instead of pressing; an
 * aiming hand (its back to the camera) presses as before.
 */
class PalmPinchLauncher0546Test {
    /** [TestHands] has the thumb on the left: named "Right" by MediaPipe it shows its palm, named "Left" its back. */
    private fun run(palmToward: Boolean): List<PointerSignal> {
        val r = HandGestureRecognizer(1920, 1080, AirBox(), readiness = PinchReadiness.OFF).apply { palmPinchOpensLauncher = true }
        val out = mutableListOf<PointerSignal>()
        var t = 0L
        fun frame(gap: Float) {
            val h = TestHands.hand(extended = listOf(true, true, true, true), thumbGap = gap, timestampMs = t).copy(rightHand = palmToward)
            out += r.update(h, t).signals
            t += 33
        }
        repeat(15) { frame(1.0f) }
        listOf(0.7f, 0.4f, 0.2f).forEach { frame(it) }
        repeat(6) { frame(0.05f) }
        repeat(6) { frame(1.0f) }
        return out
    }

    /** 0.5.47: with the side flipped (the app's), the same two hands swap: Mike's turned hand opens, his aiming one presses. */
    @Test
    fun withTheSideFlippedTheTwoHandsSwap() {
        fun flippedRun(palmToward: Boolean): List<PointerSignal> {
            val r = HandGestureRecognizer(1920, 1080, AirBox(), readiness = PinchReadiness.OFF).apply { palmPinchOpensLauncher = true; palmSideFlipped = true }
            val out = mutableListOf<PointerSignal>()
            var t = 0L
            fun frame(gap: Float) {
                out += r.update(TestHands.hand(extended = listOf(true, true, true, true), thumbGap = gap, timestampMs = t).copy(rightHand = palmToward), t).signals
                t += 33
            }
            repeat(15) { frame(1.0f) }
            listOf(0.7f, 0.4f, 0.2f).forEach { frame(it) }
            repeat(6) { frame(0.05f) }
            repeat(6) { frame(1.0f) }
            return out
        }
        assertEquals(1, flippedRun(false).count { it == PointerSignal.ShowDock })
        assertEquals(1, flippedRun(true).count { it is PointerSignal.Down })
    }

    @Test
    fun aPalmTowardPinchOpensTheLauncherAndAnAimingPinchPresses() {
        val palm = run(palmToward = true)
        // Once: the held open palm no longer opens it too while the palm pinch is on.
        assertEquals(1, palm.count { it == PointerSignal.ShowDock })
        assertTrue(palm.none { it is PointerSignal.Down || it is PointerSignal.Up })
        val aim = run(palmToward = false)
        assertEquals(0, aim.count { it == PointerSignal.ShowDock })
        assertEquals(1, aim.count { it is PointerSignal.Down })
        assertEquals(1, aim.count { it is PointerSignal.Up })
    }
}
