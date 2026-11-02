package com.hayeco.flipspaces.core.hands

import com.hayeco.flipspaces.core.pointer.PointerSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.7: hand tracking that does not drop out. Shaped like Mike's 0.5.6 log (13 s,
 * one hand): 30 fps tracked for 3 to 4 s with a moving hand, then "hand 43%" (MediaPipe
 * finding it on fewer than half the frames), then lost, then the view cycling full, centre (zoom
 * 1.8) and close (zoom 2.5) every 0.2 to 0.4 s while the hand, moved off centre, was not in the
 * zoomed squares; and one "hand size jumped 241% in one frame" on re-acquire.
 */
class HandDropoutTest {
    private val w = 960
    private val h = 540
    private val frameMs = 33L

    /** A palm detector that finds the hand when its centre is inside the view's square. */
    private fun sees(view: DetectionView, cx: Float, cy: Float): Boolean {
        val win = DetectionWindow(w, h, 0, view)
        val x = cx * w
        val y = cy * h
        return x >= win.left && x <= win.left + win.side && y >= win.top && y <= win.top + win.side
    }

    private fun hand(cx: Float, cy: Float = 0.5f, t: Long) = TestHands.hand(cx = cx, cy = cy + 0.1f, size = 0.06f, timestampMs = t)

    @Test
    fun aLostHandIsLookedForInTheWholeViewAtFullRateAndFoundAtOnce() {
        val c = DetectionCycle.steady()
        var t = 0L
        // 3.5 s tracked, the hand sweeping from the centre to the right.
        while (t < 3_500) {
            val cx = 0.5f + 0.35f * t / 3_500f
            c.record(c.current, hand(cx, t = t), w, h, t)
            t += frameMs
        }
        assertTrue(c.hasHand)
        // "hand 43%": found on 3 of 7 frames, then not at all for 0.4 s while it keeps moving.
        val pattern = listOf(true, false, false, true, false, true, false, false, false)
        for (found in pattern) {
            c.record(c.current, if (found) hand(0.85f, t = t) else null, w, h, t)
            t += frameMs
        }
        assertFalse(c.hasHand)
        val lostAt = t
        val views = mutableListOf<String>()
        while (t < lostAt + 400) {
            assertTrue("full rate at ${t - lostAt} ms", c.shouldSend(t))
            c.sent(t)
            views += c.current.label
            c.record(c.current, null, w, h, t)
            t += frameMs
        }
        // No zoomed views and no cycling while the hand was seen in the last second.
        assertTrue(views.toString(), views.all { it == "full" })
        // Back in view at the right edge, where the centre and close squares do not reach.
        assertFalse(sees(DetectionCycle.DEFAULT_VIEWS[1], 0.9f, 0.5f))
        var frames = 0
        while (!c.hasHand) {
            val v = c.current
            c.record(v, if (sees(v, 0.9f, 0.5f)) hand(0.9f, t = t) else null, w, h, t)
            t += frameMs
            frames++
        }
        // Two frames: MediaPipe's palm detector and the confirming frame (0.5.6: seconds).
        assertEquals(2, frames)
        assertEquals("full", c.current.label)
    }

    @Test
    fun afterASecondTheSearchLooksNearWhereTheHandWasThenCycles() {
        val c = DetectionCycle.steady()
        var t = 0L
        repeat(30) {
            c.record(c.current, hand(0.8f, 0.6f, t), w, h, t)
            t += frameMs
        }
        val lastSeen = t - frameMs
        repeat(3) {
            c.record(c.current, null, w, h, t)
            t += frameMs
        }
        assertFalse(c.hasHand)
        val labels = mutableListOf<Pair<Long, DetectionView>>()
        while (t < lastSeen + 1_600) {
            labels += (t - lastSeen) to c.current
            c.record(c.current, null, w, h, t)
            t += frameMs
        }
        assertTrue(labels.filter { it.first < 1_000 }.all { it.second.label == "full" })
        val near = labels.first { it.second.label == DetectionCycle.NEAR_LABEL }
        assertTrue("near view at ${near.first} ms", near.first in 1_000..1_100)
        assertEquals(1.8f, near.second.zoom, 1e-4f)
        // Centred on the hand's last box (its centre sits a little above the wrist).
        assertEquals(0.8f, near.second.centerX, 0.05f)
        assertTrue(near.second.centerY in 0.5f..0.7f)
        // Six frames there, then the usual cycle from the view after the whole frame.
        val afterNear = labels.dropWhile { it.second.label != DetectionCycle.NEAR_LABEL }.drop(6).firstOrNull()
        assertNotNull(afterNear)
        assertEquals("centre", afterNear!!.second.label)
    }

    @Test
    fun aHandFoundInTheNearViewIsTrackedThere() {
        val c = DetectionCycle.steady()
        var t = 0L
        repeat(10) {
            c.record(c.current, hand(0.3f, 0.6f, t), w, h, t)
            t += frameMs
        }
        while (c.current.label != DetectionCycle.NEAR_LABEL) {
            c.record(c.current, null, w, h, t)
            t += frameMs
        }
        val near = c.current
        repeat(2) {
            c.record(near, hand(0.3f, 0.6f, t), w, h, t)
            t += frameMs
        }
        assertTrue(c.hasHand)
        assertEquals(near, c.current)
    }

    @Test
    fun theThresholdsKeepABlurredOrFastHandTracked() {
        val t = HandThresholds()
        // 0.5.6 had 0.4 and 0.4; MediaPipe's palm detector ran on every frame the presence dipped.
        assertTrue(t.presence < 0.4f)
        assertTrue(t.tracking < 0.4f)
        assertTrue(t.presence >= t.detection)
    }

    /** A pointing hand sweeping right at about 960 px/s on a 1920 px display (air box of the whole picture). */
    private fun sweep(r: HandGestureRecognizer, from: Long, until: Long, x0: Float = 0.3f): Long {
        var t = from
        while (t < until) {
            r.update(TestHands.pointing(cx = x0 + 0.5f * t / 1000f, t = t), t)
            t += frameMs
        }
        return t
    }

    private fun recognizer() = HandGestureRecognizer(1920, 1080, AirBox(0f, 0f, 1f, 1f))

    @Test
    fun aSweepCoastsThroughTwoEmptyFramesAndGlidesBack() {
        val r = recognizer()
        var t = sweep(r, 0, 500)
        val before = r.cursor!!
        val coast = mutableListOf<PointerSignal.MoveTo>()
        repeat(2) {
            val u = r.update(null, t)
            assertTrue(u.status.present)
            assertTrue(u.signals.none { it is PointerSignal.Cancel || it is PointerSignal.Up })
            coast += u.signals.filterIsInstance<PointerSignal.MoveTo>()
            t += frameMs
        }
        // The cursor keeps going the way the hand went, and less far than the hand did.
        assertEquals(2, coast.size)
        assertTrue(coast[0].x > before.x && coast[1].x > coast[0].x)
        assertTrue("coasted ${coast[1].x - before.x} px", coast[1].x - before.x in 10f..60f)
        // The hand back: the first move starts where the coast left the cursor, no jump back.
        val back = r.update(TestHands.pointing(cx = 0.3f + 0.5f * t / 1000f, t = t), t).signals.filterIsInstance<PointerSignal.MoveTo>().single()
        assertEquals(coast[1].x, back.x, 0.5f)
    }

    @Test
    fun theCoastStopsAfterATenthOfASecondAndTheHandHoldsForMore() {
        val r = recognizer()
        var t = sweep(r, 0, 500)
        val last = t - frameMs
        val xs = mutableListOf<Float>()
        while (t - last < r.lostAfterMs) {
            val u = r.update(null, t)
            assertTrue("present at ${t - last} ms", u.status.present)
            u.signals.filterIsInstance<PointerSignal.MoveTo>().forEach { xs += it.x }
            t += frameMs
        }
        // Coasted for the first 100 ms only (three frames), then held still until lost.
        assertEquals(3, xs.size)
        assertFalse(r.update(null, last + r.lostAfterMs).status.present)
    }

    @Test
    fun aStillHandOrAPinchDoesNotCoast() {
        val still = recognizer()
        var t = 0L
        while (t < 500) {
            still.update(TestHands.pointing(cx = 0.5f, t = t), t)
            t += frameMs
        }
        assertTrue(still.update(null, t).signals.isEmpty())

        val pinched = HandGestureRecognizer(1920, 1080, AirBox(0f, 0f, 1f, 1f), PinchDetector(enterFrames = 1, exitFrames = 1))
        t = sweep(pinched, 0, 300)
        repeat(5) {
            pinched.update(TestHands.pinching(cx = 0.3f + 0.5f * t / 1000f, t = t), t)
            t += frameMs
        }
        assertTrue(pinched.pinched)
        assertTrue(pinched.update(null, t).signals.none { it is PointerSignal.MoveTo })
    }

    @Test
    fun aHandFoundAgainBiggerIsNotSkippedButAJumpWithoutAGapStillIs() {
        fun hand(size: Float, t: Long) = TestHands.hand(cx = 0.5f, cy = 0.8f, size = size, extended = listOf(true, false, false, false), thumbGap = 0.8f, timestampMs = t)
        fun skipped(u: HandUpdate) = u.traces.any { it is PinchTrace.FrameSkipped }

        val r = recognizer()
        var t = 0L
        repeat(10) {
            r.update(hand(0.06f, t), t)
            t += frameMs
        }
        // Two empty frames, then the hand found again 233% bigger (Mike's re-acquire: 241%).
        r.update(null, t)
        t += frameMs
        r.update(null, t)
        t += frameMs
        assertFalse(skipped(r.update(hand(0.2f, t), t)))

        val s = recognizer()
        t = 0L
        repeat(10) {
            s.update(hand(0.06f, t), t)
            t += frameMs
        }
        assertTrue(skipped(s.update(hand(0.2f, t), t)))
    }

    @Test
    fun turningTwoHandsOnTakesEffectWithoutARestart() {
        assertEquals(1, HandCount.wanted(false))
        assertEquals(2, HandCount.wanted(true))
        // The detection thread compares between frames: a change rebuilds, the same count does not.
        assertEquals(2, HandCount.change(running = 1, twoHands = true))
        assertEquals(null, HandCount.change(running = 2, twoHands = true))
        assertEquals(1, HandCount.change(running = 2, twoHands = false))
        assertTrue(HandCount.line(1).contains("palm detector only when the hand is lost"))
    }
}
