package com.hayeco.flipspaces.core.hands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PinchAndPoseTest {
    @Test
    fun pinchNeedsTwoFramesBelowEnterThreshold() {
        val d = PinchDetector(enterRatio = 0.25f, exitRatio = 0.4f, enterFrames = 2, exitFrames = 2)
        assertFalse(d.update(0.2f))
        assertTrue(d.update(0.2f))
    }

    @Test
    fun hysteresisHoldsPinchBetweenThresholds() {
        val d = PinchDetector(enterFrames = 1, exitFrames = 1)
        assertTrue(d.update(0.1f))
        // Jitter between 0.25 and 0.4 keeps the pinch on.
        listOf(0.3f, 0.38f, 0.26f, 0.39f).forEach { assertTrue("released at $it", d.update(it)) }
        assertFalse(d.update(0.45f))
        // And off again: values between the thresholds do not start a new pinch.
        listOf(0.3f, 0.26f, 0.35f).forEach { assertFalse("pinched at $it", d.update(it)) }
        assertTrue(d.update(0.2f))
    }

    @Test
    fun singleFrameSpikeDoesNotRelease() {
        val d = PinchDetector(enterFrames = 1, exitFrames = 2)
        d.update(0.1f)
        assertTrue(d.update(0.6f))
        assertTrue(d.update(0.1f))
        assertTrue(d.update(0.6f))
        assertFalse(d.update(0.6f))
    }

    @Test(expected = IllegalArgumentException::class)
    fun enterMustBeBelowExit() {
        PinchDetector(enterRatio = 0.5f, exitRatio = 0.4f)
    }

    @Test
    fun pinchRatioIgnoresDistanceFromCamera() {
        val near = TestHands.hand(size = 0.3f, thumbGap = 0.5f)
        val far = TestHands.hand(size = 0.1f, thumbGap = 0.5f)
        assertEquals(0.5f, near.pinchRatio, 0.01f)
        assertEquals(near.pinchRatio, far.pinchRatio, 1e-3f)
    }

    @Test
    fun classifiesPoses() {
        assertEquals(HandPose.OPEN_PALM, HandPoseClassifier.classify(TestHands.openPalm(), pinching = false))
        assertEquals(HandPose.POINT, HandPoseClassifier.classify(TestHands.pointing(), pinching = false))
        assertEquals(HandPose.FIST, HandPoseClassifier.classify(TestHands.fist(), pinching = false))
        assertEquals(HandPose.PINCH, HandPoseClassifier.classify(TestHands.openPalm(), pinching = true))
    }

    @Test
    fun openFingersWithThumbTuckedIsNotAPalm() {
        val palm = TestHands.openPalm()
        val knuckle = palm[HandFrame.INDEX_MCP]
        val tucked = palm.landmarks.toMutableList().also {
            it[HandFrame.THUMB_TIP] = Landmark(knuckle.x - 0.02f, knuckle.y + 0.02f)
        }
        val hand = palm.copy(landmarks = tucked)
        assertEquals(HandPose.OTHER, HandPoseClassifier.classify(hand, pinching = false))
    }

    @Test
    fun oneEuroFilterSmoothsJitterButFollowsMoves() {
        val f = OneEuroFilter(minCutoffHz = 1f, beta = 0.05f)
        var t = 0L
        var out = 0f
        repeat(30) {
            out = f.filter(if (it % 2 == 0) 100f else 104f, t)
            t += 33
        }
        assertTrue("jitter not smoothed: $out", abs(out - 102f) < 1.5f)
        repeat(30) {
            out = f.filter(500f, t)
            t += 33
        }
        assertTrue("did not follow move: $out", abs(out - 500f) < 5f)
    }
}
