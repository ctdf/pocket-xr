package com.hayeco.flipspaces.core.hands

import com.hayeco.flipspaces.core.pointer.HandSide
import com.hayeco.flipspaces.core.pointer.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.5.1: one hand found twice is one hand (Mike's 0.5.0 log). */
class HandOverlapTest {
    private val hands = SyntheticHands(1920, 1080)

    private fun copy(h: HandFrame, dx: Float = 0f, right: Boolean? = h.rightHand, score: Float = h.score) =
        HandFrame(h.landmarks.map { Landmark(it.x + dx, it.y, it.z) }, h.timestampMs, right, score, h.world)

    @Test
    fun theSameHandLabelledLeftAndRightIsOneHand() {
        val a = hands.hand(Point(700f, 500f), 0.6f, 0, right = true)
        val b = copy(a, dx = 0.01f, right = false, score = 0.8f)
        assertTrue(HandOverlap.same(a, b))
        val kept = HandOverlap.distinct(listOf(a, b))
        assertEquals(1, kept.size)
        assertSame(a, kept[0])
    }

    @Test
    fun theBetterScoredDetectionIsKept() {
        val a = hands.hand(Point(700f, 500f), 0.6f, 0)
        val worse = copy(a, score = 0.5f)
        val better = copy(a, dx = 0.005f, score = 0.97f)
        assertSame(better, HandOverlap.distinct(listOf(worse, better)).single())
    }

    @Test
    fun twoRealHandsApartAreBothKept() {
        val left = hands.hand(Point(500f, 600f), 0.6f, 0, right = false)
        val right = hands.hand(Point(1400f, 600f), 0.6f, 0, right = true)
        assertFalse(HandOverlap.same(left, right))
        assertEquals(2, HandOverlap.distinct(listOf(left, right)).size)
    }

    @Test
    fun handsSideBySideStayTwo() {
        // Close together (a two hand stretch starting), boxes touching but wrists well apart.
        val a = hands.hand(Point(800f, 600f), 0.6f, 0)
        val b = copy(a, dx = 0.12f)
        assertEquals(2, HandOverlap.distinct(listOf(a, b)).size)
    }

    @Test
    fun overlapIsSharedAreaOverTheSmallerBox() {
        assertEquals(0f, HandOverlap.overlap(floatArrayOf(0f, 0f, 1f, 1f), floatArrayOf(2f, 2f, 3f, 3f)), 0f)
        assertEquals(1f, HandOverlap.overlap(floatArrayOf(0f, 0f, 1f, 1f), floatArrayOf(0.2f, 0.2f, 0.4f, 0.4f)), 1e-6f)
        assertEquals(0.5f, HandOverlap.overlap(floatArrayOf(0f, 0f, 1f, 1f), floatArrayOf(0.5f, 0f, 1.5f, 1f)), 1e-6f)
    }

    @Test
    fun aDuplicateDrawsOneCursorNotTwo() {
        val r = HandsRecognizer(HandGestureRecognizer(1920, 1080), second = HandGestureRecognizer(1920, 1080))
        val a = hands.hand(Point(700f, 500f), 0.6f, 0, right = true)
        val u = r.update(listOf(a, copy(a, dx = 0.008f, right = false, score = 0.9f)), 0)
        assertEquals(1, u.hands.values.count { it.present })
        assertEquals(1, r.duplicatesDropped)
        assertTrue(u.hands[HandSide.RIGHT]?.present == true || u.hands[HandSide.LEFT]?.present == true)
    }
}
