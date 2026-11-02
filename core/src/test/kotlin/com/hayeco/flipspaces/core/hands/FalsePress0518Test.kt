package com.hayeco.flipspaces.core.hands

import com.hayeco.flipspaces.core.pointer.PointerSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.18, the vc38 test: 5 probable false presses of 42.
 * Two came 166 and 167 ms after a bad frame while the phone was being opened, 1.5 s apart;
 * three from a hand at the very bottom of the camera picture, the one holding the phone, which
 * pressed after its fingers flicked open for a moment (one closed a window), within 2 s.
 */
class FalsePress0518Test {
    private fun recognizer(lock: PinchLockSettings = PinchLockSettings()) =
        HandGestureRecognizer(1920, 1080, AirBox(0f, 0f, 1f, 1f), PinchDetector(enterFrames = 1, exitFrames = 1), lock = lock)

    private fun gate(s: PinchLockSettings, bad: Long? = null, phone: String? = null, low: Boolean = false) = PressGate.refusal(
        s, 0.28f, 0.35f, null, false, 50L, 9f, 1f, 0.5f, 232L, framesBelow = 2,
        sinceBadFrameMs = bad, phoneHandled = phone, lowBand = low,
    )

    @Test
    fun theTwoPhoneOpeningPressesAreRefused() {
        // 166 and 167 ms after a bad frame: 0.5.17 let them through, 0.5.18 does not.
        assertNull(gate(PinchLockSettings.V0517, bad = 166))
        assertTrue(gate(PinchLockSettings(), bad = 166)!!.startsWith("bad frame 166 ms ago"))
        assertTrue(gate(PinchLockSettings(), bad = 167)!!.startsWith("bad frame 167 ms ago"))
        assertNull(gate(PinchLockSettings(), bad = 250))
        // And the phone opening itself mutes hand presses for a second (the second press came 89 ms after it).
        val phone = PhoneHandling()
        phone.postureChanged(1_000L)
        val why = phone.muted(1_089L)
        assertTrue(why!!.startsWith("the phone was folded or opened 89 ms ago"))
        assertEquals("phone in hand", PressGate.kind(gate(PinchLockSettings(), phone = why)!!))
        assertNull(phone.muted(2_100L))
        // A finger on the phone's trackpad mutes them while it is down and for 250 ms after.
        phone.touch(true, 5_000L)
        assertEquals("a finger is on the phone's trackpad", phone.muted(5_100L))
        phone.touch(false, 5_200L)
        assertTrue(phone.muted(5_300L)!!.startsWith("the phone's trackpad was touched 100 ms ago"))
        assertNull(phone.muted(5_460L))
    }

    /** The left hand at the bottom of the picture, fingers curled round the phone: wrist at y 0.95. */
    private fun bottom(t: Long, gap: Float) = TestHands.hand(0.1f, 0.95f, size = 0.08f, extended = listOf(true, false, false, false), thumbGap = gap, timestampMs = t)

    private fun run(r: HandGestureRecognizer, frames: List<Pair<Float, Float>>): List<PointerSignal> {
        // (cy, thumbGap) per 33 ms frame.
        val out = mutableListOf<PointerSignal>()
        var t = 0L
        val traces = mutableListOf<PinchTrace>()
        for ((cy, gap) in frames) {
            val h = TestHands.hand(0.1f, cy, size = 0.08f, extended = listOf(true, false, false, false), thumbGap = gap, timestampMs = t)
            val u = r.update(h, t)
            out += u.signals
            traces += u.traces
            t += 33
        }
        traces.forEach { println("  " + it.line()) }
        return out
    }

    @Test
    fun aHandHoldingThePhoneLowInThePictureDoesNotPress() {
        // Curled round the phone, the fingers flick open for two frames, then close.
        val flick = List(6) { 0.95f to 0.4f } + List(2) { 0.95f to 0.8f } + List(6) { 0.95f to 0.05f }
        assertTrue(bottom(0, 0.05f).nearBottom(0.88f))
        val now = run(recognizer(), flick)
        val old = run(recognizer(PinchLockSettings.V0517), flick)
        assertEquals("0.5.17 pressed", 1, old.count { it is PointerSignal.Down })
        assertEquals("0.5.18 pressed", 0, now.count { it is PointerSignal.Down })
    }

    @Test
    fun aHandSeenOpenAboveTheBandOrHeldOpenInItStillPresses() {
        // Open at y 0.7, then lower into the band and pinch there.
        val above = List(8) { 0.7f to 0.8f } + listOf(0.78f to 0.8f, 0.86f to 0.8f, 0.95f to 0.8f) + List(6) { 0.95f to 0.05f }
        assertEquals(1, run(recognizer(), above).count { it is PointerSignal.Down })
        // Open in the band for a third of a second (0.5.6's low hand), then a pinch.
        val held = List(10) { 0.95f to 0.8f } + List(6) { 0.95f to 0.05f }
        assertEquals(1, run(recognizer(), held).count { it is PointerSignal.Down })
    }
}
