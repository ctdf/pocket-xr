package com.hayeco.flipspaces.core.hands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** View cycle timing, the hand crop and the idle throttle (0.3.2). */
class DetectionCycleTest {
    private val w = 960
    private val h = 540

    /**
     * A hand about a tenth of the frame wide with its bounding box centred on ([cx], [cy]) in
     * upright normalised coordinates (TestHands' box runs 1.3 [size] left and 0.3 right of the
     * wrist, and 2 [size] up from it).
     */
    private fun hand(cx: Float = 0.5f, cy: Float = 0.5f, size: Float = 0.06f) = TestHands.hand(cx = cx + 0.5f * size, cy = cy + size, size = size)

    /** Feeds [n] results for frames sent with the cycle's current view; returns the views they were sent with. */
    private fun DetectionCycle.feed(n: Int, found: (Int) -> HandFrame?): List<String> = (0 until n).map { i ->
        val v = current
        record(v, found(i), w, h, i * 33L)
        v.label
    }

    @Test
    fun eachSearchViewHoldsForItsFrames() {
        val c = DetectionCycle(roi = null)
        val labels = c.feed(36) { null }
        assertEquals(List(6) { "full" } + List(6) { "centre" } + List(6) { "close" } + List(6) { "full" } + List(6) { "centre" } + List(6) { "close" }, labels)
    }

    @Test
    fun aLoneFalsePalmNeitherStopsNorRestartsTheCycle() {
        // 0.3.1 flipped full and centre every few frames: a one frame hit in the centre view
        // counted as tracking and its miss sent the cycle back to the whole frame.
        val c = DetectionCycle(roi = null)
        val labels = c.feed(18) { i -> if (i == 7 || i == 9) hand() else null }
        assertEquals(List(6) { "full" } + List(6) { "centre" } + List(6) { "close" }, labels)
        assertFalse(c.hasHand)
    }

    @Test
    fun resultsOfFramesSentWithAnOlderViewDoNotCount() {
        val c = DetectionCycle(framesPerView = 2, roi = null)
        val full = c.current
        c.record(full, null, w, h, 0)
        c.record(full, null, w, h, 33)
        assertEquals("centre", c.current.label)
        // A frame prepared with the whole frame view comes back after the switch: ignored.
        assertFalse(c.record(full, null, w, h, 66))
        assertEquals("centre", c.current.label)
        c.record(c.current, null, w, h, 99)
        assertEquals("centre", c.current.label)
    }

    @Test
    fun twoHitsInARowTrackTheHandAndStopTheCycle() {
        val c = DetectionCycle(roi = null)
        c.feed(7) { null }
        assertEquals("centre", c.current.label)
        c.feed(2) { hand() }
        assertTrue(c.hasHand)
        val labels = c.feed(60) { hand() }
        assertTrue(labels.all { it == "centre" })
    }

    @Test
    fun trackedHandGetsAFullResolutionCropThatHoldsStill() {
        val c = DetectionCycle()
        c.feed(2) { hand() }
        val crop = c.current
        assertEquals(HandRoi.LABEL, crop.label)
        assertTrue("zoom ${crop.zoom}", crop.zoom > 1.5f)
        assertEquals(0.5f, crop.centerX, 0.05f)
        // Small moves keep the same crop: MediaPipe keeps its track and skips the palm detector.
        c.feed(30) { i -> hand(cx = 0.5f + 0.005f * (i % 5)) }
        assertEquals(crop, c.current)
    }

    @Test
    fun cropMovesWhenTheHandNearsItsEdge() {
        val c = DetectionCycle()
        c.feed(2) { hand() }
        val first = c.current
        val side = 1f / first.zoom * w
        // Far enough to leave the inner zone: a new crop centred on the hand.
        val moved = 0.5f + 0.3f * side / w
        assertTrue(c.record(first, hand(cx = moved), w, h, 100))
        assertEquals(moved, c.current.centerX, 0.02f)
    }

    @Test
    fun firstMissAfterAViewChangeIsNotCounted() {
        val c = DetectionCycle(lostAfterMisses = 1)
        c.feed(2) { hand() }
        val crop = c.current
        assertEquals(HandRoi.LABEL, crop.label)
        // MediaPipe loses its track for the frame after a view change: not a lost hand.
        c.record(crop, null, w, h, 100)
        assertTrue(c.hasHand)
        // A real miss is.
        c.record(crop, null, w, h, 133)
        assertFalse(c.hasHand)
        assertEquals("full", c.current.label)
    }

    @Test
    fun missInTheCropFallsBackToTheViewThatFoundTheHand() {
        val c = DetectionCycle()
        c.feed(2) { hand() }
        val crop = c.current
        c.record(crop, hand(), w, h, 66)
        assertTrue(c.record(crop, null, w, h, 99))
        assertEquals("full", c.current.label)
        assertTrue(c.hasHand)
        // Found again in the wider view: a new crop.
        c.record(c.current, null, w, h, 132)
        c.record(c.current, hand(), w, h, 165)
        assertEquals(HandRoi.LABEL, c.current.label)
    }

    @Test
    fun cropSizesComeInSteps() {
        val roi = HandRoi()
        val a = roi.plan(null, hand(size = 0.060f), w, h)!!
        val b = roi.plan(null, hand(size = 0.061f), w, h)!!
        assertEquals(a.zoom, b.zoom, 1e-4f)
        // Big hands: never more than the frame; tiny ones: never more than 1 / minFraction zoom.
        assertEquals(1f, roi.plan(null, hand(size = 0.4f), w, h)!!.zoom, 1e-4f)
        assertTrue(roi.plan(null, hand(size = 0.005f), w, h)!!.zoom <= 1f / roi.minFraction + 1e-3f)
        assertNull(roi.plan(a, hand(size = 0.060f), w, h))
        assertNotNull(roi.plan(a, hand(size = 0.15f), w, h))
    }

    @Test
    fun idleCycleSendsFewerFrames() {
        val c = DetectionCycle(roi = null)
        var sent = 0
        // 0.5.19: four seconds at 30 fps with no hand: all frames for the first 2 s (61), then 15 a second (every other frame).
        var idle = 0
        for (i in 0 until 121) {
            val t = i * 33L
            if (c.shouldSend(t)) {
                sent++
                if (t >= 2000L) idle++
                c.sent(t)
                c.record(c.current, null, w, h, t)
            }
        }
        assertTrue("sent $sent", sent in 88..94)
        assertTrue("idle $idle", idle in 29..31)
        // A hand resets it: every frame again.
        c.record(c.current, hand(), w, h, 4000)
        assertTrue(c.shouldSend(4001))
        c.record(c.current, hand(), w, h, 4002)
        assertTrue(c.hasHand)
        assertTrue((0 until 10).all { c.shouldSend(4010L + it) })
    }

    @Test
    fun outputIsCappedButMapsBackTheSame() {
        val full = DetectionWindow(960, 540, 0, DetectionView("full"), maxOutputSide = 640)
        assertEquals(960, full.side)
        assertEquals(640, full.outputSide)
        // The frame's centre lands in the middle of the 640 px bitmap.
        val (x, y) = full.toSquare(480f, 270f)
        assertEquals(320f, x, 0.01f)
        assertEquals(320f, y, 0.01f)
        // Normalised results map back exactly as at full size.
        val p = full.toUpright(Landmark(0.5f, 0.5f))
        assertEquals(0.5f, p.x, 1e-4f)
        assertEquals(0.5f, p.y, 1e-4f)
        assertTrue(full.describe().contains("sent at 640x640"))
        // A crop smaller than the cap is never enlarged.
        val crop = DetectionWindow(960, 540, 0, DetectionView("hand", zoom = 3f), maxOutputSide = 640)
        assertEquals(320, crop.outputSide)
        assertEquals(1f, crop.scale, 0f)
    }
}
