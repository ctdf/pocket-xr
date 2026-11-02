package com.hayeco.flipspaces.core.hands

import com.hayeco.flipspaces.core.pointer.Point
import com.hayeco.flipspaces.core.pointer.PointerSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandGestureRecognizerTest {
    private fun recognizer() = HandGestureRecognizer(1920, 1080, AirBox(0f, 0f, 1f, 1f), PinchDetector(enterFrames = 1, exitFrames = 1))

    @Test
    fun airBoxMapsAndClamps() {
        val box = AirBox(0.2f, 0.2f, 0.8f, 0.8f)
        assertEquals(Point(0f, 0f), box.toDisplay(Landmark(0.1f, 0.1f), 1920, 1080))
        assertEquals(Point(1919f, 1079f), box.toDisplay(Landmark(0.95f, 0.9f), 1920, 1080))
        val mid = box.toDisplay(Landmark(0.5f, 0.5f), 1921, 1081)
        assertEquals(960f, mid.x, 0.01f)
        assertEquals(540f, mid.y, 0.01f)
        assertEquals(0f, AirBox(mirror = true).toDisplay(Landmark(0.9f, 0.5f), 1920, 1080).x, 0.01f)
    }

    @Test
    fun pointingMovesTheCursor() {
        val r = recognizer()
        val update = r.update(TestHands.pointing(t = 0), 0)
        val move = update.signals.filterIsInstance<PointerSignal.MoveTo>().single()
        // The steady point aims: 0.3 wrist (0.5) + 0.3 middle (0.48) + 0.25 ring (0.52) + 0.15 pinky (0.56) = 0.508 of the width.
        assertEquals(0.508f * 1919, move.x, 1f)
        assertEquals(0L, move.timeMs)
        assertTrue(update.status.present)
        assertEquals(HandPose.POINT, update.status.pose)
    }

    @Test
    fun palmAimIsStillAvailable() {
        val r = recognizer().apply { aim = AimPoint.PALM }
        val move = r.update(TestHands.pointing(t = 0), 0).signals.filterIsInstance<PointerSignal.MoveTo>().single()
        // 0.3 index knuckle (0.44) + 0.3 middle (0.48) + 0.2 ring (0.52) + 0.2 wrist (0.5) = 0.48 of the width.
        assertEquals(0.48f * 1919, move.x, 1f)
    }

    @Test
    fun indexKnuckleAimIsStillAvailable() {
        val r = recognizer().apply { aim = AimPoint.INDEX_KNUCKLE }
        val move = r.update(TestHands.pointing(t = 0), 0).signals.filterIsInstance<PointerSignal.MoveTo>().single()
        // x = 0.5 - 0.3 * 0.2 = 0.44 of the width.
        assertEquals(0.44f * 1919, move.x, 1f)
    }

    @Test
    fun pinchPressesWhereTheCursorWasBeforeThePinch() {
        val r = recognizer()
        var t = 0L
        repeat(5) {
            r.update(TestHands.pointing(cx = 0.5f, t = t), t)
            t += 33
        }
        // The pinch frame moves the hand 1% of the frame; the press lands at the rewound position.
        // 0.5.5: the aim locks on the pinch frame and the press follows on the next one.
        val locked = r.update(TestHands.pinching(cx = 0.51f, t = t), t)
        assertTrue(locked.signals.none { it is PointerSignal.Down })
        t += 33
        val update = r.update(TestHands.pinching(cx = 0.51f, t = t), t)
        val down = update.signals.filterIsInstance<PointerSignal.Down>().single()
        assertEquals(0.508f * 1919, down.at!!.x, 2f)
        assertTrue(update.status.pinched)
        assertTrue(down.timeMs!! < t)
        assertTrue(update.traces.any { it is PinchTrace.Press })
    }

    @Test
    fun cursorStaysFrozenWhilePinchedUntilTheHandReallyMoves() {
        val r = recognizer()
        // 0.5.5: the aim locks once the hand has been seen for the select look back (66 ms).
        r.update(TestHands.pointing(t = 0), 0)
        r.update(TestHands.pointing(t = 33), 33)
        r.update(TestHands.pointing(t = 66), 66)
        // The lock (its one held move), then a few pixels of wobble for as long as the pinch lasts: no move.
        r.update(TestHands.pinching(t = 99), 99)
        val held = (4..12).flatMap { i -> r.update(TestHands.pinching(cx = 0.5f + 0.004f * (i % 2), t = i * 33L), i * 33L).signals }
        assertTrue(held.none { it is PointerSignal.MoveTo })
        val later = r.update(TestHands.pinching(cx = 0.7f, t = 500), 500)
        assertTrue(later.signals.any { it is PointerSignal.MoveTo })
    }

    @Test
    fun releasingThePinchSendsUp() {
        val r = recognizer()
        r.update(TestHands.pointing(t = 0), 0)
        r.update(TestHands.pointing(t = 33), 33)
        r.update(TestHands.pointing(t = 66), 66)
        // 0.5.5: the lock, then the press a frame later.
        r.update(TestHands.pinching(t = 99), 99)
        r.update(TestHands.pinching(t = 132), 132)
        val up = r.update(TestHands.pointing(t = 165), 165)
        assertNotNull(up.signals.filterIsInstance<PointerSignal.Up>().singleOrNull())
        assertFalse(up.status.pinched)
    }

    @Test
    fun openPalmHeldShowsDockOnce() {
        val r = recognizer()
        val all = (0..20).flatMap { i -> r.update(TestHands.openPalm(t = i * 50L), i * 50L).signals }
        assertEquals(1, all.count { it == PointerSignal.ShowDock })
    }

    @Test
    fun shortPalmFlashDoesNotShowDock() {
        val r = recognizer()
        val signals = mutableListOf<PointerSignal>()
        for (i in 0..20) {
            val t = i * 50L
            val hand = if (i % 5 == 0) TestHands.pointing(t = t) else TestHands.openPalm(t = t)
            signals += r.update(hand, t).signals
        }
        assertTrue(signals.none { it == PointerSignal.ShowDock })
    }

    @Test
    fun losingTheHandMidPinchFinishesTheClickAtTheLock() {
        // 0.3.6: a lock always ends; a press the hand vanished from is released where it was locked.
        val r = recognizer()
        r.update(TestHands.pointing(t = 0), 0)
        r.update(TestHands.pointing(t = 33), 33)
        r.update(TestHands.pointing(t = 66), 66)
        r.update(TestHands.pinching(t = 99), 99)
        val down = r.update(TestHands.pinching(t = 132), 132).signals.filterIsInstance<PointerSignal.Down>().single()
        assertTrue(r.update(null, 200).signals.isEmpty())
        // 0.5.7: lost after lostAfterMs (450 ms, was 300) without the hand.
        val lost = r.update(null, 132 + r.lostAfterMs)
        assertEquals(listOf(PointerSignal.Up(down.at, down.timeMs)), lost.signals)
        assertTrue(lost.traces.single() is PinchTrace.HandLost)
        assertFalse(lost.status.present)
        assertFalse(r.pinched)
    }

    @Test
    fun losingTheHandMidDragCancels() {
        val r = recognizer()
        r.update(TestHands.pointing(t = 0), 0)
        r.update(TestHands.pointing(t = 33), 33)
        r.update(TestHands.pointing(t = 66), 66)
        r.update(TestHands.pinching(t = 99), 99)
        r.update(TestHands.pinching(t = 132), 132)
        // A big move unlocks a drag once the pinch can no longer be a tap (0.5.5: 250 ms).
        var t = 165L
        while (t <= 420L) {
            r.update(TestHands.pinching(cx = 0.7f + (t - 165L) / 5000f, t = t), t)
            t += 33
        }
        val lost = r.update(null, t - 33 + r.lostAfterMs)
        assertEquals(listOf(PointerSignal.Cancel), lost.signals)
    }

    @Test
    fun pinchProgressRisesAsFingersClose() {
        // 0.5.0: 0 at the hand's open ratio (0.6 until it has been learnt), so wide means past it.
        val r = recognizer()
        val wide = r.update(TestHands.hand(thumbGap = 0.9f), 0).status.pinchProgress
        val r2 = recognizer()
        val close = r2.update(TestHands.hand(thumbGap = 0.3f), 0).status.pinchProgress
        assertEquals(0f, wide, 0.001f)
        assertTrue(close > 0.5f)
    }
}
