package com.hayeco.flipspaces.core.spatial

import com.hayeco.flipspaces.core.hands.HandGestureRecognizer
import com.hayeco.flipspaces.core.hands.HandsRecognizer
import com.hayeco.flipspaces.core.hands.SyntheticHands
import com.hayeco.flipspaces.core.pointer.HandSide
import com.hayeco.flipspaces.core.pointer.Point
import com.hayeco.flipspaces.core.pointer.PointerSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.5.11: Mike's "sometimes a pinch shows 2 ray cast cursor dots": one dot, the active hand's. */
class OneCursorTest {
    private val hands = SyntheticHands(1920, 1080)

    @Test
    fun aHandWhoseNameFlipsMidPinchKeepsItsSide() {
        val r = HandsRecognizer(HandGestureRecognizer(1920, 1080), second = HandGestureRecognizer(1920, 1080))
        var t = 10_000L
        val sides = mutableListOf<Set<HandSide>>()
        // MediaPipe's "Left" is the user's right; the hand closes into a pinch and MediaPipe calls it "Right" for a few frames.
        for (i in 0 until 40) {
            val flipped = i in 15..25
            val ratio = if (i in 12..28) 0.15f else 0.7f
            val u = r.update(listOf(hands.hand(Point(1200f, 540f), ratio, t, right = flipped)), t, 30f)
            sides += u.signals.filterIsInstance<PointerSignal.ForHand>().map { it.hand }.toSet()
            t += 33
        }
        assertTrue("signals for ${sides.flatten().toSet()}", sides.flatten().all { it == HandSide.RIGHT })
        assertTrue(r.labelFlipsIgnored > 0)
    }

    @Test
    fun aNewHandStillTakesItsName() {
        val r = HandsRecognizer(HandGestureRecognizer(1920, 1080), second = HandGestureRecognizer(1920, 1080))
        // Nobody followed yet: MediaPipe's "Right" is the user's left.
        val sides = r.assign(listOf(hands.hand(Point(500f, 540f), 0.7f, 0, right = true)))
        assertNotNull(sides[HandSide.LEFT])
    }

    @Test
    fun oneDotTheActiveHands() {
        val c = CursorChoice()
        val both = listOf(HandSide.RIGHT, HandSide.LEFT)
        assertNotNull(c.pick(both, { false }))
        assertEquals(HandSide.RIGHT, c.active)
        // The left pinches: its dot, and the right's goes.
        assertTrue(c.pick(both, { it == HandSide.LEFT })!!.contains("left hand's"))
        assertEquals(HandSide.LEFT, c.active)
        // Both pinch (a two hand stretch): it stays on the one that had it.
        assertNull(c.pick(both, { true }))
        assertEquals(HandSide.LEFT, c.active)
        // Neither: it stays, until the other hand clearly aims.
        c.pick(both, { false }, { if (it == HandSide.RIGHT) 10f else 2f })
        assertEquals(HandSide.LEFT, c.active)
        c.pick(both, { false }, { if (it == HandSide.RIGHT) 60f else 2f })
        assertEquals(HandSide.RIGHT, c.active)
        // The right leaves: the left's.
        c.pick(listOf(HandSide.LEFT), { false })
        assertEquals(HandSide.LEFT, c.active)
        c.pick(emptyList(), { false })
        assertNull(c.active)
    }
}
