package com.hayeco.flipspaces.core.hands

import com.hayeco.flipspaces.core.pointer.PointerSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.8: pinches that register with the hand low in the camera. Shaped like Mike's 0.5.6 two hand
 * log (two minutes): tracking at 15 fps with gaps, 32 "thumb or index at the edge of
 * the picture" frames, "the press holds for 150 ms" twice, and in the right hand run
 * the hand at y 894 to 982 of 1080 with locks ending "hand lost, press released at the lock" or
 * "lock dropped without a press" after 166 to 435 ms.
 */
class LowHandPinchTest {
    private fun recognizer() = HandGestureRecognizer(1920, 1080, AirBox(0f, 0f, 1f, 1f), PinchDetector(enterFrames = 1, exitFrames = 1))

    /** [base] turned fingers down around its wrist at [cy], the tips past the bottom of the picture, with MediaPipe's [score]. */
    private fun low(base: HandFrame, cy: Float, score: Float = 0.95f) =
        base.copy(landmarks = base.landmarks.map { Landmark(it.x, 2 * cy - it.y) }, score = score)

    /** Mike's low hand: wrist at y 0.86 (930 of 1080), the thumb and index tips at about 1.02. */
    private fun lowPointing(t: Long, cx: Float = 0.5f, score: Float = 0.95f) =
        low(TestHands.hand(cx, 0.86f, size = 0.08f, extended = listOf(true, false, false, false), thumbGap = 0.8f, timestampMs = t), 0.86f, score)

    private fun lowPinching(t: Long, cx: Float = 0.5f, score: Float = 0.95f) =
        low(TestHands.hand(cx, 0.86f, size = 0.08f, extended = listOf(true, false, false, false), thumbGap = 0.05f, timestampMs = t), 0.86f, score)

    @Test
    fun aConfidentHandWithItsTipsJustPastTheBottomIsReady() {
        val r = PinchReadiness()
        val hand = lowPinching(0)
        assertTrue(hand[HandFrame.INDEX_TIP].y > 1f)
        assertTrue(hand.palmInFrame())
        assertNull(r.reason(hand, null))
        assertTrue(hand.nearBottom())
        // A hand MediaPipe is not sure of, a tip past the slack, or a palm out of the picture: not ready.
        assertEquals("thumb or index at the edge of the picture", r.reason(lowPinching(0, score = 0.6f), null))
        val deep = low(TestHands.hand(0.5f, 0.9f, size = 0.12f, thumbGap = 0.05f), 0.9f)
        assertTrue(deep[HandFrame.INDEX_TIP].y > 1f + r.bottomSlack)
        assertNotNull(r.reason(deep, null))
        val palmOut = low(TestHands.hand(0.5f, 1.01f, size = 0.04f, thumbGap = 0.05f), 1.01f)
        assertNotNull(r.reason(palmOut, null))
        // The sides stay strict.
        val side = TestHands.hand(0.99f, 0.6f, size = 0.08f, thumbGap = 0.05f).let { h -> h.copy(landmarks = h.landmarks.map { Landmark(it.x + 0.05f, it.y) }, score = 0.95f) }
        assertNotNull(r.reason(side, null))
        assertFalse(TestHands.pinching(cy = 0.6f).nearBottom())
    }

    @Test
    fun mikesLowRightHandPinchClicksAt15FpsWithGaps() {
        val r = recognizer()
        val frame = 66L
        var t = 0L
        val signals = mutableListOf<PointerSignal>()
        val traces = mutableListOf<PinchTrace>()
        fun feed(h: HandFrame?) {
            val u = r.update(h, t)
            signals += u.signals
            traces += u.traces
            t += frame
        }
        repeat(5) { feed(lowPointing(t)) }
        // Closing: the lock, then a gap of two missed frames (132 ms, past 0.5.7's 100 ms lock loss).
        feed(lowPinching(t))
        feed(null)
        feed(null)
        repeat(3) { feed(lowPinching(t)) }
        // Pressed: three more missed frames (198 ms) under the press, then held, then opened.
        repeat(3) { feed(null) }
        repeat(2) { feed(lowPinching(t)) }
        repeat(3) { feed(lowPointing(t)) }
        assertEquals(traces.joinToString { it.line() }, 1, signals.count { it is PointerSignal.Down })
        assertEquals(1, signals.count { it is PointerSignal.Up })
        assertTrue(signals.none { it == PointerSignal.Cancel })
        assertTrue(traces.joinToString { it.line() }, traces.none { it is PinchTrace.HandLost || it is PinchTrace.NotReady })
        assertTrue(traces.none { it is PinchTrace.FrameSkipped })
        // Up comes from opening the fingers, after the press, not from a loss.
        assertTrue(signals.indexOfFirst { it is PointerSignal.Up } > signals.indexOfFirst { it is PointerSignal.Down })
    }

    @Test
    fun thePressHoldsOnTheLastPoseUnder250MsAndEndsOnlyIfTheHandStaysLost() {
        val r = recognizer()
        var t = 0L
        repeat(4) { r.update(TestHands.pointing(t = t), t); t += 33 }
        r.update(TestHands.pinching(t = t), t); t += 33
        assertNotNull(r.update(TestHands.pinching(t = t), t).signals.filterIsInstance<PointerSignal.Down>().singleOrNull())
        val pressedAt = t
        // 240 ms lost: nothing ends, the cursor stays on the lock.
        while (t < pressedAt + 240) {
            t += 33
            val u = r.update(null, t)
            assertTrue(u.signals.none { it is PointerSignal.Up || it == PointerSignal.Cancel })
            assertTrue(u.status.pinched && u.status.present)
        }
        t += 33
        val back = r.update(TestHands.pinching(t = t), t)
        assertTrue(back.traces.none { it is PinchTrace.HandLost })
        assertTrue(back.status.pinched)
        // Lost for good: released at the lock at 450 ms.
        val lastSeen = t
        assertTrue(r.update(null, lastSeen + 300).signals.isEmpty())
        val lost = r.update(null, lastSeen + r.lostAfterMs)
        assertNotNull(lost.signals.filterIsInstance<PointerSignal.Up>().singleOrNull())
        assertTrue(lost.traces.any { it is PinchTrace.HandLost && it.ended == "press released at the lock" })
    }

    @Test
    fun aLockWaitsOutAShortGapAndDropsAfter250Ms() {
        val r = recognizer()
        repeat(4) { i -> r.update(TestHands.pointing(t = i * 33L), i * 33L) }
        r.update(TestHands.pinching(t = 132), 132)
        assertTrue(r.update(null, 300).traces.none { it is PinchTrace.HandLost })
        val dropped = r.update(null, 132 + 250)
        assertTrue(dropped.traces.any { it is PinchTrace.HandLost && it.ended == "lock dropped without a press" })
    }

    @Test
    fun aPressHoldsThroughNotReadyFramesFor250Ms() {
        val r = recognizer()
        var t = 0L
        repeat(4) { r.update(TestHands.pointing(t = t), t); t += 33 }
        r.update(TestHands.pinching(t = t), t); t += 33
        r.update(TestHands.pinching(t = t), t); t += 33
        assertTrue(r.pinched)
        // MediaPipe unsure of the hand (score 0.3) for 200 ms: the press holds.
        val start = t
        while (t < start + 200) {
            assertTrue(r.update(TestHands.pinching(t = t).copy(score = 0.3f), t).signals.none { it is PointerSignal.Up })
            t += 33
        }
        assertTrue(r.pinched)
        while (t < start + 300) {
            r.update(TestHands.pinching(t = t).copy(score = 0.3f), t)
            t += 33
        }
        assertFalse(r.pinched)
    }

    @Test
    fun theStatusSaysTheHandIsLowThroughAGap() {
        val r = recognizer()
        assertTrue(r.update(lowPointing(0), 0).status.nearBottom)
        assertTrue(r.update(null, 66).status.nearBottom)
        assertFalse(r.update(TestHands.pointing(cy = 0.6f, t = 132), 132).status.nearBottom)
    }

    @Test
    fun theHintComesUpForALowHandAndGoesWhenItIsRaised() {
        val hint = CameraEdgeHint()
        val low = HandStatus(present = true, nearBottom = true)
        val high = HandStatus(present = true)
        assertNull(hint.update(listOf(low), 0))
        assertNull(hint.update(listOf(low), 150))
        assertEquals(CameraEdgeHint.TEXT, hint.update(listOf(high, low), 210))
        // Raised: still shown for a moment, so it does not flicker, then gone.
        assertNotNull(hint.update(listOf(high), 600))
        assertNull(hint.update(listOf(high), 1_020))
        // A hand passing through the bottom does not flash it.
        assertNull(hint.update(listOf(low), 2_000))
        assertNull(hint.update(listOf(high), 2_100))
        assertNull(hint.update(emptyList(), 2_400))
    }

    @Test
    fun theWholeViewKeepsTheBottomOfTheCamera() {
        // The glasses camera, 80 by 51 degrees, decoded at 960x540: the whole view is the 960 square
        // at 0,-210 sent at 640, which letterboxes the full height. The bottom row is in it.
        val win = DetectionWindow(960, 540, 0, DetectionCycle.DEFAULT_VIEWS[0], 640)
        assertEquals(960, win.side)
        assertEquals(-210f, win.top, 0.01f)
        assertEquals(640, win.outputSide)
        val (_, y) = win.toSquare(480f, 539f)
        assertTrue("bottom row at $y of 640", y in 0f..640f)
        val back = win.toUpright(Landmark(0.5f, (539f + 210f) / 960f))
        assertEquals(539f / 540f, back.y, 0.002f)
        // The near view after a loss, centred on a low hand, also reaches the bottom row.
        val near = DetectionWindow(960, 540, 0, DetectionView("near", 1.8f, 0.5f, 0.9f), 640)
        assertEquals(540f, near.top + near.side, 1f)
    }

    @Test
    fun withOneHandInViewThePalmDetectorRunsOnOneFrameInFour() {
        val s = PalmSearch()
        val uses = List(32) { s.next().also { u -> s.result(u, 1) } }
        assertEquals(8, uses.count { it == PalmSearch.Use.TWO })
        // Cost from Mike's logs: about 30 ms following one hand, about 62 ms with the palm detector too.
        val meanMs = uses.sumOf { if (it == PalmSearch.Use.TWO) 62.0 else 30.0 } / uses.size
        assertTrue("about ${1000 / meanMs} fps", 1000 / meanMs >= 25)
        // 0.5.6/0.5.7 with one two hand landmarker: the palm detector on every frame, about 16 fps.
        assertTrue(1000 / 62.0 < 17)
    }

    @Test
    fun twoHandsFoundAreFollowedEveryFrameUntilOneGoes() {
        val s = PalmSearch()
        var u = s.next()
        while (u != PalmSearch.Use.TWO) { s.result(u, 1); u = s.next() }
        assertTrue(s.result(u, 2))
        assertTrue(s.both)
        repeat(20) { assertEquals(PalmSearch.Use.TWO, s.next().also { s.result(it, 2) }) }
        // One frame with a hand missing keeps both; a second in a row goes back to one.
        assertFalse(s.result(s.next(), 1))
        assertTrue(s.result(s.next(), 1))
        assertFalse(s.both)
        assertEquals(PalmSearch.Use.ONE, s.next())
    }
}
