package com.hayeco.flipspaces.core.hands

import com.hayeco.flipspaces.core.pointer.Point
import com.hayeco.flipspaces.core.pointer.PointerSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.0's pinch: Meta's thumb bone measure, the hard and fast releases, the release from the
 * picture, the confidence gate, the 0 to 1 pinch value and the tap or drag decision by angle.
 */
class PinchStateMachineTest {
    private val hands = SyntheticHands(1920, 1080)

    @Test
    fun theBoneMeasureReadsAPadOnTheThumbAsClosed() {
        // Index tip 2 cm from the thumb tip, but resting on the side of the thumb's last bone.
        val base = hands.hand(Point(960f, 540f), 0.24f, 0)
        val ip = base[HandFrame.THUMB_IP]
        val tip = base[HandFrame.THUMB_TIP]
        val onBone = Landmark((ip.x + tip.x) / 2f, (ip.y + tip.y) / 2f)
        val image = base.landmarks.toMutableList().also { it[HandFrame.INDEX_TIP] = onBone }
        val wIp = base.world!![HandFrame.THUMB_IP]
        val wTip = base.world!![HandFrame.THUMB_TIP]
        val world = base.world!!.toMutableList().also { it[HandFrame.INDEX_TIP] = Landmark((wIp.x + wTip.x) / 2f, (wIp.y + wTip.y) / 2f) }
        val hand = base.copy(landmarks = image, world = world)
        assertTrue("tip to tip ${hand.pinchRatio}", hand.pinchRatio > 0.3f)
        assertEquals(0f, hand.pinchDistance(hold = false), 0.01f)
        // Holding adds the bone before it, so it never reads further than pressing.
        assertTrue(hand.pinchDistance(hold = true) <= hand.pinchDistance(hold = false))
    }

    @Test
    fun theHysteresisPressesBelowAndReleasesAboveWithAGap() {
        val d = PinchDetector.quest()
        // Two frames under the press threshold to press, a wobble inside the gap holds.
        assertFalse(d.update(0.40f))
        assertFalse(d.update(0.37f))
        assertTrue(d.update(0.36f))
        listOf(0.42f, 0.39f, 0.41f, 0.38f).forEach { assertTrue(d.update(it)) }
        // Two frames over the release threshold to release.
        assertTrue(d.update(0.47f))
        assertFalse(d.update(0.47f))
        assertEquals("threshold", d.releasedBy)
    }

    @Test
    fun fingersOpeningFastReleaseBeforeTheThreshold() {
        val d = PinchDetector.quest()
        d.update(0.10f) // a clear touch presses at once
        assertTrue(d.pinched)
        assertTrue(d.update(0.30f))
        // Two rising frames 0.08 over the tightest point, still under the 0.46 release: released.
        assertFalse(d.update(0.40f))
        assertEquals("opening fast", d.releasedBy)
    }

    @Test
    fun aSingleSpikeOverTheHardReleaseDoesNotEndThePinchButTwoFramesDo() {
        // 0.5.4: one bad frame (fingers snapping apart for 33 ms) no longer ends a press or a drag.
        val d = PinchDetector.quest()
        d.update(0.10f)
        assertTrue(d.update(0.65f))
        assertTrue(d.update(0.12f))
        assertTrue(d.update(0.65f))
        assertFalse(d.update(0.66f))
        assertEquals("hard", d.releasedBy)
    }

    @Test
    fun aWobbleDuringAHeldPinchDoesNotRelease() {
        val d = PinchDetector.quest()
        d.update(0.10f)
        // Up and down by 0.05 around 0.25 for a second: a drag must not drop.
        repeat(30) { i -> assertTrue("frame $i", d.update(if (i % 2 == 0) 0.25f else 0.30f)) }
    }

    @Test
    fun thePinchValueGoesFromTheOpenHandToThePress() {
        val c = PinchCalibration()
        assertEquals(0f, c.value(c.open), 1e-4f)
        assertEquals(1f, c.value(c.pressRatio), 1e-4f)
        var last = -1f
        for (i in 0..20) {
            val ratio = c.open - (c.open - c.pressRatio) * i / 20f
            val v = c.value(ratio)
            assertTrue(v >= last)
            last = v
        }
    }

    @Test
    fun aHandAtThePicturesEdgeNeverClicks() {
        val r = HandGestureRecognizer(1920, 1080, AirBox(0f, 0f, 1f, 1f))
        val hand = { ratio: Float, t: Long -> TestHands.hand(cx = 0.02f, cy = 0.8f, extended = listOf(true, false, false, false), thumbGap = ratio, timestampMs = t) }
        val signals = mutableListOf<PointerSignal>()
        val traces = mutableListOf<PinchTrace>()
        for (i in 0 until 10) r.update(hand(0.8f, i * 33L), i * 33L).let { signals += it.signals; traces += it.traces }
        for (i in 10 until 20) r.update(hand(0.05f, i * 33L), i * 33L).let { signals += it.signals; traces += it.traces; assertFalse(it.status.ready); assertEquals(0f, it.status.pinchProgress, 0f) }
        assertTrue(signals.none { it is PointerSignal.Down })
        assertEquals(1, traces.count { it is PinchTrace.NotReady })
    }

    @Test
    fun aPressHeldWhileTheHandGoesNotReadyEndsAfterTheHoldTime() {
        val r = HandGestureRecognizer(1920, 1080)
        var t = 0L
        val signals = mutableListOf<PointerSignal>()
        repeat(15) { r.update(hands.hand(Point(960f, 540f), 0.7f, t), t).let { signals += it.signals }; t += 33 }
        repeat(3) { r.update(hands.hand(Point(960f, 540f), 0.12f, t), t).let { signals += it.signals }; t += 33 }
        assertTrue(r.pinched)
        // A low score (blur): the press holds for 250 ms (0.5.8, was 150), then ends with an Up at the lock.
        val blurred = { at: Long -> hands.hand(Point(960f, 540f), 0.12f, at).copy(score = 0.2f) }
        // 0.5.4: the first blurred frame alone is skipped; the hold counts from the second.
        r.update(blurred(t), t).let { signals += it.signals; assertTrue(it.status.pinched) }
        t += 33
        r.update(blurred(t), t).let { signals += it.signals; assertTrue(it.status.pinched) }
        t += 100
        r.update(blurred(t), t).let { signals += it.signals; assertTrue(it.status.pinched) }
        t += 100
        r.update(blurred(t), t).let { signals += it.signals; assertTrue(it.status.pinched) }
        t += 100
        r.update(blurred(t), t).let { signals += it.signals; assertFalse(it.status.pinched) }
        assertEquals(1, signals.count { it is PointerSignal.Down })
        assertEquals(1, signals.count { it is PointerSignal.Up })
    }

    @Test
    fun theRecognizerReleasesWhenThePictureShowsTheFingersOpen() {
        val r = HandGestureRecognizer(1920, 1080)
        var t = 0L
        repeat(15) { r.update(hands.hand(Point(960f, 540f), 0.7f, t), t); t += 33 }
        repeat(4) { r.update(hands.hand(Point(960f, 540f), 0.12f, t), t); t += 33 }
        assertTrue(r.pinched)
        // The world landmarks still say closed (lagging), the picture has the tips apart.
        val pinchedWorld = hands.hand(Point(960f, 540f), 0.12f, t).world
        val open = hands.hand(Point(960f, 540f), 0.6f, t).copy(world = pinchedWorld)
        // 0.5.4: one frame of open tips is not enough, two in a row release.
        assertTrue(r.update(open, t).status.pinched)
        t += 33
        val u = r.update(hands.hand(Point(960f, 540f), 0.6f, t).copy(world = pinchedWorld), t)
        assertFalse(u.status.pinched)
        assertTrue(u.traces.filterIsInstance<PinchTrace.Release>().single().how.startsWith("the picture"))
    }

    /** Presses at the centre, moves [movePx] over [moveMs] while pinched, releases; true when it dragged. */
    private fun dragged(movePx: Float, moveMs: Long): Boolean {
        val s = ReplayScript(hands)
        val at = Point(960f, 540f)
        s.hold(at, 0.7f, 20)
        s.ratios(at, at, listOf(0.6f, 0.45f, 0.3f, 0.2f))
        val frames = (moveMs / 33).toInt().coerceAtLeast(1)
        s.ratios(at, at + Point(movePx, 0f), List(frames) { 0.2f })
        s.ratios(at + Point(movePx, 0f), at + Point(movePx, 0f), listOf(0.2f, 0.5f, 0.7f, 0.7f, 0.7f))
        val r = HandsRecognizer(HandGestureRecognizer(1920, 1080))
        val traces = s.build().flatMap { r.update(it.hands, it.tMs).traces }
        return traces.filterIsInstance<PinchTrace.Release>().single().dragged
    }

    @Test
    fun aTapThatWobblesAFewDegreesStaysATap() {
        // About 2 degrees (60 px at 30 px a degree) over 300 ms: Mike's 0.4.8 taps were all drags.
        assertFalse(dragged(60f, 300))
    }

    @Test
    fun aDeliberateMoveBecomesADrag() {
        assertTrue(dragged(250f, 500))
    }

    @Test
    fun aFastSweepBecomesADragAtOnce() {
        assertTrue(dragged(120f, 99))
    }
}
