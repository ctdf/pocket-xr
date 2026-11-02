package com.hayeco.flipspaces.core.hands

import com.hayeco.flipspaces.core.pointer.Point
import com.hayeco.flipspaces.core.pointer.PointerSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Pinch from world landmarks, onset rewind, freeze and drag (0.3.2). */
class PinchFreezeTest {
    private fun samples(vararg ratios: Pair<Long, Float>) = ratios.map { (t, r) -> AimSample(t, Point(t.toFloat(), 0f), r) }

    @Test
    fun rewindFindsWhereTheFingersStartedClosing() {
        val s = samples(0L to 0.8f, 33L to 0.8f, 66L to 0.8f, 100L to 0.8f, 133L to 0.7f, 166L to 0.5f, 199L to 0.3f, 232L to 0.1f)
        assertEquals(100L, PinchRewind().onset(s, 232, closing = true).timeMs)
    }

    @Test
    fun rewindNeverGoesBackFurtherThanItsMaximum() {
        // Fingers closing slowly over a third of a second.
        val s = (0..10).map { AimSample(it * 33L, Point(0f, 0f), 0.8f - it * 0.06f) }
        val onset = PinchRewind(maxMs = 150).onset(s, 330, closing = true)
        assertEquals(198L, onset.timeMs)
    }

    @Test
    fun rewindGoesBackAtLeastItsMinimum() {
        // Closed in one frame: still at least 50 ms back, past the filter's lag.
        val s = samples(0L to 0.8f, 33L to 0.8f, 66L to 0.8f, 99L to 0.1f)
        assertEquals(33L, PinchRewind(minMs = 50).onset(s, 99, closing = true).timeMs)
    }

    @Test
    fun rewindStopsAtAStillHandRatherThanWalkingBackAlongItsPath() {
        // Steady fingers, then a quick pinch: only the pinch is rewound, not the approach.
        val s = samples(0L to 0.8f, 33L to 0.8f, 66L to 0.8f, 99L to 0.8f, 132L to 0.8f, 165L to 0.4f, 198L to 0.1f)
        assertEquals(132L, PinchRewind(minMs = 50).onset(s, 198, closing = true).timeMs)
    }

    @Test
    fun releaseRewindsToWhereTheFingersStartedOpening() {
        val s = samples(0L to 0.1f, 33L to 0.1f, 66L to 0.1f, 99L to 0.25f, 132L to 0.5f)
        assertEquals(66L, PinchRewind().onset(s, 132, closing = false).timeMs)
    }

    private fun recognizer() = HandGestureRecognizer(1920, 1080, AirBox(0f, 0f, 1f, 1f), PinchDetector(enterFrames = 1, exitFrames = 1))

    @Test
    fun pressLandsWhereTheCursorWasBeforeTheFingersClosed() {
        val r = recognizer()
        var t = 0L
        repeat(10) {
            r.update(TestHands.hand(cx = 0.5f, thumbGap = 0.8f), t)
            t += 33
        }
        val target = r.update(TestHands.hand(cx = 0.5f, thumbGap = 0.8f), t).status.cursor!!
        t += 33
        // Closing the fingers drags the palm to the right, 1% of the frame a frame (within the
        // 0.3.6 rewind cap of 50 px from the hand).
        var down: PointerSignal.Down? = null
        for ((i, gap) in listOf(0.6f, 0.4f, 0.2f, 0.05f).withIndex()) {
            val u = r.update(TestHands.hand(cx = 0.5f + 0.01f * (i + 1), thumbGap = gap, extended = listOf(true, false, false, false)), t)
            down = u.signals.filterIsInstance<PointerSignal.Down>().firstOrNull() ?: down
            t += 33
        }
        assertNotNull(down)
        assertEquals(target.x, down!!.at!!.x, 3f)
    }

    @Test
    fun clickStaysPutWhenTheFingersOpen() {
        val r = recognizer()
        repeat(6) { r.update(TestHands.pointing(t = it * 33L), it * 33L) }
        // 0.5.5: the aim locks on the first pinched frame and the press follows on the next.
        r.update(TestHands.pinching(t = 198), 198)
        val down = r.update(TestHands.pinching(t = 231), 231).signals.filterIsInstance<PointerSignal.Down>().single()
        // Fingers open and the hand twitches: the release goes where the press did.
        r.update(TestHands.pinching(cx = 0.505f, t = 264), 264)
        val upUpdate = r.update(TestHands.hand(cx = 0.51f, thumbGap = 0.8f), 297)
        val up = upUpdate.signals.filterIsInstance<PointerSignal.Up>().single()
        assertEquals(down.at!!.x, up.at!!.x, 0.01f)
        assertEquals(down.at!!.y, up.at!!.y, 0.01f)
        assertTrue(upUpdate.traces.single() is PinchTrace.Release)
    }

    @Test
    fun dragStartsFromTheFrozenPointWithoutAJump() {
        val r = recognizer()
        repeat(6) { r.update(TestHands.pointing(t = it * 33L), it * 33L) }
        r.update(TestHands.pinching(t = 198), 198)
        val down = r.update(TestHands.pinching(t = 231), 231).signals.filterIsInstance<PointerSignal.Down>().single()
        // Move steadily to the right while pinched.
        val moves = (1..20).flatMap { i ->
            val t = 231 + i * 33L
            r.update(TestHands.pinching(cx = 0.5f + 0.01f * i, t = t), t).signals.filterIsInstance<PointerSignal.MoveTo>()
        }
        assertTrue(moves.isNotEmpty())
        // The first drag move starts near the press point, not where the hand is.
        assertTrue("first drag at ${moves.first().x}, press at ${down.at!!.x}", abs(moves.first().x - down.at!!.x) < 120f)
        // And it keeps following the hand from there.
        assertTrue(moves.last().x > moves.first().x + 150f)
    }

    @Test
    fun pinchUsesWorldLandmarksWhenPresent() {
        val image = TestHands.hand(thumbGap = 0.05f)
        // In the image the fingers touch, but in metres they are 4 cm apart over a 9 cm bone: no pinch.
        val world = MutableList(HandFrame.COUNT) { Landmark(0f, 0f, 0f) }
        world[HandFrame.WRIST] = Landmark(0f, 0f, 0f)
        world[HandFrame.INDEX_MCP] = Landmark(0f, -0.09f, 0f)
        world[HandFrame.INDEX_TIP] = Landmark(0f, -0.16f, 0f)
        world[HandFrame.THUMB_TIP] = Landmark(0f, -0.16f, 0.04f)
        val hand = image.copy(world = world)
        assertEquals(0.04f / 0.09f, hand.pinchRatio, 1e-3f)
        assertTrue(image.pinchRatio < 0.1f)
        val d = PinchDetector()
        assertFalse(d.update(hand.pinchRatio))
        // Hysteresis on the world ratio: 1 cm pinches, 3 cm still holds, 4.5 cm releases.
        assertTrue(d.update(0.01f / 0.09f))
        assertTrue(d.update(0.03f / 0.09f))
        assertTrue(d.update(0.03f / 0.09f))
        d.update(0.045f / 0.09f)
        assertFalse(d.update(0.045f / 0.09f))
    }

    @Test
    fun strengthRisesAsTheFingersClose() {
        val r = recognizer()
        val apart = r.update(TestHands.hand(thumbGap = 0.8f), 0).status
        val near = r.update(TestHands.hand(thumbGap = 0.3f), 33).status
        assertEquals(0f, apart.pinchProgress, 1e-3f)
        assertTrue(near.pinchProgress > 0.5f)
        assertEquals(0.3f, near.pinchRatio!!, 0.02f)
    }

    @Test
    fun palmPointBarelyMovesWhenThePinchCloses() {
        val open = TestHands.hand(thumbGap = 0.8f)
        val pinched = TestHands.pinching()
        // TestHands curls the other fingers but keeps the knuckles: the palm point does not move,
        // where the index tip would move by 0.15 of the hand.
        assertEquals(open.palmPoint.x, pinched.palmPoint.x, 1e-4f)
        assertEquals(open.palmPoint.y, pinched.palmPoint.y, 1e-4f)
    }

    @Test
    fun steadyPointIgnoresTheThumbAndIndexFinger() {
        val open = TestHands.hand(thumbGap = 0.8f)
        // Pinching pulls the index knuckle and the thumb towards each other.
        val p = open.landmarks.toMutableList()
        p[HandFrame.INDEX_MCP] = Landmark(p[HandFrame.INDEX_MCP].x + 0.03f, p[HandFrame.INDEX_MCP].y + 0.02f)
        for (i in HandFrame.THUMB_CMC..HandFrame.THUMB_TIP) p[i] = Landmark(p[i].x + 0.05f, p[i].y - 0.04f)
        val pinched = open.copy(landmarks = p)
        assertEquals(open.steadyPoint.x, pinched.steadyPoint.x, 1e-6f)
        assertEquals(open.steadyPoint.y, pinched.steadyPoint.y, 1e-6f)
        assertTrue(abs(open.palmPoint.x - pinched.palmPoint.x) > 0.005f)
    }
}
